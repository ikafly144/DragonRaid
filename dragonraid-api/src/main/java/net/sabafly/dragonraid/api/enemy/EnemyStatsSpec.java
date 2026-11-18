package net.sabafly.dragonraid.api.enemy;

/**
 * Combat and lifecycle statistics for a {@link RaidEnemyDefinition}.
 *
 * @param baseMaxHp base maximum health before party scaling
 * @param hpPerExtraPlayer additional HP scaling ratio added per player beyond 1 (e.g. {@code 0.35})
 * @param oneHitShatter whether a single valid hit destroys the enemy immediately (typical for crystals)
 * @param suppressExplosion whether vanilla explosion block/damage grief should be cancelled
 * @param showBossBar whether this enemy tracks a boss bar
 */
public record EnemyStatsSpec(
        double baseMaxHp,
        double hpPerExtraPlayer,
        boolean oneHitShatter,
        boolean suppressExplosion,
        boolean showBossBar
) {

    public EnemyStatsSpec {
        if (!Double.isFinite(baseMaxHp) || baseMaxHp <= 0.0) {
            throw new IllegalArgumentException("baseMaxHp must be positive: " + baseMaxHp);
        }
        if (!Double.isFinite(hpPerExtraPlayer) || hpPerExtraPlayer < 0.0) {
            throw new IllegalArgumentException("hpPerExtraPlayer must be >= 0: " + hpPerExtraPlayer);
        }
    }

    public double scaledMaxHp(int partySize) {
        int extra = Math.max(0, partySize - 1);
        return baseMaxHp * (1.0 + hpPerExtraPlayer * extra);
    }
}
