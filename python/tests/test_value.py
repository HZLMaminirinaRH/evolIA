#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tests for the value-accumulation model (pure logic, no device needed)."""

import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from evolia_sensors import SensorSample  # noqa: E402
from evolia_value import (  # noqa: E402
    ACTION_RATES,
    SENSOR_WEIGHTS,
    EvoliaValue,
    normalize,
)


def test_actions_accumulate():
    v = EvoliaValue(state_path=Path(tempfile.mkdtemp()) / "s.json")
    assert v.record_action("photo_taken") == ACTION_RATES["photo_taken"]
    assert v.record_action("sms_sent", count=3) == ACTION_RATES["sms_sent"] * 3
    assert v.action_counts["photo_taken"] == 1
    assert v.action_counts["sms_sent"] == 3
    expected = ACTION_RATES["photo_taken"] + ACTION_RATES["sms_sent"] * 3
    assert abs(v.total_v - expected) < 1e-9


def test_all_listed_inputs_are_modeled():
    # Every input the user asked for must exist in the model.
    for action in ("screen_input", "sms_sent", "photo_taken", "video_taken"):
        assert action in ACTION_RATES
    for sensor in ("accelerometer", "gyroscope", "magnetometer", "location", "wifi", "ble"):
        assert sensor in SENSOR_WEIGHTS


def test_unknown_action_rejected():
    v = EvoliaValue(state_path=Path(tempfile.mkdtemp()) / "s.json")
    try:
        v.record_action("mining")
    except ValueError:
        pass
    else:
        raise AssertionError("expected ValueError for unknown action")


def test_sensor_normalization_caps_at_one():
    huge = SensorSample(
        accelerometer=1000.0,
        gyroscope=1000.0,
        magnetometer=1000.0,
        location_fix=True,
        wifi_count=999,
        ble_count=999,
    )
    norm = normalize(huge)
    assert all(0.0 <= x <= 1.0 for x in norm.values())
    assert all(abs(x - 1.0) < 1e-9 for x in norm.values())


def test_zero_sample_adds_nothing():
    v = EvoliaValue(state_path=Path(tempfile.mkdtemp()) / "s.json")
    gain = v.record_sensors(SensorSample())
    assert gain == 0.0
    assert v.total_v == 0.0


def test_full_sample_equals_weight_sum():
    v = EvoliaValue(state_path=Path(tempfile.mkdtemp()) / "s.json")
    full = SensorSample(
        accelerometer=19.6,
        gyroscope=4.36,
        magnetometer=65.0,
        location_fix=True,
        wifi_count=10,
        ble_count=10,
    )
    gain = v.record_sensors(full)
    assert abs(gain - sum(SENSOR_WEIGHTS.values())) < 1e-9


def test_cycle_persists_and_reloads():
    path = Path(tempfile.mkdtemp()) / "s.json"
    v = EvoliaValue(state_path=path)
    v.record_action("video_taken")
    v.cycle(SensorSample(accelerometer=9.8, wifi_count=5))
    assert path.exists()
    # identity mirror is written next to the state file
    assert (path.parent / "evolia_identity_state.json").exists()

    reloaded = EvoliaValue(state_path=path)
    assert reloaded.load() is True
    assert reloaded.cycle_count == 1
    assert abs(reloaded.total_v - v.total_v) < 1e-9


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except Exception as exc:  # noqa: BLE001
                failures += 1
                print(f"FAIL {name}: {exc}")
    sys.exit(1 if failures else 0)
