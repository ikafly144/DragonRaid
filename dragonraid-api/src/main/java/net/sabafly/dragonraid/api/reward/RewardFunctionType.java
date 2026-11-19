package net.sabafly.dragonraid.api.reward;

/**
 * Quantity modifier functions applied to rolled reward entries or pools.
 */
public enum RewardFunctionType {
    SET_AMOUNT,
    ADD_AMOUNT,
    MULTIPLY_AMOUNT,
    ADD_METRIC_SCALED,
    CLAMP_AMOUNT
}
