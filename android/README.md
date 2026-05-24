# evolIA — Android app (Plan B)

A native Android app whose **foreground service** supervises the prebuilt evolIA
binaries. A foreground service with an ongoing notification is the only thing
Android guarantees not to kill — this is the real fix for the Termux `signal 9`.

> Status: **scaffold**. It has not been compiled in this repo's CI (no Android
> SDK there). Open it in Android Studio (or build with the Gradle wrapper once
> generated) on a machine with the Android SDK + NDK.

## What it does today (Phase 1)

`EvoliaService` runs the three Go binaries — `evolia-net`, `mesh-sync`,
`evolia-bridge` — from the app's `nativeLibraryDir`, with `EVOLIA_HOME` set to
the app's private files dir, restarting any that exit. No Termux, no Python for
this layer. `MainActivity` starts/stops the service and shows the shared state.

Why only Go here: Android only lets an app exec binaries shipped inside the APK
(as `lib*.so`). Go binaries are self-contained, so they drop straight in. Python
needs an interpreter and Rust's auth needs a UI (not a TTY), so those come next.

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

- **Phase 2 — value + web3 (replaces the Python services).** Port the pure-logic
  core (`evolia_evolve`, `evolia_value`) to Kotlin (~200 lines), read sensors via
  Android `SensorManager`, capture actions via app hooks, and anchor on-chain
  with **web3j**. This removes the Python dependency entirely.
- **Phase 3 — auth/security.** Replace the Rust TTY auth with a Kotlin screen
  (PIN + `BiometricPrompt`); reuse `evolia-security`'s crypto via JNI, or
  reimplement ChaCha20-Poly1305 / HMAC with Android's crypto APIs.

The shared `EVOLIA_HOME` file protocol stays the contract between every piece,
exactly as on Termux — so phases can land incrementally.
