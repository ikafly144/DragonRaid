package net.sabafly.dragonraid.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;

/**
 * Thread-safe encounter telemetry accumulator recording damage, destroys, revives, downs,
 * and mechanic hits per participant across data-driven enemy definitions.
 */
public final class CombatMetricsTracker {

    private static final class MutableParticipantStats {
        volatile String playerName;
        final ConcurrentHashMap<String, Double> damageByEnemyId = new ConcurrentHashMap<>();
        final ConcurrentHashMap<EnemyRole, Double> damageByRole = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Integer> destroysByEnemyId = new ConcurrentHashMap<>();
        final AtomicInteger revivesCompleted = new AtomicInteger(0);
        final AtomicInteger downedCount = new AtomicInteger(0);
        final AtomicInteger mechanicHitsTaken = new AtomicInteger(0);

        MutableParticipantStats(String playerName) {
            this.playerName = playerName;
        }
    }

    private final ConcurrentHashMap<UUID, MutableParticipantStats> byPlayer = new ConcurrentHashMap<>();

    public void reset() {
        byPlayer.clear();
    }

    public void ensureParticipant(UUID playerId, String playerName) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        MutableParticipantStats stats = byPlayer.computeIfAbsent(playerId, id -> new MutableParticipantStats(playerName));
        stats.playerName = playerName;
    }

    public void recordDamage(UUID playerId, String playerName, String enemyId, EnemyRole role, double amount) {
        if (playerId == null || !Double.isFinite(amount) || amount <= 0.0) {
            return;
        }
        Objects.requireNonNull(enemyId, "enemyId");
        Objects.requireNonNull(role, "role");
        MutableParticipantStats stats = byPlayer.computeIfAbsent(
                playerId, id -> new MutableParticipantStats(playerName != null ? playerName : id.toString()));
        if (playerName != null) {
            stats.playerName = playerName;
        }
        stats.damageByEnemyId.merge(enemyId, amount, Double::sum);
        stats.damageByRole.merge(role, amount, Double::sum);
    }

    public void recordDestroy(UUID playerId, String playerName, String enemyId) {
        if (playerId == null) {
            return;
        }
        Objects.requireNonNull(enemyId, "enemyId");
        MutableParticipantStats stats = byPlayer.computeIfAbsent(
                playerId, id -> new MutableParticipantStats(playerName != null ? playerName : id.toString()));
        if (playerName != null) {
            stats.playerName = playerName;
        }
        stats.destroysByEnemyId.merge(enemyId, 1, Integer::sum);
    }

    public void recordReviveCompleted(UUID playerId, String playerName) {
        if (playerId == null) {
            return;
        }
        ensureParticipant(playerId, playerName != null ? playerName : playerId.toString());
        byPlayer.get(playerId).revivesCompleted.incrementAndGet();
    }

    public void recordDowned(UUID playerId, String playerName) {
        if (playerId == null) {
            return;
        }
        ensureParticipant(playerId, playerName != null ? playerName : playerId.toString());
        byPlayer.get(playerId).downedCount.incrementAndGet();
    }

    public void recordMechanicHit(UUID playerId, String playerName) {
        if (playerId == null) {
            return;
        }
        ensureParticipant(playerId, playerName != null ? playerName : playerId.toString());
        byPlayer.get(playerId).mechanicHitsTaken.incrementAndGet();
    }

    public Map<UUID, ParticipantCombatMetrics> snapshot() {
        Map<UUID, ParticipantCombatMetrics> out = new LinkedHashMap<>();
        byPlayer.forEach((id, stats) -> out.put(id, new ParticipantCombatMetrics(
                id,
                stats.playerName,
                new LinkedHashMap<>(stats.damageByEnemyId),
                new LinkedHashMap<>(stats.damageByRole),
                new LinkedHashMap<>(stats.destroysByEnemyId),
                stats.revivesCompleted.get(),
                stats.downedCount.get(),
                stats.mechanicHitsTaken.get()
        )));
        return Map.copyOf(out);
    }
}
