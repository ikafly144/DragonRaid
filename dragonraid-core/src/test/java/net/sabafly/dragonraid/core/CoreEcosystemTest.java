package net.sabafly.dragonraid.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import org.junit.jupiter.api.Test;

class CoreEcosystemTest {

    @Test
    void serviceRegistryRegistersAndLooksUpImplementations() {
        DefaultRaidServiceRegistry registry = new DefaultRaidServiceRegistry();
        registry.register(String.class, "hello");
        assertEquals("hello", registry.require(String.class));
        assertTrue(registry.get(Integer.class).isEmpty());
    }

    @Test
    void combatMetricsTrackerAggregatesPerEnemyDamageDestroysRevivesAndHits() {
        CombatMetricsTracker tracker = new CombatMetricsTracker();
        UUID alice = UUID.randomUUID();
        tracker.ensureParticipant(alice, "Alice");
        tracker.recordDamage(alice, "Alice", "ancient_dragon", EnemyRole.PRIMARY_BOSS, 450.0);
        tracker.recordDamage(alice, "Alice", "ancient_dragon", EnemyRole.PRIMARY_BOSS, 150.0);
        tracker.recordDestroy(alice, "Alice", "obsidian_crystal");
        tracker.recordReviveCompleted(alice, "Alice");
        tracker.recordMechanicHit(alice, "Alice");
        tracker.recordDowned(alice, "Alice");

        Map<UUID, ParticipantCombatMetrics> snap = tracker.snapshot();
        ParticipantCombatMetrics metrics = snap.get(alice);
        assertEquals(600.0, metrics.totalBossDamage(), 1e-6);
        assertEquals(1, metrics.totalCrystalsDestroyed());
        assertEquals(1, metrics.revivesCompleted());
        assertEquals(1, metrics.mechanicHitsTaken());
        assertEquals(1, metrics.downedCount());
    }
}
