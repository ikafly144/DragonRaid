package net.sabafly.dragonraid.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.EnrageRuleSpec;
import net.sabafly.dragonraid.api.script.ReviveRuleSpec;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig;
import net.sabafly.dragonraid.script.dsl.EncounterBlueprintBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidScalingCalculatorTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private BossConfig normalBoss;
    private BossConfig hardBoss;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> RaidScalingCalculatorTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        normalBoss = BossConfig.fromBlueprint(new EncounterBlueprintBuilder("sample_normal")
                .difficulty(EncounterDifficulty.NORMAL)
                .displayNameKey("boss.normal.name")
                .primaryBoss("boss")
                .revive(ReviveRuleSpec.normalDefault())
                .enrage(EnrageRuleSpec.normalDefault())
                .enemy("boss", e -> e.displayNameKey("boss.normal.name").stats(1200.0, 0.65, false, true, true))
                .mechanic("crystals", Map.of("name-key", "ability.crystals", "base-hp", 80.0))
                .phase("phase-1", p -> p.hpRange(1.0, 0.0).step(4.0, "SPAWN_CRYSTALS", "crystals"))
                .build());
        hardBoss = BossConfig.fromBlueprint(new EncounterBlueprintBuilder("sample_hard")
                .difficulty(EncounterDifficulty.HARD)
                .displayNameKey("boss.hard.name")
                .primaryBoss("boss")
                .revive(ReviveRuleSpec.hardDefault())
                .enrage(EnrageRuleSpec.hardDefault())
                .enemy("boss", e -> e.displayNameKey("boss.hard.name").stats(2400.0, 0.65, false, true, true))
                .mechanic("crystals", Map.of("name-key", "ability.crystals", "base-hp", 80.0))
                .mechanic("celestial_crystals", Map.of("name-key", "ability.celestial_crystals", "base-hp", 150.0))
                .phase("phase-1", p -> p.hpRange(1.0, 0.0).step(4.0, "SPAWN_CRYSTALS", "crystals"))
                .build());
    }

    @Test
    void soloScaling_activatesSoloFallbackAndBaseHp() {
        ScaledEncounterParameters normalSolo = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(),
                normalBoss,
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
                scaling, normalBoss, 5);
        assertEquals(5, normalFive.partySize());
        assertEquals(1200.0 * (1.0 + 0.65 * 4), normalFive.scaledBossHp(), 1e-6);
        assertEquals(80.0 * (1.0 + 0.50 * 4), normalFive.scaledCrystalHp(), 1e-6);
        assertEquals(5, normalFive.maxRevives());
        assertEquals(0, normalFive.downedTimeoutSeconds());
        assertEquals(3, normalFive.towerCount());
        assertEquals(3, normalFive.spreadTargets());
        assertEquals(6, normalFive.crystalCount());
        assertFalse(normalFive.soloFallbackActive());
        assertEquals(1.0, normalFive.soloStackDamageMultiplier(), 1e-6);
        assertEquals(0.0, normalFive.soloCrystalGraceSeconds(), 1e-6);

        ScaledEncounterParameters hardFive = RaidScalingCalculator.calculate(
                scaling, hardBoss, 5);
        assertEquals(3, hardFive.maxRevives());
        assertEquals(45, hardFive.downedTimeoutSeconds());
        assertEquals(2400.0 * (1.0 + 0.65 * 4), hardFive.scaledBossHp(), 1e-6);
        assertEquals(150.0 * (1.0 + 0.50 * 4), hardFive.scaledCelestialCrystalHp(), 1e-6);
    }

    @Test
    void variablePartySizeExceedingMaxPlayers_scalesWhenEnforceMaxDisabled() {
        PluginConfig.ScalingSettings scaling = configManager.pluginConfig().scaling();
        assertFalse(scaling.enforceMaxPlayers());

        ScaledEncounterParameters largeRaid = RaidScalingCalculator.calculate(
                scaling, hardBoss, 12);
        assertEquals(12, largeRaid.partySize());
        assertEquals(6, largeRaid.maxRevives());
        assertEquals(6, largeRaid.towerCount());
        assertEquals(6, largeRaid.spreadTargets());
        assertEquals(9, largeRaid.crystalCount());
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
                () -> RaidScalingCalculator.calculate(strict, normalBoss, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RaidScalingCalculator.calculate(strict, normalBoss, 5));
    }
}
