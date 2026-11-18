package net.sabafly.dragonraid.api.finder;

import java.util.Objects;

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
        int minAverageItemLevel
) {

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
    }
}
