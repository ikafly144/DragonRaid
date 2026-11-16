package net.sabafly.dragonraid.protection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingBlockRollback;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe lightweight tracker for temporary blocks and liquids placed during a raid encounter,
 * supporting per-player and global caps, snapshot export for restart recovery, and Folia
 * {@code RegionScheduler}-dispatched rollback.
 */
public final class TemporaryBlockTracker {

    /**
     * Result of attempting to track a newly placed temporary block or fluid.
     */
    public enum TrackOutcome {
        RECORDED,
        ALREADY_TRACKED,
        PLAYER_LIMIT_REACHED,
        TOTAL_LIMIT_REACHED
    }

    public record TrackedBlock(BlockPosKey key, @Nullable UUID ownerId, BlockData originalData) {
    }

    private record BlockRestoration(BlockPosKey key, BlockData data) {
    }

    private final Object lock = new Object();
    private final Map<BlockPosKey, TrackedBlock> entries = new LinkedHashMap<>();
    private final Map<UUID, Integer> playerCounts = new HashMap<>();
    private final Function<BlockData, String> blockDataSerializer;
    private final Function<String, BlockData> blockDataDeserializer;

    public TemporaryBlockTracker() {
        this(BlockData::getAsString, Bukkit::createBlockData);
    }

    public TemporaryBlockTracker(
            Function<BlockData, String> blockDataSerializer,
            Function<String, BlockData> blockDataDeserializer) {
        this.blockDataSerializer = Objects.requireNonNull(blockDataSerializer, "blockDataSerializer");
        this.blockDataDeserializer = Objects.requireNonNull(blockDataDeserializer, "blockDataDeserializer");
    }

