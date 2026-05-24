#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tests for the aligned services: bitcoin, ganache_db, dashboard.

These exercise the pure logic and the shared-file interop; the network paths
(web3, bitcoinlib) are intentionally not required.
"""

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import evolia_paths as paths  # noqa: E402
import evolia_bitcoin as btc  # noqa: E402
import ganache_db  # noqa: E402
import dashboard  # noqa: E402


def _home(monkey_env):
    """Point EVOLIA_HOME at a fresh temp dir for the duration of a test."""
    import os

    d = tempfile.mkdtemp()
    os.environ["EVOLIA_HOME"] = d
    return Path(d)


# --- bitcoin conversion math -------------------------------------------------

def test_v_to_sat_and_back():
    assert btc.v_to_sat(0) == 0
    assert btc.v_to_sat(1.0) == paths.CONVERSION_RATE_V_TO_SAT
    # clamped to the max per-tx ceiling
    assert btc.v_to_sat(10_000) == btc.MAX_TX_SAT
    assert abs(btc.sat_to_btc(100_000_000) - 1.0) < 1e-12
    assert abs(btc.sat_to_usd(100_000_000, 70_000) - 70_000) < 1e-6


def test_bitcoin_conversion_persists():
    home = _home(None)
    bridge = btc.BitcoinBridge()
    conv = bridge.queue_conversion(5.0, source="test")
    assert conv["sat"] == btc.v_to_sat(5.0)
    assert paths.conversion_history().exists()

    reloaded = btc.BitcoinBridge()
    assert reloaded.load() is True
    assert len(reloaded.conversions) == 1


# --- ganache_db --------------------------------------------------------------

def test_ganache_local_mode_logs_without_chain():
    home = _home(None)
    # value model has written total_v here:
    paths.identity_state().write_text(json.dumps({"total_v": 12.5, "cycle_count": 3}))
    entry = ganache_db.sync_once()
    assert entry["status"] in ("local", "success", "failed")
    assert entry["v_value"] == 12.5
    assert paths.blockchain_sync_log().exists()


def test_ganache_no_value_is_local_noop():
    _home(None)
    entry = ganache_db.sync_once()
    assert entry["status"] == "local"
    assert entry["v_value"] == 0.0


# --- dashboard interop -------------------------------------------------------

def test_dashboard_aggregates_shared_state():
    home = _home(None)
    paths.ensure_home()
    # identity (value model)
    paths.identity_state().write_text(json.dumps({"total_v": 20.0, "cycle_count": 4}))
    # mesh vault block (Go mesh-sync writes these)
    paths.mesh_vault().mkdir(parents=True, exist_ok=True)
    (paths.mesh_vault() / "block1.json").write_text(json.dumps({"v_value": 5.0}))
    # ganache sync log
    paths.blockchain_sync_log().write_text(json.dumps({"status": "local", "v_value": 7.0}) + "\n")
    # bitcoin wallet
    paths.bitcoin_wallet_state().write_text(json.dumps({"balance_sat": 100_000, "addresses": ["tb1qx"]}))

    snap = dashboard.collect()
    assert snap["personal"]["total_v"] == 20.0
    assert snap["mesh"]["blocks"] == 1 and snap["mesh"]["total_v"] == 5.0
    assert snap["ganache"]["tx"] == 1 and snap["ganache"]["anchored_v"] == 7.0
    assert snap["bitcoin"]["balance_sat"] == 100_000
    assert snap["cognitive_power"] == 20.0 + 5.0 + 7.0
    # render must not raise
    assert "PUISSANCE COGNITIVE" in dashboard.render(snap)


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except Exception as exc:  # noqa: BLE001
                failures += 1
                print(f"FAIL {name}: {exc}")
    sys.exit(1 if failures else 0)
