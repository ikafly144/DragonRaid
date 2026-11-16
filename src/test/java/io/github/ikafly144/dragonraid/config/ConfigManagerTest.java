package io.github.ikafly144.dragonraid.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
