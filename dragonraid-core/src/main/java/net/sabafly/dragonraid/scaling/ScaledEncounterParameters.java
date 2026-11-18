package net.sabafly.dragonraid.scaling;

import net.sabafly.dragonraid.config.BossConfig;

/**
 * Immutable snapshot of encounter scaling parameters locked at pull confirmation for party size {@code N}.
 */
public record ScaledEncounterParameters(
        BossConfig.Difficulty difficulty,
        int partySize,
        double scaledBossHp,
        double scaledCrystalHp,
        double scaledCelestialCrystalHp,
        int maxRevives,
        int downedTimeoutSeconds,
        int towerCount,
        int spreadTargets,
        int crystalCount,
        boolean soloFallbackActive,
        double soloStackDamageMultiplier,
        double soloCrystalGraceSeconds,
        boolean allowVanillaTotem
) {
}
