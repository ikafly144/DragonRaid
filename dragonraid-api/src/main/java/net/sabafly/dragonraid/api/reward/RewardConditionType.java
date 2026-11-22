package net.sabafly.dragonraid.api.reward;

/**
 * Pure predicate types evaluated against {@link RewardContext} and optional RNG.
 */
public enum RewardConditionType {
    FIRST_CLEAR,
    SERVER_FIRST,
    NO_DEATH,
    DIFFICULTY,
    PARTY_SIZE,
    MAX_DURATION_MS,
    MIN_BOSS_DAMAGE,
    MIN_CRYSTALS_DESTROYED,
    MIN_REVIVES_COMPLETED,
    RANDOM_CHANCE,
    HAS_ACHIEVEMENT,
    HAS_UNLOCK,
    NOT_CLAIMED,
    ATTRIBUTE_EQUALS,
    CUSTOM_SCRIPT
}
