package net.sabafly.dragonraid.api.event;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.sabafly.dragonraid.api.enemy.EnemyRole;

/**
 * Per-participant combat telemetry recorded during an encounter across data-driven enemy definitions.
 *
 * @param playerId participant UUID
 * @param playerName participant last known name
 * @param damageByEnemyId damage dealt keyed by {@code RaidEnemyDefinition.id()}
 * @param damageByRole damage dealt aggregated by {@link EnemyRole}
 * @param destroysByEnemyId kill/shatter counts keyed by {@code RaidEnemyDefinition.id()}
 * @param revivesCompleted allied revives completed by this participant
 * @param downedCount number of times this participant entered {@code DOWNED} state
 * @param mechanicHitsTaken number of raid mechanic hits suffered by this participant
 */
public record ParticipantCombatMetrics(
        UUID playerId,
        String playerName,
        Map<String, Double> damageByEnemyId,
        Map<EnemyRole, Double> damageByRole,
        Map<String, Integer> destroysByEnemyId,
        int revivesCompleted,
        int downedCount,
        int mechanicHitsTaken
) {

    public ParticipantCombatMetrics {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        damageByEnemyId = damageByEnemyId == null ? Map.of() : Map.copyOf(damageByEnemyId);
        damageByRole = damageByRole == null ? Map.of() : Map.copyOf(damageByRole);
        destroysByEnemyId = destroysByEnemyId == null ? Map.of() : Map.copyOf(destroysByEnemyId);
    }

    public double totalBossDamage() {
        return damageByRole.getOrDefault(EnemyRole.PRIMARY_BOSS, 0.0)
                + damageByRole.getOrDefault(EnemyRole.SUB_BOSS, 0.0);
    }

    public int totalCrystalsDestroyed() {
        return destroysByEnemyId.getOrDefault("obsidian_crystal", 0);
    }
}
