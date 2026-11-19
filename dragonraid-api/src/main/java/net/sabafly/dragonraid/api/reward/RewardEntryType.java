package net.sabafly.dragonraid.api.reward;

/**
 * Entry types supported inside a Loot-Table-style {@link RewardPool}.
 */
public enum RewardEntryType {
    EMPTY,
    CURRENCY,
    VANILLA_ITEM,
    CUSTOM_ITEM,
    UNLOCK_CONTENT,
    SCRIPT_HOOK,
    TABLE_REF
}
