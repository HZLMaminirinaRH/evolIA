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


def read_motion() -> tuple[float, float, float]:
    """Return (linear_accel, gyro, magneto) magnitudes from termux-sensor.

    For motion we read the *linear* acceleration (gravity removed): it sits at
    ~0 at rest and rises with real movement, so it tracks activity far better
    than the raw accelerometer, whose constant ~9.8 gravity floor would drown
    the signal out. Sensor names vary by device, so we classify by keyword and
    skip the 'uncalibrated' variants to avoid double counting.
    """
    if not _have("termux-sensor"):
        return 0.0, 0.0, 0.0

    data = _run_json(["termux-sensor", "-a", "-n", "1"]) or {}
    accel = gyro = magneto = 0.0
    for name, payload in data.items():
        if not isinstance(payload, dict):
            continue
        mag = _magnitude(payload.get("values", []))
        low = name.lower()
        if "uncalib" in low:
            continue
        if "linear" in low and "accel" in low and accel == 0.0:
            accel = mag
        elif "gyro" in low and gyro == 0.0:
            gyro = mag
        elif "magnet" in low and magneto == 0.0:
            magneto = mag
    return accel, gyro, magneto


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
    accel, gyro, magneto = read_motion()
    return SensorSample(
        accelerometer=accel,
        gyroscope=gyro,
        magnetometer=magneto,
        location_fix=read_location(),
        wifi_count=read_wifi_count(),
        ble_count=read_ble_count(),
    )


if __name__ == "__main__":
    sample = read_all()
    print(json.dumps(sample.__dict__, indent=2))
