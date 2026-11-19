package net.sabafly.dragonraid.script.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.achievement.AchievementTriggerSpec;
import net.sabafly.dragonraid.api.achievement.AchievementTriggerType;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.pack.ContentPackManifest;
import net.sabafly.dragonraid.api.pack.LoadedContentPack;
import net.sabafly.dragonraid.api.progression.EndEntryGateMode;
import net.sabafly.dragonraid.api.progression.EndEntryGateSpec;
import net.sabafly.dragonraid.api.progression.VanillaClearHookSpec;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.reward.RewardConditionSpec;
import net.sabafly.dragonraid.api.reward.RewardConditionType;
import net.sabafly.dragonraid.api.reward.RewardEntrySpec;
import net.sabafly.dragonraid.api.reward.RewardEntryType;
import net.sabafly.dragonraid.api.reward.RewardFunctionSpec;
import net.sabafly.dragonraid.api.reward.RewardFunctionType;
import net.sabafly.dragonraid.api.reward.RewardMetricType;
import net.sabafly.dragonraid.api.reward.RewardPool;
import net.sabafly.dragonraid.api.reward.RewardPoolMode;
import net.sabafly.dragonraid.api.reward.RewardRoundingMode;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.RewardTableExtension;
import net.sabafly.dragonraid.api.reward.RollRange;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Parses and strictly validates a single {@code contents/<pack-id>/} directory.
 */
final class ContentPackYamlLoader {

    static final String PACK_MANIFEST_FILE = "pack.yml";

    private ContentPackYamlLoader() {
    }

