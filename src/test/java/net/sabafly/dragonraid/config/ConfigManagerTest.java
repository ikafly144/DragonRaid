package net.sabafly.dragonraid.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigManagerTest {

    private static ConfigManager newManager(Path tempDir) {
        return new ConfigManager(tempDir, name -> ConfigManagerTest.class.getClassLoader().getResourceAsStream(name));
    }

    @Test
    void initialize_extractsAndLoadsAllDefaultConfigs(@TempDir Path tempDir) {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        assertTrue(Files.exists(tempDir.resolve("config.yml")));
        assertTrue(Files.exists(tempDir.resolve("boss_normal.yml")));
        assertTrue(Files.exists(tempDir.resolve("boss_hard.yml")));
        assertTrue(Files.exists(tempDir.resolve("rewards.yml")));
        assertTrue(Files.exists(tempDir.resolve("lang/ja_JP.yml")));
        assertTrue(Files.exists(tempDir.resolve("lang/en_US.yml")));

        PluginConfig cfg = manager.pluginConfig();
        assertEquals("ja_JP", cfg.language().defaultLocale());
        assertEquals("world_the_end", cfg.world().endWorldName());
        assertEquals(0.65, cfg.scaling().hpAlpha(), 1e-6);
        assertTrue(cfg.protection().keepInventoryOnRaidDeath());
        assertTrue(cfg.protection().disableBedAndAnchorExplosions());
        assertTrue(cfg.protection().protectOriginalArenaTerrain());

        // Region checks
        assertTrue(cfg.regions().arena().contains(0.0, 64.0, 0.0));
        assertFalse(cfg.regions().arena().contains(200.0, 64.0, 0.0));
        assertTrue(cfg.regions().safeZone().contains(100.0, 50.0, 0.0));
        assertFalse(cfg.regions().safeZone().contains(0.0, 64.0, 0.0));

        // Scaling checks
        assertEquals(1200.0, cfg.scaling().scaleBossHp(1200.0, 1), 1e-6);
        assertEquals(1200.0 * (1.0 + 0.65 * 3), cfg.scaling().scaleBossHp(1200.0, 4), 1e-6);

        // Normal boss: 3 phases, N*1 revives, no downed timeout
        BossConfig normal = manager.normalBossConfig();
        assertEquals(BossConfig.Difficulty.NORMAL, normal.difficulty());
        assertEquals(3, normal.phases().size());
        assertEquals(4, normal.revive().computeMaxRevives(4));
        assertFalse(normal.revive().hasDownedTimeout());
        assertFalse(normal.enrage().hardEnrage().enabled());
        assertEquals("phase-1", normal.phaseForHpRatio(0.85).id());
        assertEquals("phase-2", normal.phaseForHpRatio(0.55).id());
        assertEquals("final", normal.phaseForHpRatio(0.20).id());

        // Hard boss: 4 phases, ceil(N/2) revives, 45s downed timeout, hard enrage
        BossConfig hard = manager.hardBossConfig();
        assertEquals(BossConfig.Difficulty.HARD, hard.difficulty());
        assertEquals(4, hard.phases().size());
        assertEquals(3, hard.revive().computeMaxRevives(5));
        assertEquals(1, hard.revive().computeMaxRevives(1));
        assertEquals(45, hard.revive().downedTimeoutSeconds());
        assertTrue(hard.enrage().hardEnrage().enabled());
        assertEquals("phase-3", hard.phaseForHpRatio(0.30).id());
        assertEquals("final", hard.phaseForHpRatio(0.10).id());

        // Rewards config
        RewardsConfig rewards = manager.rewardsConfig();
        assertEquals(3, rewards.currencies().size());
        assertEquals(6, rewards.customGear().size());
        assertEquals(4, rewards.customEnchants().size());
        assertEquals(54, rewards.shop().size());
        assertFalse(rewards.shop().items().isEmpty());
    }

    @Test
    void reload_invalidValueKeepsPreviousSnapshotAndThrows(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();
        PluginConfig before = manager.pluginConfig();

        Path configFile = tempDir.resolve("config.yml");
        String invalid = Files.readString(configFile, StandardCharsets.UTF_8)
                .replace("hp-alpha: 0.65", "hp-alpha: -0.5");
        Files.writeString(configFile, invalid, StandardCharsets.UTF_8);

        ConfigException ex = assertThrows(ConfigException.class, manager::reload);
        assertEquals("config.yml", ex.fileName());
        assertEquals("scaling.hp-alpha", ex.path());
        assertSame(before, manager.pluginConfig());
    }

    @Test
    void bossValidation_rejectsUnknownAbilityReference(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path normalFile = tempDir.resolve("boss_normal.yml");
        String invalid = Files.readString(normalFile, StandardCharsets.UTF_8)
                .replace("ability: tank_buster", "ability: nonexistent_skill");
        Files.writeString(normalFile, invalid, StandardCharsets.UTF_8);

        ConfigException ex = assertThrows(ConfigException.class, manager::reload);
        assertEquals("boss_normal.yml", ex.fileName());
        assertTrue(ex.getMessage().contains("nonexistent_skill"));
    }

    @Test
    void rewardsValidation_rejectsOutOfBoundsShopSlot(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path rewardsFile = tempDir.resolve("rewards.yml");
        String invalid = Files.readString(rewardsFile, StandardCharsets.UTF_8)
                .replace("slot: 10", "slot: 99");
        Files.writeString(rewardsFile, invalid, StandardCharsets.UTF_8);

        ConfigException ex = assertThrows(ConfigException.class, manager::reload);
        assertEquals("rewards.yml", ex.fileName());
        assertTrue(ex.path().contains("slot"));
    }

    @Test
    void updateRegions_persistsAndReloadsNewBounds(@TempDir Path tempDir) {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        RegionConfig current = manager.pluginConfig().regions();
        RegionConfig.ArenaRegion updatedArena = new RegionConfig.ArenaRegion(
                RegionConfig.Shape.CYLINDER,
                new RegionConfig.Point3D(10.0, 70.0, -5.0),
                150.0,
                current.arena().min(),
                current.arena().max(),
                10,
                240
        );
        RegionConfig updated = new RegionConfig(updatedArena, current.safeZone(), -16.0);
        manager.updateRegions(updated);

        PluginConfig reloaded = manager.pluginConfig();
        assertEquals(150.0, reloaded.regions().arena().radius(), 1e-6);
        assertEquals(10.0, reloaded.regions().arena().center().x(), 1e-6);
        assertEquals(-16.0, reloaded.regions().voidYThreshold(), 1e-6);
    }

    @Test
    void cuboidSafeZoneWithoutCenter_computesMidpointAsCenter(@TempDir Path tempDir) {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        // Default config.yml defines safe-zone as cuboid min(92,45,-8)..max(108,65,8) with no explicit center
        RegionConfig.Point3D center = manager.pluginConfig().regions().safeZone().center();
        assertEquals(100.0, center.x(), 1e-6);
        assertEquals(55.0, center.y(), 1e-6);
        assertEquals(0.0, center.z(), 1e-6);
    }

    @Test
    void updateRegions_rejectsInvalidRegionWithoutModifyingConfigOnDisk(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path configFile = tempDir.resolve("config.yml");
        String originalContent = Files.readString(configFile, StandardCharsets.UTF_8);
        RegionConfig current = manager.pluginConfig().regions();

        RegionConfig.ArenaRegion invalidArena = new RegionConfig.ArenaRegion(
                RegionConfig.Shape.CYLINDER,
                current.arena().center(),
                -10.0,
                current.arena().min(),
                current.arena().max(),
                current.arena().minY(),
                current.arena().maxY()
        );
        RegionConfig invalidRegions = new RegionConfig(invalidArena, current.safeZone(), current.voidYThreshold());

        ConfigException ex = assertThrows(ConfigException.class, () -> manager.updateRegions(invalidRegions));
        assertEquals("config.yml", ex.fileName());
        assertEquals("regions.arena.radius", ex.path());
        assertEquals(originalContent, Files.readString(configFile, StandardCharsets.UTF_8));
    }

    @Test
    void updateRegionsAsync_preservesYamlCommentsAndValidatesAsync(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path configFile = tempDir.resolve("config.yml");
        String original = Files.readString(configFile, StandardCharsets.UTF_8);
        String withComment = original.replace("void-y-threshold:", "# Preserve region threshold comment\n  void-y-threshold:");
        Files.writeString(configFile, withComment, StandardCharsets.UTF_8);

        RegionConfig current = manager.pluginConfig().regions();
        RegionConfig.ArenaRegion updatedArena = new RegionConfig.ArenaRegion(
                RegionConfig.Shape.CYLINDER,
                new RegionConfig.Point3D(5.0, 64.0, -5.0),
                135.0,
                current.arena().min(),
                current.arena().max(),
                current.arena().minY(),
                current.arena().maxY()
        );
        RegionConfig updated = new RegionConfig(updatedArena, current.safeZone(), -12.0);
        manager.updateRegionsAsync(updated, Runnable::run).join();

        String saved = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(saved.contains("# DragonRaid Main Configuration"));
        assertTrue(saved.contains("# Preserve region threshold comment"));
        assertEquals(135.0, manager.pluginConfig().regions().arena().radius(), 1e-6);
        assertEquals(-12.0, manager.pluginConfig().regions().voidYThreshold(), 1e-6);

        RegionConfig.ArenaRegion invalidArena = new RegionConfig.ArenaRegion(
                RegionConfig.Shape.CYLINDER,
                updatedArena.center(),
                0.0,
                updatedArena.min(),
                updatedArena.max(),
                updatedArena.minY(),
                updatedArena.maxY()
        );
        RegionConfig invalid = new RegionConfig(invalidArena, current.safeZone(), -12.0);
        CompletionException thrown = assertThrows(
                CompletionException.class,
                () -> manager.updateRegionsAsync(invalid, Runnable::run).join());
        assertInstanceOf(ConfigException.class, thrown.getCause());
        assertEquals(saved, Files.readString(configFile, StandardCharsets.UTF_8));
    }

    @Test
    void updateRegions_leavesDiskAndMemoryUntouchedWhenCompanionConfigIsInvalid(@TempDir Path tempDir)
            throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path configFile = tempDir.resolve("config.yml");
        Path hardBossFile = tempDir.resolve("boss_hard.yml");
        String originalConfig = Files.readString(configFile, StandardCharsets.UTF_8);
        double originalRadius = manager.pluginConfig().regions().arena().radius();

        // Corrupt companion boss_hard.yml after initialization.
        String corruptedBoss = Files.readString(hardBossFile, StandardCharsets.UTF_8)
                .replace("base-hp: 2400.0", "base-hp: -10.0");
        Files.writeString(hardBossFile, corruptedBoss, StandardCharsets.UTF_8);

        RegionConfig current = manager.pluginConfig().regions();
        RegionConfig.ArenaRegion candidateArena = new RegionConfig.ArenaRegion(
                RegionConfig.Shape.CYLINDER,
                current.arena().center(),
                180.0,
                current.arena().min(),
                current.arena().max(),
                current.arena().minY(),
                current.arena().maxY()
        );
        RegionConfig candidate = new RegionConfig(candidateArena, current.safeZone(), current.voidYThreshold());

        ConfigException ex = assertThrows(ConfigException.class, () -> manager.updateRegions(candidate));
        assertEquals("boss_hard.yml", ex.fileName());
        assertEquals(originalConfig, Files.readString(configFile, StandardCharsets.UTF_8));
        assertEquals(originalRadius, manager.pluginConfig().regions().arena().radius(), 1e-6);
    }

    @Test
    void integerValidation_rejectsFractionalAndOutOfRangeValues(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path rewardsFile = tempDir.resolve("rewards.yml");
        String originalRewards = Files.readString(rewardsFile, StandardCharsets.UTF_8);

        Files.writeString(rewardsFile, originalRewards.replaceFirst("amount: 1", "amount: 1.9"), StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, manager::reload);

        Files.writeString(
                rewardsFile,
                originalRewards.replaceFirst("dragon_token: 30", "dragon_token: 4294967297"),
                StandardCharsets.UTF_8);
        ConfigException overflow = assertThrows(ConfigException.class, manager::reload);
        assertEquals("rewards.yml", overflow.fileName());
    }

    @Test
    void bossPhaseAndSpreadTargets_handleEdgeBoundaries() {
        BossConfig.PhaseConfig low = new BossConfig.PhaseConfig(
                "low", "phase.low", 0.5, 0.0, 1.0, true,
                List.of(new BossConfig.TimelineStep(1.0, "LOOP", null)));
        BossConfig.PhaseConfig high = new BossConfig.PhaseConfig(
                "high", "phase.high", 1.0, 0.5, 1.0, false,
                List.of(new BossConfig.TimelineStep(1.0, "LOOP", null)));
        BossConfig ascending = new BossConfig(
                BossConfig.Difficulty.NORMAL,
                "boss.normal.name",
                1000.0,
                10.0,
                new BossConfig.BossReviveSettings(BossConfig.ReviveFormula.PER_PLAYER, 1.0, 0),
                new BossConfig.EnrageSettings(
                        new BossConfig.SoftEnrageSettings(false, "low", 0.0, 0.0),
                        new BossConfig.HardEnrageSettings(false, 0, "ability.hard_enrage.name", 0.0)),
                Map.of(),
                List.of(low, high));

        assertEquals("low", ascending.phaseForHpRatio(0.0).id());
        assertEquals("low", ascending.phaseForHpRatio(0.25).id());
        assertEquals("high", ascending.phaseForHpRatio(1.0).id());

        PluginConfig.GimmickScaling scaling = new PluginConfig.GimmickScaling(
                1, 0.25, 1, 4,
                0.5, 2, 4,
                3, 0.5, 6);
        assertEquals(1, scaling.spreadTargets(1));
        assertEquals(2, scaling.spreadTargets(2));
        assertEquals(4, scaling.spreadTargets(10));
    }

    @Test
    void updateRegions_preservesColumnZeroCommentsInsideRegionsAndDoesNotDuplicateKeys(@TempDir Path tempDir)
            throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path configFile = tempDir.resolve("config.yml");
        String original = Files.readString(configFile, StandardCharsets.UTF_8);
        String withColZeroComment = original
                .replace("  safe-zone:", "# waiting area at column 0\n  safe-zone:")
                .replace("scaling:", "# Next top-level section header\nscaling:");
        Files.writeString(configFile, withColZeroComment, StandardCharsets.UTF_8);

        RegionConfig current = manager.pluginConfig().regions();
        RegionConfig.ArenaRegion updatedArena = new RegionConfig.ArenaRegion(
                RegionConfig.Shape.CYLINDER,
                new RegionConfig.Point3D(25.0, 64.0, 25.0),
                140.0,
                current.arena().min(),
                current.arena().max(),
                current.arena().minY(),
                current.arena().maxY()
        );
        manager.updateRegions(new RegionConfig(updatedArena, current.safeZone(), -8.0));

        String saved = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(saved.contains("# waiting area at column 0"));
        assertTrue(saved.contains("# Next top-level section header"));
        // Verify safe-zone and void-y-threshold appear exactly once (no duplicate section tail left behind)
        assertEquals(1, saved.split("(?m)^  safe-zone:$", -1).length - 1);
        assertEquals(1, saved.split("(?m)^  void-y-threshold:", -1).length - 1);

        manager.reload();
        assertEquals(140.0, manager.pluginConfig().regions().arena().radius(), 1e-6);
        assertEquals(-8.0, manager.pluginConfig().regions().voidYThreshold(), 1e-6);
    }

    @Test
    void spawnPointFloatOverflow_isRejectedOnLoad(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path configFile = tempDir.resolve("config.yml");
        String original = Files.readString(configFile, StandardCharsets.UTF_8);
        Files.writeString(configFile, original.replace("yaw: 90.0", "yaw: 1e100"), StandardCharsets.UTF_8);

        ConfigException ex = assertThrows(ConfigException.class, manager::reload);
        assertEquals("config.yml", ex.fileName());
        assertTrue(ex.path().contains("spawn-point"));
    }
}
