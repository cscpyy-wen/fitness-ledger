import multiprocessing
import sqlite3
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from state_store import SQLiteStateStore


def _utc_ms(day: int, hour: int = 0, minute: int = 0, second: int = 0) -> int:
    return int(datetime(2026, 8, day, hour, minute, second, tzinfo=timezone.utc).timestamp() * 1_000)


def _provider_process_attempt(database_path, index, start_event, output_queue):
    start_event.wait(10)
    try:
        result = SQLiteStateStore(database_path).acquire_provider(
            f"process-lease-{index}",
            "token",
            1,
            100,
            30,
            now_ms=_utc_ms(29, second=20),
        )
        output_queue.put(result.reason)
    except Exception as error:  # pragma: no cover - reported to the parent assertion
        output_queue.put(f"error:{type(error).__name__}:{error}")


class SQLiteStateStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp_directory = tempfile.TemporaryDirectory()
        self.database_path = str(Path(self.temp_directory.name) / "shared-state.db")

    def tearDown(self):
        self.temp_directory.cleanup()

    def test_request_rate_survives_new_store_instance(self):
        first_process = SQLiteStateStore(self.database_path)
        self.assertIsNone(
            first_process.check_request_rates(("token:a", "client:a"), 1, 60, now_ms=1_000)
        )

        restarted_process = SQLiteStateStore(self.database_path)
        retry_after = restarted_process.check_request_rates(
            ("token:a", "client:a"), 1, 60, now_ms=2_000
        )
        self.assertEqual(59, retry_after)

    def test_daily_utc_quota_survives_restart_and_resets_on_new_day(self):
        first_process = SQLiteStateStore(self.database_path)
        admitted = first_process.acquire_provider(
            "lease-1", "token", 2, 1, 30, now_ms=_utc_ms(29, 23, 59, 50)
        )
        self.assertEqual("admitted", admitted.reason)
        first_process.release_provider("lease-1")

        restarted_process = SQLiteStateStore(self.database_path)
        denied = restarted_process.acquire_provider(
            "lease-2", "token", 2, 1, 30, now_ms=_utc_ms(29, 23, 59, 51)
        )
        next_day = restarted_process.acquire_provider(
            "lease-3", "token", 2, 1, 30, now_ms=_utc_ms(30, second=3)
        )
        self.assertEqual("daily_quota", denied.reason)
        self.assertEqual(9, denied.retry_after)
        self.assertEqual("admitted", next_day.reason)

    def test_clock_rollback_is_fail_closed_after_new_day_seen(self):
        store = SQLiteStateStore(self.database_path)
        new_day = store.acquire_provider(
            "lease-new-day",
            "token",
            2,
            10,
            30,
            now_ms=_utc_ms(30, second=2),
        )
        self.assertEqual("admitted", new_day.reason)
        store.release_provider("lease-new-day")

        stale_previous_day = store.acquire_provider(
            "lease-previous-day",
            "token",
            2,
            10,
            30,
            now_ms=_utc_ms(29, 23, 59, 59),
        )
        self.assertEqual("daily_quota", stale_previous_day.reason)
        self.assertEqual(1, stale_previous_day.retry_after)
        self.assertEqual(1, store.daily_calls_for_tests("2026-08-30", "token"))

        current_day = store.acquire_provider(
            "lease-current-day",
            "token",
            2,
            10,
            30,
            now_ms=_utc_ms(30, second=4),
        )
        self.assertEqual("admitted", current_day.reason)
        self.assertEqual(0, store.daily_calls_for_tests("2026-08-29", "token"))
        self.assertEqual(2, store.daily_calls_for_tests("2026-08-30", "token"))

    def test_cross_day_clock_rollback_remains_fail_closed_without_hours_retry_after(self):
        store = SQLiteStateStore(self.database_path)
        self.assertEqual(
            "admitted",
            store.acquire_provider(
                "new-day", "token", 2, 10, 45, now_ms=_utc_ms(30, hour=2)
            ).reason,
        )
        store.release_provider("new-day")

        rolled_back = store.acquire_provider(
            "rolled-back-two-hours",
            "token",
            2,
            10,
            45,
            now_ms=_utc_ms(29, hour=22),
        )
        self.assertEqual("daily_quota", rolled_back.reason)
        self.assertEqual(45, rolled_back.retry_after)

    def test_exhausted_previous_day_stays_denied_after_restart_and_clock_rollback(self):
        first = SQLiteStateStore(self.database_path)
        exhausted_day = first.acquire_provider(
            "lease-day-29",
            "token",
            2,
            1,
            30,
            now_ms=_utc_ms(29, 23, 59, 50),
        )
        self.assertEqual("admitted", exhausted_day.reason)
        first.release_provider("lease-day-29")

        next_day = first.acquire_provider(
            "lease-day-30",
            "token",
            2,
            1,
            30,
            now_ms=_utc_ms(30, second=2),
        )
        self.assertEqual("admitted", next_day.reason)
        first.release_provider("lease-day-30")

        restarted = SQLiteStateStore(self.database_path)
        rolled_back = restarted.acquire_provider(
            "lease-rollback",
            "token",
            2,
            1,
            30,
            now_ms=_utc_ms(29, 23, 59, 55),
        )
        self.assertEqual("daily_quota", rolled_back.reason)
        self.assertEqual(5, rolled_back.retry_after)
        self.assertEqual(1, restarted.daily_calls_for_tests("2026-08-29", "token"))
        self.assertEqual(1, restarted.daily_calls_for_tests("2026-08-30", "token"))

    def test_concurrent_rollback_attempts_cannot_reopen_exhausted_previous_day(self):
        first = SQLiteStateStore(self.database_path)
        self.assertEqual(
            "admitted",
            first.acquire_provider(
                "lease-old",
                "token",
                4,
                1,
                30,
                now_ms=_utc_ms(29, 23, 59, 50),
            ).reason,
        )
        first.release_provider("lease-old")
        self.assertEqual(
            "admitted",
            first.acquire_provider(
                "lease-new",
                "token",
                4,
                1,
                30,
                now_ms=_utc_ms(30, second=2),
            ).reason,
        )
        first.release_provider("lease-new")

        workers = 8
        barrier = threading.Barrier(workers)

        def attempt(index):
            restarted = SQLiteStateStore(self.database_path)
            barrier.wait()
            return restarted.acquire_provider(
                f"rollback-{index}",
                "token",
                workers,
                1,
                30,
                now_ms=_utc_ms(29, 23, 59, 55),
            ).reason

        with ThreadPoolExecutor(max_workers=workers) as executor:
            outcomes = list(executor.map(attempt, range(workers)))
        self.assertEqual(["daily_quota"] * workers, outcomes)
        verifier = SQLiteStateStore(self.database_path)
        self.assertEqual(1, verifier.daily_calls_for_tests("2026-08-29", "token"))
        self.assertEqual(1, verifier.daily_calls_for_tests("2026-08-30", "token"))

    def test_existing_daily_usage_bootstraps_missing_high_water_metadata(self):
        store = SQLiteStateStore(self.database_path)
        self.assertEqual(
            "admitted",
            store.acquire_provider(
                "legacy-new-day",
                "token",
                2,
                10,
                30,
                now_ms=_utc_ms(30, second=2),
            ).reason,
        )
        store.release_provider("legacy-new-day")
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "DELETE FROM metadata WHERE key='daily_usage_high_water_day_utc'"
            )
            connection.commit()
        finally:
            connection.close()

        restarted = SQLiteStateStore(self.database_path)
        rolled_back = restarted.acquire_provider(
            "legacy-rollback",
            "token",
            2,
            10,
            30,
            now_ms=_utc_ms(29, 23, 59, 59),
        )
        self.assertEqual("daily_quota", rolled_back.reason)
        self.assertEqual(1, rolled_back.retry_after)

    def test_independent_instances_atomically_share_one_concurrency_slot(self):
        SQLiteStateStore(self.database_path).initialize()
        workers = 8
        barrier = threading.Barrier(workers)

        def attempt(index):
            store = SQLiteStateStore(self.database_path)
            barrier.wait()
            return store.acquire_provider(
                f"lease-{index}",
                "token",
                1,
                100,
                30,
                now_ms=_utc_ms(29, second=10),
            ).reason

        with ThreadPoolExecutor(max_workers=workers) as executor:
            outcomes = list(executor.map(attempt, range(workers)))
        self.assertEqual(1, outcomes.count("admitted"))
        self.assertEqual(workers - 1, outcomes.count("concurrency"))
        verifier = SQLiteStateStore(self.database_path)
        self.assertEqual(1, verifier.daily_calls_for_tests("2026-08-29", "token"))

    def test_independent_instances_atomically_share_rate_limit(self):
        SQLiteStateStore(self.database_path).initialize()
        workers = 8
        barrier = threading.Barrier(workers)

        def attempt(index):
            store = SQLiteStateStore(self.database_path)
            barrier.wait()
            return store.check_request_rates(("token:shared",), 1, 60, now_ms=10_000)

        with ThreadPoolExecutor(max_workers=workers) as executor:
            outcomes = list(executor.map(attempt, range(workers)))
        self.assertEqual(1, outcomes.count(None))
        self.assertEqual(workers - 1, sum(value is not None for value in outcomes))

    def test_spawned_processes_atomically_share_provider_slot(self):
        context = multiprocessing.get_context("spawn")
        start_event = context.Event()
        output_queue = context.Queue()
        processes = [
            context.Process(
                target=_provider_process_attempt,
                args=(self.database_path, index, start_event, output_queue),
            )
            for index in range(4)
        ]
        for process in processes:
            process.start()
        start_event.set()
        outcomes = [output_queue.get(timeout=20) for _ in processes]
        for process in processes:
            process.join(timeout=20)
            self.assertEqual(0, process.exitcode)
        output_queue.close()
        output_queue.join_thread()
        self.assertFalse([outcome for outcome in outcomes if outcome.startswith("error:")])
        self.assertEqual(1, outcomes.count("admitted"))
        self.assertEqual(3, outcomes.count("concurrency"))

    def test_provider_lease_recovers_after_expiry(self):
        first = SQLiteStateStore(self.database_path)
        self.assertEqual(
            "admitted",
            first.acquire_provider(
                "orphan", "token", 1, 10, 10, now_ms=_utc_ms(29, second=1)
            ).reason,
        )
        second = SQLiteStateStore(self.database_path)
        blocked = second.acquire_provider(
            "second", "token", 1, 10, 10, now_ms=_utc_ms(29, second=2)
        )
        recovered = second.acquire_provider(
            "recovered", "token", 1, 10, 10, now_ms=_utc_ms(29, second=11) + 1
        )
        self.assertEqual("concurrency", blocked.reason)
        self.assertEqual(9, blocked.retry_after)
        self.assertEqual("admitted", recovered.reason)

    def test_provider_lease_retry_after_is_rebased_after_wall_clock_rollback(self):
        first = SQLiteStateStore(self.database_path)
        future = _utc_ms(29, hour=12)
        self.assertEqual(
            "admitted",
            first.acquire_provider(
                "future-lease", "token", 1, 10, 60, now_ms=future
            ).reason,
        )

        rolled_back = future - 2 * 60 * 60 * 1_000
        restarted = SQLiteStateStore(self.database_path)
        blocked = restarted.acquire_provider(
            "blocked-after-rollback", "token", 1, 10, 60, now_ms=rolled_back
        )
        recovered = restarted.acquire_provider(
            "recovered-after-rebased-expiry",
            "token",
            1,
            10,
            60,
            now_ms=rolled_back + 60_001,
        )

        self.assertEqual("concurrency", blocked.reason)
        self.assertEqual(60, blocked.retry_after)
        self.assertEqual("admitted", recovered.reason)

    def test_completed_idempotency_result_survives_restart_and_conflicts(self):
        first = SQLiteStateStore(self.database_path)
        started = first.begin_idempotency(
            "token", "stable-key", "payload-a", "owner-a", 30, 3_600, 100, now_ms=1_000
        )
        self.assertEqual("execute", started.action)
        self.assertTrue(
            first.complete_idempotency(
                "token",
                "stable-key",
                "owner-a",
                200,
                '{"ok":true}',
                {"Retry-After": "3"},
                now_ms=2_000,
            )
        )

        restarted = SQLiteStateStore(self.database_path)
        replay = restarted.begin_idempotency(
            "token", "stable-key", "payload-a", "owner-b", 30, 3_600, 100, now_ms=3_000
        )
        conflict = restarted.begin_idempotency(
            "token", "stable-key", "payload-b", "owner-c", 30, 3_600, 100, now_ms=3_000
        )
        self.assertEqual("replay", replay.action)
        self.assertEqual(200, replay.status_code)
        self.assertEqual('{"ok":true}', replay.response_body)
        self.assertEqual("conflict", conflict.action)

    def test_zero_retention_disables_completed_response_replay(self):
        store = SQLiteStateStore(self.database_path)
        self.assertEqual(
            "execute",
            store.begin_idempotency(
                "token", "no-retention", "payload", "owner-a", 30, 0, 100, now_ms=1_000
            ).action,
        )
        self.assertTrue(
            store.complete_idempotency(
                "token",
                "no-retention",
                "owner-a",
                200,
                '{"ok":true}',
                {},
                retain_response=False,
                now_ms=2_000,
            )
        )
        self.assertEqual(0, store.counts_for_tests()["idempotency_records"])
        self.assertEqual(
            "execute",
            store.begin_idempotency(
                "token", "no-retention", "payload", "owner-b", 30, 0, 100, now_ms=2_001
            ).action,
        )

    def test_zero_retention_purges_future_dated_terminal_rows_after_clock_rollback(self):
        store = SQLiteStateStore(self.database_path)
        self.assertEqual(
            "execute",
            store.begin_idempotency(
                "token", "future-terminal", "payload", "owner-a", 30, 3_600, 100,
                now_ms=10_000_000,
            ).action,
        )
        self.assertTrue(
            store.complete_idempotency(
                "token", "future-terminal", "owner-a", 200, '{"secret":true}', {},
                now_ms=10_001_000,
            )
        )

        decision = store.begin_idempotency(
            "token", "future-terminal", "payload", "owner-b", 30, 0, 100,
            now_ms=1_000,
        )

        self.assertEqual("execute", decision.action)
        self.assertEqual("owner-b", decision.owner_id)
        self.assertTrue(
            store.complete_idempotency(
                "token", "future-terminal", "owner-b", 200, '{"secret":true}', {},
                retain_response=False,
                now_ms=2_000,
            )
        )
        self.assertEqual(0, store.counts_for_tests()["idempotency_records"])

    def test_positive_retention_purges_future_terminal_after_clock_rollback(self):
        store = SQLiteStateStore(self.database_path)
        future = 10 * 24 * 60 * 60 * 1_000
        self.assertEqual(
            "execute",
            store.begin_idempotency(
                "token",
                "retained-future",
                "payload",
                "owner-a",
                30,
                7 * 86_400,
                100,
                now_ms=future,
            ).action,
        )
        self.assertTrue(
            store.complete_idempotency(
                "token",
                "retained-future",
                "owner-a",
                200,
                '{"secret":true}',
                {},
                now_ms=future + 1_000,
            )
        )

        rolled_back = 1_000
        decision = store.begin_idempotency(
            "token",
            "retained-future",
            "payload",
            "owner-b",
            30,
            7 * 86_400,
            100,
            now_ms=rolled_back,
        )

        self.assertEqual("execute", decision.action)
        self.assertEqual("owner-b", decision.owner_id)
        connection = sqlite3.connect(self.database_path)
        try:
            row = connection.execute(
                "SELECT status, response_body, updated_at_ms FROM idempotency_records "
                "WHERE token_key='token' AND idempotency_key='retained-future'"
            ).fetchone()
        finally:
            connection.close()
        self.assertEqual(("running", None, rolled_back), row)

    def test_running_idempotency_lease_blocks_then_recovers(self):
        first = SQLiteStateStore(self.database_path)
        first.begin_idempotency(
            "token", "running-key", "payload", "owner-a", 10, 3_600, 100, now_ms=1_000
        )
        second = SQLiteStateStore(self.database_path)
        in_progress = second.begin_idempotency(
            "token", "running-key", "payload", "owner-b", 10, 3_600, 100, now_ms=5_000
        )
        recovered = second.begin_idempotency(
            "token", "running-key", "payload", "owner-c", 10, 3_600, 100, now_ms=11_001
        )
        old_owner = first.complete_idempotency(
            "token", "running-key", "owner-a", 200, "{}", {}, now_ms=12_000
        )
        new_owner = second.complete_idempotency(
            "token", "running-key", "owner-c", 200, '{"recovered":true}', {}, now_ms=12_000
        )
        self.assertEqual("in_progress", in_progress.action)
        self.assertEqual(6, in_progress.retry_after)
        self.assertEqual("execute", recovered.action)
        self.assertFalse(old_owner)
        self.assertTrue(new_owner)

    def test_idempotency_retry_after_is_rebased_after_wall_clock_rollback(self):
        future = 10_000_000
        first = SQLiteStateStore(self.database_path)
        self.assertEqual(
            "execute",
            first.begin_idempotency(
                "token", "rollback-key", "payload", "owner-a", 45, 3_600, 100,
                now_ms=future,
            ).action,
        )

        rolled_back = future - 2 * 60 * 60 * 1_000
        restarted = SQLiteStateStore(self.database_path)
        waiting = restarted.begin_idempotency(
            "token", "rollback-key", "payload", "owner-b", 45, 3_600, 100,
            now_ms=rolled_back,
        )
        recovered = restarted.begin_idempotency(
            "token", "rollback-key", "payload", "owner-c", 45, 3_600, 100,
            now_ms=rolled_back + 45_001,
        )

        self.assertEqual("in_progress", waiting.action)
        self.assertEqual(45, waiting.retry_after)
        self.assertEqual("execute", recovered.action)
        self.assertEqual("owner-c", recovered.owner_id)

    def test_deferred_retry_window_survives_restart_then_allows_takeover(self):
        first = SQLiteStateStore(self.database_path)
        started = first.begin_idempotency(
            "token", "deferred-key", "payload", "owner-a", 30, 3_600, 100, now_ms=1_000
        )
        self.assertEqual("execute", started.action)
        self.assertTrue(
            first.defer_idempotency(
                "token",
                "deferred-key",
                "owner-a",
                retry_after_seconds=10,
                now_ms=2_000,
            )
        )

        restarted = SQLiteStateStore(self.database_path)
        waiting = restarted.begin_idempotency(
            "token", "deferred-key", "payload", "owner-b", 30, 3_600, 100, now_ms=5_000
        )
        recovered = restarted.begin_idempotency(
            "token", "deferred-key", "payload", "owner-c", 30, 3_600, 100, now_ms=12_001
        )
        self.assertEqual("in_progress", waiting.action)
        self.assertEqual(7, waiting.retry_after)
        self.assertEqual("execute", recovered.action)
        self.assertEqual("owner-c", recovered.owner_id)

    def test_auth_source_table_has_hard_capacity_and_ttl(self):
        store = SQLiteStateStore(self.database_path)
        for index in range(30):
            store.register_auth_failure(
                f"source-{index}",
                limit=5,
                window_seconds=60,
                backoff_base_seconds=2,
                backoff_max_seconds=60,
                capacity=8,
                ttl_seconds=120,
                now_ms=index * 1_000,
            )
        self.assertEqual(8, store.counts_for_tests()["auth_sources"])

        restarted = SQLiteStateStore(self.database_path)
        restarted.register_auth_failure(
            "fresh-source",
            limit=5,
            window_seconds=60,
            backoff_base_seconds=2,
            backoff_max_seconds=60,
            capacity=8,
            ttl_seconds=120,
            now_ms=200_000,
        )
        self.assertEqual(1, restarted.counts_for_tests()["auth_sources"])

    def test_auth_backoff_persists_across_store_instances(self):
        first = SQLiteStateStore(self.database_path)
        self.assertIsNone(
            first.register_auth_failure(
                "source",
                2,
                60,
                3,
                12,
                8,
                120,
                now_ms=1_000,
            )
        )
        restarted = SQLiteStateStore(self.database_path)
        self.assertEqual(
            3,
            restarted.register_auth_failure(
                "source",
                2,
                60,
                3,
                12,
                8,
                120,
                now_ms=2_000,
            ),
        )
        self.assertEqual(
            2,
            restarted.register_auth_failure(
                "source",
                2,
                60,
                3,
                12,
                8,
                120,
                now_ms=3_001,
            ),
        )


if __name__ == "__main__":
    unittest.main()
