#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Deploy EvoliaCore to an Ethereum node (Ganache, or any web3 RPC).

Uses the prebuilt artifact `contracts/EvoliaCore.json` (ABI + bytecode), so no
Solidity compiler is needed at deploy time. Writes the deployment record and
ABI under EVOLIA_HOME exactly where ganache_db expects them:

    EVOLIA_HOME/evolia_deployment.json   {contract_address, deployer, ...}
    EVOLIA_HOME/EvoliaCore.abi           the ABI

Idempotent: if a live contract is already recorded, it is reused. Requires the
`web3` package (install via requirements-web3.txt); errors clearly otherwise.

Usage:  python3 evolia_deploy.py            # uses GANACHE_URL or 127.0.0.1:8545
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

import evolia_paths as paths

GANACHE_URL = os.environ.get("GANACHE_URL", "http://127.0.0.1:8545")


def find_artifact() -> Path:
    candidates = [
        paths.evolia_home() / "EvoliaCore.json",
        Path(__file__).resolve().parent.parent / "contracts" / "EvoliaCore.json",
        Path(__file__).resolve().parent / "EvoliaCore.json",
    ]
    for c in candidates:
        if c.exists():
            return c
    raise FileNotFoundError("EvoliaCore.json artifact not found (looked in EVOLIA_HOME and contracts/)")


def deploy(rpc: str = GANACHE_URL, connect_retries: int = 10) -> str:
    from web3 import Web3  # imported lazily so the module loads without web3

    artifact = json.loads(find_artifact().read_text())
    w3 = Web3(Web3.HTTPProvider(rpc))

    for _ in range(connect_retries):
        if w3.is_connected():
            break
        time.sleep(2)
    else:
        raise ConnectionError(f"cannot reach the node at {rpc}")

    # Idempotent: reuse an existing live deployment.
    dep = paths.deployment()
    if dep.exists():
        try:
            addr = json.loads(dep.read_text()).get("contract_address")
            if addr and len(w3.eth.get_code(Web3.to_checksum_address(addr))) > 0:
                print(f"[deploy] already deployed at {addr}")
                return addr
        except Exception:
            pass

    account = w3.eth.accounts[0]
    w3.eth.default_account = account
    contract = w3.eth.contract(abi=artifact["abi"], bytecode=artifact["bytecode"])
    tx_hash = contract.constructor().transact({"from": account})
    receipt = w3.eth.wait_for_transaction_receipt(tx_hash)
    address = receipt["contractAddress"]

    paths.ensure_home()
    paths.contract_abi().write_text(json.dumps(artifact["abi"], indent=2))
    paths.deployment().write_text(json.dumps({
        "contract_address": address,
        "deployer": account,
        "block_number": receipt["blockNumber"],
        "tx_hash": receipt["transactionHash"].hex(),
    }, indent=2))

    print(f"[deploy] EvoliaCore deployed at {address} (block {receipt['blockNumber']})")
    return address


if __name__ == "__main__":
    try:
        deploy()
    except Exception as exc:
        print(f"[deploy] FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
