package io.github.ikafly144.dragonraid.config;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.NodePath;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Loads, validates, and serializes DragonRaid YAML configuration files via SpongePowered Configurate.
 */
final class YamlConfigLoader {

    private static final ThreadLocal<String> CURRENT_FILE = new ThreadLocal<>();

    static final TypeSerializerCollection SERIALIZERS = TypeSerializerCollection.defaults().childBuilder()
            .register(RegionConfig.class, new RegionConfigSerializer())
            .register(PluginConfig.AltarSettings.class, new AltarSettingsSerializer())
            .register(PluginConfig.class, new PluginConfigSerializer())
            .register(BossConfig.AbilitySettings.class, new AbilitySettingsSerializer())
            .register(BossConfig.class, new BossConfigSerializer())
            .register(RewardsConfig.class, new RewardsConfigSerializer())
            .build();

    private YamlConfigLoader() {
    }

    static YamlConfigurationLoader buildLoader(Path path) {
        return YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .defaultOptions(opts -> opts.serializers(SERIALIZERS))
                .build();
    }

    static CommentedConfigurationNode loadNode(Path path) {
        return loadNode(path, buildLoader(path));
    }

    static CommentedConfigurationNode loadNode(Path path, YamlConfigurationLoader loader) {
        String fileName = path.getFileName().toString();
        if (!Files.exists(path)) {
            throw new ConfigException(fileName, "", "Configuration file does not exist: " + path);
        }
        try {
            return loader.load();
        } catch (ConfigurateException ex) {
            throw new ConfigException(
                    fileName,
                    formatNodePath(ex.path()),
                    "Failed to parse YAML configuration: " + ex.getMessage(),
                    ex);
        }
    }

    static PluginConfig loadPluginConfig(Path path) {
        String fileName = path.getFileName().toString();
        CommentedConfigurationNode root = loadNode(path);
        return loadPluginConfig(root, fileName);
    }

    static PluginConfig loadPluginConfigFromString(String yamlContent, String fileName) {
        YamlConfigurationLoader.Builder stringBuilder = YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .defaultOptions(opts -> opts.serializers(SERIALIZERS));
        try {
            ConfigurationNode root = stringBuilder.buildAndLoadString(yamlContent);
            return loadPluginConfig(root, fileName);
        } catch (ConfigurateException ex) {
            throw new ConfigException(
                    fileName,
                    formatNodePath(ex.path()),
                    "Failed to parse YAML configuration: " + ex.getMessage(),
                    ex);
        }
    }

    private static PluginConfig loadPluginConfig(ConfigurationNode root, String fileName) {
        return withFileName(fileName, () -> getTyped(root, PluginConfig.class, fileName));
    }

    static BossConfig loadBossConfig(Path path, BossConfig.Difficulty expectedDifficulty) {
        String fileName = path.getFileName().toString();
        CommentedConfigurationNode root = loadNode(path);
        BossConfig config = withFileName(fileName, () -> getTyped(root, BossConfig.class, fileName));
        if (config.difficulty() != expectedDifficulty) {
            throw new ConfigException(
                    fileName,
                    "difficulty",
                    "Expected difficulty " + expectedDifficulty + " but found " + config.difficulty());
        }
        return config;
    }

    static RewardsConfig loadRewardsConfig(Path path) {
        String fileName = path.getFileName().toString();
        CommentedConfigurationNode root = loadNode(path);
        return withFileName(fileName, () -> getTyped(root, RewardsConfig.class, fileName));
    }

    /**
     * Renders an updated {@code config.yml} string containing the serialized {@link RegionConfig}
     * via Configurate while preserving existing YAML comments and non-region sections, without
     * modifying the file on disk.
     */
    static String renderRegionsPreservingComments(Path configPath, RegionConfig regions) {
        String fileName = configPath.getFileName().toString();
        if (!Files.exists(configPath)) {
            throw new ConfigException(fileName, "regions", "Configuration file does not exist: " + configPath);
        }
        try {
            String original = Files.readString(configPath, StandardCharsets.UTF_8);
            String lineSeparator = original.contains("\r\n") ? "\r\n" : "\n";

            YamlConfigurationLoader.Builder stringBuilder = YamlConfigurationLoader.builder()
                    .nodeStyle(NodeStyle.BLOCK)
                    .indent(2)
                    .defaultOptions(opts -> opts.serializers(SERIALIZERS));
            CommentedConfigurationNode wrapper = CommentedConfigurationNode.root(stringBuilder.defaultOptions());
            wrapper.node("regions").set(RegionConfig.class, regions);
            String renderedBlock = stringBuilder.buildAndSaveString(wrapper).stripTrailing();

            return spliceRegionsSection(original, renderedBlock, lineSeparator);
        } catch (IOException ex) {
            throw new ConfigException(fileName, "regions", "Failed to render updated regions", ex);
        }
    }

    private static String spliceRegionsSection(String original, String renderedBlock, String lineSeparator) {
        String[] rawLines = original.split("\r?\n", -1);
        int start = -1;
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (line.equals("regions:") || line.startsWith("regions: ")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            String trimmed = original.stripTrailing();
            return trimmed + lineSeparator + lineSeparator
                    + renderedBlock.replace("\n", lineSeparator) + lineSeparator;
        }

        int end = rawLines.length;
        for (int i = start + 1; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                end = i;
                break;
            }
        }
        while (end > start + 1 && rawLines[end - 1].isBlank()) {
            end--;
        }

        Map<String, List<String>> sectionComments = extractKeyComments(rawLines, start + 1, end);
        List<String> renderedWithComments = applyKeyComments(renderedBlock.split("\r?\n"), sectionComments);

