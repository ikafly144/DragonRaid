package net.sabafly.dragonraid.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.sabafly.dragonraid.protection.BlockPosKey;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingBlockRollback;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingPlayerCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRaidRecoveryRepositoryTest {

    @TempDir
    Path tempDir;

    private ExecutorService executor;
    private SqliteDatabase database;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (database != null) {
            database.close();
        }
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void persistsAndReloadsPendingBlockRollbacksAndPlayerCleanupsAcrossReopen() {
        executor = Executors.newFixedThreadPool(4);
        Path dbPath = tempDir.resolve("recovery.db");
        database = new SqliteDatabase(dbPath, 2, executor);
        SqliteRaidRecoveryRepository repo = new SqliteRaidRecoveryRepository(database);

        UUID worldId = UUID.randomUUID();
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        PendingBlockRollback b1 = new PendingBlockRollback(worldId, 10, 64, -5, "minecraft:air");
        PendingBlockRollback b2 = new PendingBlockRollback(worldId, 11, 65, -5, "minecraft:end_stone");
        repo.replacePendingBlockRollbacksSync(List.of(b1, b2));

        PendingPlayerCleanup c1 = new PendingPlayerCleanup(player1, "SURVIVAL", false, true, false, true);
        PendingPlayerCleanup c2 = new PendingPlayerCleanup(player2, "ADVENTURE", false, true, false, false);
        repo.upsertPendingPlayerCleanupAsync(c1).join();
        repo.upsertPendingPlayerCleanupAsync(c2).join();

        database.close();

        // Reopen DB (simulating server restart)
        database = new SqliteDatabase(dbPath, 2, executor);
        SqliteRaidRecoveryRepository reopened = new SqliteRaidRecoveryRepository(database);

        List<PendingBlockRollback> loadedBlocks = reopened.loadPendingBlockRollbacksSync();
        assertEquals(2, loadedBlocks.size());
        assertTrue(loadedBlocks.contains(b1));
        assertTrue(loadedBlocks.contains(b2));

        Map<UUID, PendingPlayerCleanup> loadedCleanups = reopened.loadPendingPlayerCleanupsSync();
        assertEquals(2, loadedCleanups.size());
        assertEquals(c1, loadedCleanups.get(player1));
        assertEquals(c2, loadedCleanups.get(player2));

        // Partial delete removes only restored entries
        reopened.deletePendingBlockRollbacksAsync(List.of(new BlockPosKey(worldId, 10, 64, -5))).join();
        reopened.deletePendingPlayerCleanupAsync(player1).join();

        assertEquals(List.of(b2), reopened.loadPendingBlockRollbacksSync());
        assertEquals(Map.of(player2, c2), reopened.loadPendingPlayerCleanupsSync());
    }

    @Test
    void perUuidPlayerCleanupOpsExecuteInSubmissionOrderEvenUnderThreadPool() {
        executor = Executors.newFixedThreadPool(8);
        database = new SqliteDatabase(tempDir.resolve("ordered.db"), 4, executor);
        SqliteRaidRecoveryRepository repo = new SqliteRaidRecoveryRepository(database);

        UUID player = UUID.randomUUID();
        PendingPlayerCleanup finalRecord = new PendingPlayerCleanup(player, "ADVENTURE", true, false, true, true);

        // Rapid interleaved upsert -> delete -> upsert on multi-threaded pool must preserve finalRecord
        CompletableFuture<Void> f1 = repo.upsertPendingPlayerCleanupAsync(
                new PendingPlayerCleanup(player, "SURVIVAL", false, true, false, false));
        CompletableFuture<Void> f2 = repo.deletePendingPlayerCleanupAsync(player);
        CompletableFuture<Void> f3 = repo.upsertPendingPlayerCleanupAsync(finalRecord);
        CompletableFuture.allOf(f1, f2, f3).join();

        assertEquals(Map.of(player, finalRecord), repo.loadPendingPlayerCleanupsSync());
    }

    @Test
    void staleAsyncDeletesAfterSyncReplace_cannotDeleteNewSnapshotRowsEvenAfterDrainTimeout() {
        List<Runnable> manualQueue = new java.util.ArrayList<>();
        database = new SqliteDatabase(tempDir.resolve("epoch.db"), 2, manualQueue::add);
        SqliteRaidRecoveryRepository repo = new SqliteRaidRecoveryRepository(database, 10L);

        UUID worldId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        BlockPosKey key = new BlockPosKey(worldId, 5, 64, 5);
        PendingBlockRollback oldBlock = new PendingBlockRollback(worldId, 5, 64, 5, "minecraft:air");
        PendingBlockRollback newBlock = new PendingBlockRollback(worldId, 5, 64, 5, "minecraft:obsidian");
        PendingPlayerCleanup oldCleanup = new PendingPlayerCleanup(playerId, "SURVIVAL", false, true, false, true);
        PendingPlayerCleanup newCleanup = new PendingPlayerCleanup(playerId, "ADVENTURE", true, false, false, true);

        repo.replacePendingBlockRollbacksSync(List.of(oldBlock));
        repo.replacePendingPlayerCleanupsSync(List.of(oldCleanup));

        // Enqueue async deletes that do not run before sync replacement times out on 10ms drain
        CompletableFuture<Void> staleBlockDelete = repo.deletePendingBlockRollbacksAsync(List.of(key));
        CompletableFuture<Void> stalePlayerDelete = repo.deletePendingPlayerCleanupAsync(playerId);

        repo.replacePendingBlockRollbacksSync(List.of(newBlock));
        repo.replacePendingPlayerCleanupsSync(List.of(newCleanup));

        // Now flush the stale queued tasks -> epoch checks must ignore both stale deletes
        for (Runnable task : List.copyOf(manualQueue)) {
            task.run();
        }
        CompletableFuture.allOf(staleBlockDelete, stalePlayerDelete).join();

        assertEquals(List.of(newBlock), repo.loadPendingBlockRollbacksSync());
        assertEquals(Map.of(playerId, newCleanup), repo.loadPendingPlayerCleanupsSync());
    }

    @Test
    void failedSyncReplaceDoesNotAdvanceEpoch_andRejectedDeleteCleansPendingOps() {
        List<Runnable> manualQueue = new java.util.ArrayList<>();
        database = new SqliteDatabase(tempDir.resolve("failed-sync.db"), 2, task -> {
            if (manualQueue.isEmpty()) {
                manualQueue.add(task);
            } else {
                throw new java.util.concurrent.RejectedExecutionException("Simulated pool rejection");
            }
        });
        SqliteRaidRecoveryRepository repo = new SqliteRaidRecoveryRepository(database, 10L);

        UUID worldId = UUID.randomUUID();
        BlockPosKey k1 = new BlockPosKey(worldId, 1, 64, 1);
        PendingBlockRollback existing = new PendingBlockRollback(worldId, 1, 64, 1, "minecraft:stone");
        repo.replacePendingBlockRollbacksSync(List.of(existing));

        // 1st async delete queued before failed sync replace
        CompletableFuture<Void> queuedDelete = repo.deletePendingBlockRollbacksAsync(List.of(k1));
        // Duplicate primary key in replace forces SQLException rollback -> must NOT advance epoch
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> repo.replacePendingBlockRollbacksSync(List.of(existing, existing)));

        // Flushing queuedDelete should still apply because epoch did not advance on rolled-back replace
        manualQueue.getFirst().run();
        queuedDelete.join();
        assertTrue(repo.loadPendingBlockRollbacksSync().isEmpty());

        // 2nd async delete hits RejectedExecutionException -> returned future fails exceptionally AND does not leak op
        CompletableFuture<Void> rejectedDelete = repo.deletePendingBlockRollbacksAsync(List.of(k1));
        assertTrue(rejectedDelete.isCompletedExceptionally());
        long startNanos = System.nanoTime();
        repo.replacePendingBlockRollbacksSync(List.of(existing));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue(elapsedMs < 500L, "Rejected async delete must not stall sync replace");
    }

    @Test
    void persistsReturnLocationWithoutFlagsAndMigratesLegacySchemaAcrossReopen() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        Path dbPath = tempDir.resolve("return-migration.db");

        // Create legacy 6-column pending_player_cleanups schema and insert an existing row BEFORE repo init
        database = new SqliteDatabase(dbPath, 2, executor);
        UUID legacyPlayer = UUID.randomUUID();
        database.withWriteTransaction((conn, tx) -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE pending_player_cleanups (
                        uuid TEXT PRIMARY KEY,
                        game_mode TEXT,
                        invisible INTEGER NOT NULL,
                        collidable INTEGER NOT NULL,
                        invulnerable INTEGER NOT NULL,
                        teleport_to_safe_zone INTEGER NOT NULL
                    )
                    """);
            }
            try (var ps = conn.prepareStatement("""
                INSERT INTO pending_player_cleanups (
                    uuid, game_mode, invisible, collidable, invulnerable, teleport_to_safe_zone
                ) VALUES (?, 'ADVENTURE', 1, 0, 1, 1)
                """)) {
                ps.setString(1, legacyPlayer.toString());
                ps.executeUpdate();
            }
            return null;
        });

        SqliteRaidRecoveryRepository repo = new SqliteRaidRecoveryRepository(database);
        Map<UUID, PendingPlayerCleanup> migrated = repo.loadPendingPlayerCleanupsSync();
        PendingPlayerCleanup legacyLoaded = migrated.get(legacyPlayer);
        assertEquals("ADVENTURE", legacyLoaded.gameModeName());
        assertTrue(legacyLoaded.restoreFlags());
        assertTrue(!legacyLoaded.hasReturnLocation());

        UUID returnPlayer = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        PendingPlayerCleanup returnOnly = new PendingPlayerCleanup(
                returnPlayer, null, false, true, false, true, false,
                worldId, 125.5, 72.0, -48.25, 90.0f, -15.0f);
        repo.upsertPendingPlayerCleanupAsync(returnOnly).join();
        database.close();

        database = new SqliteDatabase(dbPath, 2, executor);
        SqliteRaidRecoveryRepository reopened = new SqliteRaidRecoveryRepository(database);
        Map<UUID, PendingPlayerCleanup> reloaded = reopened.loadPendingPlayerCleanupsSync();
        assertEquals(legacyLoaded, reloaded.get(legacyPlayer));
        assertEquals(returnOnly, reloaded.get(returnPlayer));
        assertTrue(reloaded.get(returnPlayer).hasReturnLocation());
    }
}
