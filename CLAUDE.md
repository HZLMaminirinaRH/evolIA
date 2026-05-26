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
  defense/               adaptive defense (Go mirror of evolia-security::evolutive): bounded
                         attack buffer + injection detector + NetIntensity coupling + intake Gate
  pow/                   cognitive proof-of-work validator: a value increment must equal
                         base(actions)x(1+v)+floor*v with actions within physical rate caps,
                         and the total stays under a wall-clock value ceiling (MaxGainPerSec x
                         elapsed-since-genesis) that bounds even a first-contact baseline
  mesh/                  block detection + propagation + receive (StoreIncoming, PoW-validated) +
                         HMAC sign/verify (SignBlock/VerifyBlock) + LoadPeers + TotalV
  netdisc/               peer-discovery registry + announce parsing (testable)
  bridge/                peer block-exchange HTTP handlers + param fusion + defense-gated intake
  cmd/mesh-sync/         binary: emit local value (signed) + relay over UDP; receive/verify peer
                         blocks on :5555, feeding the adaptive defense
  cmd/evolia-net/        binary: LAN peer discovery -> evolia_peers.json
  cmd/evolia-bridge/     binary: HTTP API (/block, /sync, /mesh/total_v, /health, /defense)
python/                  services that produce/consume the shared state
  evolia_paths.py        shared EVOLIA_HOME layout (Python mirror of evolia-core)
  evolia_sensors.py      sensor readers (termux-api), graceful fallback off-device
  evolia_evolve.py       THE evolutive formula (exponential) — the cognitive core
  evolia_value.py        accumulator: base(actions) x (1+V) + sensor floor; emits a
                         per-cycle cognitive proof-of-work (evolia_work_proof.json)
  evolia_learning.py     Super-peer learning: aggregate peer blocks, evolve parameters
                         asymmetrically (not symmetric gossip). Learn action effectiveness,
                         sensor correlations, user engagement patterns; output evolved params.
  evolia_supernode.py    binary: run the Super-peer service (learn_and_evolve loop, 30s cadence)
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
- Go `mesh-sync` emits this node's value (read from `evolia_identity_state.json`) to peers over
  UDP each cycle and relays vault blocks, then listens on `:5555` to receive peer blocks into the
  vault (`mesh.StoreIncoming`, keyed by device id, marked seen so they are never re-propagated);
  `dashboard.py` reads all of the above and prints the unified snapshot.
- Block authentication uses a shared fleet key `EVOLIA_MESH_KEY` (HMAC-SHA256 over `device|v`):
  with it set, propagated/posted blocks are signed and verified, and forged/injection/malformed
  input is rejected and **feeds the adaptive defense** (`go/defense`, mirroring
  `evolia-security::evolutive`) so the more attacks evolIA absorbs, the harder it defends. Without
  the key, blocks degrade to unsigned (single-device still works). Strictly reactive — never
  retaliates. The defense also **relaxes** when the pressure stops: `mesh-sync` calls `Decay()`
  on each quiet cycle (no hostile datagram since the last) and `evolia-bridge` decays on a ticker,
  so the level breathes back down instead of only ever climbing.
- The absorbed defense is now **load-bearing**, not just reported: it actuates a per-source intake
  throttle (`defense.Gate`, a token bucket whose burst/refill shrink as the buffer fills, with a
  guaranteed floor + bounded source table). A sustained flood is squeezed toward the floor and the
  gate reopens as the buffer decays — wired into `mesh-sync`'s UDP receive (drop, never re-recorded
  as an attack, so it can't feed itself) and `evolia-bridge`'s `/block`+`/sync` (HTTP 429). This is
  the live, operational instantiation of `evolia-security::evolutive::a_global`
  (`defense.NetIntensity` couples the three flows on real signals — attack rate, peer block rate,
  the buffer level — with `D_evo` as the counterweight); the Rust `a_global` remains the formal spec.
- Value claims carry a **cognitive proof-of-work** so a peer cannot fabricate BTC-e: each block
  declares the work behind its increment (the cycle's actions, the sensor multiplier `v∈[0,1]`,
  and `dt`), and the receiver (`go/pow`) recomputes that `ΔV = base(actions)·(1+v) + floor·v` and
  that the declared actions stay within physical **rate caps** (`MaxRatePerSec·dt` — you cannot
  claim more videos/sms/etc. than time allows). `evolia_value.py` emits the proof each cycle to
  `evolia_work_proof.json`; `mesh-sync` attaches it to the value it emits (and relays it, since it
  is persisted in the vault). A **keyed fleet must carry a valid proof** — a signed-but-proofless
  or non-reconciling block is rejected as `ForgedWork` and feeds the adaptive defense. Each block
  is self-contained (carries its own declared prior `v_prev`) so validation survives a dropped UDP
  datagram, while the store path enforces **monotonicity** (value never rolls back; a non-advancing
  claim is silently dropped as stale).
