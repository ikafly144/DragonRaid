package net.sabafly.dragonraid.stat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;
import net.sabafly.dragonraid.api.stat.StatKey;
import net.sabafly.dragonraid.api.stat.StatSnapshot;
import org.junit.jupiter.api.Test;

class DefaultRaidStatServiceTest {

    @Test
    void mergesBaseStatsAndTemporaryModifiers() {
        DefaultRaidStatService statService = new DefaultRaidStatService();
        UUID entityId = UUID.randomUUID();

        statService.setBaseStats(entityId, Map.of(
                StatKey.MAX_HEALTH_BONUS.id(), 20.0,
                StatKey.MOVE_SPEED_BONUS.id(), 0.05
        ));
        statService.setTemporaryModifiers(entityId, Map.of(
                StatKey.MOVE_SPEED_BONUS.id(), 0.15,
                StatKey.ATTACK_DAMAGE_MULTIPLIER.id(), 1.25
        ));

        StatSnapshot snapshot = statService.snapshotFor(entityId);
        assertEquals(20.0, snapshot.get(StatKey.MAX_HEALTH_BONUS, 0.0));
        assertEquals(0.20, snapshot.get(StatKey.MOVE_SPEED_BONUS, 0.0), 1e-6);
        assertEquals(1.25, snapshot.get(StatKey.ATTACK_DAMAGE_MULTIPLIER, 1.0));

        statService.clearTemporaryModifiers(entityId);
        assertEquals(0.05, statService.snapshotFor(entityId).get(StatKey.MOVE_SPEED_BONUS, 0.0), 1e-6);

        statService.clearBaseStats(entityId);
        assertEquals(StatSnapshot.EMPTY, statService.snapshotFor(entityId));
    }

    @Test
    void rejectsBlankKeysAndNonFiniteValues() {
        DefaultRaidStatService statService = new DefaultRaidStatService();
        UUID entityId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> statService.setBaseStats(entityId, Map.of(" ", 1.0)));
        assertThrows(IllegalArgumentException.class,
                () -> statService.setTemporaryModifiers(entityId, Map.of("stat", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> statService.setTemporaryModifiers(entityId, Map.of("stat", Double.NEGATIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class,
                () -> new StatSnapshot(Map.of("stat", Double.NaN)));
    }
}
