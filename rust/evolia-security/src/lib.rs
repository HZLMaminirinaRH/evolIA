//! Cryptographic core for Evolia.
//!
//! - Master key derived from (device_id, master_password) with Argon2id.
//! - Authenticated encryption with ChaCha20-Poly1305 (random 96-bit nonce
//!   prepended to the ciphertext, the whole blob base64-encoded).
//! - Session tokens: encrypted JSON with an expiry, bound to the device.
//! - Detached signatures with HMAC-SHA256, verified in constant time.

use anyhow::{anyhow, Result};
use argon2::Argon2;
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use chacha20poly1305::aead::{Aead, OsRng};
use chacha20poly1305::{AeadCore, ChaCha20Poly1305, KeyInit, Nonce};
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

type HmacSha256 = Hmac<Sha256>;

const NONCE_LEN: usize = 12;

pub struct Security {
    device_id: String,
    master_key: [u8; 32],
}

#[derive(Serialize, Deserialize)]
struct TokenData {
    token_id: String,
    user_id: String,
    created_at: String,
    expires_at: String,
    device_id: String,
}

pub struct SessionToken {
    pub token: String,
    pub token_id: String,
    pub expires_at: String,
}

fn now() -> chrono::DateTime<chrono::Utc> {
    chrono::Utc::now()
}

/// Derive a 32-byte key from the master password, salted by the device id.
fn derive_key(device_id: &str, password: &str) -> Result<[u8; 32]> {
    let salt = Sha256::digest(device_id.as_bytes());
    let mut key = [0u8; 32];
    Argon2::default()
        .hash_password_into(password.as_bytes(), &salt[..16], &mut key)
        .map_err(|e| anyhow!("argon2 kdf: {e}"))?;
    Ok(key)
}

impl Security {
    pub fn new(device_id: &str, master_password: &str) -> Result<Self> {
        Ok(Self {
            device_id: device_id.to_string(),
            master_key: derive_key(device_id, master_password)?,
        })
    }

    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    pub fn encrypt(&self, plaintext: &str) -> Result<String> {
        let cipher = ChaCha20Poly1305::new_from_slice(&self.master_key)
            .map_err(|e| anyhow!("cipher init: {e}"))?;
        let nonce = ChaCha20Poly1305::generate_nonce(&mut OsRng);
        let ciphertext = cipher
            .encrypt(&nonce, plaintext.as_bytes())
            .map_err(|e| anyhow!("encrypt: {e}"))?;
        let mut blob = nonce.to_vec();
        blob.extend_from_slice(&ciphertext);
        Ok(STANDARD.encode(blob))
    }

    pub fn decrypt(&self, token: &str) -> Result<String> {
        let blob = STANDARD.decode(token)?;
        if blob.len() <= NONCE_LEN {
            return Err(anyhow!("ciphertext too short"));
        }
        let (nonce_bytes, ciphertext) = blob.split_at(NONCE_LEN);
        let cipher = ChaCha20Poly1305::new_from_slice(&self.master_key)
            .map_err(|e| anyhow!("cipher init: {e}"))?;
        let plaintext = cipher
            .decrypt(Nonce::from_slice(nonce_bytes), ciphertext)
            .map_err(|e| anyhow!("decrypt: {e}"))?;
        Ok(String::from_utf8(plaintext)?)
    }

    pub fn generate_session_token(&self, user_id: &str, duration_secs: i64) -> Result<SessionToken> {
        let created = now();
        let expires = created + chrono::Duration::seconds(duration_secs);

        let mut hasher = Sha256::new();
        hasher.update(user_id.as_bytes());
        hasher.update(created.to_rfc3339().as_bytes());
        hasher.update(self.device_id.as_bytes());
        let token_id = hex::encode(hasher.finalize());

        let data = TokenData {
            token_id: token_id.clone(),
            user_id: user_id.to_string(),
            created_at: created.to_rfc3339(),
            expires_at: expires.to_rfc3339(),
            device_id: self.device_id.clone(),
        };
        let token = self.encrypt(&serde_json::to_string(&data)?)?;
        Ok(SessionToken {
            token,
            token_id,
            expires_at: expires.to_rfc3339(),
        })
    }

    /// Validate a session token. Returns the user id if the token decrypts,
    /// matches this device, and has not expired.
    pub fn validate_session_token(&self, token: &str) -> Result<Option<String>> {
        let json = match self.decrypt(token) {
            Ok(j) => j,
            Err(_) => return Ok(None),
        };
        let data: TokenData = serde_json::from_str(&json)?;
        if data.device_id != self.device_id {
            return Ok(None);
        }
        let expires = chrono::DateTime::parse_from_rfc3339(&data.expires_at)?
            .with_timezone(&chrono::Utc);
        if now() > expires {
            return Ok(None);
        }
        Ok(Some(data.user_id))
    }

    pub fn sign(&self, data: &str) -> Result<String> {
        let mut mac = <HmacSha256 as Mac>::new_from_slice(&self.master_key)
            .map_err(|e| anyhow!("hmac init: {e}"))?;
        mac.update(data.as_bytes());
        Ok(hex::encode(mac.finalize().into_bytes()))
    }

    pub fn verify(&self, data: &str, signature_hex: &str) -> bool {
        let expected = match hex::decode(signature_hex) {
            Ok(b) => b,
            Err(_) => return false,
        };
        let mut mac = match <HmacSha256 as Mac>::new_from_slice(&self.master_key) {
            Ok(m) => m,
            Err(_) => return false,
        };
        mac.update(data.as_bytes());
        mac.verify_slice(&expected).is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encrypt_decrypt_roundtrip() {
        let s = Security::new("dev-1", "password123").unwrap();
        let ct = s.encrypt("hello world").unwrap();
        assert_eq!(s.decrypt(&ct).unwrap(), "hello world");
    }

    #[test]
    fn wrong_key_cannot_decrypt() {
        let a = Security::new("dev-1", "pw-a").unwrap();
        let b = Security::new("dev-1", "pw-b").unwrap();
        let ct = a.encrypt("secret").unwrap();
        assert!(b.decrypt(&ct).is_err());
    }

    #[test]
    fn signatures() {
        let s = Security::new("dev-1", "password123").unwrap();
        let sig = s.sign("payload").unwrap();
        assert!(s.verify("payload", &sig));
        assert!(!s.verify("tampered", &sig));
        assert!(!s.verify("payload", "not-hex"));
    }

    #[test]
    fn session_token_valid_then_device_bound() {
        let s = Security::new("dev-1", "password123").unwrap();
        let tok = s.generate_session_token("owner", 3600).unwrap();
        assert_eq!(
            s.validate_session_token(&tok.token).unwrap().as_deref(),
            Some("owner")
        );

        // A different device key must reject the token.
        let other = Security::new("dev-2", "password123").unwrap();
        assert!(other.validate_session_token(&tok.token).unwrap().is_none());
    }

    #[test]
    fn expired_token_rejected() {
        let s = Security::new("dev-1", "password123").unwrap();
        let tok = s.generate_session_token("owner", -1).unwrap();
        assert!(s.validate_session_token(&tok.token).unwrap().is_none());
    }
}
