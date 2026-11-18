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
        assertFalse(Files.exists(tempDir.resolve("boss_normal.yml")));
        assertFalse(Files.exists(tempDir.resolve("boss_hard.yml")));
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
        assertFalse(cfg.revive().downOnDisconnect());

        // Region checks
        assertTrue(cfg.regions().arena().contains(0.0, 64.0, 0.0));
        assertFalse(cfg.regions().arena().contains(200.0, 64.0, 0.0));
        assertTrue(cfg.regions().safeZone().contains(100.0, 50.0, 0.0));
        assertFalse(cfg.regions().safeZone().contains(0.0, 64.0, 0.0));

        // Scaling checks
        assertEquals(1200.0, cfg.scaling().scaleBossHp(1200.0, 1), 1e-6);
        assertEquals(1200.0 * (1.0 + 0.65 * 3), cfg.scaling().scaleBossHp(1200.0, 4), 1e-6);

        // Generic BossConfig.fromBlueprint conversion
        BossConfig converted = BossConfig.fromBlueprint(
                new net.sabafly.dragonraid.script.dsl.EncounterBlueprintBuilder("sample_bp")
                        .difficulty(net.sabafly.dragonraid.api.script.EncounterDifficulty.HARD)
                        .displayNameKey("boss.hard-name")
                        .primaryBoss("boss_alpha")
                        .revive(net.sabafly.dragonraid.api.script.ReviveRuleSpec.hardDefault())
                        .enrage(net.sabafly.dragonraid.api.script.EnrageRuleSpec.hardDefault())
                        .enemy("boss_alpha", e -> e
                                .displayNameKey("boss.hard-name")
                                .stats(2400.0, 0.65, false, true, true))
                        .mechanic("fireball", Map.of("name-key", "ability.fireball_barrage.name", "cast-seconds", 2.0))
                        .phase("phase-1", p -> p.hpRange(1.0, 0.5).step(4.0, "FIREBALL", "fireball"))
                        .phase("final", p -> p.hpRange(0.5, 0.0).softEnrage(true).step(3.0, "FIREBALL", "fireball"))
                        .build());
        assertEquals(BossConfig.Difficulty.HARD, converted.difficulty());
        assertEquals(2, converted.phases().size());
        assertEquals(3, converted.revive().computeMaxRevives(5));
        assertEquals(1, converted.revive().computeMaxRevives(1));
        assertEquals(45, converted.revive().downedTimeoutSeconds());
        assertTrue(converted.enrage().hardEnrage().enabled());
        assertEquals("phase-1", converted.phaseForHpRatio(0.75).id());
        assertEquals("final", converted.phaseForHpRatio(0.25).id());

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
    void blueprintConversion_rejectsUnknownAbilityReference() {
        net.sabafly.dragonraid.api.script.EncounterBlueprint invalid =
                new net.sabafly.dragonraid.script.dsl.EncounterBlueprintBuilder("bad_bp")
                        .difficulty(net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL)
                        .displayNameKey("boss.normal.name")
                        .primaryBoss("ancient_dragon")
                        .enemy("ancient_dragon", e -> e.displayNameKey("boss.normal.name"))
                        .phase("p1", p -> p.displayNameKey("phase.p1.name").step(2.0, "FIREBALL_BARRAGE", "nonexistent_skill"))
                        .build();

        ConfigException ex = assertThrows(ConfigException.class, () -> BossConfig.fromBlueprint(invalid));
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
        Path rewardsFile = tempDir.resolve("rewards.yml");
        String originalConfig = Files.readString(configFile, StandardCharsets.UTF_8);
        double originalRadius = manager.pluginConfig().regions().arena().radius();

        // Corrupt companion rewards.yml after initialization.
        String corruptedRewards = Files.readString(rewardsFile, StandardCharsets.UTF_8)
                .replace("slot: 10", "slot: 99");
        Files.writeString(rewardsFile, corruptedRewards, StandardCharsets.UTF_8);

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
        assertEquals("rewards.yml", ex.fileName());
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

    @Test
    void reload_ignoresLegacyAltarAndAutoStartKeysGracefully(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = newManager(tempDir);
        manager.initialize();

        Path configFile = tempDir.resolve("config.yml");
        String withLegacyKeys = Files.readString(configFile, StandardCharsets.UTF_8)
                .replace("end-world-name: world_the_end", "end-world-name: world_the_end\n  replace-first-dragon: true")
                .replace("pull-countdown-seconds: 8", "pull-countdown-seconds: 8\n  auto-start-on-arena-enter: true\n  altar-gui-only: false\n  altar:\n    interact-radius: 4.0");
        Files.writeString(configFile, withLegacyKeys, StandardCharsets.UTF_8);

        manager.reload();
        assertEquals(8, manager.pluginConfig().encounter().pullCountdownSeconds());
        assertEquals("world_the_end", manager.pluginConfig().world().endWorldName());
    }
}
