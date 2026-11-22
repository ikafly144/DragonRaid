package net.sabafly.dragonraid.api.reward;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable Content Finder unlock output from {@link RewardTable} evaluation.
 */
public record ContentUnlockGrant(
        String contentId,
        boolean notifyPlayer,
        String titleKey,
        String subtitleKey,
        String toastTitleKey,
        String toastDescriptionKey,
        String iconMaterial,
        String soundKey,
        @Nullable String claimKey
) implements RewardGrant {

    public ContentUnlockGrant {
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId must not be blank");
        }
        contentId = contentId.trim();
        titleKey = normalize(titleKey);
        subtitleKey = normalize(subtitleKey);
        toastTitleKey = normalize(toastTitleKey);
        toastDescriptionKey = normalize(toastDescriptionKey);
        iconMaterial = normalize(iconMaterial);
        soundKey = normalize(soundKey);
        claimKey = (claimKey == null || claimKey.isBlank()) ? null : claimKey.trim();
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
