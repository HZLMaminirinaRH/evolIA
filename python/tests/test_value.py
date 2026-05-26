#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tests for the evolutive formula and the value accumulator (pure logic)."""

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import evolia_paths as paths  # noqa: E402
from evolia_sensors import SensorSample  # noqa: E402
from evolia_evolve import ACTION_RATES, COEFF, evolve  # noqa: E402
from evolia_value import EvoliaValue  # noqa: E402


def _tmp_state():
    return Path(tempfile.mkdtemp()) / "state.json"


# --- requested ranking -------------------------------------------------------

def test_all_inputs_modeled():
    for action in ("screen_input", "sms_sent", "photo_taken", "video_taken"):
        assert action in ACTION_RATES
    for sensor in ("complexity", "magnetometer", "location", "wifi", "ble"):
        assert sensor in COEFF


def test_video_worth_most():
    assert ACTION_RATES["video_taken"] > ACTION_RATES["photo_taken"]
    assert ACTION_RATES["photo_taken"] > ACTION_RATES["sms_sent"]
    assert ACTION_RATES["sms_sent"] > ACTION_RATES["screen_input"]


def test_bluetooth_outranks_wifi():
    assert COEFF["ble"] > COEFF["wifi"]


# --- evolve formula ----------------------------------------------------------

def test_v_normalized_bounds():
    rest = evolve({}, 0.0, SensorSample(), 0)
    assert 0.0 <= rest.v_normalized <= 1.0
    busy = evolve(
        {"video_taken": 100},
        10_000.0,
        SensorSample(accelerometer=50, gyroscope=50, magnetometer=200, wifi_count=99, ble_count=99),
        100,
    )
    assert 0.0 <= busy.v_normalized <= 1.0
    assert busy.v_normalized > rest.v_normalized


def test_more_ble_increases_v_more_than_wifi():
    base = SensorSample()
    wifi = evolve({}, 0.0, SensorSample(wifi_count=8), 0).v_normalized
    ble = evolve({}, 0.0, SensorSample(ble_count=8), 0).v_normalized
    assert ble > wifi  # same count, BLE weighted higher
    assert wifi > evolve({}, 0.0, base, 0).v_normalized  # wifi still adds


def test_motion_raises_v():
    still = evolve({}, 0.0, SensorSample(), 0).v_normalized
    moving = evolve({}, 0.0, SensorSample(accelerometer=15, gyroscope=3), 0).v_normalized
    assert moving > still


# --- accumulator -------------------------------------------------------------

def test_action_recording_and_unknown_rejected():
    v = EvoliaValue(state_path=_tmp_state())
    assert v.record_action("photo_taken") == ACTION_RATES["photo_taken"]
    assert v.action_counts["photo_taken"] == 1
    try:
        v.record_action("mining")
    except ValueError:
        pass
    else:
        raise AssertionError("expected ValueError")


def test_cognition_amplifies_actions():
    # Same action, but a busier sensor sample must yield a larger gain.
    quiet = EvoliaValue(state_path=_tmp_state())
    quiet.record_action("photo_taken")
    g_quiet = quiet.cycle(SensorSample())["gain"]

    busy = EvoliaValue(state_path=_tmp_state())
    busy.record_action("photo_taken")
    g_busy = busy.cycle(
        SensorSample(accelerometer=18, gyroscope=4, ble_count=10, wifi_count=10, location_fix=True)
    )["gain"]

    assert g_busy > g_quiet


def test_sensor_floor_accrues_without_actions():
    v = EvoliaValue(state_path=_tmp_state())
    summary = v.cycle(SensorSample(accelerometer=18, gyroscope=4, ble_count=10))
    assert summary["base_btc_e"] == 0.0
    assert summary["gain"] > 0.0  # movement alone still accrues


def test_persist_and_reload():
    path = _tmp_state()
    v = EvoliaValue(state_path=path)
    v.record_action("video_taken")
    v.cycle(SensorSample(location_fix=True), elapsed_seconds=5.0)
    assert path.exists()
    assert (path.parent / "evolia_identity_state.json").exists()

    again = EvoliaValue(state_path=path)
    assert again.load() is True
    assert again.cycle_count == 1
    assert again.location_count == 1
    assert abs(again.total_v - v.total_v) < 1e-9


def test_atomic_write_persists_and_leaves_no_temp():
    d = Path(tempfile.mkdtemp())
    target = d / "evolia_value_state.json"
    paths.atomic_write_text(target, "first")
    paths.atomic_write_text(target, "second")  # overwrite, not append
    assert target.read_text() == "second"
    # No half-written temp file may be left behind on success.
    assert [p.name for p in d.iterdir()] == [target.name]


def test_work_proof_reconciles_with_formula():
    # The emitted proof must let a peer recompute the increment exactly:
    # ΔV = base(actions)·(1+v) + SENSOR_FLOOR·v. This is the contract the Go
    # pow validator enforces, so the two must agree to float precision.
    from evolia_value import SENSOR_FLOOR

    v = EvoliaValue(state_path=_tmp_state())
    v.record_action("photo_taken", 2)
    v.record_action("screen_input", 10)
    v.cycle(SensorSample(accelerometer=6, ble_count=3), elapsed_seconds=5.0)

    proof = json.loads(paths.work_proof().read_text())
    w = proof["work"]
    base = sum(ACTION_RATES[k] * c for k, c in w["actions"].items())
    expected = base * (1.0 + w["v"]) + SENSOR_FLOOR * w["v"]
    delta = proof["v_value"] - w["v_prev"]
    assert abs(delta - expected) < 1e-9, (delta, expected)
    assert 0.0 <= w["v"] <= 1.0
    assert w["dt"] > 0
    assert w["actions"] == {"photo_taken": 2, "screen_input": 10}


def test_proof_queue_appends_value_advancing_cycles():
    # Every value-advancing cycle appends one proof line to the durable queue, so
    # ganache_db can anchor each increment on-chain exactly once (full fidelity).
    import os
    from evolia_value import SENSOR_FLOOR

    home = Path(tempfile.mkdtemp())
    os.environ["EVOLIA_HOME"] = str(home)
    v = EvoliaValue(state_path=home / "state.json")
    v.record_action("photo_taken", 1)
    v.cycle(SensorSample(), elapsed_seconds=5.0)
    v.record_action("screen_input", 4)
    v.cycle(SensorSample(), elapsed_seconds=5.0)

    lines = paths.proof_queue().read_text().splitlines()
    assert len(lines) == 2, lines
    # Each queued proof reconciles with the formula the contract recomputes.
    for line in lines:
        p = json.loads(line)
        w = p["work"]
        base = sum(ACTION_RATES[k] * c for k, c in w["actions"].items())
        expected = base * (1.0 + w["v"]) + SENSOR_FLOOR * w["v"]
        assert abs((p["v_value"] - w["v_prev"]) - expected) < 1e-9
        assert 0.0 <= w["v"] <= 1.0 and w["dt"] > 0


def test_save_is_atomic_no_temp_left():
    path = _tmp_state()
    v = EvoliaValue(state_path=path)
    v.record_action("video_taken")
    v.cycle(SensorSample(), elapsed_seconds=1.0)
    leftovers = [p.name for p in path.parent.iterdir() if p.name.startswith(".tmp-")]
    assert leftovers == [], f"temp files left: {leftovers}"


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
