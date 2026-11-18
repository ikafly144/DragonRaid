package net.sabafly.dragonraid.reward;

import java.util.Objects;
import java.util.UUID;

/**
 * Breakdown of reward tokens awarded to a participant after a raid encounter.
 */
public record RewardCalculationResult(
        UUID playerId,
        int baseTokens,
        int crystalBonus,
        int reviveBonus,
        int damageBonus,
        int firstClearBonus,
        int totalTokens
) {

    public RewardCalculationResult {
        Objects.requireNonNull(playerId, "playerId");
    }
}
