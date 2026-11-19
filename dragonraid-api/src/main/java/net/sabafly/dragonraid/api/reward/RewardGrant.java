package net.sabafly.dragonraid.api.reward;

import org.jetbrains.annotations.Nullable;

/**
 * Pure immutable reward grant output produced by evaluating a {@link RewardTable}.
 */
public sealed interface RewardGrant
        permits CurrencyGrant, VanillaItemGrant, CustomItemGrant, ContentUnlockGrant, ScriptHookGrant {

    @Nullable String claimKey();
}