        List<String> merged = new ArrayList<>(rawLines.length);
        for (int i = 0; i < start; i++) {
            merged.add(rawLines[i]);
        }
        merged.addAll(renderedWithComments);
        for (int i = end; i < rawLines.length; i++) {
            merged.add(rawLines[i]);
        }
        String joined = String.join(lineSeparator, merged);
        if (!joined.endsWith(lineSeparator)) {
            joined += lineSeparator;
        }
        return joined;
    }

    private static Map<String, List<String>> extractKeyComments(String[] lines, int from, int to) {
        Map<String, List<String>> commentsByPath = new LinkedHashMap<>();
        List<String> stack = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        List<String> pending = new ArrayList<>();

        for (int i = from; i < to; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (trimmed.startsWith("#")) {
                    pending.add(line);
                }
                continue;
            }
            int indent = line.length() - trimmed.length();
            String path = updatePathStack(trimmed, indent, indents, stack);
            if (path != null) {
                if (!pending.isEmpty()) {
                    commentsByPath.put(path, List.copyOf(pending));
                    pending.clear();
                }
            } else {
                pending.clear();
            }
        }
        return commentsByPath;
    }

    private static List<String> applyKeyComments(String[] renderedLines, Map<String, List<String>> commentsByPath) {
        if (commentsByPath.isEmpty()) {
            return List.of(renderedLines);
        }
        List<String> out = new ArrayList<>(renderedLines.length + commentsByPath.size());
        List<String> stack = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();

        for (String line : renderedLines) {
            String trimmed = line.stripLeading();
            int indent = line.length() - trimmed.length();
            if (indent > 0) {
                String path = updatePathStack(trimmed, indent, indents, stack);
                if (path != null) {
                    List<String> savedComments = commentsByPath.get(path);
                    if (savedComments != null) {
                        out.addAll(savedComments);
                    }
                }
            }
            out.add(line);
        }
        return out;
    }

    private static @Nullable String updatePathStack(
            String trimmedLine, int indent, List<Integer> indents, List<String> stack) {
        int colon = trimmedLine.indexOf(':');
        if (colon <= 0 || trimmedLine.startsWith("-")) {
            return null;
        }
        String key = trimmedLine.substring(0, colon).trim();
        while (!indents.isEmpty() && indents.getLast() >= indent) {
            indents.removeLast();
            stack.removeLast();
        }
        indents.add(indent);
        stack.add(key);
        return String.join(".", stack);
    }

    static void validateRegionConfig(RegionConfig regions, String fileName) {
        Objects.requireNonNull(regions, "regions");
        if (!Double.isFinite(regions.voidYThreshold())) {
            throw new ConfigException(fileName, "regions.void-y-threshold", "void-y-threshold must be finite");
        }
        RegionConfig.ArenaRegion arena = regions.arena();
        if (arena == null) {
            throw new ConfigException(fileName, "regions.arena", "Missing arena region");
        }
        validateGeometry(
                arena.shape(), arena.center(), arena.radius(), arena.min(), arena.max(),
                arena.minY(), arena.maxY(), fileName, "regions.arena");

        RegionConfig.SafeZoneRegion safeZone = regions.safeZone();
        if (safeZone == null) {
            throw new ConfigException(fileName, "regions.safe-zone", "Missing safe-zone region");
        }
        validateGeometry(
                safeZone.shape(), safeZone.center(), safeZone.radius(), safeZone.min(), safeZone.max(),
                safeZone.minY(), safeZone.maxY(), fileName, "regions.safe-zone");
        if (safeZone.spawnPoint() == null) {
            throw new ConfigException(fileName, "regions.safe-zone.spawn-point", "Missing safe-zone spawn-point");
        }
        validateSpawnPoint(safeZone.spawnPoint(), fileName, "regions.safe-zone.spawn-point");
    }

    private static <T> T getTyped(ConfigurationNode node, Class<T> type, String fileName) {
        try {
            T result = node.get(type);
            if (result == null) {
                throw new ConfigException(fileName, ConfigValidation.fullPath(node), "Missing " + type.getSimpleName());
            }
            return result;
        } catch (SerializationException ex) {
            throw new ConfigException(
                    fileName,
                    formatNodePath(ex.path()),
                    "Failed to deserialize " + type.getSimpleName() + ": " + ex.getMessage(),
                    ex);
        }
    }

    private static <T> T withFileName(String fileName, Supplier<T> action) {
        String previous = CURRENT_FILE.get();
        CURRENT_FILE.set(fileName);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_FILE.remove();
            } else {
                CURRENT_FILE.set(previous);
            }
        }
    }

    private static String activeFileName(String fallback) {
        String active = CURRENT_FILE.get();
        return active != null ? active : fallback;
    }

    private static String formatNodePath(@Nullable NodePath path) {
        if (path == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object segment : path) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    private static RegionConfig.Point3D readPoint3D(ConfigurationNode node, String fileName) {
        if (node.virtual() || !node.isMap()) {
            throw new ConfigException(fileName, ConfigValidation.fullPath(node), "Missing 3D coordinate section");
        }
        double x = ConfigValidation.requireDouble(node, fileName, "x");
        double y = ConfigValidation.requireDouble(node, fileName, "y");
        double z = ConfigValidation.requireDouble(node, fileName, "z");
        return new RegionConfig.Point3D(x, y, z);
    }

    private static void validatePoint3D(RegionConfig.Point3D point, String fileName, String path) {
        if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())) {
            throw new ConfigException(fileName, path, "Point coordinates must be finite");
        }
    }

    private static void validateSpawnPoint(RegionConfig.SpawnPoint sp, String fileName, String path) {
        if (!Double.isFinite(sp.x()) || !Double.isFinite(sp.y()) || !Double.isFinite(sp.z())
                || !Float.isFinite(sp.yaw()) || !Float.isFinite(sp.pitch())) {
            throw new ConfigException(fileName, path, "Spawn-point coordinates and angles must be finite");
        }
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

    private static ParsedGeometry readGeometry(ConfigurationNode node, String fileName) {
        String shapeRaw = ConfigValidation.requireString(node, fileName, "shape");
        RegionConfig.Shape shape = ConfigValidation.parseEnum(
                RegionConfig.Shape.class, shapeRaw, fileName, ConfigValidation.fullPath(node.node("shape")));

        RegionConfig.Point3D center = null;
        double radius = 0.0;
        RegionConfig.Point3D min = null;
        RegionConfig.Point3D max = null;
        int minY;
        int maxY;

        if (shape == RegionConfig.Shape.CYLINDER) {
            center = readPoint3D(ConfigValidation.requireNode(node, fileName, "center"), fileName);
            radius = ConfigValidation.requirePositive(
                    ConfigValidation.requireDouble(node, fileName, "radius"),
                    fileName,
                    ConfigValidation.fullPath(node.node("radius")));
            minY = ConfigValidation.requireInt(node, fileName, "min-y");
            maxY = ConfigValidation.requireInt(node, fileName, "max-y");
            min = !node.node("min").virtual()
                    ? readPoint3D(node.node("min"), fileName)
                    : new RegionConfig.Point3D(center.x() - radius, minY, center.z() - radius);
            max = !node.node("max").virtual()
                    ? readPoint3D(node.node("max"), fileName)
                    : new RegionConfig.Point3D(center.x() + radius, maxY, center.z() + radius);
        } else {
            min = readPoint3D(ConfigValidation.requireNode(node, fileName, "min"), fileName);
            max = readPoint3D(ConfigValidation.requireNode(node, fileName, "max"), fileName);
            if (!node.node("center").virtual()) {
                center = readPoint3D(node.node("center"), fileName);
            } else {
                center = new RegionConfig.Point3D(
                        (min.x() + max.x()) / 2.0,
                        (min.y() + max.y()) / 2.0,
                        (min.z() + max.z()) / 2.0);
            }
            if (!node.node("radius").virtual()) {
                radius = ConfigValidation.requireNonNegative(
                        ConfigValidation.requireDouble(node, fileName, "radius"),
                        fileName,
                        ConfigValidation.fullPath(node.node("radius")));
            }
            minY = node.node("min-y").virtual()
                    ? (int) Math.floor(min.y())
                    : ConfigValidation.requireInt(node, fileName, "min-y");
            maxY = node.node("max-y").virtual()
                    ? (int) Math.ceil(max.y())
                    : ConfigValidation.requireInt(node, fileName, "max-y");
        }

        validateGeometry(shape, center, radius, min, max, minY, maxY, fileName, ConfigValidation.fullPath(node));
        return new ParsedGeometry(shape, center, radius, min, max, minY, maxY);
    }

    private static void validateGeometry(
            RegionConfig.Shape shape,
            RegionConfig.Point3D center,
            double radius,
            RegionConfig.Point3D min,
            RegionConfig.Point3D max,
            int minY,
            int maxY,
            String fileName,
            String prefix) {
        if (shape == null) {
            throw new ConfigException(fileName, ConfigValidation.fullPath(prefix, "shape"), "Missing region shape");
        }
        if (shape == RegionConfig.Shape.CYLINDER) {
            validatePoint3D(center, fileName, ConfigValidation.fullPath(prefix, "center"));
            ConfigValidation.requirePositive(radius, fileName, ConfigValidation.fullPath(prefix, "radius"));
            if (minY > maxY) {
                throw new ConfigException(
                        fileName,
                        ConfigValidation.fullPath(prefix, "min-y"),
                        "min-y (" + minY + ") must be <= max-y (" + maxY + ")");
            }
        } else {
            validatePoint3D(min, fileName, ConfigValidation.fullPath(prefix, "min"));
            validatePoint3D(max, fileName, ConfigValidation.fullPath(prefix, "max"));
            if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
                throw new ConfigException(
                        fileName,
                        ConfigValidation.fullPath(prefix, "min"),
                        "Cuboid min coordinates must be <= max coordinates");
            }
            if (center != null) {
                validatePoint3D(center, fileName, ConfigValidation.fullPath(prefix, "center"));
            }
            ConfigValidation.requireNonNegative(radius, fileName, ConfigValidation.fullPath(prefix, "radius"));
            if (minY > maxY) {
                throw new ConfigException(
                        fileName,
                        ConfigValidation.fullPath(prefix, "min-y"),
                        "min-y (" + minY + ") must be <= max-y (" + maxY + ")");
            }
        }
    }

    /**
     * Writes region geometry in place without wiping parent nodes so existing YAML comments remain intact.
     */
    private static void writeGeometry(
            ConfigurationNode node,
            RegionConfig.Shape shape,
            RegionConfig.Point3D center,
            double radius,
            RegionConfig.Point3D min,
            RegionConfig.Point3D max,
            int minY,
            int maxY) throws SerializationException {
        node.node("shape").set(shape.name());
        if (shape == RegionConfig.Shape.CYLINDER) {
            writePoint3D(node.node("center"), center);
            node.node("radius").set(radius);
            if (min != null) {
                writePoint3D(node.node("min"), min);
            } else {
                node.removeChild("min");
            }
            if (max != null) {
                writePoint3D(node.node("max"), max);
            } else {
                node.removeChild("max");
            }
            node.node("min-y").set(minY);
            node.node("max-y").set(maxY);
        } else {
            writePoint3D(node.node("min"), min);
            writePoint3D(node.node("max"), max);
            node.removeChild("center");
            node.removeChild("radius");
            node.removeChild("min-y");
            node.removeChild("max-y");
        }
    }

    private static void writePoint3D(ConfigurationNode node, RegionConfig.Point3D point) throws SerializationException {
        node.node("x").set(point.x());
        node.node("y").set(point.y());
        node.node("z").set(point.z());
    }

    private static final class RegionConfigSerializer implements TypeSerializer<RegionConfig> {

        @Override
        public RegionConfig deserialize(Type type, ConfigurationNode node) {
            String fileName = activeFileName(ConfigManager.CONFIG_FILE);
            if (node.virtual() || !node.isMap()) {
                throw new ConfigException(fileName, ConfigValidation.fullPath(node), "Missing regions section");
            }
            ConfigurationNode arenaNode = ConfigValidation.requireNode(node, fileName, "arena");
            ParsedGeometry arenaGeo = readGeometry(arenaNode, fileName);
            RegionConfig.ArenaRegion arena = new RegionConfig.ArenaRegion(
                    arenaGeo.shape(), arenaGeo.center(), arenaGeo.radius(),
                    arenaGeo.min(), arenaGeo.max(), arenaGeo.minY(), arenaGeo.maxY());

            ConfigurationNode safeNode = ConfigValidation.requireNode(node, fileName, "safe-zone");
            ParsedGeometry safeGeo = readGeometry(safeNode, fileName);
            ConfigurationNode spNode = ConfigValidation.requireNode(safeNode, fileName, "spawn-point");
            RegionConfig.SpawnPoint spawnPoint = new RegionConfig.SpawnPoint(
                    ConfigValidation.requireDouble(spNode, fileName, "x"),
                    ConfigValidation.requireDouble(spNode, fileName, "y"),
                    ConfigValidation.requireDouble(spNode, fileName, "z"),
                    (float) ConfigValidation.requireDouble(spNode, fileName, "yaw"),
                    (float) ConfigValidation.requireDouble(spNode, fileName, "pitch"));
            RegionConfig.SafeZoneRegion safeZone = new RegionConfig.SafeZoneRegion(
                    safeGeo.shape(), safeGeo.center(), safeGeo.radius(),
                    safeGeo.min(), safeGeo.max(), safeGeo.minY(), safeGeo.maxY(), spawnPoint);

            double voidYThreshold = ConfigValidation.requireDouble(node, fileName, "void-y-threshold");
            return new RegionConfig(arena, safeZone, voidYThreshold);
        }

        @Override
        public void serialize(Type type, @Nullable RegionConfig regions, ConfigurationNode node)
                throws SerializationException {
            if (regions == null) {
                return;
            }
            RegionConfig.ArenaRegion arena = regions.arena();
            writeGeometry(
                    node.node("arena"),
                    arena.shape(), arena.center(), arena.radius(),
                    arena.min(), arena.max(), arena.minY(), arena.maxY());

            RegionConfig.SafeZoneRegion safeZone = regions.safeZone();
            ConfigurationNode safeNode = node.node("safe-zone");
            writeGeometry(
                    safeNode,
                    safeZone.shape(), safeZone.center(), safeZone.radius(),
                    safeZone.min(), safeZone.max(), safeZone.minY(), safeZone.maxY());

            RegionConfig.SpawnPoint sp = safeZone.spawnPoint();
            ConfigurationNode spNode = safeNode.node("spawn-point");
            spNode.node("x").set(sp.x());
            spNode.node("y").set(sp.y());
            spNode.node("z").set(sp.z());
            spNode.node("yaw").set(sp.yaw());
            spNode.node("pitch").set(sp.pitch());

            node.node("void-y-threshold").set(regions.voidYThreshold());
        }
    }

    private static final class AltarSettingsSerializer implements TypeSerializer<PluginConfig.AltarSettings> {

        @Override
        public PluginConfig.AltarSettings deserialize(Type type, ConfigurationNode node) {
            String fileName = activeFileName(ConfigManager.CONFIG_FILE);
            if (node.virtual() || !node.isMap()) {
                throw new ConfigException(fileName, ConfigValidation.fullPath(node), "Missing altar section");
            }
            RegionConfig.Point3D location = readPoint3D(
                    ConfigValidation.requireNode(node, fileName, "location"), fileName);
            double interactRadius = ConfigValidation.requirePositive(
                    ConfigValidation.requireDouble(node, fileName, "interact-radius"),
                    fileName,
                    ConfigValidation.fullPath(node.node("interact-radius")));
            boolean unlockHard = ConfigValidation.requireBoolean(node, fileName, "unlock-hard-after-normal-clear");

            ConfigurationNode costsNode = ConfigValidation.requireNode(node, fileName, "summon-costs");
            List<PluginConfig.ItemCost> normalCosts = readItemCosts(costsNode, fileName, "normal");
            List<PluginConfig.ItemCost> hardCosts = readItemCosts(costsNode, fileName, "hard");
            return new PluginConfig.AltarSettings(location, interactRadius, unlockHard, normalCosts, hardCosts);
        }

        private List<PluginConfig.ItemCost> readItemCosts(
                ConfigurationNode costsNode, String fileName, String key) {
            List<? extends ConfigurationNode> items = ConfigValidation.requireList(costsNode, fileName, key);
            List<PluginConfig.ItemCost> parsed = new ArrayList<>(items.size());
            for (ConfigurationNode item : items) {
                if (!item.isMap()) {
                    throw new ConfigException(fileName, ConfigValidation.fullPath(item), "Item cost entry must be a map");
                }
                String material = ConfigValidation.requireString(item, fileName, "material");
                int amount = ConfigValidation.requirePositive(
                        ConfigValidation.requireInt(item, fileName, "amount"),
                        fileName,
                        ConfigValidation.fullPath(item.node("amount")));
                parsed.add(new PluginConfig.ItemCost(material, amount));
            }
            return List.copyOf(parsed);
        }

        @Override
        public void serialize(Type type, PluginConfig.@Nullable AltarSettings obj, ConfigurationNode node)
                throws SerializationException {
            throw new SerializationException(node, type, "AltarSettings serialization is not supported");
        }
    }

    private static final class PluginConfigSerializer implements TypeSerializer<PluginConfig> {

        @Override
        public PluginConfig deserialize(Type type, ConfigurationNode root) throws SerializationException {
            String fileName = activeFileName(ConfigManager.CONFIG_FILE);
            PluginConfig.LanguageSettings language = readLanguage(root, fileName);
            PluginConfig.DatabaseSettings database = readDatabase(root, fileName);
            PluginConfig.WorldSettings world = readWorld(root, fileName);
            RegionConfig regions = root.node("regions").get(RegionConfig.class);
            if (regions == null) {
                throw new ConfigException(fileName, "regions", "Missing regions section");
            }
            PluginConfig.ScalingSettings scaling = readScaling(root, fileName);
            PluginConfig.EncounterSettings encounter = readEncounter(root, fileName);
            PluginConfig.ReviveSettings revive = readRevive(root, fileName);
            PluginConfig.ProtectionSettings protection = readProtection(root, fileName);
            PluginConfig.VisualSettings visuals = readVisuals(root, fileName);
            return new PluginConfig(language, database, world, regions, scaling, encounter, revive, protection, visuals);
        }

        private PluginConfig.LanguageSettings readLanguage(ConfigurationNode root, String fileName) {
            ConfigurationNode lang = ConfigValidation.requireNode(root, fileName, "language");
            String defaultLocale = ConfigValidation.requireString(lang, fileName, "default");
            boolean perPlayer = ConfigValidation.requireBoolean(lang, fileName, "per-player-locale");
            List<String> supported = ConfigValidation.requireStringList(lang, fileName, "supported");
            if (supported.isEmpty()) {
                throw new ConfigException(fileName, "language.supported", "Supported locales list cannot be empty");
            }
            if (!supported.contains(defaultLocale)) {
                throw new ConfigException(
                        fileName,
                        "language.default",
                        "Default locale '" + defaultLocale + "' is missing from language.supported");
            }
            return new PluginConfig.LanguageSettings(defaultLocale, perPlayer, supported);
        }

        private PluginConfig.DatabaseSettings readDatabase(ConfigurationNode root, String fileName) {
            ConfigurationNode db = ConfigValidation.requireNode(root, fileName, "database");
            String dbType = ConfigValidation.requireString(db, fileName, "type");
            if (!"SQLITE".equalsIgnoreCase(dbType)) {
                throw new ConfigException(fileName, "database.type", "Unsupported database type: " + dbType);
            }
            String file = ConfigValidation.requireString(db, fileName, "file");
            int poolSize = ConfigValidation.requirePositive(
                    ConfigValidation.requireInt(db, fileName, "pool-size"), fileName, "database.pool-size");
            return new PluginConfig.DatabaseSettings(dbType, file, poolSize);
        }

        private PluginConfig.WorldSettings readWorld(ConfigurationNode root, String fileName) {
            ConfigurationNode world = ConfigValidation.requireNode(root, fileName, "world");
            return new PluginConfig.WorldSettings(
                    ConfigValidation.requireString(world, fileName, "end-world-name"),
                    ConfigValidation.requireBoolean(world, fileName, "replace-first-dragon"),
                    ConfigValidation.requireBoolean(world, fileName, "preserve-vanilla-progression"));
        }

        private PluginConfig.ScalingSettings readScaling(ConfigurationNode root, String fileName) {
            ConfigurationNode scaling = ConfigValidation.requireNode(root, fileName, "scaling");
            int minPlayers = ConfigValidation.requirePositive(
                    ConfigValidation.requireInt(scaling, fileName, "min-players"), fileName, "scaling.min-players");
            int maxPlayers = ConfigValidation.requirePositive(
                    ConfigValidation.requireInt(scaling, fileName, "max-players"), fileName, "scaling.max-players");
            if (maxPlayers < minPlayers) {
                throw new ConfigException(
                        fileName,
                        "scaling.max-players",
                        "max-players (" + maxPlayers + ") must be >= min-players (" + minPlayers + ")");
            }
            boolean enforceMax = ConfigValidation.requireBoolean(scaling, fileName, "enforce-max-players");
            double hpAlpha = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireDouble(scaling, fileName, "hp-alpha"), fileName, "scaling.hp-alpha");
            double objectHpAlpha = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireDouble(scaling, fileName, "object-hp-alpha"),
                    fileName,
                    "scaling.object-hp-alpha");

            ConfigurationNode gimmickNode = ConfigValidation.requireNode(scaling, fileName, "gimmick");
            int towerBase = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireInt(gimmickNode, fileName, "tower-base"),
                    fileName, "scaling.gimmick.tower-base");
            double towerPerPlayers = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireDouble(gimmickNode, fileName, "tower-per-players"),
                    fileName, "scaling.gimmick.tower-per-players");
            int towerMin = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireInt(gimmickNode, fileName, "tower-min"),
                    fileName, "scaling.gimmick.tower-min");
            int towerMax = ConfigValidation.requirePositive(
                    ConfigValidation.requireInt(gimmickNode, fileName, "tower-max"),
                    fileName, "scaling.gimmick.tower-max");
            if (towerMax < towerMin) {
                throw new ConfigException(fileName, "scaling.gimmick.tower-max", "tower-max must be >= tower-min");
            }
            double spreadRatio = ConfigValidation.requireUnitRatio(
                    ConfigValidation.requireDouble(gimmickNode, fileName, "spread-ratio"),
                    fileName, "scaling.gimmick.spread-ratio");
            int spreadMin = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireInt(gimmickNode, fileName, "spread-min"),
                    fileName, "scaling.gimmick.spread-min");
            int spreadMax = ConfigValidation.requirePositive(
                    ConfigValidation.requireInt(gimmickNode, fileName, "spread-max"),
                    fileName, "scaling.gimmick.spread-max");
            if (spreadMax < spreadMin) {
                throw new ConfigException(fileName, "scaling.gimmick.spread-max", "spread-max must be >= spread-min");
            }
            int crystalBase = ConfigValidation.requirePositive(
                    ConfigValidation.requireInt(gimmickNode, fileName, "crystal-base"),
                    fileName, "scaling.gimmick.crystal-base");
            double crystalPerAdd = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireDouble(gimmickNode, fileName, "crystal-per-additional-player"),
                    fileName, "scaling.gimmick.crystal-per-additional-player");
            int crystalMax = ConfigValidation.requirePositive(
                    ConfigValidation.requireInt(gimmickNode, fileName, "crystal-max"),
                    fileName, "scaling.gimmick.crystal-max");
            if (crystalMax < crystalBase) {
                throw new ConfigException(
                        fileName, "scaling.gimmick.crystal-max", "crystal-max must be >= crystal-base");
            }
            PluginConfig.GimmickScaling gimmick = new PluginConfig.GimmickScaling(
                    towerBase, towerPerPlayers, towerMin, towerMax,
                    spreadRatio, spreadMin, spreadMax,
                    crystalBase, crystalPerAdd, crystalMax);

            ConfigurationNode soloNode = ConfigValidation.requireNode(scaling, fileName, "solo-fallback");
            PluginConfig.SoloFallbackSettings solo = new PluginConfig.SoloFallbackSettings(
                    ConfigValidation.requireBoolean(soloNode, fileName, "enabled"),
                    ConfigValidation.requirePositive(
                            ConfigValidation.requireDouble(soloNode, fileName, "stack-damage-multiplier"),
                            fileName, "scaling.solo-fallback.stack-damage-multiplier"),
                    ConfigValidation.requireNonNegative(
                            ConfigValidation.requireDouble(soloNode, fileName, "simultaneous-crystal-grace-seconds"),
                            fileName, "scaling.solo-fallback.simultaneous-crystal-grace-seconds"),
                    ConfigValidation.requireBoolean(soloNode, fileName, "allow-vanilla-totem"));

            return new PluginConfig.ScalingSettings(
                    minPlayers, maxPlayers, enforceMax, hpAlpha, objectHpAlpha, gimmick, solo);
        }

        private PluginConfig.EncounterSettings readEncounter(ConfigurationNode root, String fileName)
                throws SerializationException {
            ConfigurationNode enc = ConfigValidation.requireNode(root, fileName, "encounter");
            int pullCountdown = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireInt(enc, fileName, "pull-countdown-seconds"),
                    fileName, "encounter.pull-countdown-seconds");
            boolean autoStart = ConfigValidation.requireBoolean(enc, fileName, "auto-start-on-arena-enter");
            boolean altarGuiOnly = ConfigValidation.requireBoolean(enc, fileName, "altar-gui-only");
            boolean lockLate = ConfigValidation.requireBoolean(enc, fileName, "lock-late-joiners-to-safe-zone");
            PluginConfig.AltarSettings altar = enc.node("altar").get(PluginConfig.AltarSettings.class);
            if (altar == null) {
                throw new ConfigException(fileName, "encounter.altar", "Missing altar section");
            }
            return new PluginConfig.EncounterSettings(pullCountdown, autoStart, altarGuiOnly, lockLate, altar);
        }

        private PluginConfig.ReviveSettings readRevive(ConfigurationNode root, String fileName) {
            ConfigurationNode rev = ConfigValidation.requireNode(root, fileName, "revive");
            double channelSec = ConfigValidation.requirePositive(
                    ConfigValidation.requireDouble(rev, fileName, "channel-seconds"),
                    fileName, "revive.channel-seconds");
            double interactRad = ConfigValidation.requirePositive(
                    ConfigValidation.requireDouble(rev, fileName, "interact-radius"),
                    fileName, "revive.interact-radius");
            boolean requireSneak = ConfigValidation.requireBoolean(rev, fileName, "require-sneak");
            boolean allowRightClick = ConfigValidation.requireBoolean(rev, fileName, "allow-right-click-hold");
            double invulSec = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireDouble(rev, fileName, "invulnerability-seconds"),
                    fileName, "revive.invulnerability-seconds");
            double healPct = ConfigValidation.requireUnitRatio(
                    ConfigValidation.requirePositive(
                            ConfigValidation.requireDouble(rev, fileName, "heal-percent"),
                            fileName, "revive.heal-percent"),
                    fileName, "revive.heal-percent");
            double voidRescueOffset = ConfigValidation.requireDouble(rev, fileName, "void-rescue-y-offset");
            int wipeDelay = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireInt(rev, fileName, "wipe-delay-seconds"),
                    fileName, "revive.wipe-delay-seconds");
            boolean wipeAllLeave = ConfigValidation.requireBoolean(rev, fileName, "wipe-if-all-leave-arena");
            return new PluginConfig.ReviveSettings(
                    channelSec, interactRad, requireSneak, allowRightClick,
                    invulSec, healPct, voidRescueOffset, wipeDelay, wipeAllLeave);
        }

        private PluginConfig.ProtectionSettings readProtection(ConfigurationNode root, String fileName) {
            ConfigurationNode prot = ConfigValidation.requireNode(root, fileName, "protection");
            int maxPerPlayer = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireInt(prot, fileName, "max-temporary-blocks-per-player"),
                    fileName, "protection.max-temporary-blocks-per-player");
            int maxTotal = ConfigValidation.requireNonNegative(
                    ConfigValidation.requireInt(prot, fileName, "max-temporary-blocks-total"),
                    fileName, "protection.max-temporary-blocks-total");
            if (maxTotal < maxPerPlayer) {
                throw new ConfigException(
                        fileName,
                        "protection.max-temporary-blocks-total",
                        "max-temporary-blocks-total must be >= max-temporary-blocks-per-player");
            }
            return new PluginConfig.ProtectionSettings(
                    ConfigValidation.requireBoolean(prot, fileName, "keep-inventory-on-raid-death"),
                    ConfigValidation.requireBoolean(prot, fileName, "keep-experience-on-raid-death"),
                    ConfigValidation.requireBoolean(prot, fileName, "disable-bed-and-anchor-explosions"),
                    ConfigValidation.requireBoolean(prot, fileName, "protect-original-arena-terrain"),
                    ConfigValidation.requireBoolean(prot, fileName, "allow-temporary-blocks"),
                    ConfigValidation.requireBoolean(prot, fileName, "allow-water-buckets"),
                    ConfigValidation.requireBoolean(prot, fileName, "allow-ender-pearls"),
                    ConfigValidation.requireBoolean(prot, fileName, "allow-wind-charges"),
                    maxPerPlayer,
                    maxTotal,
                    ConfigValidation.requireBoolean(prot, fileName, "rollback-temporary-blocks-on-end"));
        }

        private PluginConfig.VisualSettings readVisuals(ConfigurationNode root, String fileName) {
            ConfigurationNode vis = ConfigValidation.requireNode(root, fileName, "visuals");
            return new PluginConfig.VisualSettings(
                    ConfigValidation.requireBoolean(vis, fileName, "use-packet-events"),
                    ConfigValidation.requirePositive(
                            ConfigValidation.requireInt(vis, fileName, "aoe-update-interval-ticks"),
                            fileName, "visuals.aoe-update-interval-ticks"),
                    ConfigValidation.requireBoolean(vis, fileName, "soul-marker-glow"),
                    ConfigValidation.requirePositive(
                            ConfigValidation.requireDouble(vis, fileName, "tether-particle-step"),
                            fileName, "visuals.tether-particle-step"),
                    ConfigValidation.requirePositive(
                            ConfigValidation.requireDouble(vis, fileName, "max-render-distance"),
                            fileName, "visuals.max-render-distance"));
        }

        @Override
        public void serialize(Type type, @Nullable PluginConfig obj, ConfigurationNode node)
                throws SerializationException {
            throw new SerializationException(node, type, "PluginConfig full serialization is not supported");
        }
    }

    private static final class AbilitySettingsSerializer implements TypeSerializer<BossConfig.AbilitySettings> {

        @Override
        public BossConfig.AbilitySettings deserialize(Type type, ConfigurationNode node) {
            String fileName = activeFileName("boss.yml");
            if (node.virtual() || !node.isMap()) {
                throw new ConfigException(fileName, ConfigValidation.fullPath(node), "Missing ability definition");
            }
            String id = String.valueOf(node.key());
            String nameKey = ConfigValidation.requireString(node, fileName, "name-key");
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey());
                if ("name-key".equals(key)) {
                    continue;
                }
                Object value = entry.getValue().raw();
                if (value != null) {
                    parameters.put(key, value);
                }
            }
            return new BossConfig.AbilitySettings(id, nameKey, parameters);
        }

        @Override
        public void serialize(Type type, BossConfig.@Nullable AbilitySettings obj, ConfigurationNode node)
                throws SerializationException {
            throw new SerializationException(node, type, "AbilitySettings serialization is not supported");
        }
    }

    private static final class BossConfigSerializer implements TypeSerializer<BossConfig> {

        @Override
        public BossConfig deserialize(Type type, ConfigurationNode root) throws SerializationException {
            String fileName = activeFileName("boss.yml");
            BossConfig.Difficulty difficulty = ConfigValidation.parseEnum(
                    BossConfig.Difficulty.class,
                    ConfigValidation.requireString(root, fileName, "difficulty"),
                    fileName,
                    "difficulty");
            String displayNameKey = ConfigValidation.requireString(root, fileName, "display-name-key");
            double baseHp = ConfigValidation.requirePositive(
                    ConfigValidation.requireDouble(root, fileName, "base-hp"), fileName, "base-hp");
            double baseAttack = ConfigValidation.requirePositive(
                    ConfigValidation.requireDouble(root, fileName, "base-attack-damage"),
                    fileName, "base-attack-damage");

            ConfigurationNode revNode = ConfigValidation.requireNode(root, fileName, "revive");
            BossConfig.ReviveFormula formula = ConfigValidation.parseEnum(
                    BossConfig.ReviveFormula.class,
                    ConfigValidation.requireString(revNode, fileName, "formula"),
                    fileName,
                    "revive.formula");
            BossConfig.BossReviveSettings revive = new BossConfig.BossReviveSettings(
                    formula,
                    ConfigValidation.requireNonNegative(
                            ConfigValidation.requireDouble(revNode, fileName, "multiplier"),
                            fileName, "revive.multiplier"),
                    ConfigValidation.requireNonNegative(
                            ConfigValidation.requireInt(revNode, fileName, "downed-timeout-seconds"),
                            fileName, "revive.downed-timeout-seconds"));

            BossConfig.EnrageSettings enrage = readEnrage(root, fileName);

            ConfigurationNode abilitiesNode = ConfigValidation.requireNode(root, fileName, "abilities");
            Map<String, BossConfig.AbilitySettings> abilities = new LinkedHashMap<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : abilitiesNode.childrenMap().entrySet()) {
                String id = String.valueOf(entry.getKey());
                BossConfig.AbilitySettings ability = entry.getValue().get(BossConfig.AbilitySettings.class);
                if (ability == null) {
                    throw new ConfigException(fileName, ConfigValidation.fullPath(entry.getValue()), "Invalid ability");
                }
                abilities.put(id, ability);
            }
            if (abilities.isEmpty()) {
                throw new ConfigException(fileName, "abilities", "Boss must define at least one ability");
            }

            List<BossConfig.PhaseConfig> phases = readPhases(root, fileName, abilities);
            if (enrage.softEnrage().enabled()) {
                String startPhase = enrage.softEnrage().startPhase();
                boolean found = phases.stream().anyMatch(p -> p.id().equals(startPhase));
                if (!found) {
                    throw new ConfigException(
                            fileName,
                            "enrage.soft-enrage.start-phase",
                            "Soft enrage start-phase '" + startPhase + "' does not exist in phases");
                }
            }

            return new BossConfig(
                    difficulty,
                    displayNameKey,
                    baseHp,
                    baseAttack,
                    revive,
                    enrage,
                    Collections.unmodifiableMap(abilities),
                    List.copyOf(phases));
        }

        private BossConfig.EnrageSettings readEnrage(ConfigurationNode root, String fileName) {
            ConfigurationNode enrageNode = ConfigValidation.requireNode(root, fileName, "enrage");
            ConfigurationNode softNode = ConfigValidation.requireNode(enrageNode, fileName, "soft-enrage");
            BossConfig.SoftEnrageSettings soft = new BossConfig.SoftEnrageSettings(
                    ConfigValidation.requireBoolean(softNode, fileName, "enabled"),
                    ConfigValidation.requireString(softNode, fileName, "start-phase"),
                    ConfigValidation.requireNonNegative(
                            ConfigValidation.requireDouble(softNode, fileName, "void-corruption-shrink-per-second"),
                            fileName, "enrage.soft-enrage.void-corruption-shrink-per-second"),
                    ConfigValidation.requireNonNegative(
                            ConfigValidation.requireDouble(softNode, fileName, "void-damage-per-second"),
                            fileName, "enrage.soft-enrage.void-damage-per-second"));

            ConfigurationNode hardNode = ConfigValidation.requireNode(enrageNode, fileName, "hard-enrage");
            boolean hardEnabled = ConfigValidation.requireBoolean(hardNode, fileName, "enabled");
            int timeLimit = ConfigValidation.requireInt(hardNode, fileName, "time-limit-seconds");
            double castSeconds = ConfigValidation.requireDouble(hardNode, fileName, "cast-seconds");
            if (hardEnabled) {
                ConfigValidation.requirePositive(timeLimit, fileName, "enrage.hard-enrage.time-limit-seconds");
                ConfigValidation.requirePositive(castSeconds, fileName, "enrage.hard-enrage.cast-seconds");
            } else {
                ConfigValidation.requireNonNegative(timeLimit, fileName, "enrage.hard-enrage.time-limit-seconds");
                ConfigValidation.requireNonNegative(castSeconds, fileName, "enrage.hard-enrage.cast-seconds");
            }
            BossConfig.HardEnrageSettings hard = new BossConfig.HardEnrageSettings(
                    hardEnabled,
                    timeLimit,
                    ConfigValidation.requireString(hardNode, fileName, "ability-name-key"),
                    castSeconds);
            return new BossConfig.EnrageSettings(soft, hard);
        }

        private List<BossConfig.PhaseConfig> readPhases(
                ConfigurationNode root, String fileName, Map<String, BossConfig.AbilitySettings> abilities) {
            List<? extends ConfigurationNode> phaseNodes = ConfigValidation.requireList(root, fileName, "phases");
            if (phaseNodes.isEmpty()) {
                throw new ConfigException(fileName, "phases", "Boss must define at least one phase");
            }
            List<BossConfig.PhaseConfig> phases = new ArrayList<>(phaseNodes.size());
            Set<String> seenIds = new HashSet<>();
            for (ConfigurationNode phaseNode : phaseNodes) {
                if (!phaseNode.isMap()) {
                    throw new ConfigException(
                            fileName, ConfigValidation.fullPath(phaseNode), "Phase entry must be a map");
                }
                String id = ConfigValidation.requireString(phaseNode, fileName, "id");
                if (!seenIds.add(id)) {
                    throw new ConfigException(
                            fileName, ConfigValidation.fullPath(phaseNode.node("id")), "Duplicate phase id: " + id);
                }
                String nameKey = ConfigValidation.requireString(phaseNode, fileName, "name-key");
                double hpMax = ConfigValidation.requireUnitRatio(
                        ConfigValidation.requireDouble(phaseNode, fileName, "hp-threshold-max"),
                        fileName, ConfigValidation.fullPath(phaseNode.node("hp-threshold-max")));
                double hpMin = ConfigValidation.requireUnitRatio(
                        ConfigValidation.requireDouble(phaseNode, fileName, "hp-threshold-min"),
                        fileName, ConfigValidation.fullPath(phaseNode.node("hp-threshold-min")));
                if (hpMin > hpMax) {
                    throw new ConfigException(
                            fileName,
                            ConfigValidation.fullPath(phaseNode.node("hp-threshold-min")),
                            "hp-threshold-min (" + hpMin + ") must be <= hp-threshold-max (" + hpMax + ")");
                }
                double timelineSpeed = ConfigValidation.requirePositive(
                        ConfigValidation.requireDouble(phaseNode, fileName, "timeline-speed"),
                        fileName, ConfigValidation.fullPath(phaseNode.node("timeline-speed")));
                boolean softEnrage = ConfigValidation.requireBoolean(phaseNode, fileName, "soft-enrage");

                List<? extends ConfigurationNode> stepNodes =
                        ConfigValidation.requireList(phaseNode, fileName, "timeline");
                if (stepNodes.isEmpty()) {
                    throw new ConfigException(
                            fileName,
                            ConfigValidation.fullPath(phaseNode.node("timeline")),
                            "Phase timeline cannot be empty");
                }
                List<BossConfig.TimelineStep> timeline = new ArrayList<>(stepNodes.size());
                for (ConfigurationNode stepNode : stepNodes) {
                    if (!stepNode.isMap()) {
                        throw new ConfigException(
                                fileName, ConfigValidation.fullPath(stepNode), "Timeline step must be a map");
                    }
                    double delay = ConfigValidation.requireNonNegative(
                            ConfigValidation.requireDouble(stepNode, fileName, "delay-seconds"),
                            fileName, ConfigValidation.fullPath(stepNode.node("delay-seconds")));
                    String action = ConfigValidation.requireString(stepNode, fileName, "action");
                    ConfigurationNode abilityNode = stepNode.node("ability");
                    String ability = null;
                    if (!abilityNode.virtual()) {
                        ability = ConfigValidation.requireString(abilityNode, fileName);
                        if (!abilities.containsKey(ability)) {
                            throw new ConfigException(
                                    fileName,
                                    ConfigValidation.fullPath(abilityNode),
                                    "Unknown ability referenced in timeline: " + ability);
                        }
                    }
                    timeline.add(new BossConfig.TimelineStep(delay, action, ability));
                }
                phases.add(new BossConfig.PhaseConfig(
                        id, nameKey, hpMax, hpMin, timelineSpeed, softEnrage, List.copyOf(timeline)));
            }
            return phases;
        }

        @Override
        public void serialize(Type type, @Nullable BossConfig obj, ConfigurationNode node)
                throws SerializationException {
            throw new SerializationException(node, type, "BossConfig serialization is not supported");
        }
    }

    private static final class RewardsConfigSerializer implements TypeSerializer<RewardsConfig> {

        @Override
        public RewardsConfig deserialize(Type type, ConfigurationNode root) {
            String fileName = activeFileName(ConfigManager.REWARDS_FILE);
            Map<String, RewardsConfig.CurrencyDefinition> currencies = readCurrencies(root, fileName);
            Map<String, RewardsConfig.CustomGearDefinition> customGear = readCustomGear(root, fileName);
            Map<String, RewardsConfig.CustomEnchantDefinition> customEnchants = readCustomEnchants(root, fileName);
            Map<BossConfig.Difficulty, RewardsConfig.DifficultyClearReward> clearRewards =
                    readClearRewards(root, fileName, currencies);
            RewardsConfig.ShopSettings shop = readShop(root, fileName, currencies, customGear, customEnchants);
            return new RewardsConfig(
                    Collections.unmodifiableMap(currencies),
                    Collections.unmodifiableMap(clearRewards),
                    Collections.unmodifiableMap(customGear),
                    Collections.unmodifiableMap(customEnchants),
                    shop);
        }

        private Map<String, RewardsConfig.CurrencyDefinition> readCurrencies(
                ConfigurationNode root, String fileName) {
            ConfigurationNode node = ConfigValidation.requireNode(root, fileName, "currencies");
            Map<String, RewardsConfig.CurrencyDefinition> result = new LinkedHashMap<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                String id = String.valueOf(entry.getKey());
                ConfigurationNode child = entry.getValue();
                result.put(id, new RewardsConfig.CurrencyDefinition(
                        id,
                        ConfigValidation.requireString(child, fileName, "name-key"),
                        ConfigValidation.requireString(child, fileName, "icon"),
                        ConfigValidation.requirePositive(
                                ConfigValidation.requireInt(child, fileName, "max-balance"),
                                fileName, ConfigValidation.fullPath(child.node("max-balance")))));
            }
            if (result.isEmpty()) {
                throw new ConfigException(fileName, "currencies", "At least one currency must be defined");
            }
            return result;
        }

        private Map<String, RewardsConfig.CustomGearDefinition> readCustomGear(
                ConfigurationNode root, String fileName) {
            ConfigurationNode node = ConfigValidation.requireNode(root, fileName, "custom-gear");
            Map<String, RewardsConfig.CustomGearDefinition> result = new LinkedHashMap<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                String id = String.valueOf(entry.getKey());
                ConfigurationNode child = entry.getValue();
                ConfigurationNode statsNode = ConfigValidation.requireNode(child, fileName, "stats");
                Map<String, Double> stats = new LinkedHashMap<>();
                for (Map.Entry<Object, ? extends ConfigurationNode> statEntry : statsNode.childrenMap().entrySet()) {
                    String statKey = String.valueOf(statEntry.getKey());
                    stats.put(statKey, ConfigValidation.requireDouble(statEntry.getValue(), fileName));
                }
                result.put(id, new RewardsConfig.CustomGearDefinition(
                        id,
                        ConfigValidation.requireString(child, fileName, "name-key"),
                        ConfigValidation.requireString(child, fileName, "lore-key"),
                        ConfigValidation.requireString(child, fileName, "material"),
                        ConfigValidation.requireNonNegative(
                                ConfigValidation.requireInt(child, fileName, "custom-model-data"),
                                fileName, ConfigValidation.fullPath(child.node("custom-model-data"))),
                        Collections.unmodifiableMap(stats)));
            }
            return result;
        }

        private Map<String, RewardsConfig.CustomEnchantDefinition> readCustomEnchants(
                ConfigurationNode root, String fileName) {
            ConfigurationNode node = ConfigValidation.requireNode(root, fileName, "custom-enchants");
            Map<String, RewardsConfig.CustomEnchantDefinition> result = new LinkedHashMap<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                String id = String.valueOf(entry.getKey());
                ConfigurationNode child = entry.getValue();
                Map<String, Double> stats = new LinkedHashMap<>();
                for (Map.Entry<Object, ? extends ConfigurationNode> statEntry : child.childrenMap().entrySet()) {
                    String key = String.valueOf(statEntry.getKey());
                    if ("name-key".equals(key) || "lore-key".equals(key) || "max-level".equals(key)) {
                        continue;
                    }
                    stats.put(key, ConfigValidation.requireDouble(statEntry.getValue(), fileName));
                }
                result.put(id, new RewardsConfig.CustomEnchantDefinition(
                        id,
                        ConfigValidation.requireString(child, fileName, "name-key"),
                        ConfigValidation.requireString(child, fileName, "lore-key"),
                        ConfigValidation.requirePositive(
                                ConfigValidation.requireInt(child, fileName, "max-level"),
                                fileName, ConfigValidation.fullPath(child.node("max-level"))),
                        Collections.unmodifiableMap(stats)));
            }
            return result;
        }

        private Map<BossConfig.Difficulty, RewardsConfig.DifficultyClearReward> readClearRewards(
                ConfigurationNode root,
                String fileName,
                Map<String, RewardsConfig.CurrencyDefinition> currencies) {
            ConfigurationNode clearNode = ConfigValidation.requireNode(root, fileName, "clear-rewards");
            Map<BossConfig.Difficulty, RewardsConfig.DifficultyClearReward> result =
                    new EnumMap<>(BossConfig.Difficulty.class);
            for (BossConfig.Difficulty diff : BossConfig.Difficulty.values()) {
                String key = diff.name().toLowerCase(java.util.Locale.ROOT);
                ConfigurationNode diffNode = ConfigValidation.requireNode(clearNode, fileName, key);
                Map<String, Integer> points = readCurrencyMap(
                        ConfigValidation.requireNode(diffNode, fileName, "points"), fileName, currencies);
                Map<String, Integer> firstClearBonus = readCurrencyMap(
                        ConfigValidation.requireNode(diffNode, fileName, "first-clear-bonus"), fileName, currencies);
                List<? extends ConfigurationNode> dropNodes =
                        ConfigValidation.requireList(diffNode, fileName, "drops");
                List<RewardsConfig.DropEntry> drops = new ArrayList<>(dropNodes.size());
                for (ConfigurationNode dropNode : dropNodes) {
                    if (!dropNode.isMap()) {
                        throw new ConfigException(
                                fileName, ConfigValidation.fullPath(dropNode), "Drop entry must be a map");
                    }
                    int level = dropNode.node("level").virtual()
                            ? 1
                            : ConfigValidation.requirePositive(
                                    ConfigValidation.requireInt(dropNode, fileName, "level"),
                                    fileName, ConfigValidation.fullPath(dropNode.node("level")));
                    double chance = ConfigValidation.requireUnitRatio(
                            ConfigValidation.requireDouble(dropNode, fileName, "chance"),
                            fileName, ConfigValidation.fullPath(dropNode.node("chance")));
                    int minAmount = ConfigValidation.requirePositive(
                            ConfigValidation.requireInt(dropNode, fileName, "min-amount"),
                            fileName, ConfigValidation.fullPath(dropNode.node("min-amount")));
                    int maxAmount = ConfigValidation.requirePositive(
                            ConfigValidation.requireInt(dropNode, fileName, "max-amount"),
                            fileName, ConfigValidation.fullPath(dropNode.node("max-amount")));
                    if (maxAmount < minAmount) {
                        throw new ConfigException(
                                fileName,
                                ConfigValidation.fullPath(dropNode.node("max-amount")),
                                "max-amount must be >= min-amount");
                    }
                    boolean perPlayer = dropNode.node("per-player").virtual()
                            || ConfigValidation.requireBoolean(dropNode, fileName, "per-player");
                    drops.add(new RewardsConfig.DropEntry(
                            ConfigValidation.requireString(dropNode, fileName, "type"),
                            ConfigValidation.requireString(dropNode, fileName, "id"),
                            level,
                            chance,
                            minAmount,
                            maxAmount,
                            perPlayer));
                }
                result.put(diff, new RewardsConfig.DifficultyClearReward(
                        points, firstClearBonus, List.copyOf(drops)));
            }
            return result;
        }

        private Map<String, Integer> readCurrencyMap(
                ConfigurationNode node,
                String fileName,
                Map<String, RewardsConfig.CurrencyDefinition> currencies) {
            Map<String, Integer> result = new LinkedHashMap<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                String currencyId = String.valueOf(entry.getKey());
                if (!currencies.containsKey(currencyId)) {
                    throw new ConfigException(
                            fileName,
                            ConfigValidation.fullPath(entry.getValue()),
                            "Unknown currency id: " + currencyId);
                }
                int amount = ConfigValidation.requireNonNegative(
                        ConfigValidation.requireInt(entry.getValue(), fileName),
                        fileName, ConfigValidation.fullPath(entry.getValue()));
                result.put(currencyId, amount);
            }
            return Collections.unmodifiableMap(result);
        }

        private RewardsConfig.ShopSettings readShop(
                ConfigurationNode root,
                String fileName,
                Map<String, RewardsConfig.CurrencyDefinition> currencies,
                Map<String, RewardsConfig.CustomGearDefinition> customGear,
                Map<String, RewardsConfig.CustomEnchantDefinition> customEnchants) {
            ConfigurationNode shopNode = ConfigValidation.requireNode(root, fileName, "shop");
            String titleKey = ConfigValidation.requireString(shopNode, fileName, "title-key");
            int size = ConfigValidation.requirePositive(
                    ConfigValidation.requireInt(shopNode, fileName, "size"), fileName, "shop.size");
            if (size % 9 != 0 || size > 54) {
                throw new ConfigException(fileName, "shop.size", "Shop size must be a positive multiple of 9 up to 54");
            }
            List<? extends ConfigurationNode> itemNodes = ConfigValidation.requireList(shopNode, fileName, "items");
            List<RewardsConfig.ShopItemDefinition> items = new ArrayList<>(itemNodes.size());
            Set<Integer> usedSlots = new HashSet<>();
            Set<String> usedIds = new HashSet<>();
            for (ConfigurationNode itemNode : itemNodes) {
                if (!itemNode.isMap()) {
                    throw new ConfigException(
                            fileName, ConfigValidation.fullPath(itemNode), "Shop item must be a map");
                }
                String id = ConfigValidation.requireString(itemNode, fileName, "id");
                if (!usedIds.add(id)) {
                    throw new ConfigException(
                            fileName, ConfigValidation.fullPath(itemNode.node("id")), "Duplicate shop item id: " + id);
                }
                int slot = ConfigValidation.requireInt(itemNode, fileName, "slot");
                if (slot < 0 || slot >= size) {
                    throw new ConfigException(
                            fileName,
                            ConfigValidation.fullPath(itemNode.node("slot")),
                            "Shop item slot " + slot + " is out of range [0, " + (size - 1) + "]");
                }
                if (!usedSlots.add(slot)) {
                    throw new ConfigException(
                            fileName, ConfigValidation.fullPath(itemNode.node("slot")), "Duplicate shop slot: " + slot);
                }
                String itemType = ConfigValidation.requireString(itemNode, fileName, "type");
                String target = ConfigValidation.requireString(itemNode, fileName, "target");
                int level = itemNode.node("level").virtual()
                        ? 1
                        : ConfigValidation.requirePositive(
                                ConfigValidation.requireInt(itemNode, fileName, "level"),
                                fileName, ConfigValidation.fullPath(itemNode.node("level")));
                int amount = ConfigValidation.requirePositive(
                        ConfigValidation.requireInt(itemNode, fileName, "amount"),
                        fileName, ConfigValidation.fullPath(itemNode.node("amount")));
                boolean requiresHard = !itemNode.node("requires-hard-clear").virtual()
                        && ConfigValidation.requireBoolean(itemNode, fileName, "requires-hard-clear");

                ConfigurationNode costsNode = ConfigValidation.requireNode(itemNode, fileName, "costs");
                Map<String, Integer> costs = readCurrencyMap(costsNode, fileName, currencies);
                if (costs.isEmpty()) {
                    throw new ConfigException(
                            fileName,
                            ConfigValidation.fullPath(costsNode),
                            "Shop item must define at least one cost");
                }
                validateShopTarget(
                        itemType, target, level, customGear, customEnchants,
                        fileName, ConfigValidation.fullPath(itemNode.node("target")));

                items.add(new RewardsConfig.ShopItemDefinition(
                        id, slot, itemType, target, level, amount, requiresHard, costs));
            }
            return new RewardsConfig.ShopSettings(titleKey, size, List.copyOf(items));
        }

        private void validateShopTarget(
                String itemType,
                String target,
                int level,
                Map<String, RewardsConfig.CustomGearDefinition> customGear,
                Map<String, RewardsConfig.CustomEnchantDefinition> customEnchants,
                String fileName,
                String path) {
            switch (itemType) {
                case "CUSTOM_GEAR" -> {
                    if (!customGear.containsKey(target)) {
                        throw new ConfigException(fileName, path, "Unknown custom-gear target: " + target);
                    }
                }
                case "CUSTOM_ENCHANT_BOOK" -> {
                    RewardsConfig.CustomEnchantDefinition enchant = customEnchants.get(target);
                    if (enchant == null) {
                        throw new ConfigException(fileName, path, "Unknown custom-enchant target: " + target);
                    }
                    if (level > enchant.maxLevel()) {
                        throw new ConfigException(
                                fileName, path, "Shop enchant level " + level + " exceeds max-level " + enchant.maxLevel());
                    }
                }
                default -> {
                    // Allow future/vanilla item types such as VANILLA_ITEM without rejecting custom-gear check.
                }
            }
        }

        @Override
        public void serialize(Type type, @Nullable RewardsConfig obj, ConfigurationNode node)
                throws SerializationException {
            throw new SerializationException(node, type, "RewardsConfig serialization is not supported");
        }
    }
}
