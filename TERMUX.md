# evolIA on Termux — required tools & setup

This is the full list of what evolIA needs on a Termux (Android) device, plus
how to build and run it. A helper script automates everything:
`bash scripts/install-termux.sh`.

## 1. The Termux:API app (mandatory for sensors/SMS)

Install the **Termux:API** companion app (from F-Droid — same source as Termux).
Without it, the `termux-*` commands below cannot talk to Android.

## 2. Termux packages (`pkg install`)

| Package      | Why                                                        |
|--------------|------------------------------------------------------------|
| `python`     | the value model, sensors, action capture, dashboard, etc. |
| `rust`       | builds the security spine (`cargo`, `rustc`)               |
| `golang`     | builds the mesh-sync and network services                 |
| `git`        | clone / update the repo                                    |
| `termux-api` | the `termux-*` CLIs the Python code calls                  |

```sh
pkg update -y
pkg install -y python rust golang git termux-api
```

Optional (only for the on-chain / real-Bitcoin features, which otherwise run in
a degraded LOCAL mode): `clang`, `libffi`, `openssl`, `libsecp256k1` — these are
build dependencies for the Python packages `web3` and `bitcoinlib`.

```sh
pkg install -y clang libffi openssl libsecp256k1   # optional
pip install requests                                # used by sensors
pip install web3 bitcoinlib                         # optional, heavy build
```

## 3. Storage access (for photo/video capture)

```sh
termux-setup-storage     # grant access; creates ~/storage/... incl. ~/storage/dcim
```

The action detector watches `~/storage/dcim` (override with `EVOLIA_DCIM`).

## 4. `termux-*` commands used, by feature

| Feature (input)        | Command used                |
|------------------------|-----------------------------|
| accelerometer/gyro/mag | `termux-sensor`             |
| location               | `termux-location`           |
| WiFi access points     | `termux-wifi-scaninfo`      |
| BLE devices            | `termux-bluetooth-scaninfo` |
| sms_sent detection     | `termux-sms-list`           |
| biometric auth (opt.)  | `termux-fingerprint`        |

Each one degrades gracefully: if a command is missing or returns nothing
(e.g. location disabled), that signal simply contributes 0.

## 5. Build & install

```sh
git clone <your-fork-url> evolIA && cd evolIA
bash scripts/install-termux.sh
```

The script: installs packages, runs `termux-setup-storage`, builds the Rust
binaries (`evolia-start`, `evolia-stop`) and the Go binaries
(`evolia-mesh-sync`, `evolia-net`) into `$PREFIX/bin`, copies the Python
services into `~/evolia`, and writes `~/evolia/services.toml`.

## 6. Run

```sh
evolia-start     # 3-layer owner auth, then launches every service
evolia-stop      # owner auth, then stops everything
```

First run prompts for PIN + password (+ optional fingerprint). State lives in
`~/evolia` (override with `EVOLIA_HOME`). The dashboard prints the unified
snapshot; logs are under `~/evolia/logs/`.

## 6b. Validate the mesh (on-device)

The fleet key `EVOLIA_MESH_KEY` must be the **same on every node**; with it set,
blocks are HMAC-signed and forged/injection/malformed input is rejected and
hardens the adaptive defense (which then relaxes once the pressure stops).

### Single device — smoke test (no second phone needed)

Exercises the bridge intake, defense hardening, and defense decay in one shell:

```sh
export EVOLIA_MESH_KEY="my-fleet-key"   # or leave unset to test the unsigned path
evolia-bridge &                         # HTTP API on :8080
SIG=$(EVOLIA_MESH_KEY="$EVOLIA_MESH_KEY" python3 - <<'PY'
import hashlib, hmac, os
key=os.environ.get("EVOLIA_MESH_KEY","").encode()
print(hmac.new(key, b"peerX|7", hashlib.sha256).hexdigest() if key else "")
PY
)

# a) Idempotent intake: re-posting the same peer keeps the latest value,
#    it does NOT sum (no TotalV double-count).
curl -s localhost:8080/block -d "{\"source_device\":\"peerX\",\"v_value\":7,\"sig\":\"$SIG\"}"
curl -s localhost:8080/block -d "{\"source_device\":\"peerX\",\"v_value\":7,\"sig\":\"$SIG\"}"
curl -s localhost:8080/mesh/total_v     # expect mesh_total_v = 7 (not 14)

# b) Defense rises on injection-like input (rejected, never stored).
curl -s localhost:8080/block -d '{"source_device":"x;DROP TABLE peers--","v_value":1}'
curl -s localhost:8080/defense          # defense_level > 0

# c) Defense relaxes after a few quiet seconds (the bridge decays on a ticker).
sleep 6 && curl -s localhost:8080/defense   # defense_level has dropped
```

