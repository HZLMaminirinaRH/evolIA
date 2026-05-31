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
  egress/                per-peer-host outbound token bucket (preventive self-DoS guard):
                         caps the rate at which mesh-sync sends to any single peer so a
                         growing peer set or a queued-message blast cannot saturate the
                         radio. Independent of defense.Gate (which throttles hostile
                         ingress); a throttled send is dropped, not scored as an attack.
                         Burst 8, refill 4/s, keyed by host (blocks+chat share budget).
  peerhealth/            per-peer send-side health tracker with exponential backoff:
                         a peer whose Dial or Write fails enters a cooldown window
                         (5s → 5min cap, doubling) and is skipped until it expires.
                         A single success re-warms it instantly. Independent of
                         defense and egress; a cold-peer skip is silent (no log
                         spam, no attack score). Keyed by host so blocks+chat share
                         the verdict. Send-side signal only — receive-side correlation
                         is Phase 3.
  meshstats/             UDP transport telemetry recorder + JSON persister: each
                         mesh-sync cycle writes evolia_mesh_stats.json (sends_ok,
                         sends_fail, peers_cold, throttle_events {egress, ingress_
                         defense, cold_skipped}, attacks_by_flow {blocks/chat × kind},
                         receives, defense_level, updated_at). The Android UI reads
                         this file for live diagnostic, parallel to evolia_chat_bt_
                         stats.json for the Bluetooth transport. Atomic writes via
                         paths.WriteFileAtomic so a half-written stats file is never
                         observable.
  chat/                  opaque transport for the app's end-to-end peer chat: routing
                         envelope (Message) around a sealed body the relay NEVER decrypts,
                         outbox drain (atomic rename) + inbox append (id dedup) + injection-
                         classified intake. E2E lives in the app; Go only routes.
  cmd/mesh-sync/         binary: emit local value (signed) + relay over UDP; receive/verify peer
                         blocks on :5555, feeding the adaptive defense; also relays opaque
                         chat envelopes (drain outbox -> peers, receive on :5556 -> inbox)
  cmd/evolia-net/        binary: LAN peer discovery on :5557 -> evolia_peers.json
  cmd/evolia-bridge/     binary: HTTP API (/block, /sync, /mesh/total_v, /health, /defense)

  UDP port allocation (strict, no collision):
    :5555  mesh-sync block intake (value sync, signed blocks + PoW)
    :5556  mesh-sync chat intake  (opaque E2E envelopes, never decrypted)
    :5557  evolia-net discovery   (LAN announces broadcast, dedicated port)
python/                  services that produce/consume the shared state
  evolia_paths.py        shared EVOLIA_HOME layout (Python mirror of evolia-core)
  evolia_sensors.py      sensor readers (termux-api), graceful fallback off-device
  evolia_evolve.py       THE evolutive formula (exponential) — the cognitive core
  evolia_value.py        accumulator: base(actions) x (1+V) + sensor floor; emits a
                         per-cycle cognitive proof-of-work (evolia_work_proof.json)
                         + appends each value-advancing cycle's proof to the
                         durable anchor queue (evolia_proof_queue.jsonl)
  evolia_learning.py     Super-peer learning: aggregate peer blocks, evolve parameters
                         asymmetrically (not symmetric gossip). Learn action effectiveness,
                         sensor correlations, user engagement patterns; output evolved params.
  evolia_supernode.py    binary: run the Super-peer service (learn_and_evolve loop, 30s cadence)
  evolia_actions.py      action capture (SMS/photo/video + CLI) -> action queue
  evolia_run.py          main loop: drain action queue + sample sensors + cycle
  evolia_deploy.py       deploy EvoliaCore from the prebuilt artifact (web3)
  ganache_db.py          anchor value on-chain (web3); prefers anchorProof (chain
                         recomputes+verifies the PoW increment), legacy snapshot
                         fallback; LOCAL mode without web3
  evolia_bitcoin.py      V -> satoshi conversion + wallet/conversion state
  dashboard.py           read-only aggregation of the shared state
contracts/               EvoliaCore.sol (on-chain PoW verifier: anchorProof
                         recomputes ΔV + enforces rate caps -> provenValue;
                         per-account ledger provenOf + transfer for owner-to-owner
                         BTC-e payments, conserving the total) +
                         prebuilt EvoliaCore.json (abi+bytecode)
