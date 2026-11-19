package net.sabafly.dragonraid.api.pack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.reward.RewardTable;
import org.jetbrains.annotations.Nullable;

/**
 * Unified immutable snapshot representing a single atomic reload generation across Content Finder
 * entries, achievement definitions, merged reward tables, and vanilla End progression rules.
 */
public record ContentPackCatalogSnapshot(
        long generation,
        Map<String, ContentEntrySpec> contents,
        Map<String, AchievementDefinition> achievements,
        Map<String, RewardTable> rewardTables,
        List<VanillaProgressionRule> progressionRules
) {

    public ContentPackCatalogSnapshot {
        contents = copyMap(contents);
        achievements = copyMap(achievements);
        rewardTables = copyMap(rewardTables);
        progressionRules = progressionRules == null ? List.of() : List.copyOf(progressionRules);
    }

    public static ContentPackCatalogSnapshot empty() {
        return new ContentPackCatalogSnapshot(0L, Map.of(), Map.of(), Map.of(), List.of());
    }

    public Optional<ContentEntrySpec> resolveContent(@Nullable String encounterId) {
        if (encounterId == null || encounterId.isBlank()) {
            return Optional.empty();
        }
        String trimmed = encounterId.trim();
        ContentEntrySpec direct = contents.get(trimmed);
        if (direct != null) {
            return Optional.of(direct);
        }
        for (ContentEntrySpec spec : contents.values()) {
            if (trimmed.equals(spec.blueprintId())) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    public Optional<RewardTable> rewardTable(@Nullable String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rewardTables.get(tableId.trim()));
    }

    private static <K, V> Map<K, V> copyMap(@Nullable Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<K, V> copy = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
