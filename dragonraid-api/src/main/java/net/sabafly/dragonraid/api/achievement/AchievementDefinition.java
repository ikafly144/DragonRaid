package net.sabafly.dragonraid.api.achievement;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Data-driven achievement definition loaded from Content Packs or registered programmatically.
 */
public record AchievementDefinition(
        String id,
        String packId,
        String titleKey,
        String descriptionKey,
        String iconMaterial,
        boolean hidden,
        Set<String> prerequisites,
        List<AchievementTriggerSpec> triggers,
        List<String> rewards
) {

    public AchievementDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        id = id.trim();
        packId = packId == null ? "" : packId.trim();
        Objects.requireNonNull(titleKey, "titleKey");
        Objects.requireNonNull(descriptionKey, "descriptionKey");
        iconMaterial = (iconMaterial == null || iconMaterial.isBlank()) ? "NETHER_STAR" : iconMaterial.trim();
        prerequisites = prerequisites == null ? Set.of() : Set.copyOf(prerequisites);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        rewards = normalizeIdList(rewards);
    }

    public AchievementDefinition(
            String id,
            String packId,
            String titleKey,
            String descriptionKey,
            @Nullable String iconMaterial,
            boolean hidden,
            @Nullable Set<String> prerequisites,
            @Nullable List<AchievementTriggerSpec> triggers) {
        this(id, packId, titleKey, descriptionKey, iconMaterial, hidden, prerequisites, triggers, List.of());
    }

    public AchievementDefinition(
            String id,
            String titleKey,
            String descriptionKey,
            @Nullable String iconMaterial,
            boolean hidden,
            @Nullable Set<String> prerequisites,
            @Nullable List<AchievementTriggerSpec> triggers) {
        this(id, "", titleKey, descriptionKey, iconMaterial, hidden, prerequisites, triggers, List.of());
    }

    private static List<String> normalizeIdList(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
