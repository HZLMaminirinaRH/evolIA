#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Super-peer service: coordinate the mesh, learn patterns, strengthen the formula.

The Super-peer is a central coordinating node that:
1. Periodically reads peer blocks from the shared mesh vault
2. Aggregates learning signals: actions, sensors, attack patterns
3. Evolves cognitive parameters asymmetrically (not symmetric gossip)
4. Writes learned parameters for relay by mesh-sync

This creates learning-based formula evolution: peers benefit from the Super-peer's
learning without subordination. The evolved parameters are fused back by the bridge
and propagated by mesh-sync on the next cycle.

Run as a service (e.g., in services.toml):
    [[service]]
    name = "evolia_supernode"
    command = "python3"
    args = ["evolia_supernode.py", "continuous", "30"]

Runs indefinitely, learning every 30 seconds (or provide an interval in EVOLIA_SUPERNODE_CYCLE_SECONDS).
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

import evolia_paths as paths
import evolia_learning as learning


def _cycle_interval() -> float:
    """Read EVOLIA_SUPERNODE_CYCLE_SECONDS or default to 30s."""
    try:
        return max(float(os.environ.get("EVOLIA_SUPERNODE_CYCLE_SECONDS", "30")), 1.0)
    except ValueError:
        return 30.0


def learn_once() -> dict[str, float]:
    """Single learning pass: read vault, aggregate, evolve, return params."""
    vault = str(paths.mesh_vault())
    try:
        evolved = learning.learn_and_evolve(vault)
        if evolved:
            learning.save_evolved_params(evolved)
            return evolved
    except Exception as e:
        print(f"supernode: learning error: {e}", file=sys.stderr)

    return {}


def continuous(interval: float = _cycle_interval()) -> None:
    """Run in a loop: learn every interval seconds."""
    print(f"supernode: starting, cycle={interval:.1f}s", file=sys.stderr)
    while True:
        try:
            evolved = learn_once()
            if evolved:
                stats = ", ".join(f"{k}={v:.3f}" for k, v in evolved.items())
                print(f"supernode: evolved {{{stats}}}", file=sys.stderr)
        except KeyboardInterrupt:
            print("supernode: interrupted", file=sys.stderr)
            break
        except Exception as e:
            print(f"supernode: error: {e}", file=sys.stderr)

        time.sleep(interval)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "continuous":
        interval = _cycle_interval()
        if len(sys.argv) > 2:
            try:
                interval = float(sys.argv[2])
            except ValueError:
                pass
        continuous(interval)
    else:
        # One-shot: learn and print
        evolved = learn_once()
        if evolved:
            print(json.dumps(evolved, indent=2))
        else:
            print("no peer data to learn from")
