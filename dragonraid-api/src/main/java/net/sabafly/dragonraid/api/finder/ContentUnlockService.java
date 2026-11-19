package net.sabafly.dragonraid.api.finder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * SPI tracking data-driven content unlock flags per player.
 */
public interface ContentUnlockService {

    boolean isUnlocked(UUID playerId, String contentId);

    boolean unlock(UUID playerId, String contentId);

    Set<String> unlockedContents(UUID playerId);

    default boolean unlock(UUID playerId, @Nullable String playerName, String contentId) {
        return unlock(playerId, contentId);
    }

    default boolean revoke(UUID playerId, String contentId) {
        return false;
    }

    default CompletableFuture<Set<String>> loadPlayer(UUID playerId) {
        return CompletableFuture.completedFuture(unlockedContents(playerId));
    }

    default void evictPlayer(UUID playerId) {
    }
}
