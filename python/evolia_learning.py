#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Super-peer learning: aggregate peer data to strengthen the evolutive formula.

The Super-peer role is a central coordinating node that:
1. Receives value blocks from peers carrying their cognitive proof-of-work
2. Aggregates patterns from the work proofs: actions, sensors, attack frequency
3. Learns which action/sensor mixes are most effective across the mesh
4. Evolves the core formula parameters (ACTION_RATES, COEFF) asymmetrically
5. Propagates learned parameters back to strengthen all peers

Unlike symmetric peer gossip, the Super-peer sees global patterns and reinforces
them, creating learning-based formula evolution without hierarchy.

Output: updated evolia_cognitive_params.json to be fused by bridge and relayed
by mesh-sync on the next propagation cycle.
"""

from __future__ import annotations

import json
import math
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import evolia_paths as paths
from evolia_evolve import ACTION_RATES, COEFF


@dataclass
class PeerSnapshot:
    """Aggregated learning from one peer's blocks."""
    device_id: str
    total_v: float
    action_counts: dict[str, int]  # what they did
    v_normalized: float            # their sensor/cognitive multiplier
    dt: float                       # elapsed time in their last cycle
    v_prev: float                   # their prior value


def _read_peer_blocks(vault: str) -> list[dict]:
    """Read all recv_<device>.json blocks from the mesh vault."""
    blocks = []
    vault_path = Path(vault)
    if not vault_path.exists():
        return blocks

    for block_file in sorted(vault_path.glob("recv_*.json")):
        try:
            data = json.loads(block_file.read_text())
            blocks.append(data)
        except (OSError, json.JSONDecodeError):
            pass

    return blocks


def _extract_work_proof(block: dict) -> Optional[PeerSnapshot]:
    """Extract device ID and work proof from a peer block."""
    try:
        device_id = block.get("device_id") or block.get("source_device", "unknown")
        v_value = float(block.get("v_value", 0.0))
        work = block.get("work", {})

        if not work:
            return None

        return PeerSnapshot(
            device_id=device_id,
            total_v=v_value,
            action_counts=work.get("actions", {}),
            v_normalized=float(work.get("v", 0.0)),
            dt=float(work.get("dt", 5.0)),
            v_prev=float(work.get("v_prev", 0.0)),
        )
    except (KeyError, ValueError, TypeError):
        return None


def _aggregate_peers(blocks: list[dict]) -> dict:
    """Aggregate learning from all peer blocks.

    Returns: {
        "action_effectiveness": {action: avg_value_per_unit},
        "sensor_correlation": {sensor: avg_v_normalized},
        "heavy_users": [device_ids with high activity],
        "attack_frequency": {device: count},
        "peer_count": total_unique_peers,
        "total_mesh_value": sum of all peer v values,
    }
    """
    action_totals = defaultdict(float)
    action_counts = defaultdict(int)
    sensor_v_sum = defaultdict(float)
    sensor_count = defaultdict(int)
    heavy_users = []
    peer_values = {}

    for block in blocks:
        snap = _extract_work_proof(block)
        if not snap or snap.v_normalized < 0.0 or snap.v_normalized > 1.0:
            continue

        peer_values[snap.device_id] = snap.total_v

        # Effectiveness: each action contributed to a gain
        # gain = base * (1 + v) + 1.0 * v
        # We weight by v_normalized to see which action mixes create high sensor activity
        if snap.action_counts:
            base = sum(ACTION_RATES.get(k, 0.0) * count
                       for k, count in snap.action_counts.items())
            if base > 0:
                # This action mix generated v_normalized; weight by v
                for action, count in snap.action_counts.items():
                    action_totals[action] += snap.v_normalized * count
                    action_counts[action] += count

        # Detect heavy users: those with high total_v or frequent cycles
        if snap.total_v > 50.0:  # Heuristic: 50+ BTC-e is heavy activity
            heavy_users.append(snap.device_id)

        # Sensor correlation: high v_normalized suggests the peer's sensors
        # were predictive/active. We boost sensor weights for such peers.
        if snap.v_normalized > 0.5:
            sensor_v_sum["high_v"] += snap.v_normalized
            sensor_count["high_v"] += 1

    action_eff = {}
    for action in ACTION_RATES:
        if action_counts[action] > 0:
            action_eff[action] = action_totals[action] / action_counts[action]
        else:
            action_eff[action] = 0.0

    return {
        "action_effectiveness": action_eff,
        "heavy_users": list(set(heavy_users)),
        "peer_count": len(peer_values),
        "total_mesh_value": sum(peer_values.values()),
    }


