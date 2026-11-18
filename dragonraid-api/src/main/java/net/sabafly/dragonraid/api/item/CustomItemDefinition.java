package net.sabafly.dragonraid.api.item;

import java.util.Map;
import java.util.Objects;

/**
 * Data-driven custom gear/item metadata with item level ({@code iLvl}) and base stat contributions.
 */
public record CustomItemDefinition(
        String itemId,
        String displayNameKey,
        int itemLevel,
        Map<String, Double> baseStats
) {

    public CustomItemDefinition {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        Objects.requireNonNull(displayNameKey, "displayNameKey");
        if (itemLevel < 0) {
            throw new IllegalArgumentException("itemLevel must be >= 0");
        }
        baseStats = baseStats == null ? Map.of() : Map.copyOf(baseStats);
    }
}
