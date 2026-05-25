#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""evolIA value accumulation, driven by the evolutive formula.

The accumulator turns the core formula (`evolia_evolve`) into a growing total.
Per cycle:

    base = BTC-e of the digital actions recorded since the last cycle
    V    = evolve(...).v_normalized            # cognitive multiplier in 0..1
    gain = base * (1 + V) + SENSOR_FLOOR * V   # actions amplified by cognition,
                                               # plus a floor so movement alone
                                               # still accrues value
    total_v += gain

So actions are the baseline (video worth the most), the evolutive V amplifies
them, and the sensors — accelerometer, gyroscope, magnetometer, location, WiFi
and BLE — feed V. State persists under EVOLIA_HOME and the headline figures are
mirrored to evolia_identity_state.json for ganache_db and the dashboard.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import evolia_paths as paths
from evolia_evolve import ACTION_RATES, evolve
from evolia_sensors import SensorSample

# Max value (BTC-e) a single full-activity cycle can accrue from sensors alone.
SENSOR_FLOOR = 1.0


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


class EvoliaValue:
    def __init__(self, state_path: Path | None = None):
        self.state_path = state_path or paths.value_state()
        self.total_v = 0.0
        self.cycle_count = 0
        self.location_count = 0
        self.action_counts = {k: 0 for k in ACTION_RATES}
        self._pending_base = 0.0  # BTC-e of actions not yet folded into a cycle

    # --- inputs --------------------------------------------------------------

    def record_action(self, kind: str, count: int = 1) -> float:
        """Record digital action(s); returns the base BTC-e they contribute."""
        if kind not in ACTION_RATES:
            raise ValueError(f"unknown action: {kind!r} (expected {list(ACTION_RATES)})")
        if count < 0:
            raise ValueError("count must be >= 0")
        base = ACTION_RATES[kind] * count
        self.action_counts[kind] += count
        self._pending_base += base
        return base

    def cycle(self, sample: SensorSample, elapsed_seconds: float = 0.0) -> dict:
        """Advance one cycle: fold pending actions + sensors, persist, summarize."""
        self.cycle_count += 1
        if sample.location_fix:
            self.location_count += 1

        result = evolve(self.action_counts, elapsed_seconds, sample, self.location_count)
        base = self._pending_base
        self._pending_base = 0.0

        gain = base * (1.0 + result.v_normalized) + SENSOR_FLOOR * result.v_normalized
        self.total_v += gain
        self.save()

        return {
            "cycle": self.cycle_count,
            "base_btc_e": round(base, 4),
            "v_normalized": round(result.v_normalized, 4),
            "gain": round(gain, 4),
            "total_v": round(self.total_v, 4),
        }

    # --- persistence ---------------------------------------------------------

    def to_dict(self) -> dict:
        # total_v kept at full precision so reloads are exact.
        return {
            "timestamp": _now(),
            "total_v": self.total_v,
            "cycle_count": self.cycle_count,
            "location_count": self.location_count,
            "action_counts": self.action_counts,
        }

    def save(self) -> None:
        # Atomic writes: a signal-9 kill mid-save must never corrupt the state
        # and lose the accumulated total_v (the headline figure).
        state = self.to_dict()
        paths.atomic_write_text(self.state_path, json.dumps(state, indent=2))
        identity = self.state_path.parent / paths.identity_state().name
        paths.atomic_write_text(identity, json.dumps(
            {
                "total_v": state["total_v"],
                "cycle_count": self.cycle_count,
                "timestamp": state["timestamp"],
            },
            indent=2,
        ))

    def load(self) -> bool:
        if not self.state_path.exists():
            return False
        try:
            data = json.loads(self.state_path.read_text())
        except (OSError, ValueError):
            return False
        self.total_v = float(data.get("total_v", 0.0))
        self.cycle_count = int(data.get("cycle_count", 0))
        self.location_count = int(data.get("location_count", 0))
        self.action_counts.update(data.get("action_counts", {}))
        return True


if __name__ == "__main__":
    from evolia_sensors import read_all

    value = EvoliaValue()
    value.load()
    value.record_action("video_taken")
    value.record_action("photo_taken")
    value.record_action("screen_input", count=20)
    summary = value.cycle(read_all(), elapsed_seconds=5.0)
    print(json.dumps({"summary": summary, "state": value.to_dict()}, indent=2))