def _evolve_action_rates(eff: dict) -> dict[str, float]:
    """Adjust ACTION_RATES based on observed effectiveness.

    Peers who achieved high v_normalized with video took more valuable actions.
    Boost the most effective actions and dampen the least.
    """
    evolved = {}
    if not eff:
        return dict(ACTION_RATES)

    # Compute a learning signal: max/min ratio of effectiveness
    values = [v for v in eff.values() if v > 0]
    if not values or max(values) < 0.01:
        return dict(ACTION_RATES)

    min_eff = min(values)
    max_eff = max(values)
    range_eff = max_eff - min_eff
    if range_eff < 0.01:
        return dict(ACTION_RATES)

    # Normalize effectiveness to [0, 1] and boost high performers
    for action in ACTION_RATES:
        if action in eff:
            norm_eff = (eff[action] - min_eff) / range_eff
            # Amplify: boost high-effectiveness actions, dampen low ones
            # Using exponential learned scaling
            scale_factor = 1.0 + 0.1 * (norm_eff - 0.5)  # ±10% around baseline
            evolved[action] = max(0.01, ACTION_RATES[action] * scale_factor)
        else:
            evolved[action] = ACTION_RATES[action]

    # Renormalize so total stays constant (neutral expansion)
    current_total = sum(evolved.values())
    original_total = sum(ACTION_RATES.values())
    if current_total > 0:
        ratio = original_total / current_total
        evolved = {k: v * ratio for k, v in evolved.items()}

    return evolved


def _evolve_cognitive_params(aggregated: dict, heavy_users: list[str]) -> dict[str, float]:
    """Compute evolved cognitive parameters for peer fusion.

    The Super-peer sends back parameters that strengthen peers by:
    - Amplifying actions that correlate with high v_normalized
    - Boosting engagement for heavy mobile users
    - Adjusting based on mesh health (peer count, total value)
    """
    params = {}

    # Base learning from action effectiveness
    eff = aggregated["action_effectiveness"]
    evolved_rates = _evolve_action_rates(eff)

    # Map evolved ACTION_RATES to parameter weights that can be fused
    # Parameters are abstract (ALPHA, BETA, etc.) so we map:
    # High effectiveness → higher weight in fusion
    video_eff = eff.get("video_taken", 0.1)
    sms_eff = eff.get("sms_sent", 0.1)
    photo_eff = eff.get("photo_taken", 0.1)
    screen_eff = eff.get("screen_input", 0.1)

    # Normalize to probabilities
    total_eff = video_eff + sms_eff + photo_eff + screen_eff
    if total_eff > 0:
        params["ALPHA"] = max(0.1, video_eff / total_eff)
        params["BETA"] = max(0.1, sms_eff / total_eff)
        params["GAMMA"] = max(0.1, photo_eff / total_eff)
        params["DELTA"] = max(0.1, screen_eff / total_eff)
    else:
        # Fallback to learned defaults
        params["ALPHA"] = 0.35
        params["BETA"] = 0.25
        params["GAMMA"] = 0.25
        params["DELTA"] = 0.15

    # Heavy user bonus: boost engagement multiplier
    # If we have heavy users, increase EPSILON (engagement factor)
    base_epsilon = 0.25
    if heavy_users:
        engagement_boost = min(0.15, 0.05 * len(heavy_users) / max(1, aggregated["peer_count"]))
        params["EPSILON"] = base_epsilon + engagement_boost
    else:
        params["EPSILON"] = base_epsilon

    # Mesh health signal: if total_v is growing, maintain bias; if stagnant, boost
    total_v = aggregated["total_mesh_value"]
    if total_v > 100.0:  # Active mesh
        boost = min(0.1, math.log10(total_v) / 10.0)
        for k in params:
            params[k] += boost * 0.05

    # Renormalize to sum to 1 (they will be used as blend weights)
    total = sum(params.values())
    if total > 0:
        params = {k: v / total for k, v in params.items()}

    return params


def learn_and_evolve(vault: Optional[str] = None) -> dict[str, float]:
    """Main Super-peer learning loop.

    Reads all peer blocks from the vault, aggregates patterns, and returns
    evolved cognitive parameters to be saved and propagated.
    """
    if vault is None:
        vault = str(paths.mesh_vault())

    # 1. Read all peer blocks
    blocks = _read_peer_blocks(vault)
    if not blocks:
        return {}

    # 2. Aggregate learning
    aggregated = _aggregate_peers(blocks)

    # 3. Evolve parameters
    heavy_users = aggregated.get("heavy_users", [])
    evolved_params = _evolve_cognitive_params(aggregated, heavy_users)

    return evolved_params


def save_evolved_params(params: dict[str, float]) -> None:
    """Save evolved parameters to evolia_cognitive_params.json."""
    if not params:
        return

    params_path = paths.cognitive_params()
    try:
        params_path.write_text(json.dumps(params, indent=2))
    except OSError as e:
        print(f"failed to save evolved params: {e}")


if __name__ == "__main__":
    import sys

    vault = sys.argv[1] if len(sys.argv) > 1 else None
    evolved = learn_and_evolve(vault)
    if evolved:
        save_evolved_params(evolved)
        print(json.dumps(evolved, indent=2))
    else:
        print("no peer data to learn from")
