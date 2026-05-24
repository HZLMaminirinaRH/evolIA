//! evolia-start: the owner-only entry point.
//!
//! Flow: refuse to run if services are already up → ensure auth is set up →
//! run three-layer authentication → derive the security key in-process →
//! mint a session token → launch the configured services, passing the token
//! and device id to each child via the environment → record their pids.

use anyhow::Result;
use nix::sys::signal::kill;
use nix::unistd::Pid;
use serde::{Deserialize, Serialize};
use std::path::Path;
use std::process::{Command, Stdio};

const SESSION_SECS: i64 = 3600;

#[derive(Deserialize, Clone)]
struct ServiceSpec {
    name: String,
    command: String,
    #[serde(default)]
    args: Vec<String>,
    /// If set, the service is skipped unless this file exists under EVOLIA_HOME.
    #[serde(default)]
    requires_file: Option<String>,
}

#[derive(Deserialize, Default)]
struct ServicesFile {
    #[serde(default)]
    service: Vec<ServiceSpec>,
}

#[derive(Serialize, Deserialize)]
struct PidEntry {
    name: String,
    pid: u32,
}

#[derive(Serialize)]
struct SessionFile<'a> {
    user_id: &'a str,
    token_id: &'a str,
    expires_at: &'a str,
    device_id: &'a str,
    started_at: String,
}

fn device_id() -> String {
    if let Ok(v) = std::env::var("EVOLIA_DEVICE_ID") {
        return v;
    }
    if let Ok(h) = std::fs::read_to_string("/proc/sys/kernel/hostname") {
        let h = h.trim();
        if !h.is_empty() {
            return h.to_string();
        }
    }
    "super-pair-001".to_string()
}

fn alive(pid: u32) -> bool {
    kill(Pid::from_raw(pid as i32), None).is_ok()
}

/// The default service mesh. Each one is skipped automatically if its script
/// is not present yet, so this works on a greenfield checkout and grows as
/// services land. The Go mesh-sync binary is opt-in via services.toml (its
/// path is environment-specific once built).
fn default_services() -> Vec<ServiceSpec> {
    let py = |name: &str, file: &str, args: &[&str]| {
        let mut a = vec![file.to_string()];
        a.extend(args.iter().map(|s| s.to_string()));
        ServiceSpec {
            name: name.to_string(),
            command: "python3".to_string(),
            args: a,
            requires_file: Some(file.to_string()),
        }
    };
    vec![
        py("actions", "evolia_actions.py", &[]),
        py("evolia_run", "evolia_run.py", &[]),
        py("ganache_db", "ganache_db.py", &["continuous", "30"]),
        py("dashboard", "dashboard.py", &[]),
    ]
}

fn load_services() -> Vec<ServiceSpec> {
    let path = evolia_core::services_file();
    if path.exists() {
        if let Ok(txt) = std::fs::read_to_string(&path) {
            match toml::from_str::<ServicesFile>(&txt) {
                Ok(f) if !f.service.is_empty() => return f.service,
                Ok(_) => {}
                Err(e) => eprintln!("⚠️  services.toml ignoré (parse: {e})"),
            }
        }
    }
    default_services()
}

fn launch(spec: &ServiceSpec, home: &Path, dev: &str, token: &str) -> Option<u32> {
    if let Some(f) = &spec.requires_file {
        if !home.join(f).exists() {
            println!("· skip {} (fichier absent: {})", spec.name, f);
            return None;
        }
    }

    let logs = home.join("logs");
    std::fs::create_dir_all(&logs).ok();
    let log_path = logs.join(format!("{}.log", spec.name));
    let out = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .ok()?;
    let err = out.try_clone().ok()?;

    match Command::new(&spec.command)
        .args(&spec.args)
        .current_dir(home)
        .env("EVOLIA_SESSION_TOKEN", token)
        .env("EVOLIA_DEVICE_ID", dev)
        .stdin(Stdio::null())
        .stdout(Stdio::from(out))
        .stderr(Stdio::from(err))
        .spawn()
    {
        Ok(child) => {
            println!("✅ {} (pid {}) → logs/{}.log", spec.name, child.id(), spec.name);
            Some(child.id())
        }
        Err(e) => {
            println!("⚠️  {} non lancé: {}", spec.name, e);
            None
        }
    }
}

fn main() -> Result<()> {
    let home = evolia_core::ensure_home()?;
    let bar = "=".repeat(72);
    println!("{bar}");
    println!("🚀 EVOLIA START");
    println!("{bar}");

    // Refuse to start on top of a live mesh.
    let pids_path = evolia_core::pids_file();
    if pids_path.exists() {
        if let Ok(txt) = std::fs::read_to_string(&pids_path) {
            if let Ok(entries) = serde_json::from_str::<Vec<PidEntry>>(&txt) {
                if entries.iter().any(|e| alive(e.pid)) {
                    eprintln!("⚠️  Des services semblent déjà actifs. Lancez 'evolia-stop' d'abord.");
                    std::process::exit(1);
                }
            }
        }
    }

    // Auth gate.
    evolia_auth::ensure_setup()?;
    let outcome = match evolia_auth::authenticate()? {
        Some(o) => o,
        None => {
            eprintln!("\n❌ Authentification échouée — accès refusé (owner uniquement).");
            std::process::exit(1);
        }
    };
    println!("\n✅ Authentification réussie ({}).", outcome.user_id);

    // Security key, derived in-process from the just-verified password.
    let dev = device_id();
    let security = evolia_security::Security::new(&dev, &outcome.master_password)?;
    let token = security.generate_session_token(&outcome.user_id, SESSION_SECS)?;
    let self_check = security.validate_session_token(&token.token)?.is_some();
    println!("🔐 Sécurité initialisée (device={dev}, token valide={self_check}).");

    // Record session metadata (no raw token on disk; children get it via env).
    let session = SessionFile {
        user_id: &outcome.user_id,
        token_id: &token.token_id,
        expires_at: &token.expires_at,
        device_id: &dev,
        started_at: chrono::Utc::now().to_rfc3339(),
    };
    let session_path = evolia_core::session_file();
    std::fs::write(&session_path, serde_json::to_string_pretty(&session)?)?;
    evolia_core::set_owner_only(&session_path).ok();

    // Launch services.
    println!("\n🚀 Lancement des services…");
    let mut pids = Vec::new();
    for spec in load_services() {
        if let Some(pid) = launch(&spec, &home, &dev, &token.token) {
            pids.push(PidEntry {
                name: spec.name.clone(),
                pid,
            });
        }
    }

    std::fs::write(&pids_path, serde_json::to_string_pretty(&pids)?)?;
    evolia_core::set_owner_only(&pids_path).ok();

    println!("\n{bar}");
    println!("✅ {} service(s) lancé(s). Arrêt: 'evolia-stop'.", pids.len());
    println!("{bar}");
    Ok(())
}
