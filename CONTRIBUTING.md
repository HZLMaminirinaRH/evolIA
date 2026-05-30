# Contributing to evolIA

Thank you for your interest in evolIA! This document explains how to set up your development environment, write code, and submit pull requests with the security posture the project requires.

## Prerequisites

- Git (with GPG or Sigstore support)
- Rust (`rustup` + `cargo`)
- Go 1.20+
- Python 3.10+
- Android SDK + NDK (for the Kotlin app; optional for Python/Rust/Go work)
- 2FA enabled on your GitHub account

## Setup: Signing Your Commits

All commits to evolIA are expected to be signed. Choose one:

### Option A: GPG (Traditional)

1. **Generate a GPG key** (if you don't have one):
   ```bash
   gpg --full-generate-key
   # Choose:
   # - Key type: RSA and RSA (default)
   # - Key size: 4096
   # - Validity: 0 (no expiry) or 5 years
   # - Name: Your full name
   # - Email: your-github-email@example.com
   ```

2. **List your keys**:
   ```bash
   gpg --list-secret-keys --keyid-format=long
   # Output: sec   rsa4096/ABC123DEF456GHI 2026-01-15 [SC]
   # Copy ABC123DEF456GHI
   ```

3. **Upload to GitHub**:
   - Go to https://github.com/settings/keys
   - Click "New GPG key"
   - Paste the output of: `gpg --armor --export ABC123DEF456GHI`
   - Click "Add GPG key"

4. **Tell Git to sign**:
   ```bash
   git config --global user.signingkey ABC123DEF456GHI
   git config --global commit.gpgsign true
   ```

5. **Create commits**:
   ```bash
   git commit -m "your message"  # Automatically signed now
   # Or explicitly: git commit -S -m "your message"
   ```

### Option B: Sigstore (Keyless, Recommended for First-Time Contributors)

1. **Install gitsign**:
   ```bash
   # macOS
   brew install sigstore/sigstore/gitsign
   
   # Linux (download from https://github.com/sigstore/gitsign/releases)
   wget https://github.com/sigstore/gitsign/releases/download/v0.9.0/gitsign-linux-amd64
   chmod +x gitsign-linux-amd64 && sudo mv gitsign-linux-amd64 /usr/local/bin/gitsign
   ```

2. **Configure Git**:
   ```bash
   git config --global gpg.x509.program gitsign
   git config --global gpg.format x509
   git config --global commit.gpgsign true
   ```

3. **Create commits**:
   ```bash
   git commit -m "your message"
   # On first commit, your browser opens for GitHub OAuth. Sign in and authorize.
   # Sigstore issues a certificate tied to your GitHub identity; no key to manage.
   ```

## Development Workflow

### 1. Fork & Clone

```bash
# Fork on GitHub (click "Fork" on https://github.com/HZLMaminirinaRH/evolIA)
git clone https://github.com/YOUR-USERNAME/evolIA.git
cd evolIA
git remote add upstream https://github.com/HZLMaminirinaRH/evolIA.git
```

### 2. Create a Branch

Branch names should reflect the feature or fix:

```bash
git fetch upstream
git checkout -b feature/my-feature upstream/main
# or
git checkout -b fix/bug-description upstream/main
```

### 3. Make Changes

- **Read CLAUDE.md** first — it documents the architecture and development practices.
- **Sync constants** if you change formulas (ACTION_RATES, COEFF, ceiling_factor, etc.) — they must be identical across Python/Go/Kotlin/Solidity.
- **Run tests** before committing:
  ```bash
  # Rust
  cd rust && cargo test && cargo fmt --check && cargo clippy -- -D warnings
  
  # Go
  cd go && go test ./... && gofmt -l . && go vet ./...
  
  # Python
  cd python && python3 tests/test_value.py && python3 tests/test_services.py && python3 tests/test_actions.py
  
  # Android (Kotlin unit tests only; no emulator)
  cd android && ./gradlew :app:testDebugUnitTest
  ```

### 4. Commit with a Message

Good commit messages follow the format:

```
<type>: <short summary (50 chars max)>

<optional body explaining the why, not the what (72 char wrap)>

<optional footer with references: Closes #123, etc.>
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `refactor`: Code reorganization (no behavior change)
- `test`: Test additions / improvements
- `docs`: Documentation
- `chore`: Build, deps, tooling

Example:
```
fix: prevent null dereference in promtSecret on Android

The Android auth-on-launch flow was crashing when verifyPin or
verifyPassword threw an uncaught exception (e.g., corrupted Argon2
hash). Wrap both in try-catch and treat failure as "incorrect
credential" rather than a crash.

Fixes #220
```

### 5. Rebase & Push

```bash
# Sync with upstream (rebase, not merge, to keep history linear)
git fetch upstream
git rebase upstream/main

# Resolve any conflicts, test again, then push to your fork
git push origin feature/my-feature
```

### 6. Open a Pull Request

- Go to https://github.com/HZLMaminirinaRH/evolIA/pulls
- Click "New pull request"
- Set base to `main`, compare to your branch
- **Title**: Short summary (same as commit message subject)
- **Description**: 
  - What the change does (the problem and the solution)
  - How to test it
  - Any breaking changes? Dependency updates?
  - Link to any related issues (`Fixes #123`)

### 7. Address Review Comments

- Maintainers will review for:
  - **Correctness**: Does it work? Are there edge cases?
  - **Symbiosis**: Are constants synced across languages?
  - **Style**: Does it follow the codebase conventions?
  - **Security**: No new vulnerabilities (supply-chain or runtime)?
  - **Tests**: Are new features tested? Do tests still pass?

- **Do NOT force-push after review starts** (it makes review harder). Instead, push new commits; the maintainer will squash before merge if needed.

### 8. Merge

Once approved and CI passes, a maintainer will merge your PR with a squash commit (to keep main clean). Your contribution is live!

---

## Code Style & Conventions

### Rust
- `cargo fmt` (automatic; run before commit)
- `cargo clippy -- -D warnings` (no warnings)
- Comments: only when the *why* is non-obvious (not repeating the code)

### Go
- `gofmt` (automatic; run before commit)
- `go vet` (no issues)
- Exported functions documented with `// Func ...` comments

### Python
- `py_compile` (syntax check; included in CI)
- PEP 8 style (no `black` formatter yet, but code is readable)
- Standard library only for core modules; optional (`web3`, `bitcoinlib`) guarded

### Kotlin (Android)
- Android Studio default formatter
- No lint warnings
- Nullability: use `?` / `!!` sparingly; prefer default values

### Solidity
- `solc 0.8.21 --optimize --optimize-runs 200` (pinned; used to build artifacts)
- Contracts must be pre-compiled and committed as `contracts/EvoliaCore.json` (abi+bytecode)

---

## Security Review

If your change touches:
- **Auth, crypto, or session management** (Rust `evolia-security`, `evolia-auth`)
- **Proof-of-work validation** (Go `pow`, Solidity `EvoliaCore.anchorProof`)
- **Defense mechanisms** (Rust/Go/Kotlin adaptive defense, injection detection)
- **Chat or mesh propagation** (Go `chat`, `mesh`, Android `ChatIntake`)
- **Dependencies** (adding new crates, packages, libraries)

… then the PR will undergo a **security review** in addition to code review. This is not a blocker; it just means:
- Maintainer will ask clarifying questions about threat model
- You may be asked to add tests for edge cases or error paths
- Review may take a few days

---

## Testing

### Before You Push

```bash
# Run ALL test suites locally
./scripts/test-all.sh  # (if it exists)

# Or manually:
cd rust && cargo test && cd ../go && go test ./... && cd ../python && python3 tests/*.py && cd ../android && ./gradlew :app:testDebugUnitTest
```

### CI

GitHub Actions automatically run the same tests on your PR. If any fail, fix the issue and push a new commit (don't force-push). CI will re-run.

### For Android (Emulator Testing)

Unit tests run in CI with `--offline` to avoid SDK downloads. If you need to test the actual app on an emulator or device, you must do so **locally** (CI sandbox has no Android SDK). See `android/README.md` for setup.

---

## Commit Message Examples

Good:
```
fix: wrap verifyPin/verifyPassword in try-catch to prevent crashes

The auth-on-launch flow crashes when Argon2 verification fails due to
a corrupted hash file. Wrap both verify calls in try-catch so invalid
credentials are treated as "incorrect, retry" rather than a crash.

Fixes #220
```

Bad:
```
update auth stuff
bug fixes
changed some things
```

---

## Conventions: Constants Across Languages

If you add a new constant (e.g., a PoW exponent, a sensor scale, a rate cap):

1. **Define it in one place** (usually the Python module, since it's the reference)
2. **Replicate in Go** with the same name and value, plus a `// mirrors python/module.py`
3. **Replicate in Kotlin** ditto
4. **Replicate in Solidity** (scaled to integers if needed, e.g., ×100 for decimals)
5. **Add a test** that verifies the replicas match (use `assert` or similar)
6. **Comment in CLAUDE.md** if the constant is user-facing (so Termin operator knows about it)

Example:
```python
# python/evolia_evolve.py
_NEW_CONST = 42.5  # Adjust per empirical engagement data
```

```go
// go/pow/pow.go
const NewConst = 42.5  // mirrors python/evolia_evolve.py
```

```kotlin
// android/Evolve.kt
private const val NEW_CONST = 42.5  // mirrors python/evolia_evolve.py
```

```solidity
// contracts/EvoliaCore.sol
uint256 internal constant NEW_CONST_CENTI = 4250;  // 42.5 * 100, mirrors python
```

---

## Questions?

- **Architecture**: Read `CLAUDE.md`
- **Protocol security**: Read `SECURITY.md`
- **API usage / features**: Check the README and code comments
- **Anything else**: Open a discussion at https://github.com/HZLMaminirinaRH/evolIA/discussions

Welcome to the project! 🙏
