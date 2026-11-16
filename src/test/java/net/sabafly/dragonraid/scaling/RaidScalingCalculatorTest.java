package net.sabafly.dragonraid.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidScalingCalculatorTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> RaidScalingCalculatorTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
    }

    @Test
    void soloScaling_activatesSoloFallbackAndBaseHp() {
        ScaledEncounterParameters normalSolo = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(),
                configManager.normalBossConfig(),
                1);

        assertEquals(1, normalSolo.partySize());
        assertEquals(1200.0, normalSolo.scaledBossHp(), 1e-6);
        assertEquals(80.0, normalSolo.scaledCrystalHp(), 1e-6);
        assertEquals(1, normalSolo.maxRevives());
        assertEquals(0, normalSolo.downedTimeoutSeconds());
        assertEquals(1, normalSolo.towerCount());
        assertEquals(1, normalSolo.spreadTargets());
        assertEquals(4, normalSolo.crystalCount());
        assertTrue(normalSolo.soloFallbackActive());
        assertEquals(0.35, normalSolo.soloStackDamageMultiplier(), 1e-6);
        assertEquals(12.0, normalSolo.soloCrystalGraceSeconds(), 1e-6);
        assertTrue(normalSolo.allowVanillaTotem());
    }

    @Test
    void multiPlayerScaling_computesNormalAndHardParametersAccurately() {
        PluginConfig.ScalingSettings scaling = configManager.pluginConfig().scaling();

        ScaledEncounterParameters normalFive = RaidScalingCalculator.calculate(
                scaling, configManager.normalBossConfig(), 5);
        assertEquals(5, normalFive.partySize());
        assertEquals(1200.0 * (1.0 + 0.65 * 4), normalFive.scaledBossHp(), 1e-6);
        assertEquals(80.0 * (1.0 + 0.50 * 4), normalFive.scaledCrystalHp(), 1e-6);
        assertEquals(5, normalFive.maxRevives()); // N * 1
        assertEquals(0, normalFive.downedTimeoutSeconds());
        assertEquals(3, normalFive.towerCount()); // 1 + floor(5 * 0.5)
        assertEquals(3, normalFive.spreadTargets()); // ceil(5 * 0.5)
        assertEquals(6, normalFive.crystalCount()); // 4 + floor(4 * 0.5)
        assertFalse(normalFive.soloFallbackActive());
        assertEquals(1.0, normalFive.soloStackDamageMultiplier(), 1e-6);
        assertEquals(0.0, normalFive.soloCrystalGraceSeconds(), 1e-6);

        ScaledEncounterParameters hardFive = RaidScalingCalculator.calculate(
                scaling, configManager.hardBossConfig(), 5);
        assertEquals(3, hardFive.maxRevives()); // ceil(5 / 2)
        assertEquals(45, hardFive.downedTimeoutSeconds());
        assertEquals(2400.0 * (1.0 + 0.65 * 4), hardFive.scaledBossHp(), 1e-6);
        assertEquals(150.0 * (1.0 + 0.50 * 4), hardFive.scaledCelestialCrystalHp(), 1e-6);
    }

    @Test
    void variablePartySizeExceedingMaxPlayers_scalesWhenEnforceMaxDisabled() {
        PluginConfig.ScalingSettings scaling = configManager.pluginConfig().scaling();
        assertFalse(scaling.enforceMaxPlayers());

        ScaledEncounterParameters largeRaid = RaidScalingCalculator.calculate(
                scaling, configManager.hardBossConfig(), 12);
        assertEquals(12, largeRaid.partySize());
        assertEquals(6, largeRaid.maxRevives()); // ceil(12 / 2)
        assertEquals(6, largeRaid.towerCount()); // clamped to tower-max=6
        assertEquals(6, largeRaid.spreadTargets()); // ceil(12 * 0.5) = 6 <= 8
        assertEquals(9, largeRaid.crystalCount()); // 4 + floor(11 * 0.5) = 9 <= 10
    }

    @Test
    void outOfRangePartySize_throwsIllegalArgumentException() {
        PluginConfig.ScalingSettings base = configManager.pluginConfig().scaling();
        PluginConfig.ScalingSettings strict = new PluginConfig.ScalingSettings(
                2,
                4,
                true,
                base.hpAlpha(),
                base.objectHpAlpha(),
                base.gimmick(),
                base.soloFallback());

        assertThrows(IllegalArgumentException.class,
                () -> RaidScalingCalculator.calculate(strict, configManager.normalBossConfig(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> RaidScalingCalculator.calculate(strict, configManager.normalBossConfig(), 5));
    }
}
