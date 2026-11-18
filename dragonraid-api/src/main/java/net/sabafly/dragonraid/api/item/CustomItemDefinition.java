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
        if (baseStats == null) {
            baseStats = Map.of();
        } else {
            for (Map.Entry<String, Double> entry : baseStats.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("Stat key must not be blank");
                }
                Double value = entry.getValue();
                if (value == null || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("Stat value for '" + entry.getKey() + "' must be finite: " + value);
                }
            }
            baseStats = Map.copyOf(baseStats);
        }
    }
}
