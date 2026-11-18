package net.sabafly.dragonraid.api.economy;

import java.util.Objects;

/**
 * Definition of a virtual currency managed by {@link RaidEconomyService}.
 */
public record CurrencyDefinition(
        String currencyId,
        String displayNameKey,
        long maxBalance,
        long weeklyCap
) {

    public CurrencyDefinition {
        if (currencyId == null || currencyId.isBlank()) {
            throw new IllegalArgumentException("currencyId must not be blank");
        }
        Objects.requireNonNull(displayNameKey, "displayNameKey");
        if (maxBalance < 0L || weeklyCap < 0L) {
            throw new IllegalArgumentException("maxBalance and weeklyCap must be >= 0");
        }
    }
}
