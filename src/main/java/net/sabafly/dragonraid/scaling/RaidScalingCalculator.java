package net.sabafly.dragonraid.scaling;

import java.util.Objects;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.PluginConfig;

/**
 * Computes {@link ScaledEncounterParameters} from the active {@link PluginConfig.ScalingSettings}
 * and {@link BossConfig} for the confirmed party size {@code N}.
 */
public final class RaidScalingCalculator {

    private RaidScalingCalculator() {
    }

    /**
     * Computes scaled encounter parameters for {@code rawPartySize} participants.
     */
    public static ScaledEncounterParameters calculate(
            PluginConfig.ScalingSettings scaling,
            BossConfig bossConfig,
            int rawPartySize) {
        Objects.requireNonNull(scaling, "scaling");
        Objects.requireNonNull(bossConfig, "bossConfig");

        int effectiveN = clampPartySize(scaling, rawPartySize);
        double scaledBossHp = scaling.scaleBossHp(bossConfig.baseHp(), effectiveN);

        double baseCrystalHp = bossConfig.ability("crystals")
                .map(a -> a.getDouble("base-hp", 80.0))
                .orElse(80.0);
        double scaledCrystalHp = scaling.scaleObjectHp(baseCrystalHp, effectiveN);

        double baseCelestialHp = bossConfig.ability("celestial_crystals")
                .map(a -> a.getDouble("base-hp", 150.0))
                .orElse(150.0);
        double scaledCelestialHp = scaling.scaleObjectHp(baseCelestialHp, effectiveN);

        int maxRevives = bossConfig.revive().computeMaxRevives(effectiveN);
        int downedTimeoutSeconds = bossConfig.revive().downedTimeoutSeconds();

        PluginConfig.GimmickScaling gimmick = scaling.gimmick();
        int towerCount = gimmick.towerCount(effectiveN);
        int spreadTargets = gimmick.spreadTargets(effectiveN);
        int crystalCount = gimmick.crystalCount(effectiveN);

        PluginConfig.SoloFallbackSettings solo = scaling.soloFallback();
        boolean soloFallbackActive = effectiveN == 1 && solo.enabled();
        double stackDamageMultiplier = soloFallbackActive ? solo.stackDamageMultiplier() : 1.0;
        double crystalGraceSeconds = soloFallbackActive ? solo.simultaneousCrystalGraceSeconds() : 0.0;
        boolean allowVanillaTotem = soloFallbackActive ? solo.allowVanillaTotem() : true;

        return new ScaledEncounterParameters(
                bossConfig.difficulty(),
                effectiveN,
                scaledBossHp,
                scaledCrystalHp,
                scaledCelestialHp,
                maxRevives,
                downedTimeoutSeconds,
                towerCount,
                spreadTargets,
                crystalCount,
                soloFallbackActive,
                stackDamageMultiplier,
                crystalGraceSeconds,
                allowVanillaTotem
        );
    }

    private static int clampPartySize(PluginConfig.ScalingSettings scaling, int rawPartySize) {
        int n = Math.max(scaling.minPlayers(), Math.max(1, rawPartySize));
        if (scaling.enforceMaxPlayers()) {
            n = Math.min(n, scaling.maxPlayers());
        }
        return n;
    }
}
