package net.sabafly.dragonraid.api.reward;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable vanilla item grant output from {@link RewardTable} evaluation.
 */
public record VanillaItemGrant(
        String materialName,
        int amount,
        @Nullable String claimKey
) implements RewardGrant {

    public VanillaItemGrant {
        if (materialName == null || materialName.isBlank()) {
            throw new IllegalArgumentException("materialName must not be blank");
        }
        materialName = materialName.trim();
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        claimKey = (claimKey == null || claimKey.isBlank()) ? null : claimKey.trim();
    }
}
