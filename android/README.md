# evolIA — Android app (Plan B)

A native Android app whose **foreground service** supervises the prebuilt evolIA
binaries. A foreground service with an ongoing notification is the only thing
Android guarantees not to kill — this is the real fix for the Termux `signal 9`.

> Status: **scaffold**. It has not been compiled in this repo's CI (no Android
> SDK there). Open it in Android Studio (or build with the Gradle wrapper once
> generated) on a machine with the Android SDK + NDK.

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

- **Phase 2b — finish the value layer.** *Done:* runtime permissions
  (location / BLUETOOTH_SCAN / POST_NOTIFICATIONS) requested before start;
  `AndroidSensors` feeds the formula real WiFi scan counts, a continuous BLE
  device count and the last-known location fix (all permission-guarded, degrade
  to 0/false); `EvoliaAnchor` ports `ganache_db.py` LOCAL mode, appending each
  sync to `evolia_blockchain_sync.log` (status `local`) every 30s in the service
  loop. *Remaining:* real on-chain anchoring with **web3j** (status `success`
  via `EvoliaCore.anchorValue`); real action capture (camera/SMS observers
  enqueueing to `ActionQueue`).
- **Phase 3 — auth/security.** Replace the Rust TTY auth with a Kotlin screen
  (PIN + `BiometricPrompt`); reuse `evolia-security`'s crypto via JNI, or
  reimplement ChaCha20-Poly1305 / HMAC with Android's crypto APIs.

The Kotlin core mirrors `evolia_evolve.py` line-for-line; reference outputs
(at-rest `V=0`, full-activity `V≈0.6109`, BLE > WiFi) match the Python core.

The shared `EVOLIA_HOME` file protocol stays the contract between every piece,
exactly as on Termux — so phases can land incrementally.
