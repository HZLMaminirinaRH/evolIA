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


# A node can be offline for a long time; bound the backlog so the queue can't
# grow without limit on a mobile device. The newest proofs are kept; the oldest
# spill (the chain then trails total_v by the spilled increments — only ever
# under a sustained offline spell larger than this many value-advancing cycles).
MAX_QUEUE = 5000


def take_proof_batch() -> list[dict]:
    """Atomically take every queued proof (oldest first); cycles produced after
    this call append to a fresh queue. Mirrors evolia_actions.drain()."""
    q = paths.proof_queue()
    if not q.exists():
        return []
    tmp = q.with_suffix(".draining")
    try:
        os.replace(q, tmp)  # atomic on the same filesystem
    except OSError:
        return []
    proofs: list[dict] = []
    for line in tmp.read_text().splitlines():
        try:
            p = json.loads(line)
        except ValueError:
            continue
        if isinstance(p, dict) and isinstance(p.get("work"), dict):
            proofs.append(p)
    tmp.unlink(missing_ok=True)
    return proofs


def requeue_proofs(proofs: list[dict]) -> None:
    """Return unanchored proofs to the queue for the next sync. Re-appending is a
    plain, race-free append (the same op the producer uses): order does not matter
    because the contract sums increments — provenValue is commutative. Bounded to
    the newest MAX_QUEUE so an unreachable node can't grow the backlog forever."""
    proofs = proofs[-MAX_QUEUE:]
    if not proofs:
        return
    paths.ensure_home()
    with open(paths.proof_queue(), "a") as f:
        for p in proofs:
            f.write(json.dumps(p) + "\n")


def _is_revert(exc: Exception) -> bool:
    """A deterministic on-chain rejection (a forged/invalid proof) vs a transient
    infrastructure error. A revert will never anchor, so the proof is dropped;
    anything else (node down, timeout) is kept and retried."""
    return any(s in type(exc).__name__ for s in ("ContractLogic", "Revert", "TransactionFailed"))


def anchor_proof_batch(w3, contract, account, proofs):
    """Anchor each queued proof in order. Returns (anchored, dropped, unanchored):
    a reverting proof is dropped (logged), the first infrastructure failure stops
    the batch and returns the unanchored remainder for retry."""
    anchored = 0
    dropped = 0
    for i, p in enumerate(proofs):
        try:
            anchor_proof_on_contract(w3, contract, account, p["work"])
            anchored += 1
        except Exception as exc:  # noqa: BLE001 - classified by _is_revert
            if _is_revert(exc):
                dropped += 1
                continue
            return anchored, dropped, proofs[i:]
    return anchored, dropped, []


def sync_once() -> dict:
    """Anchor once. Drains the per-cycle proof queue so the chain's provenValue
    tracks total_v cycle-for-cycle (full fidelity). Each proof is removed only
    after it anchors on-chain; unanchored proofs are kept for the next sync. Falls
    back to the legacy self-declared snapshot only when no proofs are queued and
    the contract lacks anchorProof. Returns the log entry that was written."""
    v_value = read_total_v()
    batch = take_proof_batch()
    conn = _connect()

    # Proofs queued but no node reachable: keep them (bounded) for later.
    if batch and conn is None:
        requeue_proofs(batch)
        entry = build_log_entry(v_value, "local", note=f"{len(batch)} proof(s) queued (web3/contract unavailable)")
        log_sync(entry)
        return entry

    # Proofs queued and a node is up: anchor the verified increments.
    if batch:
        w3, contract = conn
        if not _supports_proof(contract):
            requeue_proofs(batch)  # contract predates anchorProof; keep + snapshot
            entry = _legacy_anchor(w3, contract, v_value)
            log_sync(entry)
            return entry
        anchored, dropped, unanchored = anchor_proof_batch(w3, contract, w3.eth.default_account, batch)
        requeue_proofs(unanchored)
        proven = contract.functions.provenValue().call()
        status = "success" if not unanchored else ("partial" if anchored else "failed")
        entry = build_log_entry(
            proven / 100.0, status, mode="proven",
            anchored=anchored, dropped=dropped, requeued=len(unanchored),
            proven_value_centi=proven,
        )
        log_sync(entry)
        return entry

    # No proofs queued: heartbeat, or the legacy self-declared snapshot path.
    if conn is None:
        note = "no value to anchor" if v_value <= 0 else "web3/contract unavailable"
        entry = build_log_entry(v_value, "local", note=note)
        log_sync(entry)
        return entry
    w3, contract = conn
    if _supports_proof(contract):
        # Verified contract, nothing new to prove this cycle: don't self-declare.
        note = "no value to anchor" if v_value <= 0 else "no new cycle proof to anchor"
        entry = build_log_entry(v_value, "local", note=note)
        log_sync(entry)
        return entry
    entry = _legacy_anchor(w3, contract, v_value)
    log_sync(entry)
    return entry


def _legacy_anchor(w3, contract, v_value) -> dict:
    """Self-declared snapshot fallback (proofless bootstrap / pre-anchorProof
    contract). Does not write to the sync log; the caller does."""
    if v_value <= 0:
        return build_log_entry(v_value, "local", note="no value to anchor")
    try:
        return anchor_on_contract(w3, contract, w3.eth.default_account, v_value)
    except Exception as exc:  # pragma: no cover - network path
        return build_log_entry(v_value, "failed", error=str(exc))


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
