#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Real on-chain anchoring test using an in-process EVM (eth-tester).

Deploys the committed EvoliaCore artifact to a Python EVM and anchors values
through the actual ganache_db code path — no Ganache node or solc required.
Skips cleanly if web3 / eth-tester are not installed.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

try:
    from web3 import Web3

    try:
        from web3 import EthereumTesterProvider
    except ImportError:  # older/newer layout
        from web3.providers.eth_tester import EthereumTesterProvider
    import eth_tester  # noqa: F401

    HAVE_WEB3 = True
except Exception:
    HAVE_WEB3 = False

import evolia_deploy  # noqa: E402
import ganache_db  # noqa: E402


def _artifact() -> dict:
    return json.loads(evolia_deploy.find_artifact().read_text())


def test_artifact_present_and_valid():
    art = _artifact()
    assert art["bytecode"].startswith("0x") and len(art["bytecode"]) > 4
    fns = {e.get("name") for e in art["abi"] if e.get("type") == "function"}
    assert {"anchorValue", "blockCount", "totalCognitiveValue"} <= fns
    # The verified proof-of-work path must be present on-chain.
    assert {"anchorProof", "computeGain", "provenValue", "provenBlockCount"} <= fns


def test_anchor_on_real_evm():
    if not HAVE_WEB3:
        print("   (SKIP: web3/eth-tester not installed)")
        return

    w3 = Web3(EthereumTesterProvider())
    account = w3.eth.accounts[0]
    art = _artifact()

    deployer = w3.eth.contract(abi=art["abi"], bytecode=art["bytecode"])
    receipt = w3.eth.wait_for_transaction_receipt(deployer.constructor().transact({"from": account}))
    contract = w3.eth.contract(address=receipt["contractAddress"], abi=art["abi"])

    # Anchor through the actual ganache_db code path.
    entry = ganache_db.anchor_on_contract(w3, contract, account, 12.34)
    assert entry["status"] == "success"
    assert contract.functions.blockCount().call() == 1
    assert contract.functions.totalCognitiveValue().call() == int(12.34 * 100)

    # A second anchor accumulates on-chain.
    ganache_db.anchor_on_contract(w3, contract, account, 5.0)
    assert contract.functions.blockCount().call() == 2
    assert contract.functions.totalCognitiveValue().call() == int(12.34 * 100) + 500


def test_anchor_proof_recomputes_and_rejects_forgery():
    if not HAVE_WEB3:
        print("   (SKIP: web3/eth-tester not installed)")
        return

    w3 = Web3(EthereumTesterProvider())
    account = w3.eth.accounts[0]
    art = _artifact()

    deployer = w3.eth.contract(abi=art["abi"], bytecode=art["bytecode"])
    receipt = w3.eth.wait_for_transaction_receipt(deployer.constructor().transact({"from": account}))
    contract = w3.eth.contract(address=receipt["contractAddress"], abi=art["abi"])

    # computeGain mirrors the off-chain formula: screen×10 + photo×2 at v=0.4 ->
    # base = 10·0.05 + 2·2.50 = 5.5; ΔV = 5.5·1.4 + 1·0.4 = 8.1 -> 810 centi.
    assert contract.functions.computeGain(10, 0, 2, 0, 400).call() == 810

    # Honest proof through the actual ganache_db path: screen_input×40 at v=0 over
    # 5s -> 40·0.05 = 2.0 BTC-e = 200 centi. The chain recomputes it.
    entry = ganache_db.anchor_proof_on_contract(w3, contract, account, {"actions": {"screen_input": 40}, "v": 0.0, "dt": 5.0})
    assert entry["status"] == "success" and entry["mode"] == "proven"
    assert entry["proven_value_centi"] == 200  # the value is the on-chain sum, not declared
    assert contract.functions.provenValue().call() == 200
    assert contract.functions.provenBlockCount().call() == 1

    # A second honest proof accumulates: photo_taken×1 at v=0 -> 2.5 BTC-e = 250.
    ganache_db.anchor_proof_on_contract(w3, contract, account, {"actions": {"photo_taken": 1}, "v": 0.0, "dt": 5.0})
    assert contract.functions.provenValue().call() == 450

    # A forged proof — 1000 videos in 5s (cap is 2/s) — reverts on-chain and does
    # not move the proven value: fabricating BTC-e is impossible at the source.
    raised = False
    try:
        ganache_db.anchor_proof_on_contract(w3, contract, account, {"actions": {"video_taken": 1000}, "v": 0.0, "dt": 5.0})
    except Exception:
        raised = True
    assert raised, "forged work must revert on-chain"
    assert contract.functions.provenValue().call() == 450


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
