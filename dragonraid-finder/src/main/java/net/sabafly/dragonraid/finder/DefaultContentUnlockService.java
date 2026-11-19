package net.sabafly.dragonraid.finder;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;

/**
 * Thread-safe {@link ContentUnlockService} with optional SQLite persistence backed by
 * {@link PlayerProgressionRepository}.
 */
public final class DefaultContentUnlockService implements ContentUnlockService {

    private final PlayerProgressionRepository repository;
    private final LongSupplier clockMillis;
    private final Logger logger;
    private final Map<UUID, Set<String>> unlockedByPlayer = new ConcurrentHashMap<>();

    public DefaultContentUnlockService() {
        this(null, System::currentTimeMillis, Logger.getLogger(DefaultContentUnlockService.class.getName()));
    }

    public DefaultContentUnlockService(PlayerProgressionRepository repository) {
        this(repository, System::currentTimeMillis, Logger.getLogger(DefaultContentUnlockService.class.getName()));
    }

    public DefaultContentUnlockService(
            PlayerProgressionRepository repository,
            LongSupplier clockMillis,
            Logger logger) {
        this.repository = repository;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public boolean isUnlocked(UUID playerId, String contentId) {
        if (playerId == null || contentId == null || contentId.isBlank()) {
            return false;
        }
        Set<String> unlocked = unlockedByPlayer.get(playerId);
        return unlocked != null && unlocked.contains(contentId.trim());
    }

    @Override
    public boolean unlock(UUID playerId, String contentId) {
        return unlock(playerId, null, contentId);
    }

    @Override
    public boolean unlock(UUID playerId, String playerName, String contentId) {
        Objects.requireNonNull(playerId, "playerId");
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId must not be blank");
        }
        String normalizedId = contentId.trim();
        boolean added = unlockedByPlayer
                .computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                .add(normalizedId);
        if (added && repository != null) {
            repository.grantContentUnlock(playerId, playerName, normalizedId, clockMillis.getAsLong())
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to persist content unlock " + normalizedId + " for " + playerId, ex);
                        return false;
                    });
        }
        return added;
    }

    @Override
    public boolean revoke(UUID playerId, String contentId) {
        if (playerId == null || contentId == null || contentId.isBlank()) {
            return false;
        }
        String normalizedId = contentId.trim();
        Set<String> unlocked = unlockedByPlayer.get(playerId);
        boolean removed = unlocked != null && unlocked.remove(normalizedId);
        if (repository != null) {
            repository.revokeContentUnlock(playerId, normalizedId)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to revoke content unlock " + normalizedId + " for " + playerId, ex);
                        return false;
                    });
        }
        return removed;
    }

    @Override
    public Set<String> unlockedContents(UUID playerId) {
        if (playerId == null) {
            return Set.of();
        }
        Set<String> unlocked = unlockedByPlayer.get(playerId);
        return unlocked == null ? Set.of() : Set.copyOf(unlocked);
    }

    @Override
    public CompletableFuture<Set<String>> loadPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (repository == null) {
            return CompletableFuture.completedFuture(unlockedContents(playerId));
        }
        return repository.loadUnlockedContents(playerId).thenApply(loaded -> {
            Set<String> set = unlockedByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
            if (loaded != null) {
                set.addAll(loaded);
            }
            return Set.copyOf(set);
        });
    }

    @Override
    public void evictPlayer(UUID playerId) {
        if (playerId != null) {
            unlockedByPlayer.remove(playerId);
        }
    }
}