### Two devices — the real mesh (same Wi-Fi/LAN)

On **each** phone: run `install-termux.sh`, export the **same** `EVOLIA_MESH_KEY`,
then `evolia-start`. Verify:

1. **Discovery** — `evolia-net` broadcasts announces; within ~30 s each node's
   `~/evolia/evolia_peers.json` lists the other peer.
2. **Propagation** — `mesh-sync` emits each node's signed value over UDP `:5555`;
   the dashboard on both phones shows `mesh_total_v` equal to the **sum** of both
   nodes' `total_v` (each peer counted once, even across many cycles).
3. **Defense, end to end** — send a forged block (wrong/missing signature) to a
   node's `:5555` from a laptop; it is rejected and `~/evolia/evolia_mesh_sync.log`
   logs `bad signature ... defense=…`. Stop sending and the level decays on the
   next quiet cycles.

If a phone keeps getting `signal 9` (OEM battery manager) before these steps
complete, see section 8 and consider Plan B (section 9).

## 7. Recording screen-input actions

Screen taps cannot be captured passively on a non-rooted device, so feed them
explicitly (e.g. from a Termux:Tasker shortcut or widget):

```sh
python3 ~/evolia/evolia_actions.py record screen_input 1
```

## 7b. Real on-chain anchoring (Web3 + Ganache) — optional

By default `ganache_db` runs in LOCAL mode. For a real blockchain test:

```sh
bash scripts/setup-web3-termux.sh    # installs nodejs+ganache+web3, ships the
                                     # prebuilt contract, rewrites services.toml
evolia-stop && evolia-start          # Ganache -> deploy (once) -> on-chain anchoring
```

Then confirm it anchored on-chain (status "success", not "local"):

```sh
grep success ~/evolia/logs/ganache_db.log
```

The contract is shipped prebuilt in `contracts/EvoliaCore.json`, so **no Solidity
compiler is needed**. Re-run `setup-web3-termux.sh` after any `install-termux.sh`
(the latter writes the LOCAL-mode services.toml).

## 8. Staying alive on Android (avoiding signal 9)

`evolia-start` already does two things to survive Android:

- it takes a **CPU wake lock** (`termux-wake-lock`) automatically, and
  `evolia-stop` releases it (`termux-wake-unlock`);
- it launches every service in its **own session** (`setsid`), so the services
  are detached from the terminal and keep running after `evolia-start` returns
  and after the terminal is closed.

For maximum resilience on aggressive OEMs (Motorola, Xiaomi, …):

1. Disable battery optimization for **Termux** and **Termux:API**.
2. Lock the Termux notification / keep its persistent notification.
3. Install **Termux:Boot** to relaunch on reboot, and consider
   **termux-services** (runit) which *restarts* a service if Android kills it:
   ```sh
   pkg install termux-services
   ```

Even with all of the above, a pure-Termux background daemon is at the mercy of
the OEM battery manager. If services keep getting `signal 9`, use Plan B.

## 9. Plan B — a thin Kotlin foreground-service app

The Android-sanctioned way to run persistently is a **foreground Service** with
an ongoing notification; Android will not kill it the way it kills background
Termux processes. The good news: **we do not rewrite the project**. The Kotlin
app only needs to *supervise the binaries we already built*.

Sketch:

1. Bundle the compiled binaries in the APK:
   - Rust: `evolia-start`, `evolia-stop`
   - Go: `evolia-net`, `evolia-mesh-sync`, `evolia-bridge`
   - Python services + a Python runtime (e.g. Chaquopy, or call the Termux
     Python), or port `evolia_run`/`evolia_value` to Kotlin later.
   Place them under the app's `nativeLibraryDir` / `filesDir`.
2. A `ForegroundService` (with `startForeground()` + a notification) sets
   `EVOLIA_HOME` to the app's files dir and `Runtime.exec()`s the services,
   restarting any that exit. This reuses the exact same shared-file protocol.
3. Acquire a `PARTIAL_WAKE_LOCK` in the service for CPU-while-screen-off.
4. Optional UI: render the dashboard snapshot (read `evolia_identity_state.json`
   etc.) in a simple Compose screen.

This keeps the Rust security spine and the Go networking intact and swaps only
the *process supervisor* (Termux shell → Android foreground service). It is the
recommended path if Termux persistence proves unreliable on the device.
