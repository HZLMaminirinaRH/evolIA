#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Sensor readers for evolIA.

Reads the device signals that feed the value model:

    motion   accelerometer, gyroscope, magnetometer   (termux-sensor)
    location GPS / network fix                         (termux-location)
    wifi     nearby access points                      (termux-wifi-scaninfo)
    ble      nearby Bluetooth LE devices               (termux-bluetooth-scaninfo)

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

    accelerometer: float = 0.0  # m/s^2, vector magnitude
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
    """Return (accel, gyro, magneto) magnitudes from termux-sensor.

    Sensor names vary by device, so we classify by keyword and skip the
    'uncalibrated' / 'linear' variants to avoid double counting.
    """
    if not _have("termux-sensor"):
        return 0.0, 0.0, 0.0

    data = _run_json(["termux-sensor", "-n", "1"]) or {}
    accel = gyro = magneto = 0.0
    for name, payload in data.items():
        if not isinstance(payload, dict):
            continue
        mag = _magnitude(payload.get("values", []))
        low = name.lower()
        if "uncalib" in low:
            continue
        if "accel" in low and "linear" not in low and accel == 0.0:
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


def read_wifi_count() -> int:
    """Number of visible WiFi access points."""
    if not _have("termux-wifi-scaninfo"):
        return 0
    data = _run_json(["termux-wifi-scaninfo"])
    return len(data) if isinstance(data, list) else 0


def read_ble_count() -> int:
    """Number of visible BLE devices (best-effort; 0 if unsupported)."""
    if not _have("termux-bluetooth-scaninfo"):
        return 0
    data = _run_json(["termux-bluetooth-scaninfo"], timeout=15)
    return len(data) if isinstance(data, list) else 0


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
