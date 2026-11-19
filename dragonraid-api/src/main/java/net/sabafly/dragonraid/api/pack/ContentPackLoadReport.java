package net.sabafly.dragonraid.api.pack;

import java.util.List;

/**
 * Summary produced after reloading all Content Packs under {@code contents/}.
 */
public record ContentPackLoadReport(
        List<LoadedContentPack> loadedPacks,
        List<String> skippedPackIds,
        int totalContents,
        int totalAchievements,
        int totalRewardTables,
        int totalRewardExtensions,
        int totalProgressionRules
) {

    public ContentPackLoadReport {
        loadedPacks = loadedPacks == null ? List.of() : List.copyOf(loadedPacks);
        skippedPackIds = skippedPackIds == null ? List.of() : List.copyOf(skippedPackIds);
    }

    public static ContentPackLoadReport empty() {
        return new ContentPackLoadReport(List.of(), List.of(), 0, 0, 0, 0, 0);
    }
}