    static LoadedContentPack loadPack(Path packDir) {
        Objects.requireNonNull(packDir, "packDir");
        String folderName = packDir.getFileName().toString();
        Path manifestFile = packDir.resolve(PACK_MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            throw new ContentPackException(folderName, manifestFile, "", "Missing pack.yml manifest");
        }

        ConfigurationNode manifestRoot = loadYaml(folderName, manifestFile);
        ContentPackManifest manifest = parseManifest(folderName, manifestFile, manifestRoot);
        if (!manifest.enabled()) {
            return new LoadedContentPack(
                    manifest,
                    packDir,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        String packId = manifest.id();
        List<ContentEntrySpec> contents = new ArrayList<>();
        for (Path file : listYamlFiles(packId, packDir.resolve("contents"))) {
            ConfigurationNode root = loadYaml(packId, file);
            for (ConfigurationNode node : extractEntries(packId, file, root, "contents")) {
                contents.add(parseContentEntry(packId, file, node));
            }
        }

        List<AchievementDefinition> achievements = new ArrayList<>();
        for (Path file : listYamlFiles(packId, packDir.resolve("achievements"))) {
            ConfigurationNode root = loadYaml(packId, file);
            for (ConfigurationNode node : extractEntries(packId, file, root, "achievements")) {
                achievements.add(parseAchievement(packId, file, node));
            }
        }

        List<RewardTable> rewardTables = new ArrayList<>();
        List<RewardTableExtension> rewardTableExtensions = new ArrayList<>();
        for (Path file : listYamlFiles(packId, packDir.resolve("rewards"))) {
            ConfigurationNode root = loadYaml(packId, file);
            loadRewardFile(packId, file, root, rewardTables, rewardTableExtensions);
        }

        List<VanillaProgressionRule> progressionRules = new ArrayList<>();
        Path rootProgression = packDir.resolve("progression.yml");
        if (Files.isRegularFile(rootProgression)) {
            ConfigurationNode root = loadYaml(packId, rootProgression);
            for (ConfigurationNode node : extractEntries(packId, rootProgression, root, "rules")) {
                progressionRules.add(parseProgressionRule(packId, rootProgression, node));
            }
        }
        for (Path file : listYamlFiles(packId, packDir.resolve("progression"))) {
            ConfigurationNode root = loadYaml(packId, file);
            for (ConfigurationNode node : extractEntries(packId, file, root, "rules")) {
                progressionRules.add(parseProgressionRule(packId, file, node));
            }
        }

        List<Path> enemyFiles = listAllRegularFiles(packId, packDir.resolve("enemies"));
        List<Path> itemFiles = listAllRegularFiles(packId, packDir.resolve("items"));
        List<Path> shopFiles = listAllRegularFiles(packId, packDir.resolve("shops"));
        List<Path> scriptFiles = listAllRegularFiles(packId, packDir.resolve("scripts"));

        return new LoadedContentPack(
                manifest,
                packDir,
                contents,
                achievements,
                rewardTables,
                rewardTableExtensions,
                progressionRules,
                enemyFiles,
                itemFiles,
                shopFiles,
                scriptFiles
        );
    }

    private static ContentPackManifest parseManifest(String folderName, Path file, ConfigurationNode root) {
        if (root.virtual() || !root.isMap()) {
            throw new ContentPackException(folderName, file, "", "pack.yml must be a YAML map");
        }
        String id = optionalString(folderName, file, root.node("id"), folderName);
        if (id.isBlank()) {
            throw new ContentPackException(folderName, file, "id", "Pack id must not be blank");
        }
        String name = optionalString(id, file, root.node("name"), id);
        String version = optionalString(id, file, root.node("version"), "1.0.0");
        boolean enabled = optionalBoolean(id, file, root.node("enabled"), true);
        int priority = optionalInt(id, file, root.node("priority"), 100);
        String description = optionalString(id, file, root.node("description"), "");
        try {
            return new ContentPackManifest(id, name, version, enabled, priority, description);
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(id, file, "", ex.getMessage(), ex);
        }
    }

    private static ContentEntrySpec parseContentEntry(String packId, Path file, ConfigurationNode node) {
        String id = requireNonBlankString(packId, file, node, "id");
        String displayNameKey = optionalString(packId, file, node.node("display-name-key"), id);
        String blueprintId = optionalString(packId, file, node.node("blueprint-id"), id);
        String unlockKey = optionalString(packId, file, node.node("required-unlock-key"), "");
        int minPlayers = optionalInt(packId, file, node.node("min-players"), 1);
        int maxPlayers = optionalInt(packId, file, node.node("max-players"), 8);
        int minIlvl = optionalInt(packId, file, node.node("min-average-item-level"), 0);
        List<String> clearRewards = parseStringList(packId, file, node.node("clear-rewards"));
        try {
            return new ContentEntrySpec(id, displayNameKey, blueprintId, unlockKey, minPlayers, maxPlayers, minIlvl, clearRewards);
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static AchievementDefinition parseAchievement(String packId, Path file, ConfigurationNode node) {
        String id = requireNonBlankString(packId, file, node, "id");
        String titleKey = optionalString(packId, file, node.node("title-key"), "ach." + id + ".title");
        String descriptionKey = optionalString(packId, file, node.node("description-key"), "ach." + id + ".desc");
        String icon = optionalString(packId, file, node.node("icon"), "NETHER_STAR");
        boolean hidden = optionalBoolean(packId, file, node.node("hidden"), false);
        Set<String> prerequisites = parseStringSet(packId, file, node.node("prerequisites"));
        List<String> rewards = parseStringList(packId, file, node.node("rewards"));

        List<AchievementTriggerSpec> triggers = new ArrayList<>();
        ConfigurationNode triggersNode = node.node("triggers");
        ConfigurationNode singleTriggerNode = node.node("trigger");
        if (!triggersNode.virtual()) {
            if (!triggersNode.isList()) {
                throw new ContentPackException(packId, file, nodePath(triggersNode), "'triggers' must be a list");
            }
            for (ConfigurationNode triggerNode : triggersNode.childrenList()) {
                triggers.add(parseAchievementTrigger(packId, file, triggerNode));
            }
        } else if (!singleTriggerNode.virtual()) {
            if (singleTriggerNode.isList()) {
                for (ConfigurationNode triggerNode : singleTriggerNode.childrenList()) {
                    triggers.add(parseAchievementTrigger(packId, file, triggerNode));
                }
            } else {
                triggers.add(parseAchievementTrigger(packId, file, singleTriggerNode));
            }
        }

        try {
            return new AchievementDefinition(id, packId, titleKey, descriptionKey, icon, hidden, prerequisites, triggers, rewards);
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static AchievementTriggerSpec parseAchievementTrigger(String packId, Path file, ConfigurationNode node) {
        if (!node.isMap()) {
            throw new ContentPackException(packId, file, nodePath(node), "Achievement trigger entry must be a map");
        }
        AchievementTriggerType type = requireEnum(packId, file, node, "type", AchievementTriggerType.class);
        String encounterId = optionalString(packId, file, node.node("encounter-id"), "");
        EncounterDifficulty difficulty = optionalEnum(packId, file, node.node("difficulty"), EncounterDifficulty.class);
        String worldName = optionalString(packId, file, node.node("world"), "");
        String mechanicId = optionalString(packId, file, node.node("mechanic-id"), "");
        String sourceEnemyId = optionalString(packId, file, node.node("source-enemy-id"), "");
        String customEventKey = optionalString(packId, file, node.node("custom-event-key"), "");
        String conditionHook = optionalString(packId, file, node.node("condition-hook"), "");

        long maxDurationMs = 0L;
        if (!node.node("max-duration-ms").virtual()) {
            maxDurationMs = optionalLong(packId, file, node.node("max-duration-ms"), 0L);
        } else if (!node.node("max-duration-seconds").virtual()) {
            long sec = optionalLong(packId, file, node.node("max-duration-seconds"), 0L);
            maxDurationMs = Math.multiplyExact(sec, 1000L);
        }

        int minPartySize = optionalInt(packId, file, node.node("min-party-size"), 0);
        int maxPartySize = optionalInt(packId, file, node.node("max-party-size"), 0);
        double minBossDamage = optionalDouble(packId, file, node.node("min-boss-damage"), 0.0);
        int minCrystals = optionalInt(packId, file, node.node("min-crystals-destroyed"), 0);
        int minRevives = optionalInt(packId, file, node.node("min-revives-completed"), 0);
        Map<String, Object> params = parseParamsMap(node.node("params"));

        try {
            return new AchievementTriggerSpec(
                    type,
                    encounterId,
                    difficulty,
                    worldName,
                    mechanicId,
                    sourceEnemyId,
                    customEventKey,
                    conditionHook,
                    maxDurationMs,
                    minPartySize,
                    maxPartySize,
                    minBossDamage,
                    minCrystals,
                    minRevives,
                    params
            );
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static void loadRewardFile(
            String packId,
            Path file,
            ConfigurationNode root,
            List<RewardTable> tablesOut,
            List<RewardTableExtension> extensionsOut) {
        if (root.virtual() || root.raw() == null) {
            return;
        }
        if (root.isList()) {
            for (ConfigurationNode item : root.childrenList()) {
                if (!item.node("inject-into").virtual()) {
                    extensionsOut.add(parseRewardExtension(packId, file, item));
                } else {
                    tablesOut.add(parseRewardTable(packId, file, item));
                }
            }
            return;
        }
        if (!root.isMap()) {
            throw new ContentPackException(packId, file, "", "Reward YAML root must be a map or list");
        }
        if (!root.node("rewards").virtual()) {
            throw new ContentPackException(
                    packId,
                    file,
                    "rewards",
                    "Legacy trigger-based 'rewards:' schema is not supported; use 'reward-tables:' or 'extensions:'"
            );
        }

        boolean hasSections = false;
        ConfigurationNode tablesNode = !root.node("reward-tables").virtual()
                ? root.node("reward-tables")
                : root.node("tables");
        if (!tablesNode.virtual()) {
            if (!tablesNode.isList()) {
                throw new ContentPackException(packId, file, nodePath(tablesNode), "'reward-tables' must be a list");
            }
            for (ConfigurationNode tableNode : tablesNode.childrenList()) {
                tablesOut.add(parseRewardTable(packId, file, tableNode));
            }
            hasSections = true;
        }

        ConfigurationNode extNode = root.node("extensions");
        if (!extNode.virtual()) {
            if (!extNode.isList()) {
                throw new ContentPackException(packId, file, "extensions", "'extensions' must be a list");
            }
            for (ConfigurationNode item : extNode.childrenList()) {
                extensionsOut.add(parseRewardExtension(packId, file, item));
            }
            hasSections = true;
        }

        if (hasSections) {
            return;
        }
        if (!root.node("inject-into").virtual()) {
            extensionsOut.add(parseRewardExtension(packId, file, root));
            return;
        }
        if (!root.node("id").virtual()) {
            tablesOut.add(parseRewardTable(packId, file, root));
            return;
        }
        if (!root.childrenMap().isEmpty()) {
            throw new ContentPackException(
                    packId,
                    file,
                    "",
                    "Expected 'reward-tables', 'extensions', or single table/extension entry with 'id'"
            );
        }
    }

    private static RewardTable parseRewardTable(String packId, Path file, ConfigurationNode node) {
        if (!node.isMap()) {
            throw new ContentPackException(packId, file, nodePath(node), "Reward table entry must be a map");
        }
        if (!node.node("trigger").virtual()) {
            throw new ContentPackException(
                    packId,
                    file,
                    nodePath(node.node("trigger")),
                    "Reward tables are pure loot tables and must not define 'trigger'; reference table IDs from callers"
            );
        }
        String id = requireNonBlankString(packId, file, node, "id");
        List<RewardPool> pools = parsePoolList(packId, file, node.node("pools"));
        try {
            return new RewardTable(id, packId, pools);
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static RewardTableExtension parseRewardExtension(String packId, Path file, ConfigurationNode node) {
        if (!node.isMap()) {
            throw new ContentPackException(packId, file, nodePath(node), "Reward extension entry must be a map");
        }
        String id = requireNonBlankString(packId, file, node, "id");
        String targetTableId = requireNonBlankString(packId, file, node, "inject-into");
        int priority = optionalInt(packId, file, node.node("priority"), 100);
        boolean optional = optionalBoolean(packId, file, node.node("optional"), false);
        List<RewardPool> pools = parsePoolList(packId, file, node.node("pools"));
        try {
            return new RewardTableExtension(id, packId, targetTableId, priority, optional, pools);
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static List<RewardPool> parsePoolList(String packId, Path file, ConfigurationNode poolsNode) {
        if (poolsNode.virtual() || !poolsNode.isList() || poolsNode.childrenList().isEmpty()) {
            throw new ContentPackException(packId, file, nodePath(poolsNode), "'pools' must be a non-empty list");
        }
        List<RewardPool> pools = new ArrayList<>();
        for (ConfigurationNode poolNode : poolsNode.childrenList()) {
            pools.add(parseRewardPool(packId, file, poolNode));
        }
        return pools;
    }

    private static RewardPool parseRewardPool(String packId, Path file, ConfigurationNode node) {
        if (!node.isMap()) {
            throw new ContentPackException(packId, file, nodePath(node), "Reward pool entry must be a map");
        }
        String id = optionalString(packId, file, node.node("id"), "");
        RewardPoolMode mode = optionalEnum(packId, file, node.node("mode"), RewardPoolMode.class);
        if (mode == null) {
            mode = RewardPoolMode.WEIGHTED;
        }
        RollRange rolls = parseRollRange(packId, file, node.node("rolls"), RollRange.exact(1));
        double bonusRollsPerLuck = optionalDouble(packId, file, node.node("bonus-rolls-per-luck"), 0.0);
        String claimKey = optionalString(packId, file, node.node("claim-key"), "");
        List<RewardConditionSpec> conditions = parseConditions(packId, file, node.node("conditions"));

        ConfigurationNode entriesNode = node.node("entries");
        if (entriesNode.virtual() || !entriesNode.isList() || entriesNode.childrenList().isEmpty()) {
            throw new ContentPackException(packId, file, nodePath(entriesNode), "'entries' must be a non-empty list");
        }
        List<RewardEntrySpec> entries = new ArrayList<>();
        for (ConfigurationNode entryNode : entriesNode.childrenList()) {
            entries.add(parseRewardEntry(packId, file, entryNode));
        }
        List<RewardFunctionSpec> functions = parseFunctions(packId, file, node.node("functions"));

        try {
            return new RewardPool(id, mode, rolls, bonusRollsPerLuck, claimKey, conditions, entries, functions);
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static RewardEntrySpec parseRewardEntry(String packId, Path file, ConfigurationNode node) {
        if (!node.isMap()) {
            throw new ContentPackException(packId, file, nodePath(node), "Reward entry must be a map");
        }
        RewardEntryType type = requireEnum(packId, file, node, "type", RewardEntryType.class);
        String target = optionalString(packId, file, node.node("target"), "");
        int weight = optionalInt(packId, file, node.node("weight"), 1);
        double quality = optionalDouble(packId, file, node.node("quality"), 0.0);
        RollRange defaultAmount = (type == RewardEntryType.CURRENCY
                || type == RewardEntryType.VANILLA_ITEM
                || type == RewardEntryType.CUSTOM_ITEM)
                ? RollRange.exact(1)
                : RollRange.exact(0);
        RollRange amount = parseRollRange(packId, file, node.node("amount"), defaultAmount);
        boolean notifyPlayer = optionalBoolean(packId, file, node.node("notify"), true);
        String titleKey = optionalString(packId, file, node.node("title-key"), "");
        String subtitleKey = optionalString(packId, file, node.node("subtitle-key"), "");
        String toastTitleKey = optionalString(packId, file, node.node("toast-title-key"), "");
        String toastDescKey = optionalString(packId, file, node.node("toast-description-key"), "");
        String icon = optionalString(packId, file, node.node("icon"), "");
        String sound = optionalString(packId, file, node.node("sound"), "");
        Map<String, Object> params = parseParamsMap(node.node("params"));
        List<RewardConditionSpec> conditions = parseConditions(packId, file, node.node("conditions"));
        List<RewardFunctionSpec> functions = parseFunctions(packId, file, node.node("functions"));

        try {
            return new RewardEntrySpec(
                    type,
                    target,
                    weight,
                    quality,
                    amount,
                    notifyPlayer,
                    titleKey,
                    subtitleKey,
                    toastTitleKey,
                    toastDescKey,
                    icon,
                    sound,
                    params,
                    conditions,
                    functions
            );
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static List<RewardConditionSpec> parseConditions(String packId, Path file, ConfigurationNode node) {
        if (node.virtual() || node.raw() == null) {
            return List.of();
        }
        if (!node.isList()) {
            throw new ContentPackException(packId, file, nodePath(node), "'conditions' must be a list");
        }
        List<RewardConditionSpec> out = new ArrayList<>();
        for (ConfigurationNode condNode : node.childrenList()) {
            out.add(parseCondition(packId, file, condNode));
        }
        return out;
    }

    private static RewardConditionSpec parseCondition(String packId, Path file, ConfigurationNode node) {
        if (!node.isMap()) {
            throw new ContentPackException(packId, file, nodePath(node), "Condition entry must be a map");
        }
        RewardConditionType type = requireEnum(packId, file, node, "type", RewardConditionType.class);
        boolean negate = optionalBoolean(packId, file, node.node("negate"), false);
        String target = firstNonEmptyString(
                packId,
                file,
                node.node("target"),
                node.node("key"),
                node.node("achievement"),
                node.node("content"),
                node.node("hook")
        );
        EncounterDifficulty difficulty = optionalEnum(packId, file, node.node("difficulty"), EncounterDifficulty.class);
        int minInt = optionalInt(packId, file, node.node("min"), 0);
        int maxInt = optionalInt(packId, file, node.node("max"), 0);

        long longValue = 0L;
        if (!node.node("max-duration-ms").virtual()) {
            longValue = optionalLong(packId, file, node.node("max-duration-ms"), 0L);
        } else if (!node.node("max-duration-seconds").virtual()) {
            long sec = optionalLong(packId, file, node.node("max-duration-seconds"), 0L);
            longValue = Math.multiplyExact(sec, 1000L);
        }

        double doubleValue = 0.0;
        if (!node.node("chance").virtual()) {
            doubleValue = optionalDouble(packId, file, node.node("chance"), 0.0);
        } else if (!node.node("min-boss-damage").virtual()) {
            doubleValue = optionalDouble(packId, file, node.node("min-boss-damage"), 0.0);
        } else if (!node.node("value").virtual()) {
            doubleValue = optionalDouble(packId, file, node.node("value"), 0.0);
        }

        double luckMultiplier = optionalDouble(packId, file, node.node("luck-multiplier"), 0.0);
        String expectedValue = scalarAsString(packId, file, node.node("expected"), "");
        Map<String, Object> params = parseParamsMap(node.node("params"));

        try {
            return new RewardConditionSpec(
                    type,
                    negate,
                    target,
                    difficulty,
                    minInt,
                    maxInt,
                    longValue,
                    doubleValue,
                    luckMultiplier,
                    expectedValue,
                    params
            );
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static List<RewardFunctionSpec> parseFunctions(String packId, Path file, ConfigurationNode node) {
        if (node.virtual() || node.raw() == null) {
            return List.of();
        }
        if (!node.isList()) {
            throw new ContentPackException(packId, file, nodePath(node), "'functions' must be a list");
        }
        List<RewardFunctionSpec> out = new ArrayList<>();
        for (ConfigurationNode fnNode : node.childrenList()) {
            out.add(parseFunction(packId, file, fnNode));
        }
        return out;
    }

    private static RewardFunctionSpec parseFunction(String packId, Path file, ConfigurationNode node) {
        if (!node.isMap()) {
            throw new ContentPackException(packId, file, nodePath(node), "Function entry must be a map");
        }
        RewardFunctionType type = requireEnum(packId, file, node, "type", RewardFunctionType.class);
        RollRange amountRange = !node.node("amount").virtual()
                ? parseRollRange(packId, file, node.node("amount"), null)
                : null;
        double factor = optionalDouble(packId, file, node.node("factor"), 1.0);
        RewardRoundingMode rounding = optionalEnum(packId, file, node.node("rounding"), RewardRoundingMode.class);
        RewardMetricType metric = optionalEnum(packId, file, node.node("metric"), RewardMetricType.class);
        String attributeKey = optionalString(packId, file, node.node("attribute-key"), "");
        double step = optionalDouble(packId, file, node.node("step"), 1.0);
        double perStep = optionalDouble(packId, file, node.node("per-step"), 0.0);
        long minBound = optionalLong(packId, file, node.node("min"), 0L);
        long maxBound = optionalLong(packId, file, node.node("max"), 0L);
        List<RewardConditionSpec> conditions = parseConditions(packId, file, node.node("conditions"));

        try {
            return new RewardFunctionSpec(
                    type,
                    amountRange,
                    factor,
                    rounding,
                    metric,
                    attributeKey,
                    step,
                    perStep,
                    minBound,
                    maxBound,
                    conditions
            );
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static @Nullable RollRange parseRollRange(
            String packId,
            Path file,
            ConfigurationNode node,
            @Nullable RollRange fallback) {
        if (node.virtual() || node.raw() == null) {
            return fallback;
        }
        try {
            if (node.isMap()) {
                int min = optionalInt(packId, file, node.node("min"), 0);
                int max = optionalInt(packId, file, node.node("max"), min);
                return RollRange.between(min, max);
            }
            int exact = optionalInt(packId, file, node, 0);
            return RollRange.exact(exact);
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static VanillaProgressionRule parseProgressionRule(String packId, Path file, ConfigurationNode node) {
        String id = requireNonBlankString(packId, file, node, "id");
        int priority = optionalInt(packId, file, node.node("priority"), 100);

        EndEntryGateSpec entryGate = null;
        ConfigurationNode gateNode = node.node("entry-gate");
        if (!gateNode.virtual()) {
            if (!gateNode.isMap()) {
                throw new ContentPackException(packId, file, nodePath(gateNode), "'entry-gate' must be a map");
            }
            EndEntryGateMode mode = requireEnum(packId, file, gateNode, "mode", EndEntryGateMode.class);
            String target = optionalString(packId, file, gateNode.node("target"), "");
            String denialMessageKey = optionalString(packId, file, gateNode.node("denial-message-key"), "");
            boolean allowBypass = optionalBoolean(packId, file, gateNode.node("allow-bypass-permission"), true);
            boolean pushBack = optionalBoolean(packId, file, gateNode.node("push-back-on-deny"), true);
            entryGate = new EndEntryGateSpec(mode, target, denialMessageKey, allowBypass, pushBack);
        }

        List<VanillaClearHookSpec> clearHooks = new ArrayList<>();
        Set<String> seenHookIds = new LinkedHashSet<>();
        ConfigurationNode hooksNode = node.node("clear-hooks");
        if (!hooksNode.virtual()) {
            if (!hooksNode.isList()) {
                throw new ContentPackException(packId, file, nodePath(hooksNode), "'clear-hooks' must be a list");
            }
            for (ConfigurationNode hookNode : hooksNode.childrenList()) {
                VanillaClearHookSpec hook = parseClearHook(packId, file, hookNode);
                if (!hook.id().isEmpty() && !seenHookIds.add(hook.id())) {
                    throw new ContentPackException(
                            packId, file, nodePath(hookNode), "Duplicate clear-hook id '" + hook.id() + "' in rule '" + id + "'");
                }
                clearHooks.add(hook);
            }
        }

        try {
            return new VanillaProgressionRule(id, packId, priority, entryGate, clearHooks);
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static VanillaClearHookSpec parseClearHook(String packId, Path file, ConfigurationNode node) {
        if (!node.isMap()) {
            throw new ContentPackException(packId, file, nodePath(node), "clear-hook entry must be a map");
        }
        String hookId = optionalString(packId, file, node.node("id"), "");
        String encounterId = requireNonBlankString(packId, file, node, "encounter-id");
        EncounterDifficulty difficulty = optionalEnum(packId, file, node.node("difficulty"), EncounterDifficulty.class);
        boolean firstClearOnly = optionalBoolean(packId, file, node.node("first-clear-only"), false);
        boolean serverFirstOnly = optionalBoolean(packId, file, node.node("server-first-only"), false);
        String endWorldName = optionalString(packId, file, node.node("end-world"), "");
        boolean syncDefeated = optionalBoolean(packId, file, node.node("sync-dragon-battle-defeated"), false);
        boolean generatePortal = optionalBoolean(packId, file, node.node("generate-exit-portal"), false);
        boolean removeDragon = optionalBoolean(packId, file, node.node("remove-live-vanilla-dragon"), false);
        int spawnGatewayCount = optionalInt(packId, file, node.node("spawn-gateway-count"), 0);
        int maxGateways = optionalInt(packId, file, node.node("max-gateways"), VanillaClearHookSpec.MAX_VANILLA_GATEWAYS);
        String scriptHookId = optionalString(packId, file, node.node("script-hook"), "");
        Map<String, Object> params = parseParamsMap(node.node("params"));

        try {
            return new VanillaClearHookSpec(
                    hookId,
                    encounterId,
                    difficulty,
                    firstClearOnly,
                    serverFirstOnly,
                    endWorldName,
                    syncDefeated,
                    generatePortal,
                    removeDragon,
                    spawnGatewayCount,
                    maxGateways,
                    scriptHookId,
                    params
            );
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(packId, file, nodePath(node), ex.getMessage(), ex);
        }
    }

    private static ConfigurationNode loadYaml(String packId, Path file) {
        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(file)
                    .nodeStyle(NodeStyle.BLOCK)
                    .indent(2)
                    .build();
            CommentedConfigurationNode node = loader.load();
            return node;
        } catch (ConfigurateException ex) {
            throw new ContentPackException(packId, file, "", "Failed to parse YAML: " + ex.getMessage(), ex);
        }
    }

    private static List<? extends ConfigurationNode> extractEntries(
            String packId,
            Path file,
            ConfigurationNode root,
            String listKey) {
        if (root.virtual() || root.raw() == null) {
            return List.of();
        }
        if (root.isList()) {
            return root.childrenList();
        }
        if (!root.isMap()) {
            throw new ContentPackException(packId, file, "", "YAML root must be a map or list");
        }
        ConfigurationNode listNode = root.node(listKey);
        if (!listNode.virtual()) {
            if (!listNode.isList()) {
                throw new ContentPackException(packId, file, listKey, "'" + listKey + "' must be a list");
            }
            return listNode.childrenList();
        }
        if (!root.node("id").virtual()) {
            return List.of(root);
        }
        if (root.childrenMap().isEmpty()) {
            return List.of();
        }
        throw new ContentPackException(packId, file, "", "Expected '" + listKey + "' list or single entry with 'id'");
    }

    private static List<Path> listYamlFiles(String packId, Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                    .toList();
        } catch (IOException ex) {
            throw new ContentPackException(packId, dir, "", "Failed to list YAML files: " + ex.getMessage(), ex);
        }
    }

    private static List<Path> listAllRegularFiles(String packId, Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                    .toList();
        } catch (IOException ex) {
            throw new ContentPackException(packId, dir, "", "Failed to list resource files: " + ex.getMessage(), ex);
        }
    }

    private static void rejectNonScalar(String packId, Path file, ConfigurationNode node, String expectedType) {
        if (!node.virtual() && (node.isMap() || node.isList())) {
            throw new ContentPackException(
                    packId,
                    file,
                    nodePath(node),
                    "Expected scalar " + expectedType + " value, got " + (node.isList() ? "list" : "map")
            );
        }
    }

    private static String requireNonBlankString(String packId, Path file, ConfigurationNode parent, String key) {
        ConfigurationNode node = parent.node(key);
        rejectNonScalar(packId, file, node, "string");
        if (node.virtual() || node.rawScalar() == null) {
            throw new ContentPackException(packId, file, nodePath(node), "Missing required string '" + key + "'");
        }
        String value = node.getString();
        if (value == null || value.isBlank()) {
            throw new ContentPackException(packId, file, nodePath(node), "String '" + key + "' must not be blank");
        }
        return value.trim();
    }

    private static String optionalString(String packId, Path file, ConfigurationNode node, String fallback) {
        rejectNonScalar(packId, file, node, "string");
        if (node.virtual() || node.rawScalar() == null) {
            return fallback;
        }
        String value = node.getString();
        return value == null ? fallback : value.trim();
    }

    private static String scalarAsString(String packId, Path file, ConfigurationNode node, String fallback) {
        rejectNonScalar(packId, file, node, "scalar");
        if (node.virtual() || node.rawScalar() == null) {
            return fallback;
        }
        return node.rawScalar().toString().trim();
    }

    private static String firstNonEmptyString(String packId, Path file, ConfigurationNode... nodes) {
        for (ConfigurationNode node : nodes) {
            String value = optionalString(packId, file, node, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static boolean optionalBoolean(String packId, Path file, ConfigurationNode node, boolean fallback) {
        rejectNonScalar(packId, file, node, "boolean");
        if (node.virtual() || node.rawScalar() == null) {
            return fallback;
        }
        Object raw = node.rawScalar();
        if (raw instanceof Boolean bool) {
            return bool;
        }
        throw new ContentPackException(packId, file, nodePath(node), "Expected boolean value, got: " + raw);
    }

    private static int optionalInt(String packId, Path file, ConfigurationNode node, int fallback) {
        rejectNonScalar(packId, file, node, "integer");
        if (node.virtual() || node.rawScalar() == null) {
            return fallback;
        }
        Object raw = node.rawScalar();
        if (raw instanceof Number number && !(raw instanceof Double) && !(raw instanceof Float)) {
            long val = number.longValue();
            if (val < Integer.MIN_VALUE || val > Integer.MAX_VALUE) {
                throw new ContentPackException(packId, file, nodePath(node), "Integer out of range: " + raw);
            }
            return (int) val;
        }
        throw new ContentPackException(packId, file, nodePath(node), "Expected integer value, got: " + raw);
    }

    private static long optionalLong(String packId, Path file, ConfigurationNode node, long fallback) {
        rejectNonScalar(packId, file, node, "long");
        if (node.virtual() || node.rawScalar() == null) {
            return fallback;
        }
        Object raw = node.rawScalar();
        if (raw instanceof Number number && !(raw instanceof Double) && !(raw instanceof Float)) {
            return number.longValue();
        }
        throw new ContentPackException(packId, file, nodePath(node), "Expected integer/long value, got: " + raw);
    }

    private static double optionalDouble(String packId, Path file, ConfigurationNode node, double fallback) {
        rejectNonScalar(packId, file, node, "numeric");
        if (node.virtual() || node.rawScalar() == null) {
            return fallback;
        }
        Object raw = node.rawScalar();
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        throw new ContentPackException(packId, file, nodePath(node), "Expected numeric value, got: " + raw);
    }

    private static <E extends Enum<E>> E requireEnum(
            String packId,
            Path file,
            ConfigurationNode parent,
            String key,
            Class<E> enumType) {
        String raw = requireNonBlankString(packId, file, parent, key);
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(
                    packId,
                    file,
                    nodePath(parent.node(key)),
                    "Unsupported " + enumType.getSimpleName() + " value: '" + raw + "'",
                    ex
            );
        }
    }

    private static <E extends Enum<E>> @Nullable E optionalEnum(
            String packId,
            Path file,
            ConfigurationNode node,
            Class<E> enumType) {
        rejectNonScalar(packId, file, node, enumType.getSimpleName());
        if (node.virtual() || node.rawScalar() == null) {
            return null;
        }
        String raw = node.getString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ContentPackException(
                    packId,
                    file,
                    nodePath(node),
                    "Unsupported " + enumType.getSimpleName() + " value: '" + raw + "'",
                    ex
            );
        }
    }

    private static Set<String> parseStringSet(String packId, Path file, ConfigurationNode node) {
        if (node.virtual() || node.raw() == null) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        if (node.isList()) {
            for (ConfigurationNode child : node.childrenList()) {
                String value = child.getString();
                if (value == null || value.isBlank()) {
                    throw new ContentPackException(packId, file, nodePath(child), "List item must not be blank");
                }
                set.add(value.trim());
            }
        } else {
            String single = node.getString();
            if (single != null && !single.isBlank()) {
                set.add(single.trim());
            }
        }
        return Collections.unmodifiableSet(set);
    }

    private static List<String> parseStringList(String packId, Path file, ConfigurationNode node) {
        if (node.virtual() || node.raw() == null) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        if (node.isList()) {
            for (ConfigurationNode child : node.childrenList()) {
                String value = child.getString();
                if (value == null || value.isBlank()) {
                    throw new ContentPackException(packId, file, nodePath(child), "List item must not be blank");
                }
                list.add(value.trim());
            }
        } else {
            String single = node.getString();
            if (single != null && !single.isBlank()) {
                list.add(single.trim());
            }
        }
        return List.copyOf(list);
    }

    private static Map<String, Object> parseParamsMap(ConfigurationNode node) {
        if (node.virtual() || !node.isMap()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object val = extractPlainValue(entry.getValue());
            if (val != null) {
                map.put(key, val);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static @Nullable Object extractPlainValue(ConfigurationNode node) {
        if (node.virtual()) {
            return null;
        }
        if (node.isList()) {
            List<Object> list = new ArrayList<>();
            for (ConfigurationNode child : node.childrenList()) {
                Object val = extractPlainValue(child);
                if (val != null) {
                    list.add(val);
                }
            }
            return Collections.unmodifiableList(list);
        }
        if (node.isMap()) {
            return parseParamsMap(node);
        }
        return node.rawScalar();
    }

    private static String nodePath(ConfigurationNode node) {
        StringBuilder sb = new StringBuilder();
        for (Object segment : node.path()) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(segment);
        }
        return sb.toString();
    }
}
