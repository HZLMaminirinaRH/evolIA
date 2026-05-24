// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

/// @title EvoliaCore
/// @notice Anchors the cognitive value (V, denominated in BTC-e) on-chain.
///         The Python `ganache_db` service calls `anchorValue` each sync,
///         recording an immutable history of the device's evolution.
contract EvoliaCore {
    struct Evolution {
        uint256 value;
        uint256 timestamp;
        string sensoryType;
    }

    string public name = "Evolia Cognitive Value";
    string public symbol = "BTC-e";
    address public primaryNode;
    uint256 public totalCognitiveValue;
    Evolution[] public history;

    event NewEvolutionAnchored(uint256 blockId, uint256 value, string sensoryType);

    constructor() {
        primaryNode = msg.sender;
    }

    /// @notice Anchor a value (already scaled to an integer by the caller).
    function anchorValue(uint256 _value, string calldata _type) external {
        history.push(Evolution({value: _value, timestamp: block.timestamp, sensoryType: _type}));
        totalCognitiveValue += _value;
        emit NewEvolutionAnchored(history.length - 1, _value, _type);
    }

    function blockCount() external view returns (uint256) {
        return history.length;
    }
}
