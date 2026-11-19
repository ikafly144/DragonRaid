package net.sabafly.dragonraid.api.pack;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.RewardTableExtension;

/**
 * Fully parsed and validated snapshot of a single Content Pack directory.
 */
public record LoadedContentPack(
        ContentPackManifest manifest,
        Path rootDirectory,
        List<ContentEntrySpec> contents,
        List<AchievementDefinition> achievements,
        List<RewardTable> rewardTables,
        List<RewardTableExtension> rewardTableExtensions,
        List<VanillaProgressionRule> progressionRules,
        List<Path> enemyFiles,
        List<Path> itemFiles,
        List<Path> shopFiles,
        List<Path> scriptFiles
) {

    public LoadedContentPack {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        contents = contents == null ? List.of() : List.copyOf(contents);
        achievements = achievements == null ? List.of() : List.copyOf(achievements);
        rewardTables = rewardTables == null ? List.of() : List.copyOf(rewardTables);
        rewardTableExtensions = rewardTableExtensions == null ? List.of() : List.copyOf(rewardTableExtensions);
        progressionRules = progressionRules == null ? List.of() : List.copyOf(progressionRules);
        enemyFiles = enemyFiles == null ? List.of() : List.copyOf(enemyFiles);
        itemFiles = itemFiles == null ? List.of() : List.copyOf(itemFiles);
        shopFiles = shopFiles == null ? List.of() : List.copyOf(shopFiles);
        scriptFiles = scriptFiles == null ? List.of() : List.copyOf(scriptFiles);
    }
}