android/                 Plan B: Kotlin app — foreground service supervising the
                         Go binaries + a native Kotlin port of the value engine
                         (core/: Evolve, EvoliaValue, ActionQueue, EvoliaPaths;
                         sensors/AndroidSensors; sensors/CompassView+CompassMath +
                         CompassActivity: a visual compass off the rotation-vector
                         sensor — accel+gyro+magneto fusion — plus a live magnetic-
                         field readout (µT); purely additive UX, the gyroscope and
                         magnetometer already feed V). Mirrors evolia_evolve.py so it
                         runs without Python (the signal-9 fix). Built in Android
                         Studio, not in CI (no Android SDK here); see android/README.md.
                         chat/: end-to-end peer messaging — ChatIdentity (Ed25519 sign
                         + X25519 ECDH from one seed -> ChaCha20-Poly1305), ChatStore +
                         ChatManager (seal -> outbox / inbox -> open), ChatActivity UI.
                         BluetoothMeshTransport: Bluetooth Classic RFCOMM relay
                         (insecure socket + own E2E crypto, à la Briar) for offline
                         peer messaging; ChatIntake (transport-agnostic receive
                         pipeline, Kotlin mirror of go/chat) + BluetoothFraming
                         (length-prefixed frames) are pure/JVM-tested.
                         chain/: on-chain BTC-e via web3j — EvoliaWallet (a device
                         Ethereum key, encrypted at rest in the Android Keystore) +
                         ChainAnchor (deploys/anchors EvoliaCore, plus transfer() /
                         refreshBalance() for owner-to-owner payments). MainActivity
                         adds Transfer + Receive buttons (Transfer gated by the strict
                         owner auth; Receive just shows/shares the address — receiving
                         is passive) and shows the transferable on-chain balance with a
                         clickable (*) -> ExchangeGuideActivity (the explicit on-chain
                         prerequisites: RPC, gas, reachable node). The bundled
                         assets/EvoliaCore.json mirrors contracts/EvoliaCore.json.