- The proof-of-work is **woven into the evolutive defense**, not a static check. On intake the
  receiver bounds a claim by an **admissible value ceiling** (`mesh.AdmissibleCeiling`) that composes
  two layers: (1) a **physical wall-clock ceiling** — the most value the fleet could have earned
  since its genesis (`pow.ValueCeiling = MaxGainPerSec · elapsed`, anchored on the fleet-wide
  `EVOLIA_GENESIS_UNIX`), which **bounds even the trust-on-first-use baseline** (a device cannot
  predate genesis, so it can't assert more than physics allows) — closing the old v1 TOFU minting
  hole to a physical limit; and (2) the **evolutive defense factor** (`defense.CeilingFactor`) — as
  the absorbed-defense level rises (`ForgedWork` feeds it), the admissible growth headroom above what
  we already trust shrinks toward a floor (`0.25×`), so *the more forged-work pressure evolIA absorbs,
  the less value any block may claim*. This is the **PoW arm of `a_global`'s `D_evo`** (formal spec:
  `evolia-security::evolutive::ceiling_factor`), and it breathes back up as the buffer decays.
  Tightening applies only to headroom above trusted value, so an attack storm throttles new/forged
  baselines without ever rejecting an established peer's honest increment. With `EVOLIA_GENESIS_UNIX`
  unset the ceiling is `+Inf` (disabled; the per-increment PoW checks still apply) — set it fleet-wide
  (same value on every node, like `EVOLIA_MESH_KEY`) to activate the bound. Residual latitude: a
  key-holder can still pick any baseline *under* the physical ceiling on first contact; the structural
  closure (verifiable history rather than a self-declared baseline) is on-chain PoW verification, the
  planned next layer.
- Both intake paths store a peer block **keyed by device id** (`recv_<device>.json`) and overwrite
  on re-send — the UDP receiver (`mesh.StoreIncoming`) and the HTTP bridge (`bridge.StoreBlock`,
  via the shared `mesh.StorePeerBlock`) — so `TotalV` counts each peer once and never inflates
  from repeated posts.
- The **Super-peer role** is a central coordinating node (asymmetric, not hierarchical) that:
  - **Reads** peer blocks from the mesh vault (carrying their cognitive work proofs)
  - **Learns** patterns: which actions/sensor mixes achieve high `v_normalized`, user engagement,
    attack frequency per peer
  - **Evolves** the formula: adjusts `ACTION_RATES` and parameter weights based on global patterns
  - **Propagates** learned parameters back via `evolia_cognitive_params.json` (picked up by
    mesh-sync and relayed to all peers)
  
  Unlike symmetric peer gossip, the Super-peer sees patterns across the entire mesh and uses them
  to strengthen all peers. One node can run as the Super-peer (e.g., `evolia_supernode.py continuous`),
  or multiple nodes can learn independently (latest/strongest wins via fusion). Valorizes heavy
  mobile users by boosting engagement parameters for peers with high activity.

The value economy is tunable in `evolia_evolve.py`: `ACTION_RATES` (video > photo > sms >
screen) and `COEFF` (BLE > WiFi). The Super-peer learns optimal tuning from peer behavior.

`evolia-start` authenticates the owner, derives the security key **in-process** from the
just-verified password (the "liaison directe" between auth and security), mints a session
token, and launches the configured services — passing `EVOLIA_SESSION_TOKEN` and
`EVOLIA_DEVICE_ID` to each child via the environment. The launched service list defaults to
`evolia_actions.py`, `evolia_run.py`, `evolia_supernode.py continuous 30` (the Super-peer
learning loop), `ganache_db.py continuous 30`, and `dashboard.py`; each is skipped if its
script is absent, and the list can be overridden by `$EVOLIA_HOME/services.toml`
(`[[service]]` tables: `name`, `command`, `args`, `requires_file`) — e.g. to add the built
Go `mesh-sync` binary. `scripts/install-termux.sh` writes that `services.toml` with the full
mesh (Go binaries + Python services, including `supernode`).

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
- Run: `go run ./cmd/mesh-sync` (honors `EVOLIA_HOME`, `EVOLIA_PEERS`,
  `EVOLIA_MESH_CYCLE_SECONDS` — the emit/decay cadence, default 5s; shared with
  the bridge's defense-decay ticker — and `EVOLIA_GENESIS_UNIX`, the fleet-wide
  proof-of-work value-ceiling anchor: set the SAME Unix timestamp on every node,
  like `EVOLIA_MESH_KEY`; unset disables the ceiling)

## Build / test / run (Python)

Standard-library only for the value model and sensors; optional services
(`ganache_db.py`, `evolia_bitcoin.py`) guard `web3`/`bitcoinlib` and run in a degraded
LOCAL mode when those are absent. Run from the `python/` directory:

- Tests: run the scripts in `tests/` (`test_value.py`, `test_services.py`,
  `test_actions.py`, `test_web3.py`). `test_web3.py` skips unless web3+eth-tester
  are installed.
- Demo a module: `EVOLIA_HOME=/tmp/x python3 evolia_value.py` (also `dashboard.py`, etc.)
- Super-peer service: `python3 evolia_supernode.py continuous [interval]` (default 30s cadence;
  reads mesh vault, aggregates peer work proofs, learns and evolves parameters)
- Deps: `pip install -r requirements.txt` (none for the value model);
  `pip install -r requirements-web3.txt` for real on-chain anchoring.

## When more code lands, update this file

Keep entries grounded in what is actually in the repo; verify by reading files rather than
assuming. Add commands/architecture notes here the moment Go (`go.mod`) or Python
(`requirements.txt`/`pyproject.toml`) services land, so future sessions stay productive.
