package net.sabafly.dragonraid.api.reward;

/**
 * Numeric metrics read from {@link RewardContext} for {@link RewardFunctionType#ADD_METRIC_SCALED}.
 */
public enum RewardMetricType {
    BOSS_DAMAGE,
    CRYSTALS_DESTROYED,
    REVIVES_COMPLETED,
    PARTY_SIZE,
    DURATION_SECONDS,
    LUCK,
    ATTRIBUTE
}
