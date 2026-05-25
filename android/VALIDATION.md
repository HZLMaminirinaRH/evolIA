# evolIA Android (Plan B) — device validation guide

CI proves the app **compiles** and the pure cores (value, crypto, auth) are
**correct**. This guide covers what CI cannot: **on-device behaviour** — sensors
emitting, photos captured, a real signed `anchorValue`, the Go binaries running,
and the owner auth gate. Work top to bottom; each phase has concrete steps and
pass criteria.

## 0. Prerequisites

- An **arm64** device or emulator, **API 26+** (use **33+** to exercise the
  media-capture and biometric paths).
- **Android SDK + NDK** installed; `adb` on PATH.
- For on-chain validation: a reachable Ethereum JSON-RPC node (e.g. Ganache).
  From an emulator the host is `http://10.0.2.2:8545`; from a physical device use
  the host's LAN IP.

## 1. Build & install

```sh
# a) Cross-compile the Go binaries into jniLibs (needs the NDK)
export ANDROID_NDK_HOME=/path/to/android-ndk
bash scripts/build-android-binaries.sh
ls android/app/src/main/jniLibs/arm64-v8a/   # expect libevolia_net.so, libevolia_mesh_sync.so, libevolia_bridge.so

# b) Build + install the debug APK
#    Open android/ in Android Studio (it generates the Gradle wrapper) and Run,
#    or with a local Gradle / generated wrapper:
cd android && ./gradlew :app:installDebug
```

> The Go binaries are optional for the value/auth/anchor layers — if jniLibs is
> empty the app still runs (Phase 1 is simply skipped). They are required only
> for Phase 1 below.

## Inspecting shared state

All state lives in app-private storage (`EVOLIA_HOME` = `files/evolia`). On a
**debug** build, read it with `run-as`:

```sh
adb shell run-as com.evolia.app ls -la files/evolia
adb shell run-as com.evolia.app cat files/evolia/evolia_identity_state.json
```

In-app, the **Rafraîchir l'état** button shows `evolia_identity_state.json` plus
the wallet address — the quickest live readout.

## 2. Phase 3 first — the auth gate

Auth is the entry point, so validate it first.

**First launch (setup):**
1. Tap **Démarrer Evolia** → a PIN dialog appears.
2. Enter a PIN; confirm it rejects non-4–6-digit input.
3. Enter a password; confirm it rejects < 8 chars.
4. Choose biometric yes/no.
5. ✅ Pass: `adb shell run-as com.evolia.app cat files/evolia/.evolia_auth.json`
   shows `pin_hash` / `password_hash` as `$argon2id$v=19$...` strings,
   `owner: true`.

**Subsequent launches (verify):**
1. Tap **Démarrer** → PIN prompt. Enter a wrong PIN → "incorrect, N essais
   restants"; after 3 → "Authentification échouée" (service does **not** start).
2. Correct PIN → password prompt (same 3-attempt behaviour).
3. If biometric was enabled → `BiometricPrompt` appears; a registered fingerprint
   succeeds. With none enrolled → "Biométrie indisponible — étape ignorée".
4. ✅ Pass: on success `.evolia_session.json` exists with a `token` + `device_id`,
   and the foreground notification "evolIA" appears.

> Note: Argon2 verification runs on the UI thread — a ~100–300 ms pause when you
> tap OK is expected, not a hang.

## 3. Phase 2 — the value engine

1. With the service running, wait ~15 s (cycles are 5 s) and tap **Rafraîchir
   l'état** a few times. ✅ `cycle_count` and `total_v` increase.
2. Tap **Action: vidéo (+8 BTC-e)**, wait one cycle, refresh. ✅ `total_v` jumps
   more than a quiet cycle (video has the highest action rate).
3. Cross-check files:
   ```sh
   adb shell run-as com.evolia.app cat files/evolia/evolia_value_state.json
   adb shell run-as com.evolia.app cat files/evolia/evolia_action_queue.jsonl
   ```

