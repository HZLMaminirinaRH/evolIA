#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Bitcoin bridge: convert accumulated value (BTC-e) into testnet satoshis.

Conversion math and state persistence are pure standard library and always
work. Real BIP44 address generation and balance lookups require `bitcoinlib`
and network access; those degrade gracefully when the library is absent so the
module still imports and the dashboard still has data to show.

All files live under EVOLIA_HOME (see evolia_paths), shared with the rest.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import evolia_paths as paths

RATE = paths.CONVERSION_RATE_V_TO_SAT
MIN_TX_SAT = 1_000
MAX_TX_SAT = 1_000_000

try:
    from bitcoinlib.keys import HDKey  # noqa: F401
    from bitcoinlib.mnemonic import Mnemonic  # noqa: F401

    HAS_BITCOINLIB = True
except Exception:  # pragma: no cover - depends on optional install
    HAS_BITCOINLIB = False


def v_to_sat(v_value: float) -> int:
    """Convert a BTC-e value to satoshis, clamped to the per-tx bounds."""
    sat = int(v_value * RATE)
    return max(MIN_TX_SAT, min(sat, MAX_TX_SAT)) if sat > 0 else 0


def sat_to_btc(sat: int) -> float:
    return sat / 100_000_000


def sat_to_usd(sat: int, usd_per_btc: float = 70_000.0) -> float:
    return sat_to_btc(sat) * usd_per_btc


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


class BitcoinBridge:
    def __init__(self, wallet_path: Path | None = None, history_path: Path | None = None):
        self.wallet_path = wallet_path or paths.bitcoin_wallet_state()
        self.history_path = history_path or paths.conversion_history()
        self.addresses: list[str] = []
        self.balance_sat = 0
        self.conversions: list[dict] = []

    def queue_conversion(self, v_value: float, source: str = "value") -> dict:
        """Record a V -> SAT conversion (pending until broadcast)."""
        conv = {
            "timestamp": _now(),
            "v_value": v_value,
            "sat": v_to_sat(v_value),
            "source": source,
            "status": "pending",
            "address": self.addresses[0] if self.addresses else None,
        }
        self.conversions.append(conv)
        self.save()
        return conv

    def to_dict(self) -> dict:
        return {
            "timestamp": _now(),
            "addresses": self.addresses,
            "balance_sat": self.balance_sat,
            "pending_conversions": sum(1 for c in self.conversions if c["status"] == "pending"),
            "has_bitcoinlib": HAS_BITCOINLIB,
        }

    def save(self) -> None:
        self.wallet_path.parent.mkdir(parents=True, exist_ok=True)
        self.wallet_path.write_text(json.dumps(self.to_dict(), indent=2))
        self.history_path.write_text(json.dumps(
            {"timestamp": _now(), "conversions": self.conversions}, indent=2
        ))

    def load(self) -> bool:
        if not self.wallet_path.exists():
            return False
        try:
            data = json.loads(self.wallet_path.read_text())
        except (OSError, ValueError):
            return False
        self.addresses = data.get("addresses", [])
        self.balance_sat = int(data.get("balance_sat", 0))
        if self.history_path.exists():
            try:
                self.conversions = json.loads(self.history_path.read_text()).get("conversions", [])
            except (OSError, ValueError):
                self.conversions = []
        return True


if __name__ == "__main__":
    bridge = BitcoinBridge()
    bridge.load()
    conv = bridge.queue_conversion(42.0, source="demo")
    print(json.dumps({"conversion": conv, "wallet": bridge.to_dict()}, indent=2))
