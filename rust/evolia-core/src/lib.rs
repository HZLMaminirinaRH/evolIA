//! Shared paths and small helpers for the Evolia security spine.
//!
//! Layout on disk (all under `EVOLIA_HOME`, default `$HOME/evolia`):
//!   .evolia_auth.json      owner credentials (Argon2 hashes), perms 0600
//!   .evolia_session.json   current session metadata (no raw secrets), 0600
//!   .evolia_pids.json      pids of services launched by evolia-start, 0600
//!   services.toml          optional override of the launched services
//!   logs/<name>.log        per-service stdout/stderr

use std::path::{Path, PathBuf};

/// Resolve the Evolia home directory.
///
/// `EVOLIA_HOME` wins; otherwise `$HOME/evolia` (matching the Termux layout
/// the Python services already assume).
pub fn evolia_home() -> PathBuf {
    if let Ok(h) = std::env::var("EVOLIA_HOME") {
        return PathBuf::from(h);
    }
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join("evolia")
}

pub fn auth_file() -> PathBuf {
    evolia_home().join(".evolia_auth.json")
}

pub fn session_file() -> PathBuf {
    evolia_home().join(".evolia_session.json")
}

pub fn pids_file() -> PathBuf {
    evolia_home().join(".evolia_pids.json")
}

pub fn services_file() -> PathBuf {
    evolia_home().join("services.toml")
}

/// Create the Evolia home directory if needed and return it.
pub fn ensure_home() -> std::io::Result<PathBuf> {
    let h = evolia_home();
    std::fs::create_dir_all(&h)?;
    Ok(h)
}

/// Restrict a file to owner read/write only (0600). No-op on non-unix.
#[cfg(unix)]
pub fn set_owner_only(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut perms = std::fs::metadata(path)?.permissions();
    perms.set_mode(0o600);
    std::fs::set_permissions(path, perms)
}

#[cfg(not(unix))]
pub fn set_owner_only(_path: &Path) -> std::io::Result<()> {
    Ok(())
}
