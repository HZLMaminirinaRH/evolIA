#!/usr/bin/env bash
# ============================================================================
# Cross-compile the Go networking services for Android (arm64) and drop them
# into the app's jniLibs as lib*.so, where the foreground service can exec them.
# Requires the Android NDK (set ANDROID_NDK_HOME).
# ============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your Android NDK path}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

echo "==> Cross-compiling Go services for android/arm64 (PIE via NDK clang)"
cd "$REPO/go"
export GOOS=android GOARCH=arm64 CGO_ENABLED=1
export CC="$TOOLCHAIN/aarch64-linux-android26-clang"

go build -o "$OUT/libevolia_net.so" ./cmd/evolia-net
go build -o "$OUT/libevolia_mesh_sync.so" ./cmd/mesh-sync
go build -o "$OUT/libevolia_bridge.so" ./cmd/evolia-bridge

echo "==> Done:"
ls -1 "$OUT"

# --- Rust security spine (optional, Phase 3) -------------------------------
# rustup target add aarch64-linux-android
# Add to ~/.cargo/config.toml:
#   [target.aarch64-linux-android]
#   linker = "<NDK>/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
# Then:
#   ( cd "$REPO/rust" && cargo build --release --target aarch64-linux-android )
#   cp "$REPO"/rust/target/aarch64-linux-android/release/evolia-start "$OUT/libevolia_start.so"
