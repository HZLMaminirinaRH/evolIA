//! Three-layer owner authentication for Evolia: PIN, password, biometric.
//!
//! Credentials are hashed with Argon2 (PHC strings, per-secret random salt)
//! and stored in `.evolia_auth.json` with 0600 permissions. Only the owner
//! who passes all enabled layers can start or stop the service mesh.

use anyhow::{anyhow, Result};
use argon2::password_hash::rand_core::OsRng;
use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::Argon2;
use serde::{Deserialize, Serialize};
use std::io::Write;

/// Attempts allowed per layer before authentication fails.
const MAX_ATTEMPTS: u32 = 3;

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct AuthConfig {
    pub owner: bool,
    pub pin_hash: String,
    pub password_hash: String,
    pub biometric_enabled: bool,
    pub created: String,
    pub last_auth: Option<String>,
}

/// Result of a successful authentication. Carries the verified master
/// password so the caller can derive the security key in-process (the
/// "liaison directe" between auth and security).
pub struct AuthOutcome {
    pub user_id: String,
    pub master_password: String,
}

fn now_iso() -> String {
    chrono::Utc::now().to_rfc3339()
}

fn hash_secret(secret: &str) -> Result<String> {
    let salt = SaltString::generate(&mut OsRng);
    let phc = Argon2::default()
        .hash_password(secret.as_bytes(), &salt)
        .map_err(|e| anyhow!("argon2 hash: {e}"))?
        .to_string();
    Ok(phc)
}

fn verify_secret(secret: &str, phc: &str) -> bool {
    match PasswordHash::new(phc) {
        Ok(parsed) => Argon2::default()
            .verify_password(secret.as_bytes(), &parsed)
            .is_ok(),
        Err(_) => false,
    }
}

pub fn load_config() -> Result<Option<AuthConfig>> {
    let path = evolia_core::auth_file();
    if !path.exists() {
        return Ok(None);
    }
    let data = std::fs::read_to_string(&path)?;
    Ok(Some(serde_json::from_str(&data)?))
}

fn save_config(cfg: &AuthConfig) -> Result<()> {
    evolia_core::ensure_home()?;
    let path = evolia_core::auth_file();
    std::fs::write(&path, serde_json::to_string_pretty(cfg)?)?;
    evolia_core::set_owner_only(&path)?;
    Ok(())
}

fn prompt_line(prompt: &str) -> Result<String> {
    print!("{prompt}");
    std::io::stdout().flush().ok();
    let mut s = String::new();
    std::io::stdin().read_line(&mut s)?;
    Ok(s.trim().to_string())
}

/// Interactive first-time setup. The caller becomes the OWNER.
pub fn setup_initial_credentials() -> Result<AuthConfig> {
    let bar = "=".repeat(72);
    println!("\n{bar}");
    println!("EVOLIA AUTH — SETUP INITIAL (vous êtes l'OWNER)");
    println!("{bar}\n");

    let pin = loop {
        let p = prompt_line("Entrez un PIN (4-6 chiffres): ")?;
        if (4..=6).contains(&p.len()) && p.chars().all(|c| c.is_ascii_digit()) {
            break p;
        }
        println!("❌ PIN invalide (4-6 chiffres).");
    };

    let password = loop {
        let p = rpassword::prompt_password("Entrez un mot de passe (min 8 caractères): ")?;
        if p.chars().count() >= 8 {
            break p;
        }
        println!("❌ Mot de passe trop court (min 8).");
    };

    let biometric_enabled =
        prompt_line("Configurer la biométrie (empreinte)? (y/n): ")?.eq_ignore_ascii_case("y");

    let cfg = AuthConfig {
        owner: true,
        pin_hash: hash_secret(&pin)?,
        password_hash: hash_secret(&password)?,
        biometric_enabled,
        created: now_iso(),
        last_auth: None,
    };
    save_config(&cfg)?;
    println!(
        "\n✅ Authentification configurée ({}).",
        evolia_core::auth_file().display()
    );
    Ok(cfg)
}

