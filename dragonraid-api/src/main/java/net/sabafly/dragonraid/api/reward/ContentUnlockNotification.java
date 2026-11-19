package net.sabafly.dragonraid.api.reward;

import org.jetbrains.annotations.Nullable;

/**
 * Presentation payload used by {@link ContentUnlockNotifier} to announce newly unlocked content.
 */
public record ContentUnlockNotification(
        String contentId,
        String displayNameKey,
        String titleKey,
        String subtitleKey,
        String toastTitleKey,
        String toastDescriptionKey,
        String iconMaterial,
        String soundKey,
        float soundVolume,
        float soundPitch
) {

    public static final String DEFAULT_TITLE_KEY = "content.unlock.title";
    public static final String DEFAULT_SUBTITLE_KEY = "content.unlock.subtitle";
    public static final String DEFAULT_TOAST_TITLE_KEY = "content.unlock.toast-title";
    public static final String DEFAULT_TOAST_DESC_KEY = "content.unlock.toast-desc";
    public static final String DEFAULT_ICON_MATERIAL = "ENDER_EYE";
    public static final String DEFAULT_SOUND_KEY = "minecraft:ui.toast.challenge_complete";

    public ContentUnlockNotification {
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId must not be blank");
        }
        contentId = contentId.trim();
        displayNameKey = (displayNameKey == null || displayNameKey.isBlank()) ? contentId : displayNameKey.trim();
        titleKey = fallback(titleKey, DEFAULT_TITLE_KEY);
        subtitleKey = fallback(subtitleKey, DEFAULT_SUBTITLE_KEY);
        toastTitleKey = fallback(toastTitleKey, DEFAULT_TOAST_TITLE_KEY);
        toastDescriptionKey = fallback(toastDescriptionKey, DEFAULT_TOAST_DESC_KEY);
        iconMaterial = fallback(iconMaterial, DEFAULT_ICON_MATERIAL);
        soundKey = fallback(soundKey, DEFAULT_SOUND_KEY);
        if (!Float.isFinite(soundVolume) || soundVolume < 0.0f) {
            soundVolume = 1.0f;
        }
        if (!Float.isFinite(soundPitch) || soundPitch <= 0.0f) {
            soundPitch = 1.0f;
        }
    }

    public static ContentUnlockNotification defaults(String contentId, @Nullable String displayNameKey) {
        return new ContentUnlockNotification(
                contentId,
                displayNameKey,
                DEFAULT_TITLE_KEY,
                DEFAULT_SUBTITLE_KEY,
                DEFAULT_TOAST_TITLE_KEY,
                DEFAULT_TOAST_DESC_KEY,
                DEFAULT_ICON_MATERIAL,
                DEFAULT_SOUND_KEY,
                1.0f,
                1.0f
        );
    }

    private static String fallback(@Nullable String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
