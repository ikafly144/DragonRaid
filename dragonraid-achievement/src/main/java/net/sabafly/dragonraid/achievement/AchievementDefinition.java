package net.sabafly.dragonraid.achievement;

import java.util.Objects;

/**
 * Metadata describing an achievement unlocked via DragonRaid encounter/mechanic events.
 */
public record AchievementDefinition(
        String id,
        String titleKey,
        String descriptionKey
) {

    public AchievementDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(titleKey, "titleKey");
        Objects.requireNonNull(descriptionKey, "descriptionKey");
    }
}
