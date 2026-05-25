//! Evolutive defense — the adaptive, reactive layer of the security spine.
//!
//! Premise: the more evolIA absorbs hostile input (injection attempts, forged
//! signatures, malformed/unauthorized payloads), the *harder* it defends. A
//! bounded in-memory buffer — the "mémoire tampon en son sein" — keeps the
//! recent attack pressure; the defense level is the accumulated severity it
//! holds, and decays as quiet time passes.
//!
//! This is strictly **reactive**: detect, reject, harden, record. It never
//! retaliates and never reaches outward — it only makes evolIA stricter with
//! whatever is presented to it next.
//!
//! `a_global` is the evolutive intensity model coupling the three flows of the
//! core: `A_global = A_evo + P_free − D_evo` (attack evolution + passive
//! propagation − evolutive defense). Defense subtracts, so as it grows the net
//! intensity falls — evolIA "wins" the more it is attacked.

use std::collections::VecDeque;

// Dynamic intensity functions, with their cognitive interpretation:
//
//   A_evo   — composante évolutive: adaptation dynamique du Super-pair selon les
//             conditions internes et externes (Φ, A, Λ, ∇C).
//   P_free  — composante passive: diffusion naturelle par l'environnement
//             (Ω = facteur de diffusion libre, ∇Ψ = gradient de propagation).
//   D_evo   — défense évolutive en contre-poids: auto-régulation et résistance
//             adaptative (Θ = résilience adaptative, ∇Ξ = gradient de défense
//             contextuelle / zones de protection renforcée).
pub type FuncPhi = fn(f64, &[f64], f64, f64) -> f64;
pub type FuncA = fn(f64, &[f64], f64, f64) -> f64;
pub type FuncLambda = fn(f64, &[f64], f64, f64) -> f64;
pub type FuncGradC = fn(&[f64], f64, f64) -> Vec<f64>;
/// Ω(τ) — facteur de diffusion libre (influence du milieu physique/numérique).
pub type FuncOmega = fn(f64, &[f64], f64, f64) -> f64;
/// ∇Ψ(x) — gradient de propagation (zones favorables à la transmission passive).
pub type FuncGradPsi = fn(&[f64], f64, f64) -> Vec<f64>;
/// Θ(τ) — facteur de résilience adaptative (capacité de réponse cognitive).
pub type FuncTheta = fn(f64, &[f64], f64, f64) -> f64;
/// ∇Ξ(x) — gradient de défense contextuelle (zones de protection renforcée).
pub type FuncGradXi = fn(&[f64], f64, f64) -> Vec<f64>;

/// Combined intensity: `A_global = A_evo + P_free − D_evo`, integrated over
/// `[0, t_max)` with step `dt`.
///
/// This is the **formal spec** of the coupling. The live, operational form runs
/// in the Go `defense` package (`NetIntensity` + the admission `Gate`), where
/// the three flows are bound to real service signals (attack rate, peer block
/// rate, the absorbed-defense buffer) and the `D_evo` counterweight actuates a
/// per-source intake throttle that hardens under attack and relaxes on decay.
#[allow(clippy::too_many_arguments)]
pub fn a_global(
    t_max: f64,
    phi_func: FuncPhi,
    a_func: FuncA,
    lambda_func: FuncLambda,
    grad_c_func: FuncGradC,
    omega_func: FuncOmega,
    grad_psi_func: FuncGradPsi,
    theta_func: FuncTheta,
    grad_xi_func: FuncGradXi,
    x_vec: &[f64],
    w: f64,
    sigma: f64,
    dt: f64,
) -> f64 {
    let mut a_evo_val = 0.0;
    let mut p_free_val = 0.0;
    let mut d_evo_val = 0.0;
    let mut t = 0.0;

    while t < t_max {
        // --- Attaque évolutive ---
        let phi_tau = phi_func(t, x_vec, w, sigma);
        let a_tau = a_func(t, x_vec, w, sigma);
        let lambda_tau = lambda_func(t, x_vec, w, sigma);
        let grad_c = grad_c_func(x_vec, w, sigma);
        let grad_sum: f64 = grad_c.iter().sum();
        let exp_growth = (a_tau * phi_tau).exp();
        a_evo_val += (phi_tau * exp_growth + lambda_tau * grad_sum) * dt;

        // --- Propagation passive ---
        let omega_tau = omega_func(t, x_vec, w, sigma);
        let grad_psi = grad_psi_func(x_vec, w, sigma);
        let grad_psi_sum: f64 = grad_psi.iter().sum();
        p_free_val += omega_tau * grad_psi_sum * dt;

        // --- Défense évolutive ---
        let theta_tau = theta_func(t, x_vec, w, sigma);
        let grad_xi = grad_xi_func(x_vec, w, sigma);
        let grad_xi_sum: f64 = grad_xi.iter().sum();
        d_evo_val += theta_tau * grad_xi_sum * dt;

        t += dt;
    }

    a_evo_val + p_free_val - d_evo_val
}

/// What kind of hostile input evolIA absorbed. Severity feeds the defense level.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AttackKind {
    SqlInjection,
    BadSignature,
    Unauthorized,
    Malformed,
}

impl AttackKind {
    /// Per-kind base severity contributed to the defense pressure.
    pub fn base_severity(self) -> f64 {
        match self {
            AttackKind::SqlInjection => 1.0,
            AttackKind::BadSignature => 0.8,
            AttackKind::Unauthorized => 0.6,
            AttackKind::Malformed => 0.3,
        }
    }
}

/// One absorbed attack held in the buffer.
#[derive(Clone, Copy, Debug)]
pub struct AttackEvent {
    pub kind: AttackKind,
    pub severity: f64,
}

