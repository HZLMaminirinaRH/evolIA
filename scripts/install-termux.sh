#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# evolIA — Termux installer
# Installs packages, builds the Rust + Go binaries, and lays the Python
# services + services.toml into $HOME/evolia. Idempotent; safe to re-run.
# ============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
EVOLIA_HOME="${EVOLIA_HOME:-$HOME/evolia}"
BIN="${PREFIX:-/usr}/bin"

echo "==> evolIA installer"
echo "    repo:        $REPO"
echo "    EVOLIA_HOME: $EVOLIA_HOME"
echo "    bin:         $BIN"

echo "==> [1/5] Packages"
if command -v pkg >/dev/null 2>&1; then
    pkg update -y
    pkg install -y python rust golang git termux-api
else
    echo "    (pkg not found — skipping; ensure python, cargo, go are installed)"
fi

echo "==> [2/5] Storage access (photo/video capture)"
command -v termux-setup-storage >/dev/null 2>&1 && termux-setup-storage || \
    echo "    (termux-setup-storage unavailable — skipping)"

echo "==> [3/5] Build Rust security spine"
( cd "$REPO/rust" && cargo build --release )
install -m 0755 "$REPO/rust/target/release/evolia-start" "$BIN/evolia-start"
install -m 0755 "$REPO/rust/target/release/evolia-stop" "$BIN/evolia-stop"

echo "==> [4/5] Build Go services"
( cd "$REPO/go" && go build -o "$BIN/evolia-mesh-sync" ./cmd/mesh-sync )
( cd "$REPO/go" && go build -o "$BIN/evolia-net" ./cmd/evolia-net )
( cd "$REPO/go" && go build -o "$BIN/evolia-bridge" ./cmd/evolia-bridge )

echo "==> [5/5] Install Python services into $EVOLIA_HOME"
mkdir -p "$EVOLIA_HOME"
cp "$REPO"/python/*.py "$EVOLIA_HOME/"
# Ship requirements so `pip install -r requirements-web3.txt` works from here.
cp "$REPO"/python/requirements*.txt "$EVOLIA_HOME/" 2>/dev/null || true
# Ship the prebuilt contract artifact too (used by evolia_deploy in web3 mode).
cp "$REPO/contracts/EvoliaCore.json" "$EVOLIA_HOME/" 2>/dev/null || true

cat > "$EVOLIA_HOME/services.toml" <<'TOML'
# Services launched by evolia-start. Edit freely; a missing python file is
# skipped, and a missing binary just logs and is skipped.

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

echo ""
echo "==> Done. Run:  evolia-start   (and later)  evolia-stop"
