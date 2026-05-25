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
  paths/                 shared EVOLIA_HOME layout (Go mirror of evolia-core)
  mesh/                  mesh-block detection + propagation + receive (StoreIncoming) + LoadPeers + TotalV
  netdisc/               peer-discovery registry + announce parsing (testable)
  bridge/                peer block-exchange HTTP handlers + param fusion (testable)
  cmd/mesh-sync/         binary: watch the vault + propagate new blocks over UDP; receive peer blocks on :5555
  cmd/evolia-net/        binary: LAN peer discovery -> evolia_peers.json
  cmd/evolia-bridge/     binary: HTTP API (/block, /sync, /mesh/total_v, /health)
python/                  services that produce/consume the shared state
  evolia_paths.py        shared EVOLIA_HOME layout (Python mirror of evolia-core)
  evolia_sensors.py      sensor readers (termux-api), graceful fallback off-device
  evolia_evolve.py       THE evolutive formula (exponential) — the cognitive core
  evolia_value.py        accumulator: base(actions) x (1+V) + sensor floor
  evolia_actions.py      action capture (SMS/photo/video + CLI) -> action queue
  evolia_run.py          main loop: drain action queue + sample sensors + cycle
  evolia_deploy.py       deploy EvoliaCore from the prebuilt artifact (web3)
  ganache_db.py          anchor total_v on-chain (web3); LOCAL mode without it
  evolia_bitcoin.py      V -> satoshi conversion + wallet/conversion state
  dashboard.py           read-only aggregation of the shared state
contracts/               EvoliaCore.sol + prebuilt EvoliaCore.json (abi+bytecode)
android/                 Plan B: Kotlin app — foreground service supervising the
                         Go binaries + a native Kotlin port of the value engine
                         (core/: Evolve, EvoliaValue, ActionQueue, EvoliaPaths;
                         sensors/AndroidSensors). Mirrors evolia_evolve.py so it
                         runs without Python (the signal-9 fix). Built in Android
                         Studio, not in CI (no Android SDK here); see android/README.md.
```

On-chain anchoring is optional and self-contained: `contracts/EvoliaCore.json`
ships the compiled ABI+bytecode (no solc needed). With `web3` installed and a
node reachable, `evolia_deploy.py` deploys it (idempotently) and `ganache_db.py`
calls `anchorValue` each sync; `ganache_db.anchor_on_contract` is unit-tested
against an in-process EVM in `tests/test_web3.py`. Without web3, `ganache_db`
logs in LOCAL mode and the rest is unaffected.

`evolia_actions.py` only ever appends events to `evolia_action_queue.jsonl`; the
single state owner `evolia_run.py` drains that queue each cycle, so there is
exactly one writer of the value state (race-free). `evolia-net` writes
`evolia_peers.json`; mesh-sync reloads it each cycle to know where to propagate.
`evolia-bridge` accepts peer blocks over HTTP, stores them in the mesh vault
(so mesh-sync/dashboard pick them up) and fuses incoming cognitive params into
`evolia_cognitive_params.json`.

## Continuous integration

`.github/workflows/ci.yml` runs on every push and PR: Rust (`cargo fmt --check`,
`cargo clippy -- -D warnings`, build, test), Go (`gofmt -l`, `go vet`, build,
test), Python (`py_compile`, test scripts), a Web3 job (installs web3+eth-tester,
runs `tests/test_web3.py` against an in-process EVM), and Android (Gradle
`:app:testDebugUnitTest` + `:app:assembleDebug` — compiles the Kotlin app and
runs the pure value-core tests, so a broken port fails the build even though
there is no Android SDK in the local sandbox). Keep them green; run the same
commands locally before pushing.

### How the pieces interoperate

Every language resolves the same `EVOLIA_HOME` and the same state files (Rust `evolia-core`,
Python `evolia_paths`, Go `mesh.Home`), so the services communicate through files:

- `evolia_value.py` runs the **core formula** `evolia_evolve.evolve()` each cycle and writes
  `evolia_value_state.json` + mirrors `total_v`/`cycle_count` to `evolia_identity_state.json`.
- `ganache_db.py` reads `evolia_identity_state.json` and appends to `evolia_blockchain_sync.log`.
- `evolia_bitcoin.py` converts value to satoshis into `evolia_bitcoin_wallet.json` /
  `evolia_btc_conversion_history.json`.
- Go `mesh-sync` watches `evolia_mesh_vault/` and propagates new blocks over UDP to peers, and
  also listens on `:5555` to receive peer blocks into the vault (`mesh.StoreIncoming`, keyed by
  device id, marked seen so they are never re-propagated); `dashboard.py` reads all of the above
  and prints the unified snapshot.

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

- Tests: run the scripts in `tests/` (`test_value.py`, `test_services.py`,
  `test_actions.py`, `test_web3.py`). `test_web3.py` skips unless web3+eth-tester
  are installed.
- Demo a module: `EVOLIA_HOME=/tmp/x python3 evolia_value.py` (also `dashboard.py`, etc.)
- Deps: `pip install -r requirements.txt` (none for the value model);
  `pip install -r requirements-web3.txt` for real on-chain anchoring.

## When more code lands, update this file

Keep entries grounded in what is actually in the repo; verify by reading files rather than
assuming. Add commands/architecture notes here the moment Go (`go.mod`) or Python
(`requirements.txt`/`pyproject.toml`) services land, so future sessions stay productive.