/// The "mémoire tampon en son sein": a bounded ring of recent attacks. The
/// defense level is the total severity it holds — every absorbed attack raises
/// it, and `decay` forgets the oldest one as quiet time passes. Reactive only.
pub struct AdaptiveDefense {
    capacity: usize,
    buffer: VecDeque<AttackEvent>,
}

impl AdaptiveDefense {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            buffer: VecDeque::new(),
        }
    }

    /// Record an absorbed attack; the oldest is evicted when full. Returns the
    /// new defense level.
    pub fn record(&mut self, kind: AttackKind) -> f64 {
        if self.buffer.len() == self.capacity {
            self.buffer.pop_front();
        }
        self.buffer.push_back(AttackEvent {
            kind,
            severity: kind.base_severity(),
        });
        self.level()
    }

    /// Current defense level: accumulated severity of buffered attacks. The
    /// more attacks evolIA has absorbed, the higher (and the stricter callers
    /// should be).
    pub fn level(&self) -> f64 {
        self.buffer.iter().map(|e| e.severity).sum()
    }

    /// Cool down one notch: forget the oldest absorbed attack. Call on a quiet
    /// tick so the defense relaxes when the pressure stops.
    pub fn decay(&mut self) {
        self.buffer.pop_front();
    }

    pub fn len(&self) -> usize {
        self.buffer.len()
    }

    pub fn is_empty(&self) -> bool {
        self.buffer.is_empty()
    }
}

impl Default for AdaptiveDefense {
    fn default() -> Self {
        Self::new(64)
    }
}

/// Heuristic detector for SQL-injection-like payloads on control inputs (device
/// ids, peer fields, …). Defensive only: a positive result means the caller
/// should reject the input and `record` an attack so the defense level rises.
pub fn looks_like_injection(input: &str) -> bool {
    if input.contains('\0') {
        return true;
    }
    let lower = input.to_ascii_lowercase();
    const NEEDLES: [&str; 11] = [
        "' or ",
        "\" or ",
        " or 1=1",
        "'='",
        "--",
        "/*",
        "*/",
        "; drop ",
        " union select",
        "xp_cmdshell",
        "';",
    ];
    NEEDLES.iter().any(|n| lower.contains(n))
}

#[cfg(test)]
mod tests {
    use super::*;

    // The demo functions from the original specification.
    fn demo_phi(t: f64, _x: &[f64], _w: f64, _s: f64) -> f64 {
        1.0 + 0.2 * t.sin()
    }
    fn demo_a(t: f64, _x: &[f64], _w: f64, _s: f64) -> f64 {
        0.5 * t.cos()
    }
    fn demo_lambda(t: f64, _x: &[f64], _w: f64, _s: f64) -> f64 {
        0.05 * (1.0 + t.sin())
    }
    fn demo_grad_c(x: &[f64], _w: f64, _s: f64) -> Vec<f64> {
        x.iter().map(|v| v * 0.1).collect()
    }
    fn demo_omega(t: f64, _x: &[f64], _w: f64, _s: f64) -> f64 {
        0.02 * (1.0 + t.cos())
    }
    fn demo_grad_psi(x: &[f64], _w: f64, _s: f64) -> Vec<f64> {
        x.iter().map(|v| v * 0.05).collect()
    }
    fn demo_grad_xi(x: &[f64], _w: f64, _s: f64) -> Vec<f64> {
        x.iter().map(|v| v * 0.08).collect()
    }
    fn theta_weak(t: f64, _x: &[f64], _w: f64, _s: f64) -> f64 {
        0.03 * (1.0 + t.cos())
    }
    fn theta_strong(t: f64, _x: &[f64], _w: f64, _s: f64) -> f64 {
        0.9 * (1.0 + t.cos())
    }

    fn run(theta: FuncTheta) -> f64 {
        a_global(
            10.0,
            demo_phi,
            demo_a,
            demo_lambda,
            demo_grad_c,
            demo_omega,
            demo_grad_psi,
            theta,
            demo_grad_xi,
            &[1.0, 0.5, -0.2],
            0.8,
            0.3,
            0.01,
        )
    }

    #[test]
    fn a_global_is_finite() {
        assert!(run(theta_weak).is_finite());
    }

    #[test]
    fn stronger_defense_lowers_global_intensity() {
        // D_evo subtracts, so more defense => lower net intensity.
        assert!(run(theta_strong) < run(theta_weak));
    }

    #[test]
    fn defense_grows_with_attacks_and_is_bounded() {
        let mut d = AdaptiveDefense::new(3);
        assert!(d.is_empty());
        let l1 = d.record(AttackKind::Malformed);
        let l2 = d.record(AttackKind::SqlInjection);
        assert!(l2 > l1, "absorbing more attacks must raise the defense");
        // Capacity bound: the buffer never exceeds 3 even after more attacks.
        d.record(AttackKind::BadSignature);
        d.record(AttackKind::Unauthorized);
        assert_eq!(d.len(), 3);
    }

    #[test]
    fn defense_relaxes_on_decay() {
        let mut d = AdaptiveDefense::new(8);
        d.record(AttackKind::SqlInjection);
        d.record(AttackKind::SqlInjection);
        let before = d.level();
        d.decay();
        assert!(d.level() < before, "a quiet tick must relax the defense");
    }

    #[test]
    fn injection_detector_flags_classics_and_spares_normal() {
        assert!(looks_like_injection("' OR '1'='1"));
        assert!(looks_like_injection("'; DROP TABLE peers;--"));
        assert!(looks_like_injection("a UNION SELECT password FROM users"));
        assert!(!looks_like_injection("phone-galaxy-a52"));
        assert!(!looks_like_injection("owner"));
    }
}
