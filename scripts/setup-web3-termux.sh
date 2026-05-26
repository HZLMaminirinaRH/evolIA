#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# evolIA — enable REAL on-chain anchoring on Termux. Run AFTER install-termux.sh.
#
# Two targets, auto-selected:
#   * SEPOLIA  — if SEPOLIA_RPC_URL is set. Public testnet; no local node. You
#                MUST also export EVOLIA_PRIVATE_KEY (a funded test account) so
#                transactions can be signed client-side. Export both BEFORE
#                `evolia-start` so the launched services inherit them.
#   * GANACHE  — otherwise. Installs Node + Ganache (local dev chain) and brings
#                it up as a service; no private key needed (accounts are unlocked).
#
# Re-run this whenever you re-run install-termux.sh. NEVER commit EVOLIA_PRIVATE_KEY.
# ============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
EVOLIA_HOME="${EVOLIA_HOME:-$HOME/evolia}"

echo "==> [1/4] Python web3"
pip install web3

echo "==> [2/4] Ship prebuilt contract + python services"
mkdir -p "$EVOLIA_HOME"
cp "$REPO/contracts/EvoliaCore.json" "$EVOLIA_HOME/EvoliaCore.json"
cp "$REPO"/python/*.py "$EVOLIA_HOME/"
cp "$REPO"/python/requirements*.txt "$EVOLIA_HOME/" 2>/dev/null || true

if [ -n "${SEPOLIA_RPC_URL:-}" ]; then
    echo "==> [3/4] SEPOLIA mode (RPC: $SEPOLIA_RPC_URL)"
    if [ -z "${EVOLIA_PRIVATE_KEY:-}" ]; then
        echo "    !! EVOLIA_PRIVATE_KEY is not set. A public RPC will not sign for you."
        echo "       export EVOLIA_PRIVATE_KEY=0x<funded-test-account-key> and re-run,"
        echo "       and keep it exported in the shell that runs evolia-start."
    fi
    echo "    Diagnose: SEPOLIA_RPC_URL=$SEPOLIA_RPC_URL python3 ganache_db.py diagnose"
    # No local node service: deploy + anchor talk to the remote RPC, inheriting
    # SEPOLIA_RPC_URL / EVOLIA_PRIVATE_KEY from the environment of evolia-start.
    cat > "$EVOLIA_HOME/services.toml" <<'TOML'
# Sepolia testnet mode. deploy runs once (idempotent); ganache_db anchors each
# cycle's proof on-chain via anchorProof. Export SEPOLIA_RPC_URL and
# EVOLIA_PRIVATE_KEY in the shell that runs evolia-start (services inherit them).

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
name = "supernode"
command = "python3"
args = ["evolia_supernode.py", "continuous", "30"]
requires_file = "evolia_supernode.py"

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
else
    echo "==> [3/4] GANACHE mode (local dev chain)"
    command -v pkg >/dev/null 2>&1 && pkg install -y nodejs || echo "    (install nodejs manually)"
    command -v npm >/dev/null 2>&1 && npm install -g ganache || echo "    (install ganache manually: npm i -g ganache)"
    cat > "$EVOLIA_HOME/services.toml" <<'TOML'
# Local Ganache mode. Ganache comes up first; evolia_deploy runs once (idempotent,
# retries until the node is ready); ganache_db then anchors on-chain. No private
# key needed — Ganache accounts are unlocked.

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
name = "supernode"
command = "python3"
args = ["evolia_supernode.py", "continuous", "30"]
requires_file = "evolia_supernode.py"

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
fi

echo "==> [4/4] Done. Now:  evolia-stop && evolia-start"
echo "    Check it anchored:  grep success ~/evolia/logs/ganache_db.log"
