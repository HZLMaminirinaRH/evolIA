#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""The evolutive formula — the cognitive CORE of evolIA.

`V` is computed from an exponential blend of every tracked signal:

    V = a.e^(A/sA) + b.e^(T/sT) + c.e^(C/sC) + d.M + e.e^(L/sL)
        + f.e^(W/sW) + g.e^(B/sB) + h.e^(P/sP) + i.G + j.Alt

    A  weighted digital-action score (screen, sms, photo, video)
    T  elapsed seconds in the session
    C  motion complexity (linear acceleration + gyroscope activity)
    M  magnetometer activity (linear term)
    L  cumulative location fixes
    W  WiFi access points in range
    B  BLE devices in range
    P  pedometer steps taken this cycle (real engagement)
    G  gravity-sensor presence (linear; ~0 when absent)
    Alt barometric/altimeter presence (linear; ~0 when absent)

Peers missing a sensor simply feed 0 for it. Because only the resulting
`v_normalized` (and the per-cycle floor) are declared in the proof-of-work,
adding signals here never changes the Go/Solidity validators — they take `v`
as given — and the new signals propagate passively in each value block.

The coefficients encode the requested ranking: a video action is worth the
most (ACTION_RATES), and Bluetooth outranks WiFi (COEFF["ble"] > COEFF["wifi"]).
Each exponent is capped so V stays bounded; V is then normalized to 0..1 by
subtracting the at-rest baseline and dividing by the saturated ceiling, so
`v_normalized` is a clean cognitive multiplier for the value accumulator.

Everything here is a pure function of its inputs — easy to reason about and
to tune. Adjust ACTION_RATES and COEFF to reshape the economy.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from evolia_sensors import SensorSample

# Base BTC-e produced by one digital action. Video is worth the most.
ACTION_RATES = {
    "screen_input": 0.05,
    "sms_sent": 1.20,
    "photo_taken": 2.50,
    "video_taken": 8.00,
}

# Coefficients of the evolutive formula. Bluetooth ranks above WiFi.
COEFF = {
    "actions": 0.15,       # a
    "time": 0.10,          # b
    "complexity": 0.20,    # c  (linear acceleration + gyroscope)
    "magnetometer": 0.05,  # d  (linear)
    "location": 0.08,      # e
    "ble": 0.12,           # f  (Bluetooth — higher)
    "wifi": 0.05,          # g  (WiFi — lower)
    "pedometer": 0.10,     # h  (steps this cycle — real engagement)
    "gravity": 0.03,       # i  (linear; ~presence/orientation)
    "altimeter": 0.03,     # j  (linear; barometric pressure presence)
}

# Exponent scales and the shared cap (keeps e^x bounded ~ e^3).
_SCALE = {
    "actions": 50.0,
    "time": 100.0,
    "complexity": 0.5,
    "location": 20.0,
    "ble": 10.0,
    "wifi": 10.0,
    "pedometer": 8.0,
}
_CAP = 3.0


def _exp(x: float, scale: float) -> float:
    return math.exp(min(x / scale, _CAP))


def action_score(action_counts: dict) -> float:
    """Weighted sum of cumulative digital actions."""
    return sum(ACTION_RATES[k] * action_counts.get(k, 0) for k in ACTION_RATES)


# Motion normalization. sample.accelerometer is the *linear* acceleration
# (gravity removed): ~0 at rest, peaking ~10-17 m/s² on brisk movement, so the
# scale saturates real activity rather than a constant gravity floor. Tunable in
# that range; keep in sync with LINEAR_ACCEL_SCALE in android/Evolve.kt.
_LINEAR_ACCEL_SCALE = 12.0
_GYRO_SCALE = 4.36


def _motion(sample: SensorSample) -> float:
    accel = min(sample.accelerometer / _LINEAR_ACCEL_SCALE, 1.0)
    gyro = min(sample.gyroscope / _GYRO_SCALE, 1.0)
    return (accel + gyro) / 2.0


def _magneto_norm(sample: SensorSample) -> float:
    return min(sample.magnetometer / 65.0, 1.0)


# Gravity magnitude sits at ~9.81 m/s² whenever the sensor exists and 0 when it
# is absent, so this is a bounded presence/orientation term (peers without the
# sensor contribute 0). Barometric pressure is ~1013 hPa at sea level; likewise
# bounded, 0 when no barometer. Both are linear terms, like the magnetometer.
def _gravity_norm(sample: SensorSample) -> float:
    return min(sample.gravity / 9.81, 1.0)


def _altimeter_norm(sample: SensorSample) -> float:
    return min(sample.altimeter / 1013.25, 1.0)


def _components(a_score, elapsed, motion, magneto, loc_count, wifi, ble,
                pedometer, gravity, altimeter) -> dict:
    return {
        "actions": COEFF["actions"] * _exp(a_score, _SCALE["actions"]),
        "time": COEFF["time"] * _exp(elapsed, _SCALE["time"]),
        "complexity": COEFF["complexity"] * _exp(motion, _SCALE["complexity"]),
        "magnetometer": COEFF["magnetometer"] * magneto,
        "location": COEFF["location"] * _exp(loc_count, _SCALE["location"]),
        "wifi": COEFF["wifi"] * _exp(wifi, _SCALE["wifi"]),
        "ble": COEFF["ble"] * _exp(ble, _SCALE["ble"]),
        "pedometer": COEFF["pedometer"] * _exp(pedometer, _SCALE["pedometer"]),
        "gravity": COEFF["gravity"] * gravity,      # already normalized 0..1
        "altimeter": COEFF["altimeter"] * altimeter,  # already normalized 0..1
    }


# At-rest baseline (all inputs 0) and saturated ceiling (every exponent capped).
_V_BASE = sum(_components(0, 0, 0, 0, 0, 0, 0, 0, 0, 0).values())
_V_MAX = sum(_components(1e9, 1e9, 1e9, 1.0, 1e9, 1e9, 1e9, 1e9, 1.0, 1.0).values())


@dataclass
class EvolveResult:
    v_instant: float
    v_normalized: float  # 0..1, the cognitive multiplier
    components: dict


def evolve(
    action_counts: dict,
    elapsed_seconds: float,
    sample: SensorSample,
    location_count: int,
) -> EvolveResult:
    comps = _components(
        action_score(action_counts),
        max(elapsed_seconds, 0.0),
        _motion(sample),
        _magneto_norm(sample),
        location_count,
        sample.wifi_count,
        sample.ble_count,
        sample.pedometer,
        _gravity_norm(sample),
        _altimeter_norm(sample),
    )
    v_instant = sum(comps.values())
    v_norm = (v_instant - _V_BASE) / (_V_MAX - _V_BASE)
    v_norm = max(0.0, min(v_norm, 1.0))
    return EvolveResult(v_instant=v_instant, v_normalized=v_norm, components=comps)
