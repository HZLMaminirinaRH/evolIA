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
```

`evolia-start` authenticates the owner, derives the security key **in-process** from the
just-verified password (the "liaison directe" between auth and security), mints a session
token, and launches the configured services — passing `EVOLIA_SESSION_TOKEN` and
`EVOLIA_DEVICE_ID` to each child via the environment. The launched service list defaults to
the Python mesh (`network.py`, `mesh_sync.py`, `main.py`, `ganache_db.py continuous 30`,
`dashboard_v35.py`); each is skipped if its script is absent, and the list can be overridden
by `$EVOLIA_HOME/services.toml` (`[[service]]` tables: `name`, `command`, `args`,
`requires_file`).

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

## When more code lands, update this file

Keep entries grounded in what is actually in the repo; verify by reading files rather than
assuming. Add commands/architecture notes here the moment Go (`go.mod`) or Python
(`requirements.txt`/`pyproject.toml`) services land, so future sessions stay productive.
