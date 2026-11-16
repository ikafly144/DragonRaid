package io.github.ikafly144.dragonraid.config;

import static io.github.ikafly144.dragonraid.config.ConfigValidation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads and validates YAML configuration files into immutable config records.
 */
final class YamlConfigLoader {

    private YamlConfigLoader() {
    }

    static YamlConfiguration loadYaml(Path path) {
        if (!Files.exists(path)) {
            throw new ConfigException(path.getFileName().toString(), "", "Configuration file does not exist: " + path);
        }
        String fileName = path.getFileName().toString();
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(raw);
            return yaml;
        } catch (IOException | InvalidConfigurationException ex) {
            throw new ConfigException(fileName, "", "Failed to parse YAML: " + ex.getMessage(), ex);
        }
    }

    static PluginConfig loadPluginConfig(Path path) {
        String file = path.getFileName().toString();
        YamlConfiguration root = loadYaml(path);

        var language = parseLanguage(requireSection(root, "language", file), file);
        var database = parseDatabase(requireSection(root, "database", file), file);
        var world = parseWorld(requireSection(root, "world", file), file);
        var regions = parseRegions(requireSection(root, "regions", file), file);
        var scaling = parseScaling(requireSection(root, "scaling", file), file);
        var encounter = parseEncounter(requireSection(root, "encounter", file), file);
        var revive = parseRevive(requireSection(root, "revive", file), file);
        var protection = parseProtection(requireSection(root, "protection", file), file);
        var visuals = parseVisuals(requireSection(root, "visuals", file), file);

        return new PluginConfig(language, database, world, regions, scaling, encounter, revive, protection, visuals);
    }

    static BossConfig loadBossConfig(Path path, BossConfig.Difficulty expectedDifficulty) {
        String file = path.getFileName().toString();
        YamlConfiguration root = loadYaml(path);

        BossConfig.Difficulty difficulty = parseEnum(
                BossConfig.Difficulty.class,
                requireString(root, "difficulty", file),
                "difficulty",
                file
        );
        if (expectedDifficulty != null && difficulty != expectedDifficulty) {
            throw new ConfigException(file, "difficulty",
                    "Expected difficulty " + expectedDifficulty + " but found " + difficulty);
        }

        String displayNameKey = requireString(root, "display-name-key", file);
        double baseHp = requirePositive(requireDouble(root, "base-hp", file), "base-hp", file);
        double baseAttack = requireNonNegative(requireDouble(root, "base-attack-damage", file), "base-attack-damage", file);

        var revive = parseBossRevive(requireSection(root, "revive", file), file);
        var enrage = parseEnrage(requireSection(root, "enrage", file), file);
        var abilities = parseAbilities(requireSection(root, "abilities", file), file);
        var phases = parsePhases(root, abilities, file);

        return new BossConfig(difficulty, displayNameKey, baseHp, baseAttack, revive, enrage, abilities, phases);
    }

    static RewardsConfig loadRewardsConfig(Path path) {
        String file = path.getFileName().toString();
        YamlConfiguration root = loadYaml(path);

        var currencies = parseCurrencies(requireSection(root, "currencies", file), file);
        var clearRewards = parseClearRewards(requireSection(root, "clear-rewards", file), currencies, file);
        var customGear = parseCustomGear(requireSection(root, "custom-gear", file), file);
        var customEnchants = parseCustomEnchants(requireSection(root, "custom-enchants", file), file);
        var shop = parseShop(requireSection(root, "shop", file), currencies, customGear, customEnchants, file);

        return new RewardsConfig(currencies, clearRewards, customGear, customEnchants, shop);
    }

    private static PluginConfig.LanguageSettings parseLanguage(ConfigurationSection sec, String file) {
        String defaultLocale = requireString(sec, "default", file);
        boolean perPlayer = requireBoolean(sec, "per-player-locale", file);
        List<String> supported = requireStringList(sec, "supported", file);
        if (!supported.contains(defaultLocale)) {
            throw new ConfigException(file, "language.default",
                    "Default locale '" + defaultLocale + "' must be included in language.supported");
        }
        return new PluginConfig.LanguageSettings(defaultLocale, perPlayer, supported);
    }

    private static PluginConfig.DatabaseSettings parseDatabase(ConfigurationSection sec, String file) {
        String type = requireString(sec, "type", file);
        String dbFile = requireString(sec, "file", file);
        int poolSize = requirePositive(requireInt(sec, "pool-size", file), "database.pool-size", file);
        return new PluginConfig.DatabaseSettings(type, dbFile, poolSize);
    }

    private static PluginConfig.WorldSettings parseWorld(ConfigurationSection sec, String file) {
        return new PluginConfig.WorldSettings(
                requireString(sec, "end-world-name", file),
                requireBoolean(sec, "replace-first-dragon", file),
                requireBoolean(sec, "preserve-vanilla-progression", file)
        );
    }

    private record ParsedGeometry(
            RegionConfig.Shape shape,
            RegionConfig.Point3D center,
            double radius,
            RegionConfig.Point3D min,
            RegionConfig.Point3D max,
            int minY,
            int maxY
    ) {
    }

    private static RegionConfig parseRegions(ConfigurationSection sec, String file) {
        ParsedGeometry arenaGeo = parseGeometry(
                requireSection(sec, "arena", file),
                "regions.arena",
                new RegionConfig.Point3D(0.0, 64.0, 0.0),
                file);
        var arena = new RegionConfig.ArenaRegion(
                arenaGeo.shape(), arenaGeo.center(), arenaGeo.radius(),
                arenaGeo.min(), arenaGeo.max(), arenaGeo.minY(), arenaGeo.maxY());

        var safeSec = requireSection(sec, "safe-zone", file);
        ParsedGeometry safeGeo = parseGeometry(
                safeSec,
                "regions.safe-zone",
                new RegionConfig.Point3D(100.5, 50.0, 0.5),
                file);
        var spawnSec = requireSection(safeSec, "spawn-point", file);
        var spawnPoint = new RegionConfig.SpawnPoint(
                requireDouble(spawnSec, "x", file),
                requireDouble(spawnSec, "y", file),
                requireDouble(spawnSec, "z", file),
                (float) requireDouble(spawnSec, "yaw", file),
                (float) requireDouble(spawnSec, "pitch", file)
        );
        var safeZone = new RegionConfig.SafeZoneRegion(
                safeGeo.shape(), safeGeo.center(), safeGeo.radius(),
                safeGeo.min(), safeGeo.max(), safeGeo.minY(), safeGeo.maxY(), spawnPoint);

        double voidY = requireDouble(sec, "void-y-threshold", file);
        RegionConfig regions = new RegionConfig(arena, safeZone, voidY);
        validateRegionConfig(regions, file);
        return regions;
    }

    private static ParsedGeometry parseGeometry(
            ConfigurationSection sec,
            String basePath,
            RegionConfig.Point3D fallbackCenter,
            String file) {
        RegionConfig.Shape shape = parseEnum(
                RegionConfig.Shape.class, requireString(sec, "shape", file), basePath + ".shape", file);
        double radius = shape == RegionConfig.Shape.CYLINDER
                ? requirePositive(requireDouble(sec, "radius", file), basePath + ".radius", file)
                : Math.max(0.0, sec.getDouble("radius", 0.0));

        boolean hasExplicitCenter = sec.isConfigurationSection("center");
        RegionConfig.Point3D center = hasExplicitCenter
                ? parsePoint3D(requireSection(sec, "center", file), file)
                : fallbackCenter;

        RegionConfig.Point3D min = sec.isConfigurationSection("min")
                ? parsePoint3D(requireSection(sec, "min", file), file)
                : new RegionConfig.Point3D(center.x() - radius, 0.0, center.z() - radius);
        RegionConfig.Point3D max = sec.isConfigurationSection("max")
                ? parsePoint3D(requireSection(sec, "max", file), file)
                : new RegionConfig.Point3D(center.x() + radius, 256.0, center.z() + radius);

        if (!hasExplicitCenter && shape == RegionConfig.Shape.CUBOID) {
            center = new RegionConfig.Point3D(
                    (min.x() + max.x()) / 2.0,
                    (min.y() + max.y()) / 2.0,
                    (min.z() + max.z()) / 2.0);
        }

        int minY = sec.isInt("min-y") ? sec.getInt("min-y") : (int) Math.floor(min.y());
        int maxY = sec.isInt("max-y") ? sec.getInt("max-y") : (int) Math.ceil(max.y());
        return new ParsedGeometry(shape, center, radius, min, max, minY, maxY);
    }

    static void validateRegionConfig(RegionConfig regions, String file) {
        if (regions == null || regions.arena() == null || regions.safeZone() == null) {
            throw new ConfigException(file, "regions", "RegionConfig, arena, and safeZone must not be null");
        }
        if (!Double.isFinite(regions.voidYThreshold())) {
            throw new ConfigException(file, "regions.void-y-threshold", "voidYThreshold must be finite");
        }

        var arena = regions.arena();
        validateGeometry(
                arena.shape(), arena.center(), arena.radius(),
                arena.min(), arena.max(), arena.minY(), arena.maxY(),
                "regions.arena", file);

        var safe = regions.safeZone();
        validateGeometry(
                safe.shape(), safe.center(), safe.radius(),
                safe.min(), safe.max(), safe.minY(), safe.maxY(),
                "regions.safe-zone", file);
        var spawn = safe.spawnPoint();
        if (spawn == null
                || !Double.isFinite(spawn.x())
                || !Double.isFinite(spawn.y())
                || !Double.isFinite(spawn.z())
                || !Float.isFinite(spawn.yaw())
                || !Float.isFinite(spawn.pitch())) {
            throw new ConfigException(file, "regions.safe-zone.spawn-point", "Spawn point coordinates must be finite");
        }
    }

    private static void validateGeometry(
            RegionConfig.Shape shape,
            RegionConfig.Point3D center,
            double radius,
            RegionConfig.Point3D min,
            RegionConfig.Point3D max,
            int minY,
            int maxY,
            String path,
            String file) {
        if (shape == null || center == null || min == null || max == null) {
            throw new ConfigException(file, path, "Shape, center, min, and max must not be null");
        }
        validateFinitePoint(center, path + ".center", file);
        validateFinitePoint(min, path + ".min", file);
        validateFinitePoint(max, path + ".max", file);
        if (minY > maxY) {
            throw new ConfigException(file, path, "min-y must be <= max-y");
        }
        if (shape == RegionConfig.Shape.CYLINDER) {
            requirePositive(radius, path + ".radius", file);
        } else if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new ConfigException(file, path, "Cuboid min coordinates must be <= max coordinates");
        }
    }

    private static void validateFinitePoint(RegionConfig.Point3D point, String path, String file) {
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())) {
            throw new ConfigException(file, path, "Point coordinates must be finite");
        }
    }

    private static RegionConfig.Point3D parsePoint3D(ConfigurationSection sec, String file) {
        return new RegionConfig.Point3D(
                requireDouble(sec, "x", file),
                requireDouble(sec, "y", file),
                requireDouble(sec, "z", file)
        );
    }

    private static PluginConfig.ScalingSettings parseScaling(ConfigurationSection sec, String file) {
        int minPlayers = requirePositive(requireInt(sec, "min-players", file), "scaling.min-players", file);
        int maxPlayers = requireInt(sec, "max-players", file);
        if (maxPlayers > 0 && maxPlayers < minPlayers) {
            throw new ConfigException(file, "scaling.max-players", "max-players must be >= min-players");
        }
        boolean enforceMax = requireBoolean(sec, "enforce-max-players", file);
        double hpAlpha = requireNonNegative(requireDouble(sec, "hp-alpha", file), "scaling.hp-alpha", file);
        double objAlpha = requireNonNegative(requireDouble(sec, "object-hp-alpha", file), "scaling.object-hp-alpha", file);

        var gimSec = requireSection(sec, "gimmick", file);
        int towerMin = requirePositive(requireInt(gimSec, "tower-min", file), "scaling.gimmick.tower-min", file);
        int towerMax = requirePositive(requireInt(gimSec, "tower-max", file), "scaling.gimmick.tower-max", file);
        if (towerMin > towerMax) {
            throw new ConfigException(file, "scaling.gimmick", "tower-min must be <= tower-max");
        }
        int spreadMin = requirePositive(requireInt(gimSec, "spread-min", file), "scaling.gimmick.spread-min", file);
        int spreadMax = requirePositive(requireInt(gimSec, "spread-max", file), "scaling.gimmick.spread-max", file);
        if (spreadMin > spreadMax) {
            throw new ConfigException(file, "scaling.gimmick", "spread-min must be <= spread-max");
        }
        int crystalBase = requirePositive(requireInt(gimSec, "crystal-base", file), "scaling.gimmick.crystal-base", file);
        int crystalMax = requirePositive(requireInt(gimSec, "crystal-max", file), "scaling.gimmick.crystal-max", file);
        if (crystalBase > crystalMax) {
            throw new ConfigException(file, "scaling.gimmick", "crystal-base must be <= crystal-max");
        }
        var gimmick = new PluginConfig.GimmickScaling(
                requireNonNegative(requireInt(gimSec, "tower-base", file), "scaling.gimmick.tower-base", file),
                requireNonNegative(requireDouble(gimSec, "tower-per-players", file), "scaling.gimmick.tower-per-players", file),
                towerMin,
                towerMax,
                requireUnitRatio(requireDouble(gimSec, "spread-ratio", file), "scaling.gimmick.spread-ratio", file),
                spreadMin,
                spreadMax,
                crystalBase,
                requireNonNegative(requireDouble(gimSec, "crystal-per-additional-player", file), "scaling.gimmick.crystal-per-additional-player", file),
                crystalMax
        );

        var soloSec = requireSection(sec, "solo-fallback", file);
        var solo = new PluginConfig.SoloFallbackSettings(
                requireBoolean(soloSec, "enabled", file),
                requirePositive(requireDouble(soloSec, "stack-damage-multiplier", file), "scaling.solo-fallback.stack-damage-multiplier", file),
                requireNonNegative(requireDouble(soloSec, "simultaneous-crystal-grace-seconds", file), "scaling.solo-fallback.simultaneous-crystal-grace-seconds", file),
                requireBoolean(soloSec, "allow-vanilla-totem", file)
        );

        return new PluginConfig.ScalingSettings(minPlayers, maxPlayers, enforceMax, hpAlpha, objAlpha, gimmick, solo);
    }

    private static PluginConfig.EncounterSettings parseEncounter(ConfigurationSection sec, String file) {
        int countdown = requireNonNegative(requireInt(sec, "pull-countdown-seconds", file), "encounter.pull-countdown-seconds", file);
        boolean autoStart = requireBoolean(sec, "auto-start-on-arena-enter", file);
        boolean altarOnly = requireBoolean(sec, "altar-gui-only", file);
        boolean lockLate = requireBoolean(sec, "lock-late-joiners-to-safe-zone", file);

        var altarSec = requireSection(sec, "altar", file);
        var location = parsePoint3D(requireSection(altarSec, "location", file), file);
        double interactRadius = requirePositive(requireDouble(altarSec, "interact-radius", file), "encounter.altar.interact-radius", file);
        boolean unlockHard = requireBoolean(altarSec, "unlock-hard-after-normal-clear", file);
        var costsSec = requireSection(altarSec, "summon-costs", file);
        var normalCosts = parseItemCosts(costsSec, "normal", file);
        var hardCosts = parseItemCosts(costsSec, "hard", file);

        var altar = new PluginConfig.AltarSettings(location, interactRadius, unlockHard, normalCosts, hardCosts);
        return new PluginConfig.EncounterSettings(countdown, autoStart, altarOnly, lockLate, altar);
    }

    private static List<PluginConfig.ItemCost> parseItemCosts(ConfigurationSection sec, String key, String file) {
        List<Map<?, ?>> rawList = sec.getMapList(key);
        List<PluginConfig.ItemCost> result = new ArrayList<>(rawList.size());
        String basePath = fullPath(sec, key);
        for (int i = 0; i < rawList.size(); i++) {
            Map<?, ?> item = rawList.get(i);
            String mat = mapString(item, "material", basePath + "[" + i + "]", file);
            int amount = requirePositive(mapInt(item, "amount", basePath + "[" + i + "]", file), basePath + "[" + i + "].amount", file);
            result.add(new PluginConfig.ItemCost(mat, amount));
        }
        return List.copyOf(result);
    }

    private static PluginConfig.ReviveSettings parseRevive(ConfigurationSection sec, String file) {
        return new PluginConfig.ReviveSettings(
                requirePositive(requireDouble(sec, "channel-seconds", file), "revive.channel-seconds", file),
                requirePositive(requireDouble(sec, "interact-radius", file), "revive.interact-radius", file),
                requireBoolean(sec, "require-sneak", file),
                requireBoolean(sec, "allow-right-click-hold", file),
                requireNonNegative(requireDouble(sec, "invulnerability-seconds", file), "revive.invulnerability-seconds", file),
                requireUnitRatio(requireDouble(sec, "heal-percent", file), "revive.heal-percent", file),
                requireNonNegative(requireDouble(sec, "void-rescue-y-offset", file), "revive.void-rescue-y-offset", file),
                requireNonNegative(requireInt(sec, "wipe-delay-seconds", file), "revive.wipe-delay-seconds", file),
                requireBoolean(sec, "wipe-if-all-leave-arena", file)
        );
    }

    private static PluginConfig.ProtectionSettings parseProtection(ConfigurationSection sec, String file) {
        return new PluginConfig.ProtectionSettings(
                requireBoolean(sec, "keep-inventory-on-raid-death", file),
                requireBoolean(sec, "keep-experience-on-raid-death", file),
                requireBoolean(sec, "disable-bed-and-anchor-explosions", file),
                requireBoolean(sec, "protect-original-arena-terrain", file),
                requireBoolean(sec, "allow-temporary-blocks", file),
                requireBoolean(sec, "allow-water-buckets", file),
                requireBoolean(sec, "allow-ender-pearls", file),
                requireBoolean(sec, "allow-wind-charges", file),
                requireNonNegative(requireInt(sec, "max-temporary-blocks-per-player", file), "protection.max-temporary-blocks-per-player", file),
                requireNonNegative(requireInt(sec, "max-temporary-blocks-total", file), "protection.max-temporary-blocks-total", file),
                requireBoolean(sec, "rollback-temporary-blocks-on-end", file)
        );
    }

    private static PluginConfig.VisualSettings parseVisuals(ConfigurationSection sec, String file) {
        return new PluginConfig.VisualSettings(
                requireBoolean(sec, "use-packet-events", file),
                requirePositive(requireInt(sec, "aoe-update-interval-ticks", file), "visuals.aoe-update-interval-ticks", file),
                requireBoolean(sec, "soul-marker-glow", file),
                requirePositive(requireDouble(sec, "tether-particle-step", file), "visuals.tether-particle-step", file),
                requirePositive(requireDouble(sec, "max-render-distance", file), "visuals.max-render-distance", file)
        );
    }

    private static BossConfig.BossReviveSettings parseBossRevive(ConfigurationSection sec, String file) {
        var formula = parseEnum(BossConfig.ReviveFormula.class, requireString(sec, "formula", file), "revive.formula", file);
        double multiplier = requireNonNegative(requireDouble(sec, "multiplier", file), "revive.multiplier", file);
        int timeout = requireNonNegative(requireInt(sec, "downed-timeout-seconds", file), "revive.downed-timeout-seconds", file);
        return new BossConfig.BossReviveSettings(formula, multiplier, timeout);
    }

    private static BossConfig.EnrageSettings parseEnrage(ConfigurationSection sec, String file) {
        var softSec = requireSection(sec, "soft-enrage", file);
        var soft = new BossConfig.SoftEnrageSettings(
                requireBoolean(softSec, "enabled", file),
                requireString(softSec, "start-phase", file),
                requireNonNegative(requireDouble(softSec, "void-corruption-shrink-per-second", file), "enrage.soft-enrage.void-corruption-shrink-per-second", file),
                requireNonNegative(requireDouble(softSec, "void-damage-per-second", file), "enrage.soft-enrage.void-damage-per-second", file)
        );

        var hardSec = requireSection(sec, "hard-enrage", file);
        var hard = new BossConfig.HardEnrageSettings(
                requireBoolean(hardSec, "enabled", file),
                requirePositive(requireInt(hardSec, "time-limit-seconds", file), "enrage.hard-enrage.time-limit-seconds", file),
                requireString(hardSec, "ability-name-key", file),
                requirePositive(requireDouble(hardSec, "cast-seconds", file), "enrage.hard-enrage.cast-seconds", file)
        );
        return new BossConfig.EnrageSettings(soft, hard);
    }

    private static Map<String, BossConfig.AbilitySettings> parseAbilities(ConfigurationSection sec, String file) {
        Map<String, BossConfig.AbilitySettings> result = new LinkedHashMap<>();
        for (String id : sec.getKeys(false)) {
            ConfigurationSection abSec = requireSection(sec, id, file);
            String nameKey = requireString(abSec, "name-key", file);
            Map<String, Object> params = new LinkedHashMap<>();
            for (String key : abSec.getKeys(false)) {
                if (!"name-key".equals(key)) {
                    Object val = abSec.get(key);
                    if (val != null) {
                        params.put(key, val);
                    }
                }
            }
            result.put(id, new BossConfig.AbilitySettings(id, nameKey, params));
        }
        if (result.isEmpty()) {
            throw new ConfigException(file, "abilities", "At least one ability must be configured");
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<BossConfig.PhaseConfig> parsePhases(
            YamlConfiguration root, Map<String, BossConfig.AbilitySettings> abilities, String file) {
        List<Map<?, ?>> rawPhases = requireMapList(root, "phases", file);
        List<BossConfig.PhaseConfig> phases = new ArrayList<>(rawPhases.size());

        for (int i = 0; i < rawPhases.size(); i++) {
            Map<?, ?> raw = rawPhases.get(i);
            String pPath = "phases[" + i + "]";
            String id = mapString(raw, "id", pPath, file);
            String nameKey = mapString(raw, "name-key", pPath, file);
            double maxHp = requireUnitRatio(mapDouble(raw, "hp-threshold-max", pPath, file), pPath + ".hp-threshold-max", file);
            double minHp = requireUnitRatio(mapDouble(raw, "hp-threshold-min", pPath, file), pPath + ".hp-threshold-min", file);
            if (maxHp <= minHp) {
                throw new ConfigException(file, pPath, "hp-threshold-max must be > hp-threshold-min");
            }
            double speed = requirePositive(mapDouble(raw, "timeline-speed", pPath, file), pPath + ".timeline-speed", file);
            boolean softEnrage = raw.get("soft-enrage") instanceof Boolean b && b;

            Object rawTimelineObj = raw.get("timeline");
            if (!(rawTimelineObj instanceof List<?> rawTimeline) || rawTimeline.isEmpty()) {
                throw new ConfigException(file, pPath + ".timeline", "Phase timeline must be a non-empty list");
            }
            List<BossConfig.TimelineStep> steps = new ArrayList<>(rawTimeline.size());
            for (int j = 0; j < rawTimeline.size(); j++) {
                if (!(rawTimeline.get(j) instanceof Map<?, ?> stepMap)) {
                    throw new ConfigException(file, pPath + ".timeline[" + j + "]", "Timeline entry must be a map");
                }
                String sPath = pPath + ".timeline[" + j + "]";
                double delay = requireNonNegative(mapDouble(stepMap, "delay-seconds", sPath, file), sPath + ".delay-seconds", file);
                String action = mapString(stepMap, "action", sPath, file);
                Object abObj = stepMap.get("ability");
                String ability = abObj != null ? abObj.toString() : null;
                if (ability != null && !ability.isBlank() && !abilities.containsKey(ability)) {
                    throw new ConfigException(file, sPath + ".ability",
                            "Unknown ability reference '" + ability + "'");
                }
                steps.add(new BossConfig.TimelineStep(delay, action, ability));
            }

            phases.add(new BossConfig.PhaseConfig(id, nameKey, maxHp, minHp, speed, softEnrage, List.copyOf(steps)));
        }
        return List.copyOf(phases);
    }

    private static Map<String, RewardsConfig.CurrencyDefinition> parseCurrencies(ConfigurationSection sec, String file) {
        Map<String, RewardsConfig.CurrencyDefinition> map = new LinkedHashMap<>();
        for (String id : sec.getKeys(false)) {
            ConfigurationSection cSec = requireSection(sec, id, file);
            map.put(id, new RewardsConfig.CurrencyDefinition(
                    id,
                    requireString(cSec, "name-key", file),
                    requireString(cSec, "icon", file),
                    requirePositive(requireInt(cSec, "max-balance", file), "currencies." + id + ".max-balance", file)
            ));
        }
        if (map.isEmpty()) {
            throw new ConfigException(file, "currencies", "At least one currency must be defined");
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<BossConfig.Difficulty, RewardsConfig.DifficultyClearReward> parseClearRewards(
            ConfigurationSection sec, Map<String, RewardsConfig.CurrencyDefinition> currencies, String file) {
        Map<BossConfig.Difficulty, RewardsConfig.DifficultyClearReward> map = new EnumMap<>(BossConfig.Difficulty.class);
        for (BossConfig.Difficulty diff : BossConfig.Difficulty.values()) {
            String key = diff.name().toLowerCase(Locale.ROOT);
            ConfigurationSection dSec = requireSection(sec, key, file);
            Map<String, Integer> points = parseCurrencyAmounts(requireSection(dSec, "points", file), currencies, file);
            Map<String, Integer> firstBonus = parseCurrencyAmounts(requireSection(dSec, "first-clear-bonus", file), currencies, file);
            List<RewardsConfig.DropEntry> drops = parseDrops(dSec, "drops", file);
            map.put(diff, new RewardsConfig.DifficultyClearReward(points, firstBonus, drops));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Integer> parseCurrencyAmounts(
            ConfigurationSection sec, Map<String, RewardsConfig.CurrencyDefinition> currencies, String file) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String currencyId : sec.getKeys(false)) {
            if (!currencies.containsKey(currencyId)) {
                throw new ConfigException(file, fullPath(sec, currencyId), "Unknown currency '" + currencyId + "'");
            }
            int amount = requireNonNegative(requireInt(sec, currencyId, file), fullPath(sec, currencyId), file);
            result.put(currencyId, amount);
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<RewardsConfig.DropEntry> parseDrops(ConfigurationSection sec, String key, String file) {
        List<Map<?, ?>> rawList = sec.getMapList(key);
        List<RewardsConfig.DropEntry> drops = new ArrayList<>(rawList.size());
        String basePath = fullPath(sec, key);
        for (int i = 0; i < rawList.size(); i++) {
            Map<?, ?> raw = rawList.get(i);
            String dPath = basePath + "[" + i + "]";
            String type = mapString(raw, "type", dPath, file);
            String id = mapString(raw, "id", dPath, file);
            int level = raw.get("level") instanceof Number n ? n.intValue() : 1;
            double chance = requireUnitRatio(mapDouble(raw, "chance", dPath, file), dPath + ".chance", file);
            int minAmount = requirePositive(mapInt(raw, "min-amount", dPath, file), dPath + ".min-amount", file);
            int maxAmount = requirePositive(mapInt(raw, "max-amount", dPath, file), dPath + ".max-amount", file);
            if (minAmount > maxAmount) {
                throw new ConfigException(file, dPath, "min-amount must be <= max-amount");
            }
            boolean perPlayer = !(raw.get("per-player") instanceof Boolean b) || b;
            drops.add(new RewardsConfig.DropEntry(type, id, level, chance, minAmount, maxAmount, perPlayer));
        }
        return List.copyOf(drops);
    }

    private static Map<String, RewardsConfig.CustomGearDefinition> parseCustomGear(ConfigurationSection sec, String file) {
        Map<String, RewardsConfig.CustomGearDefinition> map = new LinkedHashMap<>();
        for (String id : sec.getKeys(false)) {
            ConfigurationSection gSec = requireSection(sec, id, file);
            Map<String, Double> stats = parseDoubleMap(requireSection(gSec, "stats", file), file);
            map.put(id, new RewardsConfig.CustomGearDefinition(
                    id,
                    requireString(gSec, "name-key", file),
                    requireString(gSec, "lore-key", file),
                    requireString(gSec, "material", file),
                    requireNonNegative(requireInt(gSec, "custom-model-data", file), "custom-gear." + id + ".custom-model-data", file),
                    stats
            ));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, RewardsConfig.CustomEnchantDefinition> parseCustomEnchants(ConfigurationSection sec, String file) {
        Map<String, RewardsConfig.CustomEnchantDefinition> map = new LinkedHashMap<>();
        for (String id : sec.getKeys(false)) {
            ConfigurationSection eSec = requireSection(sec, id, file);
            Map<String, Double> stats = new LinkedHashMap<>();
            for (String key : eSec.getKeys(false)) {
                if (!"name-key".equals(key) && !"lore-key".equals(key) && !"max-level".equals(key)) {
                    stats.put(key, requireDouble(eSec, key, file));
                }
            }
            map.put(id, new RewardsConfig.CustomEnchantDefinition(
                    id,
                    requireString(eSec, "name-key", file),
                    requireString(eSec, "lore-key", file),
                    requirePositive(requireInt(eSec, "max-level", file), "custom-enchants." + id + ".max-level", file),
                    Collections.unmodifiableMap(stats)
            ));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Double> parseDoubleMap(ConfigurationSection sec, String file) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            result.put(key, requireDouble(sec, key, file));
        }
        return Collections.unmodifiableMap(result);
    }

    private static RewardsConfig.ShopSettings parseShop(
            ConfigurationSection sec,
            Map<String, RewardsConfig.CurrencyDefinition> currencies,
            Map<String, RewardsConfig.CustomGearDefinition> gear,
            Map<String, RewardsConfig.CustomEnchantDefinition> enchants,
            String file) {
        String titleKey = requireString(sec, "title-key", file);
        int size = requireInt(sec, "size", file);
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new ConfigException(file, "shop.size", "Shop GUI size must be a multiple of 9 between 9 and 54");
        }

        List<Map<?, ?>> rawItems = requireMapList(sec, "items", file);
        List<RewardsConfig.ShopItemDefinition> items = new ArrayList<>(rawItems.size());
        for (int i = 0; i < rawItems.size(); i++) {
            Map<?, ?> raw = rawItems.get(i);
            String iPath = "shop.items[" + i + "]";
            String id = mapString(raw, "id", iPath, file);
            int slot = mapInt(raw, "slot", iPath, file);
            if (slot < 0 || slot >= size) {
                throw new ConfigException(file, iPath + ".slot", "Slot " + slot + " is outside [0, " + (size - 1) + "]");
            }
            String type = mapString(raw, "type", iPath, file);
            String target = mapString(raw, "target", iPath, file);
            validateShopTarget(type, target, gear, enchants, iPath, file);
            int level = raw.get("level") instanceof Number n ? n.intValue() : 1;
            int amount = requirePositive(mapInt(raw, "amount", iPath, file), iPath + ".amount", file);
            boolean requiresHard = raw.get("requires-hard-clear") instanceof Boolean b && b;

            Object rawCostsObj = raw.get("costs");
            if (!(rawCostsObj instanceof Map<?, ?> rawCosts) || rawCosts.isEmpty()) {
                throw new ConfigException(file, iPath + ".costs", "Shop item must specify at least one currency cost");
            }
            Map<String, Integer> costs = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawCosts.entrySet()) {
                String currencyId = String.valueOf(entry.getKey());
                if (!currencies.containsKey(currencyId)) {
                    throw new ConfigException(file, iPath + ".costs." + currencyId, "Unknown currency '" + currencyId + "'");
                }
                if (!(entry.getValue() instanceof Number num)) {
                    throw new ConfigException(file, iPath + ".costs." + currencyId, "Cost must be an integer");
                }
                int costVal = requirePositive(num.intValue(), iPath + ".costs." + currencyId, file);
                costs.put(currencyId, costVal);
            }

            items.add(new RewardsConfig.ShopItemDefinition(
                    id, slot, type, target, level, amount, requiresHard, Collections.unmodifiableMap(costs)));
        }

        return new RewardsConfig.ShopSettings(titleKey, size, List.copyOf(items));
    }

    private static void validateShopTarget(
            String type,
            String target,
            Map<String, RewardsConfig.CustomGearDefinition> gear,
            Map<String, RewardsConfig.CustomEnchantDefinition> enchants,
            String path,
            String file) {
        switch (type) {
            case "CUSTOM_GEAR" -> {
                if (!gear.containsKey(target)) {
                    throw new ConfigException(file, path + ".target", "Unknown custom gear '" + target + "'");
                }
            }
            case "CUSTOM_ENCHANT_BOOK" -> {
                if (!enchants.containsKey(target)) {
                    throw new ConfigException(file, path + ".target", "Unknown custom enchant '" + target + "'");
                }
            }
            case "VANILLA_ITEM" -> {
                // Validated at runtime against Bukkit Material registry
            }
            default -> throw new ConfigException(file, path + ".type", "Unsupported shop item type '" + type + "'");
        }
    }
}
