package net.sabafly.dragonraid.stat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.stat.RaidStatService;
import net.sabafly.dragonraid.api.stat.StatSnapshot;

/**
 * Thread-safe in-memory M1 implementation of {@link RaidStatService} tracking base stats
 * and temporary combat modifiers.
 */
public final class DefaultRaidStatService implements RaidStatService {

    private final Map<UUID, Map<String, Double>> baseStatsByEntity = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Double>> tempModifiersByEntity = new ConcurrentHashMap<>();

    public void setBaseStats(UUID entityId, Map<String, Double> stats) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(stats, "stats");
        baseStatsByEntity.put(entityId, Map.copyOf(stats));
    }

    @Override
    public void setTemporaryModifiers(UUID entityId, Map<String, Double> modifiers) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(modifiers, "modifiers");
        tempModifiersByEntity.put(entityId, Map.copyOf(modifiers));
    }

    @Override
    public void clearTemporaryModifiers(UUID entityId) {
        if (entityId != null) {
            tempModifiersByEntity.remove(entityId);
        }
    }

    @Override
    public StatSnapshot snapshotFor(UUID entityId) {
        if (entityId == null) {
            return StatSnapshot.EMPTY;
        }
        Map<String, Double> base = baseStatsByEntity.get(entityId);
        Map<String, Double> mods = tempModifiersByEntity.get(entityId);
        if ((base == null || base.isEmpty()) && (mods == null || mods.isEmpty())) {
            return StatSnapshot.EMPTY;
        }
        Map<String, Double> merged = new HashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (mods != null) {
            mods.forEach((k, v) -> merged.merge(k, v, Double::sum));
        }
        return new StatSnapshot(merged);
    }
}