/// Run setup if no config exists yet.
pub fn ensure_setup() -> Result<()> {
    if load_config()?.is_none() {
        setup_initial_credentials()?;
    }
    Ok(())
}

fn auth_pin(cfg: &AuthConfig) -> Result<bool> {
    println!("\n[1/3] Vérification PIN");
    for attempt in 0..MAX_ATTEMPTS {
        let pin = prompt_line(&format!(
            "PIN ({} essai(s) restant(s)): ",
            MAX_ATTEMPTS - attempt
        ))?;
        if verify_secret(&pin, &cfg.pin_hash) {
            println!("✅ PIN correct.");
            return Ok(true);
        }
        println!("❌ PIN incorrect.");
    }
    Ok(false)
}

fn auth_password(cfg: &AuthConfig) -> Result<Option<String>> {
    println!("\n[2/3] Vérification mot de passe");
    for attempt in 0..MAX_ATTEMPTS {
        let pw = rpassword::prompt_password(format!(
            "Mot de passe ({} essai(s) restant(s)): ",
            MAX_ATTEMPTS - attempt
        ))?;
        if verify_secret(&pw, &cfg.password_hash) {
            println!("✅ Mot de passe correct.");
            return Ok(Some(pw));
        }
        println!("❌ Mot de passe incorrect.");
    }
    Ok(None)
}

fn auth_biometric(cfg: &AuthConfig) -> Result<bool> {
    if !cfg.biometric_enabled {
        println!("\n[3/3] Biométrie — non configurée (ignorée).");
        return Ok(true);
    }
    println!("\n[3/3] Biométrie — placez le doigt sur le capteur…");

    let mut sensor_available = false;
    for bin in ["termux-fingerprint", "fprintd-verify"] {
        // Err means the binary is absent — just try the next one.
        if let Ok(out) = std::process::Command::new(bin).output() {
            sensor_available = true;
            if out.status.success() {
                println!("✅ Empreinte reconnue ({bin}).");
                return Ok(true);
            }
        }
    }

    if sensor_available {
        println!("❌ Empreinte non reconnue.");
        return Ok(false);
    }

    println!("⚠️  Aucun capteur disponible — confirmation manuelle.");
    Ok(prompt_line("Confirmer l'identité manuellement? (y/n): ")?.eq_ignore_ascii_case("y"))
}

/// Run the full three-layer authentication.
///
/// Returns `Ok(Some(outcome))` only if every enabled layer passes.
/// Returns `Ok(None)` if a layer is failed. Errors are reserved for I/O
/// or a missing configuration.
pub fn authenticate() -> Result<Option<AuthOutcome>> {
    let cfg = load_config()?.ok_or_else(|| anyhow!("Authentification non configurée"))?;

    let bar = "=".repeat(72);
    println!("\n{bar}");
    println!("EVOLIA AUTH — 3 NIVEAUX");
    println!("{bar}");

    if !auth_pin(&cfg)? {
        return Ok(None);
    }
    let password = match auth_password(&cfg)? {
        Some(p) => p,
        None => return Ok(None),
    };
    if !auth_biometric(&cfg)? {
        return Ok(None);
    }

    let mut updated = cfg.clone();
    updated.last_auth = Some(now_iso());
    let _ = save_config(&updated);

    Ok(Some(AuthOutcome {
        user_id: "owner".to_string(),
        master_password: password,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_roundtrip() {
        let phc = hash_secret("hunter2!!").unwrap();
        assert!(verify_secret("hunter2!!", &phc));
        assert!(!verify_secret("wrong", &phc));
    }

    #[test]
    fn distinct_salts() {
        // Same secret must produce different PHC strings (random salt).
        assert_ne!(hash_secret("same").unwrap(), hash_secret("same").unwrap());
    }
}
