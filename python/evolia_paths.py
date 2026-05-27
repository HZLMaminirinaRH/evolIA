#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Shared on-disk layout for every Python service.

This is the Python mirror of the Rust `evolia-core` crate: one place that
resolves `EVOLIA_HOME` and names the state files, so the value model, the
blockchain anchor, the bitcoin bridge and the dashboard all read and write
the *same* files and therefore interoperate.

Default home is `$HOME/evolia` (matching the Termux layout and the Rust spine);
`EVOLIA_HOME` overrides it.
"""

from __future__ import annotations

import os
import tempfile
from pathlib import Path

# 1 unit of cognitive value (BTC-e) converts to this many satoshis.
CONVERSION_RATE_V_TO_SAT = 100_000


def evolia_home() -> Path:
    env = os.environ.get("EVOLIA_HOME")
    return Path(env) if env else Path(os.path.expanduser("~")) / "evolia"


def ensure_home() -> Path:
    home = evolia_home()
    home.mkdir(parents=True, exist_ok=True)
    return home


def atomic_write_text(path: Path, text: str) -> None:
    """Write text durably: a temp file in the same dir, flushed + fsync'd, then
    atomically renamed into place. A crash or kill (signal 9) can never leave a
    half-written state file — readers see either the old or the new content."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".tmp-", suffix=path.suffix)
    try:
        with os.fdopen(fd, "w") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


# --- shared state files ------------------------------------------------------

def value_state() -> Path:
    return evolia_home() / "evolia_value_state.json"


def identity_state() -> Path:
    """Headline figures (total_v, cycle_count) read by ganache_db + dashboard."""
    return evolia_home() / "evolia_identity_state.json"


def blockchain_sync_log() -> Path:
    return evolia_home() / "evolia_blockchain_sync.log"


def bitcoin_wallet_state() -> Path:
    return evolia_home() / "evolia_bitcoin_wallet.json"


def conversion_history() -> Path:
    return evolia_home() / "evolia_btc_conversion_history.json"


def transfer_history() -> Path:
    """Append-only log of this node's outbound on-chain BTC-e transfers
    (EvoliaCore.transfer). Kept separate from the blockchain sync log so it never
    pollutes the anchored-value totals the dashboard sums."""
    return evolia_home() / "evolia_transfer_history.jsonl"


def work_proof() -> Path:
    """Latest cycle's cognitive proof-of-work, attached by mesh-sync to the
    value it emits so peers can validate the increment (see go/pow)."""
    return evolia_home() / "evolia_work_proof.json"


def proof_queue() -> Path:
    """Append-only queue of per-cycle work proofs awaiting on-chain anchoring.
    The value model appends one line per value-advancing cycle; ganache_db drains
    it and anchors each via EvoliaCore.anchorProof, so every cycle's verified
    increment lands on-chain exactly once (full fidelity, not sampled)."""
    return evolia_home() / "evolia_proof_queue.jsonl"


def mesh_vault() -> Path:
    return evolia_home() / "evolia_mesh_vault"


def deployment() -> Path:
    return evolia_home() / "evolia_deployment.json"


def cognitive_params() -> Path:
    """Learned cognitive parameters from the Super-peer, fused by bridge/mesh-sync."""
    return evolia_home() / "evolia_cognitive_params.json"


def contract_abi() -> Path:
    return evolia_home() / "EvoliaCore.abi"
