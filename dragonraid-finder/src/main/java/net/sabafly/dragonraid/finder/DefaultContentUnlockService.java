package net.sabafly.dragonraid.finder;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;

/**
 * Thread-safe in-memory M1 implementation of {@link ContentUnlockService}.
 */
public final class DefaultContentUnlockService implements ContentUnlockService {

    private final Map<UUID, Set<String>> unlockedByPlayer = new ConcurrentHashMap<>();

    @Override
    public boolean isUnlocked(UUID playerId, String contentId) {
        if (playerId == null || contentId == null || contentId.isBlank()) {
            return false;
        }
        Set<String> unlocked = unlockedByPlayer.get(playerId);
        return unlocked != null && unlocked.contains(contentId);
    }

    @Override
    public boolean unlock(UUID playerId, String contentId) {
        Objects.requireNonNull(playerId, "playerId");
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId must not be blank");
        }
        return unlockedByPlayer
                .computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                .add(contentId);
    }

    @Override
    public Set<String> unlockedContents(UUID playerId) {
        if (playerId == null) {
            return Set.of();
        }
        Set<String> unlocked = unlockedByPlayer.get(playerId);
        return unlocked == null ? Set.of() : Set.copyOf(unlocked);
    }
}
