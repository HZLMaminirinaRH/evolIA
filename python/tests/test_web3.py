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
