#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Sensor readers for evolIA.

Reads the device signals that feed the value model:

    motion   linear acceleration, gyroscope, magnetometer (termux-sensor)
    location GPS / network fix                            (termux-location)
    wifi     nearby access points (RSSI-filtered)         (termux-wifi-scaninfo)
    ble      nearby Bluetooth LE devices (RSSI-filtered)  (termux-bluetooth-scaninfo)

Every reader degrades gracefully: if the termux-api binary is missing or the
call fails (e.g. running off-device, or location disabled), it returns a
neutral value instead of raising, so the rest of the pipeline keeps running.
"""

from __future__ import annotations

import json
import math
import shutil
import subprocess
from dataclasses import dataclass


@dataclass
class SensorSample:
    """One instantaneous reading of every tracked sensor."""

    accelerometer: float = 0.0  # linear acceleration (gravity removed), m/s^2
    gyroscope: float = 0.0      # rad/s, vector magnitude
    magnetometer: float = 0.0   # microtesla, vector magnitude
    location_fix: bool = False  # True if a GPS/network fix was obtained
    wifi_count: int = 0         # number of visible access points
    ble_count: int = 0          # number of visible BLE devices
    pedometer: float = 0.0      # steps taken this cycle (0 if no step sensor)
    gravity: float = 0.0        # gravity magnitude, m/s^2 (0 if no sensor)
    altimeter: float = 0.0      # barometric pressure, hPa (0 if no barometer)


def _have(cmd: str) -> bool:
    return shutil.which(cmd) is not None


def _run_json(args: list[str], timeout: float = 6.0):
    """Run a command and parse its stdout as JSON; None on any failure."""
    try:
        out = subprocess.check_output(args, timeout=timeout, stderr=subprocess.DEVNULL)
        text = out.decode("utf-8", "replace").strip()
        return json.loads(text) if text else None
    except Exception:
        return None


def _magnitude(values) -> float:
    try:
        return math.sqrt(sum(float(v) ** 2 for v in list(values)[:3]))
    except Exception:
        return 0.0


def _first(values) -> float:
    """First scalar of a sensor's values array (e.g. pressure hPa, step count)."""
    try:
        return float(values[0]) if values else 0.0
    except (TypeError, ValueError, IndexError):
        return 0.0


def _read_termux_all() -> dict:
    """One termux-sensor pass -> raw readings for every motion/environment
    sensor we track. Sensor names vary by device, so we classify by keyword and
    skip 'uncalibrated' variants to avoid double counting. A sensor a device
    lacks simply stays 0 (peers without it feed 0 into the formula).

    For motion we read the *linear* acceleration (gravity removed): ~0 at rest,
    rising with real movement, so it tracks activity far better than the raw
    accelerometer whose constant ~9.8 gravity floor would drown the signal out.
    """
    out = {"accel": 0.0, "gyro": 0.0, "magneto": 0.0,
           "gravity": 0.0, "pressure": 0.0, "steps": 0.0}
    if not _have("termux-sensor"):
        return out
    data = _run_json(["termux-sensor", "-a", "-n", "1"]) or {}
    for name, payload in data.items():
        if not isinstance(payload, dict):
            continue
        low = name.lower()
        if "uncalib" in low:
            continue
        values = payload.get("values", [])
        mag = _magnitude(values)
        if "linear" in low and "accel" in low and out["accel"] == 0.0:
            out["accel"] = mag
        elif "gyro" in low and out["gyro"] == 0.0:
            out["gyro"] = mag
        elif "magnet" in low and out["magneto"] == 0.0:
            out["magneto"] = mag
        elif "gravity" in low and out["gravity"] == 0.0:
            out["gravity"] = mag
        elif ("pressure" in low or "barometer" in low) and out["pressure"] == 0.0:
            out["pressure"] = _first(values)
        elif "step" in low and "counter" in low and out["steps"] == 0.0:
            out["steps"] = _first(values)
    return out


def read_motion() -> tuple[float, float, float]:
    """Return (linear_accel, gyro, magneto) magnitudes from termux-sensor."""
    s = _read_termux_all()
    return s["accel"], s["gyro"], s["magneto"]


# The step counter is cumulative since boot; the value model wants steps *this
# cycle*, so we diff against the previous reading. Module-level state is fine —
# read_all runs in the single long-lived value loop. A drop (reboot reset) or a
# zero reading (no sensor) yields 0 rather than a negative/huge spike.
_last_steps: float | None = None


def _step_delta(cumulative: float) -> float:
    global _last_steps
    if cumulative <= 0.0:
        return 0.0
    if _last_steps is None or cumulative < _last_steps:
        _last_steps = cumulative
        return 0.0
    delta = cumulative - _last_steps
    _last_steps = cumulative
    return delta


def read_location() -> bool:
    """True if a GPS/network location fix is currently available."""
    if not _have("termux-location"):
        return False
    data = _run_json(["termux-location", "-p", "network", "-r", "once"], timeout=20)
    return isinstance(data, dict) and "latitude" in data


# Only signals at least this strong (dBm) count as a real nearby interaction;
# a far, weak AP/device is ambient noise, not engagement. Higher (less negative)
# means closer. Must match NEAR_RSSI_DBM in android/AndroidSensors.kt.
NEAR_RSSI_DBM = -70


def _rssi(entry: dict) -> float | None:
    """Signal strength (dBm) from a scan entry, trying the common field names;
    None when the scan omits it (then we can't filter, so the entry is kept)."""
    for key in ("rssi", "level", "RSSI"):
        val = entry.get(key)
        if isinstance(val, (int, float)):
            return float(val)
    return None


def _count_near(data, threshold: int = NEAR_RSSI_DBM) -> int:
    """Count scan entries within RSSI threshold (nearby). An entry with no
    reported RSSI is counted — we can't filter what we can't measure, so this
    degrades to the plain visible-count on devices that omit signal strength."""
    if not isinstance(data, list):
        return 0
    n = 0
    for e in data:
        if not isinstance(e, dict):
            continue
        rssi = _rssi(e)
        if rssi is None or rssi >= threshold:
            n += 1
    return n


def read_wifi_count() -> int:
    """Number of *nearby* WiFi access points (RSSI >= NEAR_RSSI_DBM)."""
    if not _have("termux-wifi-scaninfo"):
        return 0
    return _count_near(_run_json(["termux-wifi-scaninfo"]))


def read_ble_count() -> int:
    """Number of *nearby* BLE devices (RSSI >= NEAR_RSSI_DBM; best-effort)."""
    if not _have("termux-bluetooth-scaninfo"):
        return 0
    return _count_near(_run_json(["termux-bluetooth-scaninfo"], timeout=15))


def read_all() -> SensorSample:
    """Read every sensor once into a single SensorSample."""
    s = _read_termux_all()
    return SensorSample(
        accelerometer=s["accel"],
        gyroscope=s["gyro"],
        magnetometer=s["magneto"],
        location_fix=read_location(),
        wifi_count=read_wifi_count(),
        ble_count=read_ble_count(),
        pedometer=_step_delta(s["steps"]),
        gravity=s["gravity"],
        altimeter=s["pressure"],
    )


if __name__ == "__main__":
    sample = read_all()
    print(json.dumps(sample.__dict__, indent=2))
