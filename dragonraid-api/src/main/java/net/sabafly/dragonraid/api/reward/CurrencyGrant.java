package net.sabafly.dragonraid.api.reward;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable currency grant output from {@link RewardTable} evaluation.
 */
public record CurrencyGrant(
        String currencyId,
        long amount,
        @Nullable String claimKey
) implements RewardGrant {

    public CurrencyGrant {
        if (currencyId == null || currencyId.isBlank()) {
            throw new IllegalArgumentException("currencyId must not be blank");
        }
        currencyId = currencyId.trim();
        if (amount <= 0L) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        claimKey = (claimKey == null || claimKey.isBlank()) ? null : claimKey.trim();
    }
}
