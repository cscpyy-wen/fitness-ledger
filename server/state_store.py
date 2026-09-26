"""Durable, single-host coordination for the meal-analysis proxy.

SQLite WAL is intentionally used here instead of process memory.  Every mutating
decision is made under ``BEGIN IMMEDIATE`` so separate Uvicorn workers sharing the
same local filesystem volume observe one atomic rate/quota/lease state.
"""

from __future__ import annotations

import json
import math
import os
import sqlite3
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Iterator, Literal, Sequence


SCHEMA_VERSION = 1
DEFAULT_BUSY_TIMEOUT_MS = 250
_INITIALIZE_BUSY_TIMEOUT_MS = 5_000
_DAILY_USAGE_HIGH_WATER_KEY = "daily_usage_high_water_day_utc"
_DAILY_USAGE_RETENTION_DAYS = 2
_MAX_IDEMPOTENCY_DEFER_SECONDS = 3_600


@dataclass(frozen=True)
class ProviderAdmission:
    reason: Literal["admitted", "daily_quota", "concurrency"]
    retry_after: int | None = None


@dataclass(frozen=True)
class IdempotencyDecision:
    action: Literal["execute", "replay", "conflict", "in_progress", "capacity"]
    owner_id: str | None = None
    status_code: int | None = None
    response_body: str | None = None
    response_headers: dict[str, str] | None = None
    retry_after: int | None = None


