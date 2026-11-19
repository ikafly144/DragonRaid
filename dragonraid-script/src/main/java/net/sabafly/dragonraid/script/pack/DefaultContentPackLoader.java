package net.sabafly.dragonraid.script.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.pack.ContentPackLoadReport;
import net.sabafly.dragonraid.api.pack.ContentPackLoader;
import net.sabafly.dragonraid.api.pack.LoadedContentPack;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardEntrySpec;
import net.sabafly.dragonraid.api.reward.RewardEntryType;
import net.sabafly.dragonraid.api.reward.RewardPool;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.RewardTableExtension;

/**
 * Atomic {@link ContentPackLoader} staging and validating all YAML packs under
 * {@code plugins/DragonRaid/contents/<pack-id>/} before updating runtime registries.
 */
public final class DefaultContentPackLoader implements ContentPackLoader {

    private final Path contentsRoot;
    private final ContentFinderService contentFinderService;
    private final RaidAchievementService achievementService;
    private final RaidRewardService rewardService;
    private final VanillaEndProgressionPolicy progressionPolicy;
    private final Logger logger;

    private volatile List<LoadedContentPack> loadedPacks = List.of();
    private volatile ContentPackLoadReport lastReport = ContentPackLoadReport.empty();

    public DefaultContentPackLoader(
            Path contentsRoot,
            ContentFinderService contentFinderService,
            RaidAchievementService achievementService,
            RaidRewardService rewardService,
            VanillaEndProgressionPolicy progressionPolicy,
            Logger logger) {
        this.contentsRoot = Objects.requireNonNull(contentsRoot, "contentsRoot");
        this.contentFinderService = Objects.requireNonNull(contentFinderService, "contentFinderService");
        this.achievementService = Objects.requireNonNull(achievementService, "achievementService");
        this.rewardService = Objects.requireNonNull(rewardService, "rewardService");
        this.progressionPolicy = Objects.requireNonNull(progressionPolicy, "progressionPolicy");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public synchronized ContentPackLoadReport reload() {
        try {
            Files.createDirectories(contentsRoot);
        } catch (IOException ex) {
            throw new ContentPackException("", contentsRoot, "", "Failed to create contents directory: " + ex.getMessage(), ex);
        }

        List<Path> candidateDirs;
        try (Stream<Path> stream = Files.list(contentsRoot)) {
            candidateDirs = stream
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(ContentPackYamlLoader.PACK_MANIFEST_FILE)))
                    .sorted(Comparator.comparing(dir -> dir.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            throw new ContentPackException("", contentsRoot, "", "Failed to scan contents directory: " + ex.getMessage(), ex);
        }

        List<LoadedContentPack> parsedEnabled = new ArrayList<>();
        List<String> skippedPackIds = new ArrayList<>();
        Set<String> seenPackIds = new HashSet<>();

        for (Path dir : candidateDirs) {
            LoadedContentPack pack = ContentPackYamlLoader.loadPack(dir);
            String packId = pack.manifest().id();
            if (!seenPackIds.add(packId)) {
                throw new ContentPackException(packId, dir, "id", "Duplicate content pack id: " + packId);
            }
            if (!pack.manifest().enabled()) {
                skippedPackIds.add(packId);
                continue;
            }
            parsedEnabled.add(pack);
        }

        List<LoadedContentPack> sortedEnabled = parsedEnabled.stream()
                .sorted(Comparator.comparingInt((LoadedContentPack p) -> p.manifest().priority())
                        .thenComparing(p -> p.manifest().id()))
                .toList();

        List<ContentEntrySpec> stagedContents = new ArrayList<>();
        List<AchievementDefinition> stagedAchievements = new ArrayList<>();
        Map<String, RewardTable> stagedTablesById = new LinkedHashMap<>();
        List<RewardTableExtension> stagedExtensions = new ArrayList<>();
        List<VanillaProgressionRule> stagedProgressionRules = new ArrayList<>();

        Set<String> seenContentIds = new HashSet<>();
        Set<String> seenAchievementIds = new HashSet<>();
        Set<String> seenExtensionIds = new HashSet<>();
        Set<String> seenRuleIds = new HashSet<>();

        for (LoadedContentPack pack : sortedEnabled) {
            String packId = pack.manifest().id();
            for (ContentEntrySpec entry : pack.contents()) {
                if (!seenContentIds.add(entry.contentId())) {
                    throw new ContentPackException(packId, pack.rootDirectory(), "contents",
                            "Duplicate content id across enabled packs: " + entry.contentId());
                }
                stagedContents.add(entry);
            }
            for (AchievementDefinition ach : pack.achievements()) {
                if (!seenAchievementIds.add(ach.id())) {
                    throw new ContentPackException(packId, pack.rootDirectory(), "achievements",
                            "Duplicate achievement id across enabled packs: " + ach.id());
                }
                stagedAchievements.add(ach);
            }
            for (RewardTable table : pack.rewardTables()) {
                if (stagedTablesById.containsKey(table.id())) {
                    throw new ContentPackException(packId, pack.rootDirectory(), "rewards",
                            "Duplicate reward table id across enabled packs: " + table.id());
                }
                stagedTablesById.put(table.id(), table);
            }
            for (RewardTableExtension extension : pack.rewardTableExtensions()) {
                if (!seenExtensionIds.add(extension.id())) {
                    throw new ContentPackException(packId, pack.rootDirectory(), "rewards",
                            "Duplicate reward extension id across enabled packs: " + extension.id());
                }
                stagedExtensions.add(extension);
            }
            for (VanillaProgressionRule rule : pack.progressionRules()) {
                if (!seenRuleIds.add(rule.id())) {
                    throw new ContentPackException(packId, pack.rootDirectory(), "progression",
                            "Duplicate progression rule id across enabled packs: " + rule.id());
                }
                stagedProgressionRules.add(rule);
            }
        }

        validateRewardGraphAndReferences(
                stagedContents,
                stagedAchievements,
                stagedTablesById,
                stagedExtensions
        );

        // All validation passed; commit atomically to runtime registries.
        contentFinderService.replacePackContents(stagedContents);
        achievementService.replacePackDefinitions(stagedAchievements);
        rewardService.replacePackTables(stagedTablesById.values(), stagedExtensions);
        progressionPolicy.replacePackRules(stagedProgressionRules);

        ContentPackLoadReport report = new ContentPackLoadReport(
                sortedEnabled,
                skippedPackIds,
                stagedContents.size(),
                stagedAchievements.size(),
                stagedTablesById.size(),
                stagedExtensions.size(),
                stagedProgressionRules.size()
        );
        this.loadedPacks = sortedEnabled;
        this.lastReport = report;
        logger.info(() -> String.format(
                "Loaded %d content pack(s) (%d skipped): %d contents, %d achievements, %d reward tables (%d extensions), %d progression rules.",
                sortedEnabled.size(),
                skippedPackIds.size(),
                stagedContents.size(),
                stagedAchievements.size(),
                stagedTablesById.size(),
                stagedExtensions.size(),
                stagedProgressionRules.size()
        ));
        return report;
    }

    private void validateRewardGraphAndReferences(
            List<ContentEntrySpec> stagedContents,
            List<AchievementDefinition> stagedAchievements,
            Map<String, RewardTable> stagedTablesById,
            List<RewardTableExtension> stagedExtensions) {
        Set<String> knownTableIds = new LinkedHashSet<>(rewardService.staticTableIds());
        knownTableIds.addAll(stagedTablesById.keySet());

        Map<String, List<RewardPool>> mergedPools = new LinkedHashMap<>();
        for (RewardTable table : stagedTablesById.values()) {
            mergedPools.put(table.id(), new ArrayList<>(table.pools()));
        }

        List<RewardTableExtension> sortedExtensions = stagedExtensions.stream()
                .sorted(Comparator.comparingInt(RewardTableExtension::priority)
                        .thenComparing(RewardTableExtension::packId)
                        .thenComparing(RewardTableExtension::id))
                .toList();

        for (RewardTableExtension ext : sortedExtensions) {
            if (!knownTableIds.contains(ext.targetTableId())) {
                if (!ext.optional()) {
                    throw new ContentPackException(
                            ext.packId(),
                            contentsRoot,
                            "extensions",
                            "Reward extension '" + ext.id() + "' targets unknown reward table: " + ext.targetTableId()
                    );
                }
                logger.info(() -> "Skipping optional reward extension '" + ext.id()
                        + "' because target table '" + ext.targetTableId() + "' is absent.");
                continue;
            }
            mergedPools.computeIfAbsent(ext.targetTableId(), k -> new ArrayList<>())
                    .addAll(ext.appendPools());
        }

        Map<String, Set<String>> refEdges = new LinkedHashMap<>();
        for (Map.Entry<String, List<RewardPool>> entry : mergedPools.entrySet()) {
            String sourceTableId = entry.getKey();
            Set<String> targets = new LinkedHashSet<>();
            for (RewardPool pool : entry.getValue()) {
                for (RewardEntrySpec rewardEntry : pool.entries()) {
                    if (rewardEntry.type() == RewardEntryType.TABLE_REF) {
                        String targetId = rewardEntry.target();
                        if (!knownTableIds.contains(targetId)) {
                            throw new ContentPackException(
                                    "",
                                    contentsRoot,
                                    "rewards",
                                    "Reward table '" + sourceTableId + "' references unknown TABLE_REF target: " + targetId
                            );
                        }
                        targets.add(targetId);
                    }
                }
            }
            refEdges.put(sourceTableId, targets);
        }

        detectTableRefCycles(refEdges);

        for (AchievementDefinition ach : stagedAchievements) {
            for (String rewardTableId : ach.rewards()) {
                if (!knownTableIds.contains(rewardTableId)) {
                    throw new ContentPackException(
                            ach.packId(),
                            contentsRoot,
                            "achievements",
                            "Achievement '" + ach.id() + "' references unknown reward table: " + rewardTableId
                    );
                }
            }
        }

        for (ContentEntrySpec content : stagedContents) {
            for (String rewardTableId : content.clearRewards()) {
                if (!knownTableIds.contains(rewardTableId)) {
                    throw new ContentPackException(
                            "",
                            contentsRoot,
                            "contents",
                            "Content '" + content.contentId() + "' references unknown clear reward table: " + rewardTableId
                    );
                }
            }
        }
    }

    private void detectTableRefCycles(Map<String, Set<String>> refEdges) {
        Map<String, Integer> visitState = new HashMap<>(); // 0/absent=unvisited, 1=visiting, 2=visited
        Deque<String> stack = new ArrayDeque<>();
        for (String tableId : refEdges.keySet()) {
            if (visitState.getOrDefault(tableId, 0) == 0) {
                dfsDetectCycle(tableId, refEdges, visitState, stack);
            }
        }
    }

    private void dfsDetectCycle(
            String current,
            Map<String, Set<String>> refEdges,
            Map<String, Integer> visitState,
            Deque<String> stack) {
        visitState.put(current, 1);
        stack.addLast(current);
        for (String next : refEdges.getOrDefault(current, Set.of())) {
            int state = visitState.getOrDefault(next, 0);
            if (state == 1) {
                throw new ContentPackException(
                        "",
                        contentsRoot,
                        "rewards",
                        "Circular TABLE_REF detected across reward tables: "
                                + String.join(" -> ", stack) + " -> " + next
                );
            }
            if (state == 0) {
                dfsDetectCycle(next, refEdges, visitState, stack);
            }
        }
        stack.removeLast();
        visitState.put(current, 2);
    }

    @Override
    public List<LoadedContentPack> loadedPacks() {
        return loadedPacks;
    }

    @Override
    public ContentPackLoadReport lastReport() {
        return lastReport;
    }

    @Override
    public Path contentsRoot() {
        return contentsRoot;
    }
}
