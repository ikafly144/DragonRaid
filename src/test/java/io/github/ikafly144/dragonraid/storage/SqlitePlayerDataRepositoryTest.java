package io.github.ikafly144.dragonraid.storage;

import io.github.ikafly144.dragonraid.storage.PointTransactionResult.FailureReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlitePlayerDataRepositoryTest {

    @TempDir
    Path tempDir;

    private ExecutorService executor;
    private SqlitePlayerDataRepository repository;

    private SqlitePlayerDataRepository openRepository(String filename) {
        if (executor == null) {
            executor = Executors.newFixedThreadPool(8);
        }
        repository = new SqlitePlayerDataRepository(tempDir.resolve(filename), 4, executor);
        return repository;
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (repository != null) {
            repository.close();
        }
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void crudGetOrCreateAndFindByUuidAndName() {
        SqlitePlayerDataRepository repo = openRepository("crud.db");
        UUID uuid = UUID.randomUUID();

        assertTrue(repo.findByUuid(uuid).join().isEmpty());
        assertTrue(repo.findByName("Steve").join().isEmpty());
        assertTrue(repo.findByName("   ").join().isEmpty());

        PlayerRaidStats created = repo.getOrCreate(uuid, "Steve").join();
        assertEquals(uuid, created.uuid());
        assertEquals("Steve", created.lastKnownName());
        assertEquals(0L, created.virtualPoints());
        assertEquals(0L, created.totalEarnedPoints());
        assertEquals(0, created.totalClears());
        assertNull(created.bestNormalTimeMs());
        assertNull(created.bestHardTimeMs());

        Optional<PlayerRaidStats> byUuid = repo.findByUuid(uuid).join();
        assertTrue(byUuid.isPresent());
        assertEquals(created, byUuid.get());

        // Case-insensitive lookup by name
        Optional<PlayerRaidStats> byName = repo.findByName("steve").join();
        assertTrue(byName.isPresent());
        assertEquals(uuid, byName.get().uuid());

        repo.updateLastKnownName(uuid, "SteveRenamed").join();
        assertTrue(repo.findByName("Steve").join().isEmpty());
        assertEquals("SteveRenamed", repo.findByUuid(uuid).join().orElseThrow().lastKnownName());
    }

    @Test
    void addPointsAndSetPointsTrackBalanceAndTotalEarned() {
        SqlitePlayerDataRepository repo = openRepository("points.db");
        UUID uuid = UUID.randomUUID();

        // Invalid amount does not create a phantom player row
        PointTransactionResult invalid = repo.addPoints(uuid, "Alex", 0L).join();
        assertFalse(invalid.success());
        assertEquals(FailureReason.INVALID_AMOUNT, invalid.failureReason());
        assertTrue(repo.findByUuid(uuid).join().isEmpty());

        PointTransactionResult first = repo.addPoints(uuid, "Alex", 50L).join();
        assertTrue(first.success());
        assertEquals(0L, first.previousBalance());
        assertEquals(50L, first.newBalance());
        assertEquals(50L, first.amountChanged());
        assertEquals(FailureReason.NONE, first.failureReason());

        PointTransactionResult second = repo.addPoints(uuid, 25L).join();
        assertTrue(second.success());
        assertEquals(50L, second.previousBalance());
        assertEquals(75L, second.newBalance());
        assertEquals(25L, second.amountChanged());

        PointTransactionResult overflow = repo.addPoints(uuid, Long.MAX_VALUE).join();
        assertFalse(overflow.success());
        assertEquals(FailureReason.BALANCE_OVERFLOW, overflow.failureReason());
        assertEquals(75L, overflow.newBalance());

        PointTransactionResult adminSet = repo.setPoints(uuid, "Alex", 10L).join();
        assertTrue(adminSet.success());
        assertEquals(75L, adminSet.previousBalance());
        assertEquals(10L, adminSet.newBalance());

        PlayerRaidStats stats = repo.findByUuid(uuid).join().orElseThrow();
        assertEquals(10L, stats.virtualPoints());
        assertEquals(75L, stats.totalEarnedPoints());

        PointTransactionResult negativeSet = repo.setPoints(uuid, "Alex", -1L).join();
        assertFalse(negativeSet.success());
        assertEquals(FailureReason.INVALID_AMOUNT, negativeSet.failureReason());
        assertEquals(10L, negativeSet.newBalance());
    }

    @Test
    void trySpendPointsSufficientAndInsufficient() {
        SqlitePlayerDataRepository repo = openRepository("spend.db");
        UUID uuid = UUID.randomUUID();

        PointTransactionResult missing = repo.trySpendPoints(uuid, 10L).join();
        assertFalse(missing.success());
        assertEquals(FailureReason.PLAYER_NOT_FOUND, missing.failureReason());
        assertEquals(0L, missing.newBalance());

        repo.addPoints(uuid, "Miner", 100L).join();

        PointTransactionResult invalidSpend = repo.trySpendPoints(uuid, -5L).join();
        assertFalse(invalidSpend.success());
        assertEquals(FailureReason.INVALID_AMOUNT, invalidSpend.failureReason());
        assertEquals(100L, invalidSpend.newBalance());

        PointTransactionResult sufficient = repo.trySpendPoints(uuid, 40L).join();
        assertTrue(sufficient.success());
        assertEquals(100L, sufficient.previousBalance());
        assertEquals(60L, sufficient.newBalance());
        assertEquals(-40L, sufficient.amountChanged());

        PointTransactionResult insufficient = repo.trySpendPoints(uuid, 61L).join();
        assertFalse(insufficient.success());
        assertEquals(FailureReason.INSUFFICIENT_FUNDS, insufficient.failureReason());
        assertEquals(60L, insufficient.newBalance());

        PlayerRaidStats stats = repo.findByUuid(uuid).join().orElseThrow();
        assertEquals(60L, stats.virtualPoints());
        assertEquals(100L, stats.totalEarnedPoints());
    }

    @Test
    void concurrentTrySpendPointsPreventsNegativeBalance() throws Exception {
        SqlitePlayerDataRepository repo = openRepository("concurrent-spend.db");
        UUID uuid = UUID.randomUUID();
        repo.addPoints(uuid, "Raider", 100L).join();

        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<PointTransactionResult>> futures = new ArrayList<>();

        try (ExecutorService callers = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return repo.trySpendPoints(uuid, 15L).join();
                }, callers));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successes = 0;
            int failures = 0;
            for (CompletableFuture<PointTransactionResult> future : futures) {
                PointTransactionResult result = future.join();
                if (result.success()) {
                    successes++;
                } else {
                    assertEquals(FailureReason.INSUFFICIENT_FUNDS, result.failureReason());
                    failures++;
                }
            }

            assertEquals(6, successes);
            assertEquals(10, failures);
            assertEquals(10L, repo.findByUuid(uuid).join().orElseThrow().virtualPoints());
        }
    }

    @Test
    void recordClearUpdatesCountsAndBestTimesOnlyWhenFaster() {
        SqlitePlayerDataRepository repo = openRepository("clears.db");
        UUID uuid = UUID.randomUUID();

        PlayerRaidStats firstNormal = repo.recordClear(uuid, "Slayer", Difficulty.NORMAL, 320_000L).join();
        assertEquals(1, firstNormal.normalClears());
        assertEquals(0, firstNormal.hardClears());
        assertEquals(320_000L, firstNormal.bestNormalTimeMs());
        assertNull(firstNormal.bestHardTimeMs());
        assertEquals(OptionalLong.of(320_000L), firstNormal.bestTimeMs(Difficulty.NORMAL));
        assertEquals(OptionalLong.empty(), firstNormal.bestTimeMs(Difficulty.HARD));

        // Slower clear must keep existing best time
        PlayerRaidStats slowerNormal = repo.recordClear(uuid, "Slayer", Difficulty.NORMAL, 410_000L).join();
        assertEquals(2, slowerNormal.normalClears());
        assertEquals(320_000L, slowerNormal.bestNormalTimeMs());

        // Faster clear replaces best time
        PlayerRaidStats fasterNormal = repo.recordClear(uuid, "Slayer", Difficulty.NORMAL, 275_500L).join();
        assertEquals(3, fasterNormal.normalClears());
        assertEquals(275_500L, fasterNormal.bestNormalTimeMs());

        // Hard difficulty tracked independently
        PlayerRaidStats firstHard = repo.recordClear(uuid, "Slayer", Difficulty.HARD, 540_000L).join();
        assertEquals(3, firstHard.normalClears());
        assertEquals(1, firstHard.hardClears());
        assertEquals(4, firstHard.totalClears());
        assertEquals(275_500L, firstHard.bestNormalTimeMs());
        assertEquals(540_000L, firstHard.bestHardTimeMs());

        PlayerRaidStats fasterHard = repo.recordClear(uuid, "Slayer", Difficulty.HARD, 480_000L).join();
        assertEquals(2, fasterHard.clears(Difficulty.HARD));
        assertEquals(480_000L, fasterHard.bestHardTimeMs());
    }

    @Test
    void incrementDeathsRevivesAndMultiCurrencyAtomicRollback() {
        SqlitePlayerDataRepository repo = openRepository("currencies.db");
        UUID uuid = UUID.randomUUID();

        repo.incrementDeaths(uuid, "Healer").join();
        repo.incrementDeaths(uuid).join();
        PlayerRaidStats stats = repo.incrementRevives(uuid, "Healer").join();
        assertEquals(2, stats.totalDeaths());
        assertEquals(1, stats.totalRevives());

        assertTrue(repo.addCurrency(uuid, "Healer", "dragon_token", 50L).join().success());
        assertTrue(repo.addCurrency(uuid, "Healer", "abyssal_scale", 10L).join().success());

        // Multi-currency spend must roll back dragon_token when abyssal_scale is insufficient
        boolean failedPurchase = repo.trySpendCurrencies(uuid, Map.of(
            "dragon_token", 30L,
            "abyssal_scale", 15L
        )).join();
        assertFalse(failedPurchase);
        assertEquals(50L, repo.getCurrencyBalance(uuid, "dragon_token").join());
        assertEquals(10L, repo.getCurrencyBalance(uuid, "abyssal_scale").join());

        // Sufficient multi-currency spend succeeds atomically
        boolean succeededPurchase = repo.trySpendCurrencies(uuid, Map.of(
            "dragon_token", 30L,
            "abyssal_scale", 10L
        )).join();
        assertTrue(succeededPurchase);
        assertEquals(20L, repo.getCurrencyBalance(uuid, "dragon_token").join());
        assertEquals(0L, repo.getCurrencyBalance(uuid, "abyssal_scale").join());

        Map<String, Long> balances = repo.getCurrencyBalances(uuid).join();
        assertEquals(Map.of("abyssal_scale", 0L, "dragon_token", 20L), balances);

        // Single currency set & spend
        assertTrue(repo.setCurrency(uuid, "Healer", "celestial_fragment", 12L).join().success());
        assertTrue(repo.trySpendCurrency(uuid, "celestial_fragment", 5L).join().success());
        assertEquals(7L, repo.getCurrencyBalance(uuid, "celestial_fragment").join());
    }

    @Test
    void persistsAcrossReopenAndRejectsOperationsAfterClose() {
        Path dbFile = tempDir.resolve("persist.db");
        UUID uuid = UUID.randomUUID();

        try (SqlitePlayerDataRepository first = openRepository("persist.db")) {
            first.addPoints(uuid, "Persisted", 120L).join();
            first.recordClear(uuid, "Persisted", Difficulty.NORMAL, 180_000L).join();
            first.addCurrency(uuid, "Persisted", "dragon_token", 35L).join();
        }
        // Prevent @AfterEach from double-closing an already closed instance, though close() is idempotent.
        repository = null;

        try (SqlitePlayerDataRepository reopened = new SqlitePlayerDataRepository(dbFile, 2, executor)) {
            PlayerRaidStats loaded = reopened.findByUuid(uuid).join().orElseThrow();
            assertEquals("Persisted", loaded.lastKnownName());
            assertEquals(120L, loaded.virtualPoints());
            assertEquals(1, loaded.normalClears());
            assertEquals(180_000L, loaded.bestNormalTimeMs());
            assertEquals(35L, reopened.getCurrencyBalance(uuid, "dragon_token").join());

            reopened.close();
            reopened.close(); // idempotent close
            assertThrows(IllegalStateException.class, () -> reopened.findByUuid(uuid));
        }
        assertNotNull(dbFile);
    }

    @Test
    void domainValidationAndDifficultyParsing() {
        assertEquals(Difficulty.NORMAL, Difficulty.fromKey("normal"));
        assertEquals(Difficulty.HARD, Difficulty.fromKey(" HARD "));
        assertThrows(IllegalArgumentException.class, () -> Difficulty.fromKey("extreme"));

        UUID uuid = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
            new PlayerRaidStats(uuid, "Bad", -1L, 0L, 0, 0, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
            new PlayerRaidStats(uuid, "Bad", 0L, 0L, 0, 0, -5L, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
            new PointTransactionResult(true, 0L, 10L, 10L, FailureReason.INSUFFICIENT_FUNDS));
        assertThrows(IllegalArgumentException.class, () ->
            new PointTransactionResult(false, 0L, 0L, 0L, FailureReason.NONE));
    }

    @Test
    void closeWaitsForInFlightAsyncWritesBeforeClosingDataSource() throws Exception {
        Path dbFile = tempDir.resolve("drain.db");
        ExecutorService delayedPool = Executors.newSingleThreadExecutor();
        CountDownLatch releaseWrite = new CountDownLatch(1);
        UUID uuid = UUID.randomUUID();

        try {
            SqlitePlayerDataRepository repo = new SqlitePlayerDataRepository(
                dbFile, 2, delayedPool, Duration.ofSeconds(3));
            // Block the single-threaded executor briefly so addPoints stays queued when close() begins.
            delayedPool.execute(() -> {
                try {
                    releaseWrite.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            CompletableFuture<PointTransactionResult> writeFuture = repo.addPoints(uuid, "Queued", 75L);
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                releaseWrite.countDown();
            });
            releaser.start();

            repo.close();
            releaser.join(2000);
            assertTrue(writeFuture.isDone());
            assertTrue(writeFuture.join().success());

            try (SqlitePlayerDataRepository reopened = new SqlitePlayerDataRepository(dbFile, 1, Runnable::run)) {
                assertEquals(75L, reopened.findByUuid(uuid).join().orElseThrow().virtualPoints());
            }
        } finally {
            releaseWrite.countDown();
            delayedPool.shutdownNow();
            delayedPool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeTimesOutAndFailsDroppedAsyncTasksCleanlyWithoutCallbackDeadlock() {
        Path dbFile = tempDir.resolve("drop.db");
        Executor droppingExecutor = task -> {
            // Intentionally drop queued tasks without running them.
        };
        SqlitePlayerDataRepository repo = new SqlitePlayerDataRepository(
            dbFile, 1, droppingExecutor, Duration.ofMillis(80));
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();

        CompletableFuture<PointTransactionResult> first = repo.addPoints(firstUuid, "First", 25L);
        CompletableFuture<PointTransactionResult> second = repo.addPoints(secondUuid, "Second", 50L);
        // Simulate a synchronous dependent waiting on another unexecuted future.
        first.whenComplete((ignored, err) -> second.handle((v, e) -> null).join());

        repo.close();

        assertThrows(CancellationException.class, () -> first.get(2, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, () -> second.get(2, TimeUnit.SECONDS));
    }
}
