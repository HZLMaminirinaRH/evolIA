#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""evolIA cognitive-value accumulation.

The value `V` (denominated in "BTC-e", an internal unit) grows from two
families of inputs, exactly as requested:

  Digital actions (discrete events, each adds a fixed amount):
    screen_input  a screen tap / keystroke
    sms_sent      an SMS sent
    photo_taken   a photo captured
    video_taken   a video captured

  Sensors (sampled each cycle, each adds weight x normalized-activity in 0..1):
    accelerometer  motion magnitude        (m/s^2     / 19.6)
    gyroscope      rotation magnitude       (rad/s     / 4.36  ~ 250 deg/s)
    magnetometer   field magnitude          (microT    / 65)
    location       a GPS/network fix present (1.0 or 0.0)
    wifi           nearby access points      (count     / 10)
    ble            nearby BLE devices        (count     / 10)

Per cycle:   gain = sum(action gains since last cycle) + sum(sensor gains)
             total_v += gain

All weights below are deliberately simple and TUNABLE — tweak the two tables
to change how much each signal is worth. State is persisted under EVOLIA_HOME
and also mirrored to evolia_identity_state.json (total_v, cycle_count) so the
blockchain/dashboard services can read it.
"""

from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path

from evolia_sensors import SensorSample

# --- Tunable model -----------------------------------------------------------

ACTION_RATES = {
    "screen_input": 0.05,  # frequent, low value each
    "sms_sent": 1.20,
    "photo_taken": 2.50,
    "video_taken": 5.00,   # richest single action
}

SENSOR_WEIGHTS = {
    "accelerometer": 0.30,
    "gyroscope": 0.30,
    "magnetometer": 0.20,
    "location": 1.00,      # a fix is a strong, infrequent signal
    "wifi": 0.40,
    "ble": 0.40,
}

# Divisors that map a raw sensor reading onto a 0..1 activity scale.
_NORM = {
    "accelerometer": lambda s: min(s.accelerometer / 19.6, 1.0),
    "gyroscope": lambda s: min(s.gyroscope / 4.36, 1.0),
    "magnetometer": lambda s: min(s.magnetometer / 65.0, 1.0),
    "location": lambda s: 1.0 if s.location_fix else 0.0,
    "wifi": lambda s: min(s.wifi_count / 10.0, 1.0),
    "ble": lambda s: min(s.ble_count / 10.0, 1.0),
}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def evolia_home() -> Path:
    env = os.environ.get("EVOLIA_HOME")
    if env:
        return Path(env)
    return Path(os.path.expanduser("~")) / "evolia"


def normalize(sample: SensorSample) -> dict[str, float]:
    """Map a SensorSample onto per-sensor activity in 0..1."""
    return {name: fn(sample) for name, fn in _NORM.items()}


class EvoliaValue:
    """Accumulator for the cognitive value V."""

    def __init__(self, state_path: Path | None = None):
        self.state_path = state_path or (evolia_home() / "evolia_value_state.json")
        self.total_v = 0.0
        self.cycle_count = 0
        self.action_counts = {k: 0 for k in ACTION_RATES}
        self.sensor_totals = {k: 0.0 for k in SENSOR_WEIGHTS}

    # --- inputs --------------------------------------------------------------

    def record_action(self, kind: str, count: int = 1) -> float:
        """Record one or more digital actions; returns the value gained."""
        if kind not in ACTION_RATES:
            raise ValueError(f"unknown action: {kind!r} (expected one of {list(ACTION_RATES)})")
        if count < 0:
            raise ValueError("count must be >= 0")
        gain = ACTION_RATES[kind] * count
        self.action_counts[kind] += count
        self.total_v += gain
        return gain

    def record_sensors(self, sample: SensorSample) -> float:
        """Fold one sensor sample into the total; returns the value gained."""
        norm = normalize(sample)
        gain = 0.0
        for name, weight in SENSOR_WEIGHTS.items():
            contribution = weight * norm[name]
            self.sensor_totals[name] += contribution
            gain += contribution
        self.total_v += gain
        return gain

    def cycle(self, sample: SensorSample) -> dict:
        """Advance one cycle: fold sensors, persist, and return a summary."""
        self.cycle_count += 1
        sensor_gain = self.record_sensors(sample)
        self.save()
        return {
            "cycle": self.cycle_count,
            "sensor_gain": round(sensor_gain, 4),
            "total_v": round(self.total_v, 4),
        }

    # --- persistence ---------------------------------------------------------

    def to_dict(self) -> dict:
        return {
            "timestamp": _now(),
            "total_v": round(self.total_v, 6),
            "cycle_count": self.cycle_count,
            "action_counts": self.action_counts,
            "sensor_totals": {k: round(v, 6) for k, v in self.sensor_totals.items()},
        }

    def save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        state = self.to_dict()
        self.state_path.write_text(json.dumps(state, indent=2))
        # Mirror the headline figures where the other services look for them.
        identity = self.state_path.parent / "evolia_identity_state.json"
        identity.write_text(json.dumps(
            {"total_v": state["total_v"], "cycle_count": self.cycle_count, "timestamp": state["timestamp"]},
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
        self.action_counts.update(data.get("action_counts", {}))
        self.sensor_totals.update(data.get("sensor_totals", {}))
        return True


if __name__ == "__main__":
    from evolia_sensors import read_all

    value = EvoliaValue()
    value.load()

    # Demonstrate the digital-action side.
    value.record_action("photo_taken")
    value.record_action("sms_sent", count=2)
    value.record_action("screen_input", count=40)

    # Read real sensors if on-device, otherwise a neutral sample.
    summary = value.cycle(read_all())

    print(json.dumps({"summary": summary, "state": value.to_dict()}, indent=2))
