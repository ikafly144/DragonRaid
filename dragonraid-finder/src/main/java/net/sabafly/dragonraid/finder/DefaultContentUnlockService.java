package net.sabafly.dragonraid.finder;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;

/**
 * Thread-safe {@link ContentUnlockService} serializing per-player cache and SQLite persistence
 * operations backed by {@link PlayerProgressionRepository}.
 */
public final class DefaultContentUnlockService implements ContentUnlockService {

    private final PlayerProgressionRepository repository;
    private final LongSupplier clockMillis;
    private final Logger logger;
    private final Map<UUID, Set<String>> unlockedByPlayer = new ConcurrentHashMap<>();
    private final Object queueLock = new Object();
    private final Map<UUID, CompletableFuture<Void>> playerQueues = new HashMap<>();

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
        if (repository != null) {
            throw new UnsupportedOperationException(
                    "Synchronous unlock() is unsupported with a persistent repository; use unlockAsync()");
        }
        return Boolean.TRUE.equals(unlockAsync(playerId, playerName, contentId).join());
    }

    @Override
    public CompletableFuture<Boolean> unlockAsync(UUID playerId, String playerName, String contentId) {
        Objects.requireNonNull(playerId, "playerId");
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId must not be blank");
        }
        String normalizedId = contentId.trim();
        return enqueueForPlayer(playerId, () -> {
            if (repository == null) {
                boolean added = unlockedByPlayer
                        .computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                        .add(normalizedId);
                return CompletableFuture.completedFuture(added);
            }
            Set<String> current = unlockedByPlayer.get(playerId);
            if (current != null && current.contains(normalizedId)) {
                return CompletableFuture.completedFuture(false);
            }
            CompletableFuture<Boolean> persistFuture = repository.grantContentUnlock(
                    playerId, playerName, normalizedId, clockMillis.getAsLong());
            if (persistFuture == null) {
                persistFuture = CompletableFuture.completedFuture(false);
            }
            return persistFuture.handle((inserted, ex) -> {
                if (ex != null) {
                    logger.log(Level.WARNING, "Failed to persist content unlock " + normalizedId + " for " + playerId, ex);
                    throw (ex instanceof RuntimeException re) ? re : new IllegalStateException(ex);
                }
                unlockedByPlayer
                        .computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                        .add(normalizedId);
                return Boolean.TRUE.equals(inserted);
            });
        });
    }

    @Override
    public boolean revoke(UUID playerId, String contentId) {
        if (repository != null) {
            throw new UnsupportedOperationException(
                    "Synchronous revoke() is unsupported with a persistent repository; use revokeAsync()");
        }
        if (playerId == null || contentId == null || contentId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(revokeAsync(playerId, contentId).join());
    }

    @Override
    public CompletableFuture<Boolean> revokeAsync(UUID playerId, String contentId) {
        if (playerId == null || contentId == null || contentId.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String normalizedId = contentId.trim();
        return enqueueForPlayer(playerId, () -> {
            if (repository == null) {
                Set<String> unlocked = unlockedByPlayer.get(playerId);
                return CompletableFuture.completedFuture(unlocked != null && unlocked.remove(normalizedId));
            }
            CompletableFuture<Boolean> revokeFuture = repository.revokeContentUnlock(playerId, normalizedId);
            if (revokeFuture == null) {
                revokeFuture = CompletableFuture.completedFuture(false);
            }
            return revokeFuture.handle((dbRemoved, ex) -> {
                if (ex != null) {
                    logger.log(Level.WARNING, "Failed to revoke content unlock " + normalizedId + " for " + playerId, ex);
                    throw (ex instanceof RuntimeException re) ? re : new IllegalStateException(ex);
                }
                Set<String> unlocked = unlockedByPlayer.get(playerId);
                boolean memRemoved = unlocked != null && unlocked.remove(normalizedId);
                return Boolean.TRUE.equals(dbRemoved) || memRemoved;
            });
        });
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
        return enqueueForPlayer(playerId, () -> {
            if (repository == null) {
                return CompletableFuture.completedFuture(unlockedContents(playerId));
            }
            CompletableFuture<Set<String>> loadFuture = repository.loadUnlockedContents(playerId);
            if (loadFuture == null) {
                return CompletableFuture.completedFuture(unlockedContents(playerId));
            }
            return loadFuture.thenApply(loaded -> {
                Set<String> fresh = ConcurrentHashMap.newKeySet();
                if (loaded != null) {
                    fresh.addAll(loaded);
                }
                unlockedByPlayer.put(playerId, fresh);
                return Set.copyOf(fresh);
            });
        });
    }

    @Override
    public void evictPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        enqueueForPlayer(playerId, () -> {
            unlockedByPlayer.remove(playerId);
            return CompletableFuture.completedFuture(null);
        });
    }

    private <T> CompletableFuture<T> enqueueForPlayer(UUID playerId, Supplier<CompletableFuture<T>> op) {
        CompletableFuture<Void> prev;
        CompletableFuture<Void> gate = new CompletableFuture<>();
        synchronized (queueLock) {
            prev = playerQueues.get(playerId);
            playerQueues.put(playerId, gate);
        }
        CompletableFuture<T> out = new CompletableFuture<>();
        Runnable start = () -> {
            try {
                CompletableFuture<T> inner = op.get();
                if (inner == null) {
                    finishPlayerOp(playerId, gate, out, null, null);
                } else {
                    inner.whenComplete((res, err) -> finishPlayerOp(playerId, gate, out, res, err));
                }
            } catch (Throwable ex) {
                finishPlayerOp(playerId, gate, out, null, ex);
            }
        };
        if (prev == null || prev.isDone()) {
            start.run();
        } else {
            prev.whenComplete((ignored, prevErr) -> start.run());
        }
        return out;
    }

    private <T> void finishPlayerOp(
            UUID playerId,
            CompletableFuture<Void> gate,
            CompletableFuture<T> out,
            T res,
            Throwable err) {
        synchronized (queueLock) {
            if (playerQueues.get(playerId) == gate) {
                playerQueues.remove(playerId);
            }
        }
        gate.complete(null);
        if (err != null) {
            out.completeExceptionally(err);
        } else {
            out.complete(res);
        }
    }
}
