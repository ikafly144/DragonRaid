package net.sabafly.dragonraid.api.reward;

/**
 * Entry selection strategy inside a {@link RewardPool}.
 */
public enum RewardPoolMode {
    /**
     * Picks one eligible entry per roll weighted by {@code max(0, weight + quality * luck)}.
     */
    WEIGHTED,
    /**
     * Evaluates and emits every eligible entry on each roll.
     */
    ALL
}
