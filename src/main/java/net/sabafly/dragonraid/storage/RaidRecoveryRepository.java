package net.sabafly.dragonraid.storage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.sabafly.dragonraid.protection.BlockPosKey;
import org.jetbrains.annotations.Nullable;

/**
 * Persistence contract for crash-safe and restart-safe recovery of temporary raid blocks
 * and modified player states (offline participants or pending cleanups).
 */
public interface RaidRecoveryRepository {

    record PendingBlockRollback(
            UUID worldId,
            int x,
            int y,
            int z,
            String blockDataString
    ) {
        public BlockPosKey toPosKey() {
            return new BlockPosKey(worldId, x, y, z);
        }
    }

    record PendingPlayerCleanup(
            UUID playerId,
            @Nullable String gameModeName,
            boolean invisible,
            boolean collidable,
            boolean invulnerable,
            boolean teleportToSafeZone
    ) {
    }

    List<PendingBlockRollback> loadPendingBlockRollbacksSync();

    void replacePendingBlockRollbacksSync(Collection<PendingBlockRollback> entries);

    CompletableFuture<Void> deletePendingBlockRollbacksAsync(Collection<BlockPosKey> keys);

    Map<UUID, PendingPlayerCleanup> loadPendingPlayerCleanupsSync();

    CompletableFuture<Void> upsertPendingPlayerCleanupAsync(PendingPlayerCleanup cleanup);

    void replacePendingPlayerCleanupsSync(Collection<PendingPlayerCleanup> cleanups);

    CompletableFuture<Void> deletePendingPlayerCleanupAsync(UUID playerId);
}
