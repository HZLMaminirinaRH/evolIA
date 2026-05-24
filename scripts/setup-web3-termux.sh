#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# evolIA — enable REAL on-chain anchoring on Termux (Web3 + Ganache).
# Run AFTER install-termux.sh. Installs Node/Ganache + web3, ships the prebuilt
# contract, and rewrites services.toml to bring up Ganache, deploy once, and
# anchor on-chain. Re-run this whenever you re-run install-termux.sh.
# ============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
EVOLIA_HOME="${EVOLIA_HOME:-$HOME/evolia}"

echo "==> [1/4] Node + Ganache + Python web3"
command -v pkg >/dev/null 2>&1 && pkg install -y nodejs || echo "    (install nodejs manually)"
command -v npm >/dev/null 2>&1 && npm install -g ganache || echo "    (install ganache manually: npm i -g ganache)"
pip install web3

echo "==> [2/4] Ship prebuilt contract + python services"
mkdir -p "$EVOLIA_HOME"
cp "$REPO/contracts/EvoliaCore.json" "$EVOLIA_HOME/EvoliaCore.json"
cp "$REPO"/python/*.py "$EVOLIA_HOME/"

echo "==> [3/4] services.toml (Ganache -> deploy -> on-chain anchoring)"
cat > "$EVOLIA_HOME/services.toml" <<'TOML'
# Real-chain mode. Ganache comes up first; evolia_deploy runs once (idempotent,
# retries until the node is ready); ganache_db then anchors on-chain.

[[service]]
name = "ganache"
command = "ganache"
args = ["--host", "127.0.0.1", "--port", "8545"]

[[service]]
name = "deploy"
command = "python3"
args = ["evolia_deploy.py"]
requires_file = "evolia_deploy.py"

[[service]]
name = "actions"
command = "python3"
args = ["evolia_actions.py"]
requires_file = "evolia_actions.py"

[[service]]
name = "network"
command = "evolia-net"

[[service]]
name = "bridge"
command = "evolia-bridge"

[[service]]
name = "mesh_sync"
command = "evolia-mesh-sync"

[[service]]
name = "evolia_run"
command = "python3"
args = ["evolia_run.py"]
requires_file = "evolia_run.py"

[[service]]
name = "ganache_db"
command = "python3"
args = ["ganache_db.py", "continuous", "30"]
requires_file = "ganache_db.py"

[[service]]
name = "dashboard"
command = "python3"
args = ["dashboard.py"]
requires_file = "dashboard.py"
TOML

echo "==> [4/4] Done. Now:  evolia-stop && evolia-start"
echo "    Check it anchored:  grep success ~/evolia/logs/ganache_db.log"
