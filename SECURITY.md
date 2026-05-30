# Security Policy for evolIA

## Overview

evolIA is a decentralized digital value system with embedded adaptive defense against hostile input (injection attempts, forged value proofs, DDoS). This document describes both the **runtime security** (built into the protocol) and the **supply-chain security** (protecting the source code and releases).

---

## Runtime Security (In-Repo Code)

The **adaptive defense formula** (`a_global = A_evo + P_free − D_evo`) and the **proof-of-work validator** are the primary defenses against network-level attacks:

- **Injection detection**: SQL-injection-like payloads flagged on control inputs (device IDs, routing fields) across Rust/Go/Kotlin.
- **Value proof recomputation**: On-chain (`EvoliaCore.anchorProof`) recomputes `ΔV = base(actions)·(1+v) + floor·v` and enforces physical per-action rate caps.
- **Clock-skew bounding**: Value claims capped by `ValueCeiling = MaxGainPerSec · elapsed` (no device can fabricate history beyond the fleet genesis).
- **Per-source throttling**: `defense.Gate` token bucket shrinks burst and refill rate under attack pressure, guaranteeing a floor so honest peers still transit.
- **Mesh integrity**: HMAC-SHA256 signatures (`EVOLIA_MESH_KEY`) on propagated blocks; bad signatures feed the adaptive defense.

For protocol security details, see `CLAUDE.md` (sections "Formule de sécurité évolutive" and "Proof-of-work validation").

---

## Supply-Chain Security (Protecting the Source)

An attacker who steals a GitHub PAT (like in the "Megalodon" campaign) can forge commits and push malicious code. These controls prevent that:

### 1. **Signed Commits Required**

All commits to `main` and release branches must be signed with a GPG key or Sigstore key:

```bash
# Sign a commit locally (GPG)
git config user.signingkey <YOUR_GPG_KEY_ID>
git commit -S -m "your message"

# Or use Sigstore (no local key needed)
# Requires gitsign CLI: https://docs.sigstore.dev/signing/gitsign/
gitsign commit -m "your message"
```

Branch protection rules enforce this: unsigned commits are rejected.

### 2. **Branch Protection on Main + Release Branches**

- **Require signed commits**: Yes
- **Require PR reviews before merge**: 1 approval minimum (maintainers review)
- **Require status checks to pass**: CI (Rust, Go, Python, Web3, Android) must all be green
- **Require branches to be up to date**: Force rebase before merge
- **Dismiss stale reviews**: PR reviews older than CI re-run are dismissed
- **Restrict who can push**: Only admins (avoid stolen tokens with broad permissions)

### 3. **GitHub Actions Hardening**

All Actions workflows must:

- **Pin third-party Actions to SHA** (not `@v1` or `@main`):
  ```yaml
  # Good
  - uses: actions/checkout@a5ac7e51b41094c153460946c763747867050670  # v4.1.0
  
  # Bad (don't do this)
  - uses: actions/checkout@v4
  ```
  
- **Use least-privilege permissions**:
  ```yaml
  permissions:
    contents: read         # only what's needed
    checks: write          # not all actions need this
    pull-requests: write   # narrow scope
  ```

- **Audit before adopting**: Any new third-party Action must be reviewed for:
  - Source code transparency (open source, not minified)
  - Maintenance status (recent commits, active maintainer)
  - Known vulnerabilities (check GitHub Security Advisories)

- **Use `GITHUB_TOKEN` scoped to the workflow**: Never pass broad PATs to Actions.

### 4. **PAT (Personal Access Token) Policy**

- **Fine-grained tokens only**: Use GitHub's "Fine-grained personal access tokens" (not "Classic PATs")
- **Minimal scope per token**:
  - `contents`: `read` (clone/read only) or `read_and_write` (push/release only)
  - `pull-requests`: `read` (reviewers) or `write` (auto-merge)
  - Never grant `admin`, `delete_repo`, or `all-repos` access
- **Rotation**: Revoke and regenerate tokens every 90 days
- **MFA required**: Enforce 2FA on all GitHub accounts with push access
- **Audit log**: Check "Settings → Security log" monthly for suspicious token use

### 5. **Release Signing & Verification**

Releases are published with cryptographic signatures so users can verify integrity:

```bash
# Sign a release tarball locally
gpg --armor --detach-sign evolIA-v1.0.0.tar.gz
# Produces: evolIA-v1.0.0.tar.gz.asc

# Verify on the user's end
gpg --verify evolIA-v1.0.0.tar.gz.asc evolIA-v1.0.0.tar.gz
```

Alternatively, use Sigstore for keyless signing (no private key to manage):

```bash
# Publish with Sigstore (CI integration)
cosign sign-blob --yes evolIA-v1.0.0.tar.gz
# User verifies with the signing certificate (tied to your GitHub ID)
cosign verify-blob --certificate-identity-regexp ... evolIA-v1.0.0.tar.gz
```

Release notes must include the signature hash (SHA256) and verification instructions.

### 6. **Dependency Scanning**

- **Dependabot** (GitHub built-in): Monitor `go.mod`, `requirements.txt`, `Cargo.lock` for vulnerable dependencies. Auto-create PRs to patch.
- **Secret scanning**: GitHub automatically scans commits for leaked API keys, tokens, credentials. All collaborators must have this enabled.
- **Reproducible builds** (future): Pin Docker images by SHA if using containers for CI/CD.

---

## Reporting Security Issues

**Do NOT open a public GitHub issue for security vulnerabilities.** Instead:

1. Email the maintainer (rahajarisonahmaminirina@gmail.com) with:
   - Description of the vulnerability
   - Impact (runtime / supply-chain / data loss / etc.)
   - Reproduction steps (if applicable)
   - Suggested fix (if you have one)

2. The maintainer will:
   - Acknowledge receipt within 48 hours
   - Work privately to patch the issue
   - Request CVE ID if needed (critical/remote-code-execution severity)
   - Publish a security advisory + release patch
   - Credit the reporter (with permission)

---

## Runtime Defenses (Technical Summary)

See `go/defense/defense.go`, `rust/evolia-security/src/evolutive.rs`, and `android/app/src/main/java/com/evolia/app/security/Defense.kt` for implementation:

- **Adaptive defense buffer**: Bounded ring (capacity 64) of recent attack severities; decays on quiet ticks.
- **Injection detector**: 11 SQL-injection motifs + null-byte detection, identical across Rust/Go/Kotlin.
- **Proof-of-work validator**: Recomputes value increment on-chain and off-mesh with physical rate caps.
- **Ceiling factor coupling**: `D_evo` tightens value claims as the defense level rises (`1.0 → 0.25` under saturation).
- **Per-source throttling**: Token bucket for mesh-sync UDP and bridge HTTP; burst/rate shrink under pressure.

---

## Compliance & Audit

- **CI/CD audit**: All Action runs are logged in GitHub (Actions tab). Review regularly for unexpected runs or failed auth.
- **Commit audit**: `git log --verify-signature` shows which commits are signed; audit monthly.
- **Release audit**: Verify release signatures before deploying to production.
- **Dependency audit**: `go mod tidy && go mod why <dep>`, `pip-audit`, `cargo audit` before releases.

---

## Change Log

- **2026-05-30**: Initial security policy. Branch protection, signed commits, fine-grained PATs, Action pinning formalized.

---

For questions or policy updates, contact the maintainer via the security contact above.
