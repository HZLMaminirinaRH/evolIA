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

## 7. Recording screen-input actions

Screen taps cannot be captured passively on a non-rooted device, so feed them
explicitly (e.g. from a Termux:Tasker shortcut or widget):

```sh
python3 ~/evolia/evolia_actions.py record screen_input 1
```
