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
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public double get(StatKey key, double defaultValue) {
        return values.getOrDefault(key.id(), defaultValue);
    }

    public double get(String statId, double defaultValue) {
        return values.getOrDefault(statId, defaultValue);
    }
}
