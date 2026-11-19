package net.sabafly.dragonraid.api.reward;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable custom item grant output from {@link RewardTable} evaluation.
 */
public record CustomItemGrant(
        String customItemId,
        int amount,
        @Nullable String claimKey
) implements RewardGrant {

    public CustomItemGrant {
        if (customItemId == null || customItemId.isBlank()) {
            throw new IllegalArgumentException("customItemId must not be blank");
        }
        customItemId = customItemId.trim();
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        claimKey = (claimKey == null || claimKey.isBlank()) ? null : claimKey.trim();
    }
}
