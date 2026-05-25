# evolIA — Android app (Plan B)

A native Android app whose **foreground service** supervises the prebuilt evolIA
binaries. A foreground service with an ongoing notification is the only thing
Android guarantees not to kill — this is the real fix for the Termux `signal 9`.

> Status: CI compiles the app and runs the value-core unit tests on every push
> (the `Android (Plan B app)` job: `:app:assembleDebug` + `:app:testDebugUnitTest`).
> On-device behaviour (sensors, capture, on-chain anchoring) still needs a real
> device/emulator — open it in Android Studio on a machine with the SDK + NDK.

## What it does today

**Phase 1 — supervise the Go binaries.** `EvoliaService` runs `evolia-net`,
`mesh-sync` and `evolia-bridge` from the app's `nativeLibraryDir`, with
`EVOLIA_HOME` set to the app's private files dir, restarting any that exit.
Android only lets an app exec binaries shipped inside the APK (as `lib*.so`),
and Go binaries are self-contained, so they drop straight in.

**Phase 2 — native value engine (no Python).** The cognitive core is ported to
Kotlin under `core/`: `Evolve` (the evolutive formula — exponential blend,
video > photo, BLE > WiFi), `EvoliaValue` (the accumulator + persistence to the
same `evolia_value_state.json` / `evolia_identity_state.json`), `ActionQueue`
(the append-only `evolia_action_queue.jsonl` protocol) and `EvoliaPaths`.
`sensors/AndroidSensors` feeds it via `SensorManager` + `WifiManager`. The
service runs a 5s cycle loop in-process — so the value model survives without
Termux or any interpreter (the real signal-9 fix for this layer). The pure core
is unit-tested in `src/test/` (`EvolveTest`, runs on the JVM).

`MainActivity` starts/stops the service, records a demo action, and shows the
shared state.

## Build

1. Cross-compile the Go binaries into `app/src/main/jniLibs/arm64-v8a/` (needs the NDK):
   ```sh
   export ANDROID_NDK_HOME=/path/to/android-ndk
   bash ../scripts/build-android-binaries.sh
   ```
2. Open `android/` in Android Studio (Giraffe+), let it generate the Gradle
   wrapper, then Run on an arm64 device — or from the CLI once the wrapper exists:
   ```sh
   ./gradlew :app:assembleDebug
   ```
3. Install the APK, grant the notification permission, tap **Démarrer Evolia**.

## Roadmap

- **Phase 2b — finish the value layer. _Done._** Runtime permissions
  (location / BLUETOOTH_SCAN / POST_NOTIFICATIONS / media) requested before
  start; `AndroidSensors` feeds the formula real WiFi scan counts, a continuous
  BLE device count and the last-known location fix (all permission-guarded,
  degrade to 0/false); `MediaActionCapture` observes MediaStore and enqueues
  `photo_taken` / `video_taken` as new photos/videos appear (the MediaWatcher
  analog; SMS is deferred since READ_SMS is a Play-restricted permission);
  on-chain anchoring is a web3j port of `ganache_db.py` — `EvoliaWallet`
  generates the signing key on first run and stores it encrypted via the Android
  Keystore (`KeystoreCrypto`, surfacing the address for gas funding), and
  `ChainAnchor` deploys `EvoliaCore` from the bundled bytecode
  (`assets/EvoliaCore.json`) on first launch, then calls `anchorValue` each sync
  (value x100, status `success` with tx hash/block). It reads
  `evolia_chain_config.json` (`rpc_url`, `chain_id`) and caches the address in
  `evolia_deployment.json` — the same file the Python side uses. With no RPC
  configured or no node reachable it degrades to a logged LOCAL entry, exactly
  like Python.
- **Phase 3 — auth/security.** Replace the Rust TTY auth with a Kotlin screen
  (PIN + `BiometricPrompt`); reuse `evolia-security`'s crypto via JNI, or
  reimplement ChaCha20-Poly1305 / HMAC with Android's crypto APIs.

The Kotlin core mirrors `evolia_evolve.py` line-for-line; reference outputs
(at-rest `V=0`, full-activity `V≈0.6109`, BLE > WiFi) match the Python core.

The shared `EVOLIA_HOME` file protocol stays the contract between every piece,
exactly as on Termux — so phases can land incrementally.