## 4. Phase 2b — sensors, capture, anchoring

**Sensors (indirect):** sensor values fold into the formula rather than a file.
Compare the device **at rest, radios off** vs **moving with WiFi + Bluetooth on**:
over comparable windows the active `total_v` floor should be higher. Grant the
location + nearby-devices permissions when prompted.

**Photo/video capture:**
1. Leave evolIA running; open the **camera** app and take a photo.
2. Back in evolIA, refresh after a cycle. ✅ `total_v` jumps as a `photo_taken`
   is enqueued; confirm in `evolia_action_queue.jsonl`. (Requires the media read
   permission granted.)

**Anchoring — LOCAL (default, no config):**
```sh
adb shell run-as com.evolia.app cat files/evolia/evolia_blockchain_sync.log
```
✅ One JSON line every ~30 s with `"status":"local"`.

**Anchoring — on-chain (web3j):**
1. Read the wallet address from the in-app status ("Wallet à financer en gas").
2. On your RPC node, **fund that address** with enough gas (e.g. transfer ETH
   from a Ganache account).
3. Drop the chain config (read every sync — no restart needed):
   ```sh
   adb shell run-as com.evolia.app sh -c \
     'printf "{\"rpc_url\":\"http://10.0.2.2:8545\",\"chain_id\":1337}" > files/evolia/evolia_chain_config.json'
   ```
4. Within ~30 s:
   - ✅ `evolia_deployment.json` appears with a `contract_address` (first-launch
     deploy of `EvoliaCore`).
   - ✅ `evolia_blockchain_sync.log` shows a `"status":"success"` line with
     `tx_hash` + `block`.
   - ✅ On the node, the contract exists and the `anchorValue` tx is mined.
5. Negative check: point `rpc_url` at an unreachable host → entries fall back to
   `"status":"local"` (note `"node unreachable"`), never crashing.

## 5. Phase 1 — Go supervision

(Only if you built the Go binaries into jniLibs.)

1. With the service running, forward and probe the bridge:
   ```sh
   adb forward tcp:8080 tcp:8080
   curl -s http://127.0.0.1:8080/health
   ```
   ✅ The bridge responds (it serves `/health`, `/block`, `/sync`,
   `/mesh/total_v`). A response means the supervised binary is alive and was
   launched with `EVOLIA_HOME` + the session env.
2. ✅ `evolia_peers.json` is written by `evolia-net` (may list only this device).
3. ✅ Kill-resilience: the binaries are restarted on exit (3 s backoff); the
   foreground notification persists (the signal-9 fix).

## Sign-off checklist

- [ ] Setup creates `.evolia_auth.json` (argon2id hashes); wrong PIN/password
      blocks start after 3 tries.
- [ ] Auth success creates `.evolia_session.json` + the foreground notification.
- [ ] `total_v` / `cycle_count` advance each cycle; a video action bumps `V`.
- [ ] A real photo enqueues `photo_taken` and raises `V`.
- [ ] LOCAL anchoring logs `"local"` every 30 s with no config.
- [ ] With config + funded wallet: `EvoliaCore` deploys and `anchorValue`
      succeeds on-chain (`"success"` + tx hash/block).
- [ ] (If built) the Go bridge answers on `:8080` and survives restarts.

## Troubleshooting

- **No notification / service dies fast:** grant `POST_NOTIFICATIONS` (API 33+).
- **Binaries crash-loop:** confirm they are arm64 and present in jniLibs.
- **On-chain stuck on `local`:** check the RPC is reachable from the device,
  the wallet is funded, and `chain_id` matches the node. Cleartext `http://` is
  already allowed (`usesCleartextTraffic`).
- **Reset state:** `adb shell run-as com.evolia.app rm -rf files/evolia` (clears
  auth, wallet, value — next launch re-runs setup and regenerates the wallet).
