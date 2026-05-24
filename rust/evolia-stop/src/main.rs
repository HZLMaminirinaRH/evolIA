//! evolia-stop: owner-only shutdown.
//!
//! Re-runs the three-layer authentication (the same gate as evolia-start),
//! then sends SIGTERM to every recorded service, escalating to SIGKILL for
//! any that survive a short grace period. Finally clears the session and pid
//! state so a clean evolia-start can follow.

use anyhow::Result;
use nix::sys::signal::{kill, Signal};
use nix::unistd::Pid;
use serde::Deserialize;
use std::time::Duration;

const GRACE_SECS: u64 = 2;

#[derive(Deserialize)]
struct PidEntry {
    name: String,
    pid: u32,
}

fn alive(pid: u32) -> bool {
    kill(Pid::from_raw(pid as i32), None).is_ok()
}

fn main() -> Result<()> {
    let bar = "=".repeat(72);
    println!("{bar}");
    println!("🛑 EVOLIA STOP");
    println!("{bar}");

    // Owner gate — only the owner may stop the mesh.
    match evolia_auth::authenticate()? {
        Some(o) => println!("\n✅ Owner authentifié ({}).", o.user_id),
        None => {
            eprintln!("\n❌ Authentification échouée — arrêt refusé.");
            std::process::exit(1);
        }
    }

    let pids_path = evolia_core::pids_file();
    if !pids_path.exists() {
        println!("\nAucun service enregistré ({}).", pids_path.display());
        return Ok(());
    }
    let entries: Vec<PidEntry> = serde_json::from_str(&std::fs::read_to_string(&pids_path)?)?;

    println!("\n→ Envoi SIGTERM…");
    for e in &entries {
        if alive(e.pid) {
            let _ = kill(Pid::from_raw(e.pid as i32), Signal::SIGTERM);
            println!("  SIGTERM {} (pid {})", e.name, e.pid);
        } else {
            println!("  · {} (pid {}) déjà arrêté.", e.name, e.pid);
        }
    }

    std::thread::sleep(Duration::from_secs(GRACE_SECS));

    for e in &entries {
        if alive(e.pid) {
            let _ = kill(Pid::from_raw(e.pid as i32), Signal::SIGKILL);
            println!("  SIGKILL {} (pid {})", e.name, e.pid);
        }
    }

    std::fs::remove_file(&pids_path).ok();
    std::fs::remove_file(evolia_core::session_file()).ok();

    // Release the Termux CPU wake lock taken by evolia-start (no-op off-device).
    let _ = std::process::Command::new("termux-wake-unlock").status();

    println!("\n{bar}");
    println!("✅ Services arrêtés, session effacée.");
    println!("{bar}");
    Ok(())
}
