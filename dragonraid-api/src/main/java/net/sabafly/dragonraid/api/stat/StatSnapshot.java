package net.sabafly.dragonraid.api.stat;

import java.util.Map;

/**
 * Immutable snapshot of aggregated combat stats for an entity.
 */
public record StatSnapshot(
        Map<String, Double> values
) {

    public static final StatSnapshot EMPTY = new StatSnapshot(Map.of());

    public StatSnapshot {
        if (values == null) {
            values = Map.of();
        } else {
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("Stat key must not be blank");
                }
                Double value = entry.getValue();
                if (value == null || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("Stat value for '" + entry.getKey() + "' must be finite: " + value);
                }
            }
            values = Map.copyOf(values);
        }
    }

    public double get(StatKey key, double defaultValue) {
        return values.getOrDefault(key.id(), defaultValue);
    }

    public double get(String statId, double defaultValue) {
        return values.getOrDefault(statId, defaultValue);
    }
}
