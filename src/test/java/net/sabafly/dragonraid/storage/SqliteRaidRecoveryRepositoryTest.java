package net.sabafly.dragonraid.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
}
