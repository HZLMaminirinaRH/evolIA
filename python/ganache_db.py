#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Anchor the accumulated value onto the Ganache blockchain.

Reads total_v from evolia_identity_state.json (written by the value model) and
appends one entry per sync to evolia_blockchain_sync.log, which the dashboard
and the bitcoin bridge consume.

On-chain anchoring needs `web3`, a deployed contract (evolia_deployment.json)
and a reachable Ganache node. When any of those is missing the sync still runs
in LOCAL mode — it records the value with status "local" so the rest of the
pipeline keeps flowing — and clearly reports that nothing was written on-chain.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timezone

import evolia_paths as paths

GANACHE_URL = os.environ.get("GANACHE_URL", "http://127.0.0.1:8545")

try:
    from web3 import Web3

    HAS_WEB3 = True
except Exception:  # pragma: no cover - depends on optional install
    HAS_WEB3 = False


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_total_v() -> float:
    path = paths.identity_state()
    if not path.exists():
        return 0.0
    try:
        return float(json.loads(path.read_text()).get("total_v", 0.0))
    except (OSError, ValueError):
        return 0.0


def build_log_entry(v_value: float, status: str, **extra) -> dict:
    """Pure: shape one sync-log entry. status is 'success' | 'local' | 'failed'."""
    entry = {"timestamp": _now(), "status": status, "v_value": v_value}
    entry.update(extra)
    return entry


def log_sync(entry: dict) -> None:
    path = paths.blockchain_sync_log()
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a") as f:
        f.write(json.dumps(entry) + "\n")


def _connect():
    """Return (web3, contract) if a real on-chain anchor is possible, else None."""
    if not HAS_WEB3:
        return None
    if not (paths.deployment().exists() and paths.contract_abi().exists()):
        return None
    try:
        w3 = Web3(Web3.HTTPProvider(GANACHE_URL))
        if not w3.is_connected():
            return None
        address = json.loads(paths.deployment().read_text())["contract_address"]
        abi = json.loads(paths.contract_abi().read_text())
        contract = w3.eth.contract(address=Web3.to_checksum_address(address), abi=abi)
        w3.eth.default_account = w3.eth.accounts[0]
        return w3, contract
    except Exception:
        return None


def anchor_on_contract(w3, contract, account, v_value, sensory_type="Physique+Ondes"):
    """Anchor v_value on-chain via EvoliaCore.anchorValue; returns the log entry.

    The value is scaled to an integer (x100) since the contract stores uints.
    Pulled out as its own function so the real on-chain path is unit-testable
    against an in-process EVM (see tests/test_web3.py).
    """
    tx_hash = contract.functions.anchorValue(int(v_value * 100), sensory_type).transact(
        {"from": account}
    )
    receipt = w3.eth.wait_for_transaction_receipt(tx_hash)
    return build_log_entry(
        v_value,
        "success",
        tx_hash=receipt["transactionHash"].hex(),
        block=receipt["blockNumber"],
    )


def sync_once() -> dict:
    """Anchor the current value once. Returns the log entry that was written."""
    v_value = read_total_v()
    if v_value <= 0:
        entry = build_log_entry(v_value, "local", note="no value to anchor")
        log_sync(entry)
        return entry

    conn = _connect()
    if conn is None:
        entry = build_log_entry(v_value, "local", note="web3/contract unavailable")
        log_sync(entry)
        return entry

    w3, contract = conn
    try:
        entry = anchor_on_contract(w3, contract, w3.eth.default_account, v_value)
    except Exception as exc:  # pragma: no cover - network path
        entry = build_log_entry(v_value, "failed", error=str(exc))
    log_sync(entry)
    return entry


def sync_continuous(interval: int = 30) -> None:
    mode = "ON-CHAIN" if _connect() else "LOCAL (no web3/contract/node)"
    print(f"[ganache_db] sync continuous (interval={interval}s, mode={mode})", flush=True)
    try:
        while True:
            entry = sync_once()
            print(f"[ganache_db] {entry['status']} V={entry['v_value']:.2f}", flush=True)
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\n[ganache_db] stopped", flush=True)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "continuous"
    interval = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    if mode == "once":
        print(json.dumps(sync_once(), indent=2))
    else:
        sync_continuous(interval)
