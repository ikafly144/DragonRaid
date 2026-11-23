package net.sabafly.dragonraid.api.finder;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Content Finder registration metadata for an encounter or instanced duty.
 */
public record ContentEntrySpec(
        String contentId,
        String displayNameKey,
        String blueprintId,
        String requiredUnlockKey,
        int minPlayers,
        int maxPlayers,
        int minAverageItemLevel,
        List<String> clearRewards,
        ContentUnlockPolicy unlockPolicy,
        boolean hiddenUntilUnlocked,
        String descriptionKey,
        String iconMaterial,
        String category
) {

    public static final String DEFAULT_ICON_MATERIAL = "ENDER_EYE";

    public ContentEntrySpec {
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId must not be blank");
        }
        Objects.requireNonNull(displayNameKey, "displayNameKey");
        Objects.requireNonNull(blueprintId, "blueprintId");
        requiredUnlockKey = requiredUnlockKey == null ? "" : requiredUnlockKey;
        if (minPlayers < 1 || maxPlayers < minPlayers) {
            throw new IllegalArgumentException("Invalid player range: " + minPlayers + ".." + maxPlayers);
        }
        if (minAverageItemLevel < 0) {
            throw new IllegalArgumentException("minAverageItemLevel must be >= 0");
        }
        clearRewards = normalizeIdList(clearRewards);
        unlockPolicy = unlockPolicy == null ? ContentUnlockPolicy.ALL_MEMBERS : unlockPolicy;
        descriptionKey = descriptionKey == null ? "" : descriptionKey;
        iconMaterial = (iconMaterial == null || iconMaterial.isBlank()) ? DEFAULT_ICON_MATERIAL : iconMaterial.trim();
        category = category == null ? "" : category.trim();
    }

    public ContentEntrySpec(
            String contentId,
            String displayNameKey,
            String blueprintId,
            String requiredUnlockKey,
            int minPlayers,
            int maxPlayers,
            int minAverageItemLevel) {
        this(contentId, displayNameKey, blueprintId, requiredUnlockKey, minPlayers, maxPlayers, minAverageItemLevel, List.of());
    }

    public ContentEntrySpec(
            String contentId,
            String displayNameKey,
            String blueprintId,
            String requiredUnlockKey,
            int minPlayers,
            int maxPlayers,
            int minAverageItemLevel,
            List<String> clearRewards) {
        this(
                contentId,
                displayNameKey,
                blueprintId,
                requiredUnlockKey,
                minPlayers,
                maxPlayers,
                minAverageItemLevel,
                clearRewards,
                ContentUnlockPolicy.ALL_MEMBERS,
                false,
                "",
                DEFAULT_ICON_MATERIAL,
                ""
        );
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