class SQLiteStateStore:
    """Small durable coordination database for one machine/local Docker volume."""

    def __init__(
        self,
        path: str | os.PathLike[str],
        busy_timeout_ms: int = DEFAULT_BUSY_TIMEOUT_MS,
    ) -> None:
        self.path = str(Path(path).expanduser().resolve())
        self.busy_timeout_ms = busy_timeout_ms
        self._initialize_lock = threading.Lock()
        self._initialized = False

    def initialize(self) -> None:
        if self._initialized:
            return
        with self._initialize_lock:
            if self._initialized:
                return
            database_path = Path(self.path)
            database_path.parent.mkdir(parents=True, exist_ok=True)
            # Worker startup may race with another worker creating/verifying the
            # schema.  It can wait longer than request-time coordination because
            # no ASGI request is being held yet.
            with self._connection(
                busy_timeout_ms=max(self.busy_timeout_ms, _INITIALIZE_BUSY_TIMEOUT_MS)
            ) as connection:
                journal_mode = str(connection.execute("PRAGMA journal_mode=WAL").fetchone()[0]).lower()
                if journal_mode != "wal":
                    raise RuntimeError("STATE_DB_PATH must support SQLite WAL on a local filesystem")
                connection.execute("PRAGMA synchronous=FULL")
                connection.executescript(
                    """
                    CREATE TABLE IF NOT EXISTS metadata (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS rate_events (
                        bucket TEXT NOT NULL,
                        occurred_at_ms INTEGER NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS rate_events_bucket_time
                        ON rate_events(bucket, occurred_at_ms);
                    CREATE INDEX IF NOT EXISTS rate_events_time
                        ON rate_events(occurred_at_ms);
                    CREATE TABLE IF NOT EXISTS auth_sources (
                        source_key TEXT PRIMARY KEY,
                        failures_json TEXT NOT NULL,
                        blocked_until_ms INTEGER NOT NULL,
                        last_seen_ms INTEGER NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS auth_sources_last_seen
                        ON auth_sources(last_seen_ms);
                    CREATE TABLE IF NOT EXISTS daily_usage (
                        day_utc TEXT NOT NULL,
                        token_key TEXT NOT NULL,
                        calls INTEGER NOT NULL CHECK(calls >= 0),
                        PRIMARY KEY(day_utc, token_key)
                    );
                    CREATE TABLE IF NOT EXISTS provider_leases (
                        lease_id TEXT PRIMARY KEY,
                        token_key TEXT NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS provider_leases_expiry
                        ON provider_leases(expires_at_ms);
                    CREATE TABLE IF NOT EXISTS idempotency_records (
                        token_key TEXT NOT NULL,
                        idempotency_key TEXT NOT NULL,
                        payload_hash TEXT NOT NULL,
                        status TEXT NOT NULL CHECK(status IN ('running', 'completed')),
                        owner_id TEXT,
                        lease_expires_ms INTEGER,
                        http_status INTEGER,
                        response_body TEXT,
                        response_headers_json TEXT,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(token_key, idempotency_key)
                    );
                    CREATE INDEX IF NOT EXISTS idempotency_records_updated
                        ON idempotency_records(updated_at_ms);
                    """
                )
                connection.execute(
                    "INSERT OR IGNORE INTO metadata(key, value) VALUES('schema_version', ?)",
                    (str(SCHEMA_VERSION),),
                )
                row = connection.execute(
                    "SELECT value FROM metadata WHERE key='schema_version'"
                ).fetchone()
                if row is None or int(row[0]) != SCHEMA_VERSION:
                    actual = "missing" if row is None else str(row[0])
                    raise RuntimeError(f"unsupported state database schema version: {actual}")
            self._initialized = True

    def ready(self) -> None:
        self.initialize()
        with self._transaction() as connection:
            for table in (
                "rate_events",
                "auth_sources",
                "daily_usage",
                "provider_leases",
                "idempotency_records",
            ):
                connection.execute(f"SELECT 1 FROM {table} LIMIT 1").fetchone()
            # This fixed-row upsert proves the configured path is currently writable,
            # not merely readable from an old image layer.
            connection.execute(
                "INSERT INTO metadata(key, value) VALUES('readiness_probe', ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (str(_now_ms()),),
            )

    def check_request_rates(
        self,
        buckets: Sequence[str],
        limit: int,
        window_seconds: int,
        *,
        now_ms: int | None = None,
    ) -> int | None:
        self.initialize()
        current = _now_ms() if now_ms is None else now_ms
        cutoff = current - window_seconds * 1_000
        unique_buckets = tuple(dict.fromkeys(buckets))
        with self._transaction() as connection:
            connection.execute("DELETE FROM rate_events WHERE occurred_at_ms <= ?", (cutoff,))
            retry_after = 0
            for bucket in unique_buckets:
                row = connection.execute(
                    "SELECT COUNT(*), MIN(occurred_at_ms) FROM rate_events "
                    "WHERE bucket=? AND occurred_at_ms > ?",
                    (bucket, cutoff),
                ).fetchone()
                count, oldest = int(row[0]), row[1]
                if count >= limit and oldest is not None:
                    retry_after = max(
                        retry_after,
                        _ceil_seconds(int(oldest) + window_seconds * 1_000 - current),
                    )
            if retry_after:
                return retry_after
            connection.executemany(
                "INSERT INTO rate_events(bucket, occurred_at_ms) VALUES(?, ?)",
                ((bucket, current) for bucket in unique_buckets),
            )
        return None

    def register_auth_failure(
        self,
        source_key: str,
        limit: int,
        window_seconds: int,
        backoff_base_seconds: int,
        backoff_max_seconds: int,
        capacity: int,
        ttl_seconds: int,
        *,
        now_ms: int | None = None,
    ) -> int | None:
        """Record one invalid credential in a hard-cap TTL/LRU source table."""

        self.initialize()
        current = _now_ms() if now_ms is None else now_ms
        cutoff = current - window_seconds * 1_000
        ttl_cutoff = current - ttl_seconds * 1_000
        with self._transaction() as connection:
            connection.execute("DELETE FROM auth_sources WHERE last_seen_ms <= ?", (ttl_cutoff,))
            row = connection.execute(
                "SELECT failures_json, blocked_until_ms FROM auth_sources WHERE source_key=?",
                (source_key,),
            ).fetchone()
            if row is None:
                count = int(connection.execute("SELECT COUNT(*) FROM auth_sources").fetchone()[0])
                if count >= capacity:
                    # Fixed-space LRU: evict exactly one oldest source before admitting a
                    # new bucket.  A WAF should still provide the outer connection limit.
                    connection.execute(
                        "DELETE FROM auth_sources WHERE source_key=("
                        "SELECT source_key FROM auth_sources ORDER BY last_seen_ms ASC, source_key ASC LIMIT 1)"
                    )
                failures: list[int] = []
                blocked_until = 0
            else:
                try:
                    failures = [int(value) for value in json.loads(str(row[0]))]
                except (TypeError, ValueError, json.JSONDecodeError):
                    failures = []
                blocked_until = int(row[1])

            failures = [value for value in failures if value > cutoff]
            if blocked_until > current:
                connection.execute(
                    "UPDATE auth_sources SET failures_json=?, last_seen_ms=? WHERE source_key=?",
                    (_compact_json(failures), current, source_key),
                )
                return _ceil_seconds(blocked_until - current)

            failures.append(current)
            # More than 21 post-threshold samples do not change the capped exponent.
            failures = failures[-(limit + 21) :]
            retry_after: int | None = None
            blocked_until = 0
            if len(failures) >= limit:
                exponent = min(len(failures) - limit, 20)
                penalty_seconds = min(backoff_max_seconds, backoff_base_seconds * (2**exponent))
                blocked_until = current + penalty_seconds * 1_000
                retry_after = penalty_seconds
            connection.execute(
                "INSERT INTO auth_sources(source_key, failures_json, blocked_until_ms, last_seen_ms) "
                "VALUES(?, ?, ?, ?) ON CONFLICT(source_key) DO UPDATE SET "
                "failures_json=excluded.failures_json, blocked_until_ms=excluded.blocked_until_ms, "
                "last_seen_ms=excluded.last_seen_ms",
                (source_key, _compact_json(failures), blocked_until, current),
            )
            return retry_after

    def acquire_provider(
        self,
        lease_id: str,
        token_key: str,
        concurrency_limit: int,
        daily_limit: int,
        lease_seconds: int,
        *,
        now_ms: int | None = None,
    ) -> ProviderAdmission:
        self.initialize()
        with self._transaction() as connection:
            # Resolve the quota day only after BEGIN IMMEDIATE owns the write
            # lock.  A request queued across UTC midnight therefore joins the
            # same day that its atomic quota decision actually runs in.
            current = _now_ms() if now_ms is None else now_ms
            day_utc, seconds_until_midnight = _utc_quota_window_at_ms(current)

            # Persist a monotonic quota-day high-water mark in the same write
            # transaction as admission.  If the host clock crosses midnight and
            # then moves back, fail closed until it catches up instead of treating
            # the old date as a fresh quota bucket.  MAX(daily_usage.day_utc)
            # bootstraps databases created by older versions that do not yet have
            # the metadata key.
            high_water_row = connection.execute(
                "SELECT value FROM metadata WHERE key=?",
                (_DAILY_USAGE_HIGH_WATER_KEY,),
            ).fetchone()
            usage_high_water_row = connection.execute(
                "SELECT MAX(day_utc) FROM daily_usage"
            ).fetchone()
            high_water_candidates = [day_utc]
            if high_water_row is not None:
                high_water_candidates.append(_validated_utc_day(high_water_row[0]))
            if usage_high_water_row is not None and usage_high_water_row[0] is not None:
                high_water_candidates.append(_validated_utc_day(usage_high_water_row[0]))
            high_water_day = max(high_water_candidates)
            if high_water_row is None or str(high_water_row[0]) != high_water_day:
                connection.execute(
                    "INSERT INTO metadata(key, value) VALUES(?, ?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (_DAILY_USAGE_HIGH_WATER_KEY, high_water_day),
                )

            if day_utc < high_water_day:
                return ProviderAdmission(
                    "daily_quota",
                    min(
                        lease_seconds,
                        _seconds_until_utc_day_at_ms(high_water_day, current),
                    ),
                )

            # Wall time is not monotonic and these leases survive process restarts.
            # If NTP/manual administration moves the host clock backwards, an
            # absolute expiry written a moment ago can otherwise turn a 60-second
            # lease into an hours-long Retry-After.  Rebase only anomalous rows to
            # the current wall-clock epoch while preserving (and hard-capping) the
            # originally persisted lease duration.  The BEGIN IMMEDIATE transaction
            # keeps this repair atomic across all local workers.
            lease_rows = connection.execute(
                "SELECT lease_id, expires_at_ms, created_at_ms FROM provider_leases"
            ).fetchall()
            max_provider_lease_ms = lease_seconds * 1_000
            for persisted_lease_id, raw_expiry, raw_created in lease_rows:
                expiry = int(raw_expiry)
                created = int(raw_created)
                if created > current or expiry > current + max_provider_lease_ms:
                    duration = max(1_000, min(max_provider_lease_ms, expiry - created))
                    connection.execute(
                        "UPDATE provider_leases SET expires_at_ms=?, created_at_ms=? WHERE lease_id=?",
                        (current + duration, current, str(persisted_lease_id)),
                    )

            connection.execute("DELETE FROM provider_leases WHERE expires_at_ms <= ?", (current,))
            # Retain the current high-water day and its predecessor.  The
            # high-water check above protects arbitrary larger regressions even
            # after rows outside this bounded window are pruned.
            retention_start = _utc_day_offset(
                high_water_day,
                -(_DAILY_USAGE_RETENTION_DAYS - 1),
            )
            connection.execute(
                "DELETE FROM daily_usage WHERE day_utc < ?",
                (retention_start,),
            )
            row = connection.execute(
                "SELECT calls FROM daily_usage WHERE day_utc=? AND token_key=?",
                (day_utc, token_key),
            ).fetchone()
            calls = int(row[0]) if row else 0
            if calls >= daily_limit:
                return ProviderAdmission("daily_quota", seconds_until_midnight)

            active = int(connection.execute("SELECT COUNT(*) FROM provider_leases").fetchone()[0])
            if active >= concurrency_limit:
                earliest = connection.execute(
                    "SELECT MIN(expires_at_ms) FROM provider_leases"
                ).fetchone()[0]
                retry_after = (
                    min(lease_seconds, _ceil_seconds(int(earliest) - current))
                    if earliest is not None
                    else 1
                )
                return ProviderAdmission("concurrency", retry_after)

            connection.execute(
                "INSERT INTO provider_leases(lease_id, token_key, expires_at_ms, created_at_ms) "
                "VALUES(?, ?, ?, ?)",
                (lease_id, token_key, current + lease_seconds * 1_000, current),
            )
            connection.execute(
                "INSERT INTO daily_usage(day_utc, token_key, calls) VALUES(?, ?, 1) "
                "ON CONFLICT(day_utc, token_key) DO UPDATE SET calls=calls+1",
                (day_utc, token_key),
            )
        return ProviderAdmission("admitted")

    def release_provider(self, lease_id: str) -> None:
        self.initialize()
        with self._transaction() as connection:
            connection.execute("DELETE FROM provider_leases WHERE lease_id=?", (lease_id,))

    def begin_idempotency(
        self,
        token_key: str,
        idempotency_key: str,
        payload_hash: str,
        owner_id: str,
        lease_seconds: int,
        retention_seconds: int,
        capacity: int,
        *,
        now_ms: int | None = None,
    ) -> IdempotencyDecision:
        self.initialize()
        with self._transaction() as connection:
            # Capture live time after acquiring the write lock. This avoids
            # mistaking a response completed by the previous lock holder for a
            # future-dated record merely because this request waited for it.
            current = _now_ms() if now_ms is None else now_ms
            retention_cutoff = current - retention_seconds * 1_000
            if retention_seconds <= 0:
                # A zero-retention deployment must also purge terminal rows left
                # by an earlier configuration.  Do not trust their wall-clock
                # timestamps: a rollback could otherwise keep a future-dated
                # response replayable indefinitely.
                connection.execute("DELETE FROM idempotency_records WHERE status='completed'")
            else:
                # A host-clock rollback must not extend plaintext response
                # retention beyond the configured window. Future-dated
                # terminal rows are anomalous and are purged fail-closed;
                # running leases are separately rebased below.
                connection.execute(
                    "DELETE FROM idempotency_records "
                    "WHERE status='completed' AND (updated_at_ms <= ? OR updated_at_ms > ?)",
                    (retention_cutoff, current),
                )
            connection.execute(
                "DELETE FROM idempotency_records WHERE status='running' "
                "AND lease_expires_ms IS NOT NULL AND lease_expires_ms <= ?",
                (retention_cutoff,),
            )
            row = connection.execute(
                "SELECT payload_hash, status, owner_id, lease_expires_ms, http_status, "
                "response_body, response_headers_json, updated_at_ms FROM idempotency_records "
                "WHERE token_key=? AND idempotency_key=?",
                (token_key, idempotency_key),
            ).fetchone()
            if row is not None:
                if str(row[0]) != payload_hash:
                    return IdempotencyDecision("conflict")
                if str(row[1]) == "completed":
                    try:
                        headers = json.loads(str(row[6] or "{}"))
                    except (TypeError, ValueError, json.JSONDecodeError):
                        headers = {}
                    return IdempotencyDecision(
                        "replay",
                        status_code=int(row[4]),
                        response_body=str(row[5]),
                        response_headers={str(key): str(value) for key, value in headers.items()},
                    )
                lease_expires = int(row[3] or 0)
                updated_at = int(row[7] or 0)
                max_duration_seconds = (
                    _MAX_IDEMPOTENCY_DEFER_SECONDS if row[2] is None else lease_seconds
                )
                max_duration_ms = max_duration_seconds * 1_000
                if updated_at > current or lease_expires > current + max_duration_ms:
                    duration = max(1_000, min(max_duration_ms, lease_expires - updated_at))
                    lease_expires = current + duration
                    connection.execute(
                        "UPDATE idempotency_records SET lease_expires_ms=?, updated_at_ms=? "
                        "WHERE token_key=? AND idempotency_key=? AND status='running'",
                        (lease_expires, current, token_key, idempotency_key),
                    )
                if lease_expires > current:
                    return IdempotencyDecision(
                        "in_progress",
                        retry_after=min(
                            max_duration_seconds,
                            _ceil_seconds(lease_expires - current),
                        ),
                    )
                connection.execute(
                    "UPDATE idempotency_records SET owner_id=?, lease_expires_ms=?, updated_at_ms=? "
                    "WHERE token_key=? AND idempotency_key=? AND status='running'",
                    (
                        owner_id,
                        current + lease_seconds * 1_000,
                        current,
                        token_key,
                        idempotency_key,
                    ),
                )
                return IdempotencyDecision("execute", owner_id=owner_id)

            count = int(connection.execute("SELECT COUNT(*) FROM idempotency_records").fetchone()[0])
            if count >= capacity:
                overflow = count - capacity + 1
                connection.execute(
                    "DELETE FROM idempotency_records WHERE rowid IN ("
                    "SELECT rowid FROM idempotency_records WHERE status='completed' "
                    "ORDER BY updated_at_ms ASC LIMIT ?)",
                    (overflow,),
                )
                count = int(connection.execute("SELECT COUNT(*) FROM idempotency_records").fetchone()[0])
                if count >= capacity:
                    return IdempotencyDecision("capacity", retry_after=lease_seconds)

            connection.execute(
                "INSERT INTO idempotency_records("
                "token_key, idempotency_key, payload_hash, status, owner_id, lease_expires_ms, "
                "created_at_ms, updated_at_ms) VALUES(?, ?, ?, 'running', ?, ?, ?, ?)",
                (
                    token_key,
                    idempotency_key,
                    payload_hash,
                    owner_id,
                    current + lease_seconds * 1_000,
                    current,
                    current,
                ),
            )
        return IdempotencyDecision("execute", owner_id=owner_id)

    def defer_idempotency(
        self,
        token_key: str,
        idempotency_key: str,
        owner_id: str,
        retry_after_seconds: int,
        *,
        now_ms: int | None = None,
    ) -> bool:
        """Persist a retry-not-before lease after an explicitly retryable failure.

        The record deliberately remains ``running`` with no owner.  Other workers
        therefore receive the remaining lease delay, while the first request can
        still return the provider's 429/503/504 response.  Once the lease expires,
        ``begin_idempotency`` atomically assigns a new owner for the same payload.
        """

        self.initialize()
        current = _now_ms() if now_ms is None else now_ms
        retry_after = min(
            _MAX_IDEMPOTENCY_DEFER_SECONDS,
            max(1, int(retry_after_seconds)),
        )
        with self._transaction() as connection:
            cursor = connection.execute(
                "UPDATE idempotency_records SET owner_id=NULL, lease_expires_ms=?, "
                "http_status=NULL, response_body=NULL, response_headers_json=NULL, updated_at_ms=? "
                "WHERE token_key=? AND idempotency_key=? AND status='running' AND owner_id=?",
                (
                    current + retry_after * 1_000,
                    current,
                    token_key,
                    idempotency_key,
                    owner_id,
                ),
            )
            return cursor.rowcount == 1

    def complete_idempotency(
        self,
        token_key: str,
        idempotency_key: str,
        owner_id: str,
        status_code: int,
        response_body: str,
        response_headers: dict[str, str],
        *,
        retain_response: bool = True,
        now_ms: int | None = None,
    ) -> bool:
        """Finish an owned execution, optionally without retaining its response.

        When terminal retention is disabled, deleting the still-owned running
        row in this same transaction makes the privacy setting immediate: no
        completed response is ever committed for a later cleanup pass to find.
        """

        self.initialize()
        current = _now_ms() if now_ms is None else now_ms
        with self._transaction() as connection:
            if not retain_response:
                cursor = connection.execute(
                    "DELETE FROM idempotency_records WHERE token_key=? AND idempotency_key=? "
                    "AND status='running' AND owner_id=?",
                    (token_key, idempotency_key, owner_id),
                )
                return cursor.rowcount == 1
            cursor = connection.execute(
                "UPDATE idempotency_records SET status='completed', owner_id=NULL, "
                "lease_expires_ms=NULL, http_status=?, response_body=?, response_headers_json=?, "
                "updated_at_ms=? WHERE token_key=? AND idempotency_key=? "
                "AND status='running' AND owner_id=?",
                (
                    status_code,
                    response_body,
                    _compact_json(response_headers),
                    current,
                    token_key,
                    idempotency_key,
                    owner_id,
                ),
            )
            return cursor.rowcount == 1

    def release_idempotency(self, token_key: str, idempotency_key: str, owner_id: str) -> None:
        self.initialize()
        with self._transaction() as connection:
            connection.execute(
                "DELETE FROM idempotency_records WHERE token_key=? AND idempotency_key=? "
                "AND status='running' AND owner_id=?",
                (token_key, idempotency_key, owner_id),
            )

    def counts_for_tests(self) -> dict[str, int]:
        self.initialize()
        tables = (
            "rate_events",
            "auth_sources",
            "daily_usage",
            "provider_leases",
            "idempotency_records",
        )
        with self._connection() as connection:
            return {
                table: int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
                for table in tables
            }

    def daily_calls_for_tests(self, day_utc: str, token_key: str) -> int:
        self.initialize()
        with self._connection() as connection:
            row = connection.execute(
                "SELECT calls FROM daily_usage WHERE day_utc=? AND token_key=?",
                (day_utc, token_key),
            ).fetchone()
            return int(row[0]) if row else 0

    def clear_for_tests(self) -> None:
        self.initialize()
        with self._transaction() as connection:
            for table in (
                "rate_events",
                "auth_sources",
                "daily_usage",
                "provider_leases",
                "idempotency_records",
            ):
                connection.execute(f"DELETE FROM {table}")
            connection.execute(
                "DELETE FROM metadata WHERE key=?",
                (_DAILY_USAGE_HIGH_WATER_KEY,),
            )

    @contextmanager
    def _transaction(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    @contextmanager
    def _connection(self, *, busy_timeout_ms: int | None = None) -> Iterator[sqlite3.Connection]:
        connection = self._connect(busy_timeout_ms=busy_timeout_ms)
        try:
            yield connection
        finally:
            connection.close()

    def _connect(self, *, busy_timeout_ms: int | None = None) -> sqlite3.Connection:
        effective_busy_timeout_ms = (
            self.busy_timeout_ms if busy_timeout_ms is None else busy_timeout_ms
        )
        connection = sqlite3.connect(
            self.path,
            timeout=effective_busy_timeout_ms / 1_000,
            isolation_level=None,
        )
        try:
            connection.execute(f"PRAGMA busy_timeout={effective_busy_timeout_ms}")
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA synchronous=FULL")
        except Exception:
            connection.close()
            raise
        return connection


def _now_ms() -> int:
    return time.time_ns() // 1_000_000


def _utc_quota_window_at_ms(now_ms: int) -> tuple[str, int]:
    now = datetime.fromtimestamp(now_ms / 1_000, tz=timezone.utc)
    milliseconds_into_day = now_ms % 86_400_000
    retry_after = max(1, math.ceil((86_400_000 - milliseconds_into_day) / 1_000))
    return now.date().isoformat(), retry_after


def _validated_utc_day(raw_day: object) -> str:
    value = str(raw_day)
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise RuntimeError("invalid persisted daily quota high-water day") from error
    if parsed.isoformat() != value:
        raise RuntimeError("invalid persisted daily quota high-water day")
    return value


def _utc_day_offset(day_utc: str, days: int) -> str:
    return (date.fromisoformat(day_utc) + timedelta(days=days)).isoformat()


def _seconds_until_utc_day_at_ms(day_utc: str, now_ms: int) -> int:
    target = datetime.combine(
        date.fromisoformat(day_utc),
        datetime.min.time(),
        tzinfo=timezone.utc,
    )
    target_ms = int(target.timestamp() * 1_000)
    return _ceil_seconds(target_ms - now_ms)


def _ceil_seconds(milliseconds: int) -> int:
    return max(1, math.ceil(milliseconds / 1_000))


def _compact_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
