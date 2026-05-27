// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

/// @title EvoliaCore
/// @notice Anchors the cognitive value (V, denominated in BTC-e) on-chain.
///         Beyond the legacy self-declared `anchorValue` snapshot, `anchorProof`
///         makes the chain itself the *verifier*: it recomputes each value
///         increment from the declared cognitive work
///         (ΔV = base(actions)·(1+v) + floor·v), enforces the physical per-action
///         rate caps (you cannot claim more activity than elapsed time allows)
///         and v∈[0,1], then accumulates only the proven gain. A forged proof
///         reverts, so `provenValue` is always the on-chain-verified sum — never
///         a number a peer simply declared. Mirrors the off-chain Go `pow`
///         validator (go/pow) and python/evolia_evolve.py; keep the constants in
///         sync across the three languages.
contract EvoliaCore {
    struct Evolution {
        uint256 value;
        uint256 timestamp;
        string sensoryType;
    }

    /// One work-backed value increment, recorded (and verified) by anchorProof.
    struct ProvenWork {
        uint256 gain; // centi-BTC-e this increment added, recomputed on-chain
        uint256 timestamp;
        uint32 screenInput;
        uint32 smsSent;
        uint32 photoTaken;
        uint32 videoTaken;
        uint16 vMilli; // sensor/cognitive multiplier ×1000, in [0,1000]
        uint32 dtSecs; // elapsed seconds backing this increment
    }

    // Value is denominated in centi-BTC-e (×100) so integer arithmetic mirrors
    // the off-chain float formula without losing the rate decimals.
    uint256 internal constant FLOOR_CENTI = 100; // SensorFloor 1.0
    uint256 internal constant V_SCALE = 1000; // v carried as vMilli = v×1000

    // ActionRates ×100 (centi-BTC-e per action): mirrors evolia_evolve.ACTION_RATES
    // and go/pow.ActionRates.
    uint256 internal constant RATE_SCREEN = 5; // 0.05
    uint256 internal constant RATE_SMS = 120; // 1.20
    uint256 internal constant RATE_PHOTO = 250; // 2.50
    uint256 internal constant RATE_VIDEO = 800; // 8.00

    // MaxRatePerSec: mirrors go/pow.MaxRatePerSec — the anti-inflation core.
    uint256 internal constant CAP_SCREEN = 20;
    uint256 internal constant CAP_SMS = 5;
    uint256 internal constant CAP_PHOTO = 5;
    uint256 internal constant CAP_VIDEO = 2;

    uint256 internal constant MAX_ELAPSED_SECS = 3600; // mirrors pow.maxElapsed

    string public name = "Evolia Cognitive Value";
    string public symbol = "BTC-e";
    address public primaryNode;

    // Legacy self-declared snapshot total (anchorValue).
    uint256 public totalCognitiveValue;
    Evolution[] public history;

    // Proven, work-verified total (anchorProof) — the trustworthy value: every
    // centi here was recomputed from declared work that passed the rate caps.
    uint256 public provenValue;
    ProvenWork[] public provenHistory;

    // Per-account proven balance (centi-BTC-e). anchorProof credits the caller;
    // transfer moves value between accounts. The sum of provenOf across every
    // account always equals provenValue: anchorProof grows both by the same gain,
    // and transfer only moves value (never creates it), so the total is conserved.
    mapping(address => uint256) public provenOf;

    event NewEvolutionAnchored(uint256 blockId, uint256 value, string sensoryType);
    event ProvenWorkAnchored(uint256 blockId, uint256 gain, uint256 provenValue);
    event Transferred(address indexed from, address indexed to, uint256 amount);

    constructor() {
        primaryNode = msg.sender;
    }

    /// @notice Legacy: anchor a self-declared value snapshot (already ×100 by the
    ///         caller). Kept for the proofless bootstrap path; prefer anchorProof.
    function anchorValue(uint256 _value, string calldata _type) external {
        history.push(Evolution({value: _value, timestamp: block.timestamp, sensoryType: _type}));
        totalCognitiveValue += _value;
        emit NewEvolutionAnchored(history.length - 1, _value, _type);
    }

    /// @notice Recompute one value increment from its cognitive work, in
    ///         centi-BTC-e: ΔV = base·(1+v) + floor·v, base = Σ rate·count. Pure
    ///         and public: the single on-chain source of truth for what an
    ///         increment is worth, so no caller can declare it.
    function computeGain(
        uint256 screenInput,
        uint256 smsSent,
        uint256 photoTaken,
        uint256 videoTaken,
        uint256 vMilli
    ) public pure returns (uint256) {
        uint256 baseCenti =
            screenInput * RATE_SCREEN + smsSent * RATE_SMS + photoTaken * RATE_PHOTO + videoTaken * RATE_VIDEO;
        // baseCenti·(1+v) + FLOOR·v, with v = vMilli / V_SCALE.
        return baseCenti + ((baseCenti + FLOOR_CENTI) * vMilli) / V_SCALE;
    }

    /// @notice Anchor a value increment *proven* by its declared cognitive work.
    ///         The contract recomputes the gain and enforces v∈[0,1] plus the
    ///         physical per-action rate caps, then accumulates only the proven
    ///         gain. A forged proof reverts, so provenValue is always the
    ///         on-chain-verified sum. Returns the new provenValue.
    function anchorProof(
        uint256 screenInput,
        uint256 smsSent,
        uint256 photoTaken,
        uint256 videoTaken,
        uint256 vMilli,
        uint256 dtSecs
    ) external returns (uint256) {
        require(vMilli <= V_SCALE, "v out of [0,1]");
        require(dtSecs > 0 && dtSecs <= MAX_ELAPSED_SECS, "dt out of range");
        require(screenInput <= CAP_SCREEN * dtSecs, "screen rate cap");
        require(smsSent <= CAP_SMS * dtSecs, "sms rate cap");
        require(photoTaken <= CAP_PHOTO * dtSecs, "photo rate cap");
        require(videoTaken <= CAP_VIDEO * dtSecs, "video rate cap");

        uint256 gain = computeGain(screenInput, smsSent, photoTaken, videoTaken, vMilli);
        require(gain > 0, "no work");

        provenValue += gain;
        provenOf[msg.sender] += gain;
        provenHistory.push(
            ProvenWork({
                gain: gain,
                timestamp: block.timestamp,
                screenInput: uint32(screenInput),
                smsSent: uint32(smsSent),
                photoTaken: uint32(photoTaken),
                videoTaken: uint32(videoTaken),
                vMilli: uint16(vMilli),
                dtSecs: uint32(dtSecs)
            })
        );
        emit ProvenWorkAnchored(provenHistory.length - 1, gain, provenValue);
        return provenValue;
    }

    /// @notice Move proven BTC-e (centi) from the caller to `to`. The chain
    ///         orders transactions and checks the caller's balance, so a transfer
    ///         can never spend more than was proven — the structural
    ///         anti-double-spend the offline mesh alone cannot provide. The total
    ///         is conserved: value moves between accounts, provenValue (their sum)
    ///         is unchanged. Reverts on a zero/self recipient, zero amount, or an
    ///         overdraw. Returns the caller's new balance.
    function transfer(address to, uint256 amount) external returns (uint256) {
        require(to != address(0), "zero address");
        require(to != msg.sender, "self transfer");
        require(amount > 0, "zero amount");
        require(provenOf[msg.sender] >= amount, "insufficient balance");
        provenOf[msg.sender] -= amount;
        provenOf[to] += amount;
        emit Transferred(msg.sender, to, amount);
        return provenOf[msg.sender];
    }

    function blockCount() external view returns (uint256) {
        return history.length;
    }

    function provenBlockCount() external view returns (uint256) {
        return provenHistory.length;
    }
}