```

On-chain anchoring is optional and self-contained: `contracts/EvoliaCore.json`
ships the compiled ABI+bytecode (no solc needed at deploy time). With `web3`
installed and a node reachable, `evolia_deploy.py` deploys it (idempotently) and
`ganache_db.py` anchors each sync. Anchoring prefers the **proven path**: it
drains the per-cycle proof queue (`evolia_proof_queue.jsonl`) and calls
`EvoliaCore.anchorProof` for each, which **recomputes the value increment
on-chain** (`ΔV = base(actions)·(1+v) + floor·v` in centi-BTC-e) and enforces the
physical per-action **rate caps** — so the contract's `provenValue` is the
on-chain-verified sum, not a self-declared number (a forged proof reverts), and
tracks `total_v` cycle-for-cycle. It falls back to the legacy self-declared
`anchorValue` snapshot only when no proof is queued (proofless bootstrap) or the deployed
contract predates `anchorProof`. Beyond accruing value, the contract is also a
**peer-to-peer BTC-e ledger**: `anchorProof` credits the caller's per-account
balance (`provenOf[msg.sender]`) alongside the global `provenValue`, and
`EvoliaCore.transfer(to, amount)` moves proven BTC-e between owners. The chain
orders transactions and checks the sender's balance, so a transfer can never
overspend (the **structural anti-double-spend** the offline mesh alone cannot
give) and the total is conserved (value moves, never created;
`Σ provenOf == provenValue`). `ganache_db.transfer_btce(to, amount)` drives it,
**gated on an active owner session** (`EVOLIA_SESSION_TOKEN`/`EVOLIA_DEVICE_ID`
from `evolia-start`'s Argon2 auth) and logged to `evolia_transfer_history.jsonl`
(separate from the sync log so it never pollutes anchored totals); the dashboard
surfaces outbound transfers. On-chain strict: a transfer is only settled once the
chain has verified it (no web3/node ⇒ status `local`, unsettled). `transfer_on_contract`,
the conservation invariant and the overdraw revert are unit-tested in
`tests/test_web3.py`. The contract mirrors the Go `pow` validator and
`evolia_evolve.py` (rates/caps); **keep the constants in sync across the three
languages.** `ganache_db.anchor_on_contract`, `anchor_proof_on_contract` and the
forgery rejection are unit-tested against an in-process EVM in `tests/test_web3.py`.
Without web3, `ganache_db` logs in LOCAL mode and the rest is unaffected.

If you change `contracts/EvoliaCore.sol`, regenerate the committed artifact (the
CI/web3 path uses it directly, no solc): compile with solc 0.8.21
(`--optimize --optimize-runs 200`) and write `{contractName, abi, bytecode (0x…),
compiler}` to `contracts/EvoliaCore.json`.

`evolia_actions.py` only ever appends events to `evolia_action_queue.jsonl`; the
single state owner `evolia_run.py` drains that queue each cycle, so there is
exactly one writer of the value state (race-free). The same append-only/drain
pattern carries proofs to the chain: `evolia_value.py` appends each
value-advancing cycle's proof to `evolia_proof_queue.jsonl`, and `ganache_db.py`
drains it (`take_proof_batch`, atomic rename like `evolia_actions.drain`),
anchoring each via `anchorProof`. A proof leaves the queue only after it anchors
on-chain; an unreachable node or transient failure re-queues the remainder
(`requeue_proofs`, a race-free append — `provenValue` is commutative so order is
irrelevant), bounded to the newest `MAX_QUEUE` so an offline spell can't grow it
unbounded. A deterministically-reverting (forged/corrupt) proof is dropped. This
makes the on-chain `provenValue` track `total_v` cycle-for-cycle (full fidelity),
not a periodic sample. `evolia-net` writes
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
  (same value on every node, like `EVOLIA_MESH_KEY`) to activate the bound. The mesh intake bounds a
  peer's *self-declared baseline* to a physical limit; the **structural closure** is **on-chain PoW
  verification** (`EvoliaCore.anchorProof`): the contract starts every node's `provenValue` at 0 and
  only ever adds increments it recomputes from declared work that passes the rate caps, so the anchored
  value is a verified sum from genesis rather than a self-declared baseline — no fabrication latitude on
  the on-chain record.
- Both intake paths store a peer block **keyed by device id** (`recv_<device>.json`) and overwrite
  on re-send — the UDP receiver (`mesh.StoreIncoming`) and the HTTP bridge (`bridge.StoreBlock`,
  via the shared `mesh.StorePeerBlock`) — so `TotalV` counts each peer once and never inflates
  from repeated posts.
- **End-to-end peer chat** rides the same mesh as an *opaque* overlay. The cryptography is the
  app's: `ChatIdentity` (a dedicated identity, distinct from the on-chain wallet, so it works in
  LOCAL mode) derives an Ed25519 signing key + an X25519 ECDH key from one seed; `seal()` does
  static-static ECDH → HKDF-SHA256 → ChaCha20-Poly1305 with an Ed25519 signature binding the
  sender, and a challenge-response proves a peer owns its advertised public bundle. The **Go relay
  never holds a key or decrypts** — `go/chat` only routes: the app appends sealed envelopes to
  `evolia_chat_outbox.jsonl`, `mesh-sync` drains it and carries each to peers over UDP (`:5556`),
  receives inbound on `:5556`, and (routing by the fingerprint the app publishes to
  `evolia_chat_fingerprint.txt`) appends those addressed to this node to `evolia_chat_inbox.jsonl`,
  which the app reads and decrypts. Hostile chat input (malformed/injection) feeds the **same
  adaptive defense + intake gate** as block input. Delivery is best-effort (UDP; the receiver dedups
  by message id), so an ACK/retry or HTTP-bridge layer can ride on top without protocol changes.
  The chat is **ephemeral**: messages live only for a running session — `EvoliaService`
  purges the inbox + outbox (`ChatStore.purgeMessages`) on stop (`onDestroy`, after the relay
  child is killed) and on start (`onCreate`, a clean slate even after an OS kill), and the UI
  drops its in-memory view when evolIA is not running. Nothing confidential lingers on disk or in
  memory past a stop. The identity key and contacts are kept (not messages; the device stays
  reachable). On-disk the relayed bodies are sealed anyway — plaintext exists only transiently in
  the app during display. Messages are **mini-messages capped at 480 characters**
  (`ChatManager.MAX_MESSAGE_CHARS`). Composing one is itself a **valued digital action**: each sent
  message records an `sms_sent` action (via `ActionQueue`) on the sender's own device, so chat
  engagement feeds `V` → BTC-e like screen/photo/video activity (received messages are not valued —
  they were the peer's action on their device). WiFi/LAN transport is live (Phase 1, UDP, Go relay);
  **Bluetooth Classic RFCOMM** is live too (Phase 2, `android/chat/BluetoothMeshTransport`), so
  messages move peer-to-peer with NO internet and NO shared WiFi — the offline mesh use case Briar
  serves. It rides the SAME opaque sealed envelopes and NEVER decrypts a body: an *insecure* RFCOMM
  socket (no OS pairing) is deliberate and safe because end-to-end confidentiality/authenticity
  already lives in `ChatIdentity` (static-static X25519 -> ChaCha20-Poly1305 + Ed25519) — exactly
  Briar's "treat the link as untrusted, do our own crypto over it" model. The transport runs in the
  Android app (Go cannot reach the Android radio): an RFCOMM service socket (fixed SDP UUID) accepts
  connections and streams length-prefixed envelopes through `ChatIntake`, so hostile Bluetooth input
  (malformed / SQL-injection-like routing fields) feeds the **same adaptive defense** as block input
  and **breathes back down** on quiet ticks (`decayIfQuiet`, mirroring mesh-sync); the send side
  drains the outbox to in-range bonded peers and re-queues anything undelivered (so the UDP relay or
  the next tick still carries it). Degrades gracefully (no adapter / radio off / `BLUETOOTH_CONNECT`
  withheld => no-op). It needs **on-device testing** (no Android SDK or Bluetooth radio in CI/sandbox);
  the pure parts (`ChatIntake`, `BluetoothFraming`, the `ChatStore` outbox drain/requeue) are
  JVM-tested. Multi-hop store-and-forward and simultaneous cross-transport fan-out (one message
  offered to UDP AND Bluetooth at once) are Phase 3; Phase 2 is direct delivery to peers in radio
  range, mirroring the UDP relay.
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

## Monetary policy: algorithmic, not institutional

**evolIA is not a bank.** BTC-e is a unit of *proven value* — the mathematical output of physical activity
(actions, motion, proximity), rates (action caps), and time. It has no issuing authority, no central reserve,
no discretionary policy lever. The **Super-peer is a learning coordinator, not a monetary authority**:

- **What the Super-peer does NOT do:**
  - Does not mint, burn, or modify value on any device
  - Does not operate a central exchange (Bourse) or market maker
  - Does not set exchange rates (BTC-e ↔ fiat or sat)
  - Does not approve, reject, or gatekeep transactions

- **What the Super-peer does:**
  - Reads peer blocks (carrying work proofs) from the mesh vault
  - Learns patterns: which actions/sensor mixes achieve high engagement, which peers are active, which peers are adversarial
  - **Evolves formula parameters asymmetrically** (not symmetric gossip): adjusts `ACTION_RATES` thresholds and `COEFF` weights based on global patterns
  - Propagates evolved parameters back via `evolia_cognitive_params.json` to all peers
  - Strengthens the formula itself through pattern recognition, not through a ledger

- **Monetary policy IS the algorithm:**
  - `ACTION_RATES`: physical rate caps (max SMS/video/photo per second) that cannot be exceeded
  - `COEFF`: weighting (BLE > WiFi) in the base formula `base(actions) × (1+v) + floor×v`
  - `EVOLIA_GENESIS_UNIX`: the fleet-wide value ceiling (no peer can claim more than `MaxGainPerSec × elapsed`)
  - Proof-of-work validation: on-chain recomputation (`EvoliaCore.anchorProof`) recalculates the increment and rejects forgeries

- **No internal exchange mechanism:**
  - No Bourse, no order book, no bid/ask spread
  - Transfers are **peer-to-peer direct**: `transfer(to, amount)` moves proven BTC-e from one address to another
  - The contract conserves the total (Σ `provenOf` == `provenValue`); value only moves, never created

- **External exchange is P2P and opt-in:**
  - If a user wishes to convert BTC-e to satoshi (on-chain) or fiat (off-chain), that is a private transaction
  - evolIA provides no built-in conversion tool (only the guide explaining how to use external services)
  - Any external exchange is mediated by the user's choice of platform (Binance, OKX, peer, etc.) — not operated or endorsed by evolIA
  - The gateway to on-chain settlement is the RPC node (which the user controls or trusts); gas is paid in the network's native coin

- **Distribution is decentralized:**
  - Every device earns BTC-e according to its own activity (action queue, sensor data, time)
  - Peers accumulate independently; the mesh synchronizes work proofs, not value creation
  - Transfers are user-initiated and logged locally; no centralized ledger except on-chain (if opted into)
  - The app never suggests, advertises, or provides links to external exchanges; it only explains prerequisites (RPC, gas) and mechanics (send/receive)

This design closes monetary policy to discretion. The formulas, caps, and on-chain verification are the sole "laws" of the economy.

The value economy is tunable in `evolia_evolve.py`: `ACTION_RATES` (video > photo > sms >
screen) and `COEFF` (BLE > WiFi). The Super-peer learns optimal tuning from peer behavior.

Sensor capture is sharpened so it tracks real engagement, not ambient noise:
`evolia_sensors.read_motion` reads the **linear acceleration** (gravity removed) for the
motion signal — ~0 at rest, rising with actual movement — instead of the raw accelerometer
whose constant ~9.8 gravity floor drowned it out; the normalization scale is
`_LINEAR_ACCEL_SCALE` in `evolia_evolve.py` (~10-17, where brisk movement saturates). WiFi/BLE
scans are filtered by signal strength (`NEAR_RSSI_DBM = -70`): only nearby APs/devices count,
so a far weak signal isn't valued as interaction (scans without an RSSI field degrade to the
plain visible-count). The Android Kotlin port mirrors both: `AndroidSensors` registers
`TYPE_LINEAR_ACCELERATION` and RSSI-filters WiFi/BLE, and `Evolve.LINEAR_ACCEL_SCALE` matches
the Python scale — **keep these two in sync across Python and Kotlin** (Go/Rust/Solidity take
the declared `v` as given, so they are unaffected).

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
