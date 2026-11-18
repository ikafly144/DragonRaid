package net.sabafly.dragonraid.api.stat;

import java.util.Map;
import java.util.UUID;

/**
 * Stat aggregation and buff/debuff modifier SPI across players and raid enemies.
 */
public interface RaidStatService {

    void setTemporaryModifiers(UUID entityId, Map<String, Double> modifiers);

    void clearTemporaryModifiers(UUID entityId);

    StatSnapshot snapshotFor(UUID entityId);
}
