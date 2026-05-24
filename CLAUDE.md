# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

evolIA targets a mobile (Termux/Android) deployment whose goal is to **enhance mobile
digital interactions**. It is a **polyglot** project, split by responsibility:

- **Rust** — the security spine (auth, crypto, process orchestration). **Implemented.**
- **Go** — networking / mesh services (peer discovery, sync). *Planned.*
- **Python** — sensor fusion, the cognitive value (`V`) / BTC-e accumulation model,
  the dashboard, and Bitcoin integration. *Planned / in progress.*

Target runtime is Termux: paths default to `$HOME/evolia`, and sensor/location/WiFi/BLE
data is read via the `termux-api` CLIs (`termux-sensor`, `termux-location`,
`termux-wifi-scaninfo`, `termux-bluetooth-*`). Code must degrade gracefully when those
binaries are absent so it still builds and runs off-device.

## Repository layout

```
rust/                    Cargo workspace — the security spine
  evolia-core/           shared paths + on-disk state helpers (EVOLIA_HOME)
  evolia-auth/           3-layer owner auth (PIN, password, biometric); Argon2 hashing
  evolia-security/       master-key derivation, ChaCha20-Poly1305 AEAD, session tokens,
                         HMAC-SHA256 signatures
  evolia-start/          binary: refuse-if-running -> auth -> init security -> launch services
  evolia-stop/           binary: owner auth gate -> SIGTERM/SIGKILL services -> clear state
go/                      Go module `evolia` — networking
  mesh/                  mesh-block detection + propagation logic (testable)
  cmd/mesh-sync/         binary: watch the mesh vault, propagate new blocks over UDP
python/                  services that produce/consume the shared state
  evolia_paths.py        shared EVOLIA_HOME layout (Python mirror of evolia-core)
  evolia_sensors.py      sensor readers (termux-api), graceful fallback off-device
  evolia_evolve.py       THE evolutive formula (exponential) — the cognitive core
  evolia_value.py        accumulator: base(actions) x (1+V) + sensor floor
  evolia_run.py          main loop launched by evolia-start
  ganache_db.py          anchor total_v to Ganache (LOCAL mode without web3)
  evolia_bitcoin.py      V -> satoshi conversion + wallet/conversion state
  dashboard.py           read-only aggregation of the shared state
```

### How the pieces interoperate

Every language resolves the same `EVOLIA_HOME` and the same state files (Rust `evolia-core`,
Python `evolia_paths`, Go `mesh.Home`), so the services communicate through files:

- `evolia_value.py` runs the **core formula** `evolia_evolve.evolve()` each cycle and writes
  `evolia_value_state.json` + mirrors `total_v`/`cycle_count` to `evolia_identity_state.json`.
- `ganache_db.py` reads `evolia_identity_state.json` and appends to `evolia_blockchain_sync.log`.
- `evolia_bitcoin.py` converts value to satoshis into `evolia_bitcoin_wallet.json` /
  `evolia_btc_conversion_history.json`.
- Go `mesh-sync` watches `evolia_mesh_vault/` and propagates blocks; `dashboard.py` reads all
  of the above and prints the unified snapshot.

The value economy is tunable in `evolia_evolve.py`: `ACTION_RATES` (video > photo > sms >
screen) and `COEFF` (BLE > WiFi).

`evolia-start` authenticates the owner, derives the security key **in-process** from the
just-verified password (the "liaison directe" between auth and security), mints a session
token, and launches the configured services — passing `EVOLIA_SESSION_TOKEN` and
`EVOLIA_DEVICE_ID` to each child via the environment. The launched service list defaults to
`evolia_run.py`, `ganache_db.py continuous 30`, and `dashboard.py`; each is skipped if its
script is absent, and the list can be overridden by `$EVOLIA_HOME/services.toml`
(`[[service]]` tables: `name`, `command`, `args`, `requires_file`) — e.g. to add the built
Go `mesh-sync` binary.

## Build / test / run (Rust)

Run from the `rust/` directory:

- Build everything: `cargo build`
- Run all tests: `cargo test`
- Test one crate: `cargo test -p evolia-security`
- Lint: `cargo clippy --all-targets`
- Run the binaries: `cargo run -p evolia-start` / `cargo run -p evolia-stop`

Notes:
- The auth flow uses `rpassword` for masked input, which requires a **real terminal**.
  In a TTY-less sandbox it fails with `os error 6` (ENXIO) — that is expected, not a bug.
- `EVOLIA_HOME` overrides the state directory (default `$HOME/evolia`); useful for tests.
- Never commit runtime state — `.evolia_auth.json`, `.evolia_session.json`,
  `.evolia_pids.json`, and `*.log` are git-ignored.

## Build / test / run (Go)

Run from the `go/` directory:

- Build: `go build ./...`
- Vet: `go vet ./...`
- Test: `go test ./...`
- Run: `go run ./cmd/mesh-sync` (honors `EVOLIA_HOME` and `EVOLIA_PEERS`)

## Build / test / run (Python)

Standard-library only for the value model and sensors; optional services
(`ganache_db.py`, `evolia_bitcoin.py`) guard `web3`/`bitcoinlib` and run in a degraded
LOCAL mode when those are absent. Run from the `python/` directory:

- Tests: `python3 tests/test_value.py` and `python3 tests/test_services.py`
- Demo a module: `EVOLIA_HOME=/tmp/x python3 evolia_value.py` (also `dashboard.py`, etc.)
- Deps: `pip install -r requirements.txt` (none required for the value model)

## When more code lands, update this file

Keep entries grounded in what is actually in the repo; verify by reading files rather than
assuming. Add commands/architecture notes here the moment Go (`go.mod`) or Python
(`requirements.txt`/`pyproject.toml`) services land, so future sessions stay productive.
