"""Pure validation and normalization for the meal-analysis proxy."""

from __future__ import annotations

import json
import math
from typing import Any


ALLOWED_RISK_FLAGS = {
    "UNKNOWN_OIL",
    "UNKNOWN_SAUCE",
    "MIXED_DISH",
    "HIDDEN_INGREDIENTS",
    "LOW_IDENTITY_CONFIDENCE",
}
ALLOWED_TIERS = {"A", "B", "C", "D"}


def extract_json_object(text: str) -> dict[str, Any]:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.removeprefix("```json").removeprefix("```").strip()
        if cleaned.endswith("```"):
            cleaned = cleaned[:-3].strip()
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("视觉模型没有返回 JSON 对象")
    parsed = json.loads(cleaned[start : end + 1])
    if not isinstance(parsed, dict):
        raise ValueError("视觉模型响应必须是 JSON 对象")
    return parsed


def normalize_provider_result(payload: dict[str, Any], model_name: str) -> dict[str, Any]:
    raw_items = payload.get("items")
    if not isinstance(raw_items, list) or not 1 <= len(raw_items) <= 30:
        raise ValueError("items 必须包含 1–30 项食物")

    items = [_normalize_item(item) for item in raw_items]
    if not any(_looks_like_oil(item["name"]) for item in items):
        items[0]["riskFlags"] = sorted(set(items[0]["riskFlags"]) | {"UNKNOWN_OIL"})

    requested_tier = str(payload.get("evidenceTier", "C")).upper()
    if requested_tier not in ALLOWED_TIERS:
        requested_tier = "C"
    # A single uncalibrated photo can never be promoted above C by the proxy.
    evidence_tier = requested_tier if requested_tier in {"C", "D"} else "C"
    reason = _text(payload.get("evidenceReason"), 240) or (
        "单张照片可识别食物候选，但克重、烹调油、酱汁和隐藏配料仍需用户核对"
    )
    return {
        "items": items,
        "evidenceTier": evidence_tier,
        "evidenceReason": reason,
        "providerLabel": f"AI 估算 · {model_name[:60]} · 单图需确认",
    }


def _normalize_item(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("每个食物项必须是 JSON 对象")
    name = _text(raw.get("name"), 80)
    if not name:
        raise ValueError("食物名称不能为空")

    grams = _number(raw.get("grams"), "grams", 1.0, 5000.0)
    grams_min = _number(raw.get("gramsMin", grams * 0.75), "gramsMin", 1.0, 5000.0)
    grams_max = _number(raw.get("gramsMax", grams * 1.25), "gramsMax", 1.0, 5000.0)
    if not grams_min <= grams <= grams_max:
        raise ValueError(f"{name} 的克重区间无效")

    raw_per100 = raw.get("per100g")
    if not isinstance(raw_per100, dict):
        raise ValueError(f"{name} 缺少 per100g")
    per100g = {
        "kcal": _number(raw_per100.get("kcal"), "kcal", 0.0, 1000.0),
        "carbsG": _number(raw_per100.get("carbsG"), "carbsG", 0.0, 100.0),
        "proteinG": _number(raw_per100.get("proteinG"), "proteinG", 0.0, 100.0),
        "fatG": _number(raw_per100.get("fatG"), "fatG", 0.0, 100.0),
    }
    alternatives = raw.get("alternatives", [])
    if not isinstance(alternatives, list):
        alternatives = []
    alternatives = [value for value in (_text(item, 80) for item in alternatives[:8]) if value]
    flags = raw.get("riskFlags", [])
    if not isinstance(flags, list):
        flags = []
    risk_flags = sorted({str(flag).upper() for flag in flags} & ALLOWED_RISK_FLAGS)
    tier = str(raw.get("evidenceTier", "C")).upper()
    if tier not in ALLOWED_TIERS or tier in {"A", "B"}:
        tier = "C"

    return {
        "name": name,
        "grams": grams,
        "gramsMin": grams_min,
        "gramsMax": grams_max,
        "per100g": per100g,
        "sourceName": _text(raw.get("sourceName"), 120) or "视觉模型估算，待用户核对",
        "evidenceTier": tier,
        "riskFlags": risk_flags,
        "alternatives": alternatives,
    }


def _number(value: Any, label: str, minimum: float, maximum: float) -> float:
    if isinstance(value, bool):
        raise ValueError(f"{label} 不是有效数值")
    try:
        number = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{label} 不是有效数值") from error
    if not math.isfinite(number) or not minimum <= number <= maximum:
        raise ValueError(f"{label} 超出 {minimum}–{maximum} 的允许范围")
    return round(number, 2)


def _text(value: Any, limit: int) -> str:
    return str(value or "").strip()[:limit]


def _looks_like_oil(name: str) -> bool:
    lowered = name.lower()
    return any(token in lowered for token in ("油", "oil", "黄油", "butter"))
