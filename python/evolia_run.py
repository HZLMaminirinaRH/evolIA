#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Main evolIA loop: sample sensors, advance the value model, persist.

Launched by the Rust `evolia-start` binary. Digital actions (screen taps, SMS,
photos, videos) are recorded out-of-band via EvoliaValue.record_action by
whatever captures those events; this loop drives the sensor side and the
per-cycle accumulation. Interval is EVOLIA_CYCLE_SECONDS (default 5s).
"""

from __future__ import annotations

import os
import time

from evolia_sensors import read_all
from evolia_value import EvoliaValue


def main() -> None:
    interval = float(os.environ.get("EVOLIA_CYCLE_SECONDS", "5"))
    value = EvoliaValue()
    value.load()
    print(f"[evolia] loop start (interval={interval}s, total_v={value.total_v:.2f})", flush=True)

    started = time.monotonic()
    try:
        while True:
            elapsed = time.monotonic() - started
            summary = value.cycle(read_all(), elapsed_seconds=elapsed)
            print(
                f"[evolia] cycle {summary['cycle']} "
                f"V={summary['v_normalized']:.3f} "
                f"gain={summary['gain']:.3f} "
                f"total={summary['total_v']:.2f}",
                flush=True,
            )
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\n[evolia] loop stopped", flush=True)


if __name__ == "__main__":
    main()
