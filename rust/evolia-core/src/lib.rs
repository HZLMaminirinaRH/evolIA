//! Shared paths and small helpers for the Evolia security spine.
//!
//! Layout on disk (all under `EVOLIA_HOME`, default `$HOME/evolia`):
//!   .evolia_auth.json      owner credentials (Argon2 hashes), perms 0600
//!   .evolia_session.json   current session metadata (no raw secrets), 0600
//!   .evolia_pids.json      pids of services launched by evolia-start, 0600
//!   services.toml          optional override of the launched services
//!   logs/<name>.log        per-service stdout/stderr

use std::io::Write;
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

/// Write bytes durably: a temp file in the same directory, flushed and synced,
/// then atomically renamed into place and restricted to owner-only (0600). A
/// crash or kill (signal 9) mid-write can never corrupt the destination — the
/// precious files here are the owner credentials and the session token.
pub fn write_atomic(path: &Path, contents: &[u8]) -> std::io::Result<()> {
    let dir = path.parent().unwrap_or_else(|| Path::new("."));
    std::fs::create_dir_all(dir)?;
    let file_name = path.file_name().and_then(|n| n.to_str()).unwrap_or("state");
    let tmp = dir.join(format!(".tmp-{}-{}", std::process::id(), file_name));
    {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(contents)?;
        f.sync_all()?;
    }
    set_owner_only(&tmp).ok();
    if let Err(e) = std::fs::rename(&tmp, path) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_atomic_persists_and_leaves_no_temp() {
        let dir = std::env::temp_dir().join(format!("evolia-core-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join(".evolia_auth.json");

        write_atomic(&path, b"first").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "first");

        // Overwriting is atomic: the destination holds the new content exactly,
        // and no stray temp file is left behind in the directory.
        write_atomic(&path, b"second").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "second");

        let leftovers: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_name().to_string_lossy().starts_with(".tmp-"))
            .collect();
        assert!(leftovers.is_empty(), "temp files left: {leftovers:?}");

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600, "atomic write must be owner-only");
        }

        let _ = std::fs::remove_dir_all(&dir);
    }
}
