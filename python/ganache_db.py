#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Anchor the accumulated value onto the Ganache blockchain.

Reads total_v from evolia_identity_state.json (written by the value model) and
appends one entry per sync to evolia_blockchain_sync.log, which the dashboard
and the bitcoin bridge consume.

Anchoring prefers the **proven path**: when a cognitive work proof is present
(evolia_work_proof.json) it calls EvoliaCore.anchorProof, which recomputes the
value increment from the declared work on-chain (ΔV = base(actions)·(1+v)+floor·v)
and enforces the physical rate caps — so the contract's provenValue is verified,
not a self-declared number. A forged proof reverts. Falls back to the legacy
self-declared anchorValue snapshot only when no proof is available (proofless
bootstrap) or the deployed contract predates anchorProof.

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

# Order matches EvoliaCore.anchorProof's count arguments and go/pow.ActionRates.
ACTION_FIELDS = ("screen_input", "sms_sent", "photo_taken", "video_taken")

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


def read_work_proof() -> dict | None:
    """Return the latest cognitive proof {v_value, work{actions, v, dt, ...}} the
    value model emitted, or None when absent/malformed. This is what lets the
    chain recompute and verify the increment instead of trusting a bare number."""
    path = paths.work_proof()
    if not path.exists():
        return None
    try:
        proof = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    if not isinstance(proof, dict) or not isinstance(proof.get("work"), dict):
        return None
    if not isinstance(proof["work"].get("actions"), dict):
        return None
    return proof


def proof_to_args(work: dict) -> tuple:
    """Scale a work proof to anchorProof's integer arguments, mirroring the
    contract's fixed point: action counts (capped to the rate the contract
    enforces is the chain's job, not ours), v as vMilli = v×1000 ∈ [0,1000], and
    dt as whole seconds ∈ [1, 3600]. Returns
    (screen, sms, photo, video, v_milli, dt_secs)."""
    actions = work.get("actions") or {}
    counts = [max(0, int(actions.get(k, 0))) for k in ACTION_FIELDS]
    v_milli = max(0, min(1000, round(float(work.get("v", 0.0)) * 1000)))
    dt_secs = max(1, min(3600, round(float(work.get("dt", 1.0)))))
    return (*counts, v_milli, dt_secs)


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


def anchor_proof_on_contract(w3, contract, account, work):
    """Anchor a *proven* value increment: EvoliaCore.anchorProof recomputes ΔV
    from the declared work and reverts a forged proof, so the on-chain provenValue
    is verified rather than self-declared. Returns the log entry (value in BTC-e,
    derived from the contract's provenValue, not from the caller)."""
    screen, sms, photo, video, v_milli, dt_secs = proof_to_args(work)
    tx_hash = contract.functions.anchorProof(screen, sms, photo, video, v_milli, dt_secs).transact(
        {"from": account}
    )
    receipt = w3.eth.wait_for_transaction_receipt(tx_hash)
    proven = contract.functions.provenValue().call()
    return build_log_entry(
        proven / 100.0,
        "success",
        mode="proven",
        proven_value_centi=proven,
        tx_hash=receipt["transactionHash"].hex(),
        block=receipt["blockNumber"],
    )


def _supports_proof(contract) -> bool:
    """True when the deployed contract exposes anchorProof (the verified path)."""
    try:
        return any(e.get("name") == "anchorProof" and e.get("type") == "function" for e in contract.abi)
    except Exception:
        return False


def _anchor_marker_path():
    return paths.evolia_home() / "evolia_anchor_marker.json"


def _last_anchored_v() -> float:
    """The proof v_value last anchored on-chain (0 if none), so a sync that finds
    no new cycle does not re-anchor and double-count the same increment."""
    p = _anchor_marker_path()
    if not p.exists():
        return 0.0
    try:
        return float(json.loads(p.read_text()).get("v_value", 0.0))
    except (OSError, ValueError):
        return 0.0


def _mark_anchored(v_value: float) -> None:
    paths.ensure_home()
    paths.atomic_write_text(_anchor_marker_path(), json.dumps({"v_value": v_value}))


def sync_once() -> dict:
    """Anchor the current value once. Returns the log entry that was written.

    Prefers the proven path (anchorProof recomputes the increment on-chain); only
    falls back to the legacy self-declared snapshot when no proof / no support."""
    v_value = read_total_v()
    proof = read_work_proof()

    conn = _connect()
    if conn is None:
        note = "no value to anchor" if v_value <= 0 and proof is None else "web3/contract unavailable"
        entry = build_log_entry(v_value, "local", note=note)
        log_sync(entry)
        return entry

    w3, contract = conn

    # Proven path: let the contract recompute and verify the increment.
    if proof is not None and _supports_proof(contract):
        proof_v = float(proof.get("v_value", 0.0))
        if proof_v <= _last_anchored_v() + 1e-9:
            entry = build_log_entry(v_value, "local", note="no new cycle proof to anchor")
            log_sync(entry)
            return entry
        try:
            entry = anchor_proof_on_contract(w3, contract, w3.eth.default_account, proof["work"])
            _mark_anchored(proof_v)
        except Exception as exc:  # pragma: no cover - network/contract path
            entry = build_log_entry(v_value, "failed", error=str(exc))
        log_sync(entry)
        return entry

    # Legacy fallback: self-declared snapshot (proofless bootstrap / old ABI).
    if v_value <= 0:
        entry = build_log_entry(v_value, "local", note="no value to anchor")
        log_sync(entry)
        return entry
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
