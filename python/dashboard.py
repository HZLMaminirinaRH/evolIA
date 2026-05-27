#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Read-only dashboard aggregating the shared evolIA state.

It reads exactly the files the other services write — identity state (value),
the mesh vault (Go mesh-sync), the blockchain sync log (ganache_db) and the
bitcoin wallet/conversions (evolia_bitcoin) — and prints a unified snapshot.
The aggregation (`collect`) is pure and testable; `run_live` just loops on it.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

import evolia_paths as paths
from evolia_bitcoin import sat_to_btc, sat_to_usd


def _load_json(path: Path, default):
    try:
        if path.exists():
            return json.loads(path.read_text())
    except (OSError, ValueError):
        pass
    return default


def _mesh_totals() -> tuple[float, int]:
    vault = paths.mesh_vault()
    total_v = 0.0
    count = 0
    if vault.exists():
        for f in vault.glob("*.json"):
            data = _load_json(f, {})
            if isinstance(data, dict):
                total_v += float(data.get("v_value", 0.0))
                count += 1
    return total_v, count


def _transfer_totals() -> tuple[int, float]:
    """This node's outbound on-chain BTC-e transfers: (count, total BTC-e sent).
    Reads the dedicated transfer history written by ganache_db.transfer_btce."""
    path = paths.transfer_history()
    count = 0
    sent_centi = 0
    if path.exists():
        for line in path.read_text().splitlines():
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            if entry.get("status") == "success" and entry.get("mode") == "transfer":
                count += 1
                sent_centi += int(entry.get("amount_centi", 0))
    return count, sent_centi / 100.0


def _ganache_totals() -> tuple[float, int]:
    log = paths.blockchain_sync_log()
    total_v = 0.0
    count = 0
    if log.exists():
        for line in log.read_text().splitlines():
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            if entry.get("status") in ("success", "local"):
                count += 1
                total_v += float(entry.get("v_value", 0.0))
    return total_v, count


def collect() -> dict:
    """Pure aggregation of the shared state into a single snapshot dict."""
    identity = _load_json(paths.identity_state(), {"total_v": 0.0, "cycle_count": 0})
    mesh_v, mesh_count = _mesh_totals()
    ganache_v, ganache_tx = _ganache_totals()
    wallet = _load_json(paths.bitcoin_wallet_state(), {"balance_sat": 0, "addresses": []})
    conversions = _load_json(paths.conversion_history(), {}).get("conversions", [])
    transfers_out, transferred_btce = _transfer_totals()

    balance_sat = int(wallet.get("balance_sat", 0))
    return {
        "personal": {
            "total_v": float(identity.get("total_v", 0.0)),
            "cycle_count": int(identity.get("cycle_count", 0)),
        },
        "mesh": {"total_v": mesh_v, "blocks": mesh_count},
        "ganache": {"anchored_v": ganache_v, "tx": ganache_tx},
        "transfers": {"out": transfers_out, "sent_btce": transferred_btce},
        "bitcoin": {
            "addresses": len(wallet.get("addresses", [])),
            "balance_sat": balance_sat,
            "balance_btc": sat_to_btc(balance_sat),
            "balance_usd": sat_to_usd(balance_sat),
            "conversions": len(conversions),
        },
        "cognitive_power": float(identity.get("total_v", 0.0)) + mesh_v + ganache_v,
    }


def render(snapshot: dict) -> str:
    p = snapshot["personal"]
    m = snapshot["mesh"]
    g = snapshot["ganache"]
    b = snapshot["bitcoin"]
    t = snapshot["transfers"]
    lines = [
        "=" * 64,
        "  EVOLIA — DASHBOARD",
        "=" * 64,
        f"[PERSONNEL] V={p['total_v']:.2f} BTC-e  cycles={p['cycle_count']}",
        f"[MAILLAGE]  V={m['total_v']:.2f} BTC-e  blocs={m['blocks']}",
        f"[GANACHE]   V={g['anchored_v']:.2f} BTC-e  tx={g['tx']}",
        f"[BITCOIN]   {b['balance_sat']:,} SAT "
        f"({b['balance_btc']:.8f} BTC ~ ${b['balance_usd']:.2f})  "
        f"addr={b['addresses']}  conv={b['conversions']}",
        f"[TRANSFERTS] {t['sent_btce']:.2f} BTC-e envoyés  tx={t['out']}",
        "-" * 64,
        f"[PUISSANCE COGNITIVE] {snapshot['cognitive_power']:.2f} BTC-e",
        "=" * 64,
    ]
    return "\n".join(lines)


def run_live(interval: int = 5) -> None:
    try:
        while True:
            os.system("clear")
            print(render(collect()), flush=True)
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\n[dashboard] stopped", flush=True)


if __name__ == "__main__":
    print(render(collect()))