    /**
     * Records a player-initiated placement (block or bucket fluid) enforcing both per-player and total limits.
     */
    public TrackOutcome tryRecordPlayerPlacement(
            Location location,
            UUID playerId,
            BlockData originalData,
            int maxPerPlayer,
            int maxTotal) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(originalData, "originalData");
        return tryRecordInternal(BlockPosKey.of(location), playerId, originalData, maxPerPlayer, maxTotal);
    }

    /**
     * Records a secondary/system block or fluid spread (e.g. water flow, cobblestone formation)
     * enforcing only the total cap.
     */
    public TrackOutcome tryRecordSecondary(
            Location location,
            BlockData originalData,
            int maxTotal) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(originalData, "originalData");
        return tryRecordInternal(BlockPosKey.of(location), null, originalData, Integer.MAX_VALUE, maxTotal);
    }

    private TrackOutcome tryRecordInternal(
            BlockPosKey key,
            @Nullable UUID ownerId,
            BlockData originalData,
            int maxPerPlayer,
            int maxTotal) {
        synchronized (lock) {
            if (entries.containsKey(key)) {
                return TrackOutcome.ALREADY_TRACKED;
            }
            if (entries.size() >= maxTotal) {
                return TrackOutcome.TOTAL_LIMIT_REACHED;
            }
            if (ownerId != null) {
                int currentPlayerCount = playerCounts.getOrDefault(ownerId, 0);
                if (currentPlayerCount >= maxPerPlayer) {
                    return TrackOutcome.PLAYER_LIMIT_REACHED;
                }
                playerCounts.put(ownerId, currentPlayerCount + 1);
            }
            entries.put(key, new TrackedBlock(key, ownerId, originalData.clone()));
            return TrackOutcome.RECORDED;
        }
    }

    /**
     * Returns {@code true} if the block at {@code location} is currently tracked as temporary.
     */
    public boolean isTracked(@Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        BlockPosKey key = BlockPosKey.of(location);
        synchronized (lock) {
            return entries.containsKey(key);
        }
    }

    /**
     * Removes tracking for {@code location} when a temporary block is broken or collected.
     */
    public boolean untrack(@Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        BlockPosKey key = BlockPosKey.of(location);
        synchronized (lock) {
            TrackedBlock removed = entries.remove(key);
            if (removed == null) {
                return false;
            }
            if (removed.ownerId() != null) {
                playerCounts.computeIfPresent(removed.ownerId(), (id, count) -> count > 1 ? count - 1 : null);
            }
            return true;
        }
    }

    /**
     * Returns the current number of tracked temporary blocks.
     */
    public int totalCount() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /**
     * Returns the current number of tracked blocks attributed to {@code playerId}.
     */
    public int playerCount(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            return playerCounts.getOrDefault(playerId, 0);
        }
    }

    /**
     * Exports a serialized snapshot of all currently tracked blocks for durable persistence.
     */
    public List<PendingBlockRollback> exportPendingRollbacks() {
        List<TrackedBlock> snapshot;
        synchronized (lock) {
            if (entries.isEmpty()) {
                return List.of();
            }
            snapshot = new ArrayList<>(entries.values());
        }
        List<PendingBlockRollback> exported = new ArrayList<>(snapshot.size());
        for (TrackedBlock tracked : snapshot) {
            BlockPosKey key = tracked.key();
            String serialized = blockDataSerializer.apply(tracked.originalData());
            if (serialized != null && !serialized.isBlank()) {
                exported.add(new PendingBlockRollback(key.worldId(), key.x(), key.y(), key.z(), serialized));
            }
        }
        return List.copyOf(exported);
    }

    /**
     * Clears all tracked entries without modifying the world.
     */
    public void clear() {
        synchronized (lock) {
            entries.clear();
            playerCounts.clear();
        }
    }

    /**
     * Atomically snapshots and clears all tracked blocks, then restores every coordinate
     * to its original {@link BlockData} on the owning Folia region thread grouped by chunk.
     *
     * @return future completing with the number of restored blocks
     */
    public CompletableFuture<Integer> rollbackAll(RaidScheduler scheduler, @Nullable World world) {
        Objects.requireNonNull(scheduler, "scheduler");
        List<TrackedBlock> snapshot;
        synchronized (lock) {
            if (entries.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            snapshot = new ArrayList<>(entries.values());
            entries.clear();
            playerCounts.clear();
        }

        if (world == null) {
            return CompletableFuture.completedFuture(0);
        }

        UUID targetWorldId = world.getUID();
        List<BlockRestoration> restorations = new ArrayList<>(snapshot.size());
        for (TrackedBlock tracked : snapshot) {
            if (tracked.key().worldId().equals(targetWorldId)) {
                restorations.add(new BlockRestoration(tracked.key(), tracked.originalData()));
            }
        }
        return dispatchChunkRollbacks(scheduler, world, restorations)
                .thenApply(List::size);
    }

    /**
     * Restores persisted {@link PendingBlockRollback} entries across their target worlds and chunks
     * via Folia {@code RegionScheduler}, returning the keys that were successfully restored.
     */
    public CompletableFuture<List<BlockPosKey>> rollbackPendingEntries(
            RaidScheduler scheduler,
            Function<UUID, @Nullable World> worldLookup,
            List<PendingBlockRollback> pending) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(worldLookup, "worldLookup");
        Objects.requireNonNull(pending, "pending");
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        Map<UUID, List<BlockRestoration>> byWorld = new LinkedHashMap<>();
        for (PendingBlockRollback entry : pending) {
            try {
                BlockData data = blockDataDeserializer.apply(entry.blockDataString());
                if (data != null) {
                    byWorld.computeIfAbsent(entry.worldId(), ignored -> new ArrayList<>())
                            .add(new BlockRestoration(entry.toPosKey(), data));
                }
            } catch (IllegalArgumentException ignored) {
                // Skip invalid serialized block data safely.
            }
        }

        List<CompletableFuture<List<BlockPosKey>>> worldFutures = new ArrayList<>();
        for (Map.Entry<UUID, List<BlockRestoration>> worldEntry : byWorld.entrySet()) {
            World world = worldLookup.apply(worldEntry.getKey());
            if (world == null) {
                continue;
            }
            worldFutures.add(dispatchChunkRollbacks(scheduler, world, worldEntry.getValue()));
        }

        if (worldFutures.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return CompletableFuture.allOf(worldFutures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<BlockPosKey> restored = new ArrayList<>();
                    for (CompletableFuture<List<BlockPosKey>> future : worldFutures) {
                        restored.addAll(future.join());
                    }
                    return Collections.unmodifiableList(restored);
                });
    }

    private CompletableFuture<List<BlockPosKey>> dispatchChunkRollbacks(
            RaidScheduler scheduler,
            World world,
            List<BlockRestoration> restorations) {
        if (restorations.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        Map<Long, List<BlockRestoration>> byChunk = new LinkedHashMap<>();
        for (BlockRestoration item : restorations) {
            byChunk.computeIfAbsent(item.key().chunkKey(), ignored -> new ArrayList<>()).add(item);
        }

        List<BlockPosKey> restoredKeys = Collections.synchronizedList(new ArrayList<>(restorations.size()));
        AtomicInteger completedCount = new AtomicInteger(0);
        List<CompletableFuture<Void>> chunkFutures = new ArrayList<>(byChunk.size());
        for (List<BlockRestoration> chunkBlocks : byChunk.values()) {
            BlockPosKey first = chunkBlocks.getFirst().key();
            CompletableFuture<Void> chunkFuture = new CompletableFuture<>();
            chunkFutures.add(chunkFuture);
            scheduler.runAtRegion(world, first.chunkX(), first.chunkZ(), () -> {
                try {
                    for (BlockRestoration item : chunkBlocks) {
                        BlockPosKey key = item.key();
                        Block block = world.getBlockAt(key.x(), key.y(), key.z());
                        block.setBlockData(item.data(), false);
                        restoredKeys.add(key);
                        completedCount.incrementAndGet();
                    }
                    chunkFuture.complete(null);
                } catch (RuntimeException ex) {
                    chunkFuture.completeExceptionally(ex);
                }
            });
        }

        return CompletableFuture.allOf(chunkFutures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> List.copyOf(restoredKeys));
    }
}
