package net.sabafly.dragonraid.storage;

import net.sabafly.dragonraid.storage.PointTransactionResult.FailureReason;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void persistsAchievementsUnlocksRewardsClearsAndConcurrentServerFirst() throws Exception {
        Path dbFile = tempDir.resolve("progression.db");
        SqlitePlayerDataRepository repo = openRepository("progression.db");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        // Achievements
        assertTrue(repo.grantAchievement(alice, "Alice", "touch_end_portal", 1000L).join());
        assertFalse(repo.grantAchievement(alice, "Alice", "touch_end_portal", 2000L).join());
        assertTrue(repo.hasAchievement(alice, "touch_end_portal").join());
        assertEquals(Set.of("touch_end_portal"), repo.loadAchievements(alice).join());

        // Content unlocks + revoke
        assertTrue(repo.grantContentUnlock(alice, "Alice", "ender_dragon_normal", 1000L).join());
        assertFalse(repo.grantContentUnlock(alice, "Alice", "ender_dragon_normal", 1500L).join());
        assertTrue(repo.hasContentUnlock(alice, "ender_dragon_normal").join());
        assertEquals(Set.of("ender_dragon_normal"), repo.loadUnlockedContents(alice).join());
        assertTrue(repo.revokeContentUnlock(alice, "ender_dragon_normal").join());
        assertFalse(repo.hasContentUnlock(alice, "ender_dragon_normal").join());
        assertTrue(repo.grantContentUnlock(alice, "Alice", "ender_dragon_hard", 3000L).join());

        // Claimed rewards
        assertTrue(repo.tryClaimReward(alice, "Alice", "unlock_normal_on_portal", 1000L).join());
        assertFalse(repo.tryClaimReward(alice, "Alice", "unlock_normal_on_portal", 2000L).join());
        assertTrue(repo.tryClaimReward(alice, "Alice", "sf_bonus", 2500L).join());
        assertTrue(repo.hasClaimedReward(alice, "unlock_normal_on_portal").join());
        assertEquals(Set.of("unlock_normal_on_portal", "sf_bonus"), repo.loadClaimedRewards(alice).join());

        // Per-encounter clears
        assertTrue(repo.recordEncounterClear(alice, "Alice", "ender_dragon_normal", 200_000L, 1000L).join());
        assertFalse(repo.recordEncounterClear(alice, "Alice", "ender_dragon_normal", 150_000L, 2000L).join());
        assertTrue(repo.hasClearedEncounter(alice, "ender_dragon_normal").join());
        assertEquals(Set.of("ender_dragon_normal"), repo.loadClearedEncounters(alice).join());

        // Concurrent server-first atomicity
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger(0);
        ExecutorService racePool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                UUID winner = (i % 2 == 0) ? alice : bob;
                futures.add(racePool.submit((Callable<Void>) () -> {
                    ready.countDown();
                    start.await(2, TimeUnit.SECONDS);
                    if (repo.tryClaimServerFirst("encounter:ender_dragon_normal", List.of(winner), 5000L).join()) {
                        wins.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, wins.get());
        } finally {
            racePool.shutdownNow();
            racePool.awaitTermination(2, TimeUnit.SECONDS);
        }

        repo.close();
        repository = null;

        // Reopen DB and verify persistence
        try (SqlitePlayerDataRepository reopened = new SqlitePlayerDataRepository(dbFile, 2, executor)) {
            assertTrue(reopened.hasAchievement(alice, "touch_end_portal").join());
            assertTrue(reopened.hasContentUnlock(alice, "ender_dragon_hard").join());
            assertFalse(reopened.hasContentUnlock(alice, "ender_dragon_normal").join());
            assertTrue(reopened.hasClaimedReward(alice, "unlock_normal_on_portal").join());
            assertEquals(Set.of("unlock_normal_on_portal", "sf_bonus"), reopened.loadClaimedRewards(alice).join());
            assertTrue(reopened.hasClearedEncounter(alice, "ender_dragon_normal").join());
            assertTrue(reopened.isServerFirstAchieved("encounter:ender_dragon_normal").join());
            assertTrue(reopened.loadAllServerMilestones().join().contains("encounter:ender_dragon_normal"));
        }
    }

    @Test
    void addCurrencyWithCaps_enforcesMaxBalanceAndWeeklyCapAtomically() {
        SqlitePlayerDataRepository repo = openRepository("currency_caps.db");
        UUID alice = UUID.randomUUID();

        PointTransactionResult first = repo.addCurrencyWithCaps(alice, "Alice", "coin", 60L, 100L, 80L, "2026-W47").join();
        assertTrue(first.success());
        assertEquals(60L, first.newBalance());

        // Exceeding weekly cap rejects without modifying balance
        PointTransactionResult overWeekly = repo.addCurrencyWithCaps(alice, "Alice", "coin", 30L, 100L, 80L, "2026-W47").join();
        assertFalse(overWeekly.success());
        assertEquals(FailureReason.BALANCE_OVERFLOW, overWeekly.failureReason());
        assertEquals(60L, repo.getCurrencyBalance(alice, "coin").join());

        // Reaching exact weekly cap succeeds
        PointTransactionResult exactWeekly = repo.addCurrencyWithCaps(alice, "Alice", "coin", 20L, 100L, 80L, "2026-W47").join();
        assertTrue(exactWeekly.success());
        assertEquals(80L, exactWeekly.newBalance());

        // New week resets weekly quota, but maxBalance=100 still blocks +30 (80+30 > 100)
        PointTransactionResult overMax = repo.addCurrencyWithCaps(alice, "Alice", "coin", 30L, 100L, 80L, "2026-W48").join();
        assertFalse(overMax.success());
        assertEquals(FailureReason.BALANCE_OVERFLOW, overMax.failureReason());
        assertEquals(80L, repo.getCurrencyBalance(alice, "coin").join());

        PointTransactionResult exactMax = repo.addCurrencyWithCaps(alice, "Alice", "coin", 20L, 100L, 80L, "2026-W48").join();
        assertTrue(exactMax.success());
        assertEquals(100L, exactMax.newBalance());
    }

    @Test
    void legacyClearMigration_populatesClearsOnceAndSkipsOnSubsequentOpens() throws Exception {
        Path dbFile = tempDir.resolve("legacy_migration.db");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        // Construct a pre-M2 SQLite database with normal_clears/hard_clears populated
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                    CREATE TABLE player_stats (
                        uuid TEXT PRIMARY KEY,
                        last_known_name TEXT,
                        virtual_points INTEGER NOT NULL DEFAULT 0,
                        total_earned_points INTEGER NOT NULL DEFAULT 0,
                        normal_clears INTEGER NOT NULL DEFAULT 0,
                        hard_clears INTEGER NOT NULL DEFAULT 0,
                        best_normal_time_ms INTEGER,
                        best_hard_time_ms INTEGER,
                        total_deaths INTEGER NOT NULL DEFAULT 0,
                        total_revives INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT (unixepoch())
                    )
                    """);
            stmt.executeUpdate("INSERT INTO player_stats (uuid, last_known_name, normal_clears, hard_clears) VALUES ('"
                    + alice + "', 'Alice', 2, 1)");
            stmt.executeUpdate("INSERT INTO player_stats (uuid, last_known_name, normal_clears, hard_clears) VALUES ('"
                    + bob + "', 'Bob', 1, 0)");
        }

        try (SqlitePlayerDataRepository repo = new SqlitePlayerDataRepository(dbFile, 2, Runnable::run)) {
            assertEquals(java.util.Set.of("dragon_normal", "dragon_hard"), repo.loadClearedEncounters(alice).join());
            assertEquals(java.util.Set.of("dragon_normal"), repo.loadClearedEncounters(bob).join());
        }

        // Remove migrated clear rows directly; reopening must NOT re-run m2_migrate_legacy_encounter_clears_v1
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM player_encounter_clears WHERE uuid = '" + bob + "'");
        }

        try (SqlitePlayerDataRepository reopened = new SqlitePlayerDataRepository(dbFile, 2, Runnable::run)) {
            assertTrue(reopened.loadClearedEncounters(bob).join().isEmpty());
            assertEquals(java.util.Set.of("dragon_normal", "dragon_hard"), reopened.loadClearedEncounters(alice).join());
        }
    }

    @Test
    void serverFirstPendingRecipients_resumesIncompleteAndPreventsDuplicates() {
        SqlitePlayerDataRepository repo = openRepository("sf_pending.db");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();

        java.util.Set<UUID> initial = repo.claimOrResumeServerFirstRecipients(
                "ach:server_first_normal", List.of(alice, bob), 1000L).join();
        assertEquals(java.util.Set.of(alice, bob), initial);
        assertEquals(List.of(alice, bob), repo.loadServerMilestoneParticipants("ach:server_first_normal").join());

        // Mark Alice completed; Bob remains pending
        repo.completeServerFirstRecipient("ach:server_first_normal", alice).join();
        java.util.Set<UUID> resumed = repo.claimOrResumeServerFirstRecipients(
                "ach:server_first_normal", List.of(alice, bob), 2000L).join();
        assertEquals(java.util.Set.of(bob), resumed);

        // Mark Bob completed; subsequent claims (or unrelated party) yield nothing
        repo.completeServerFirstRecipient("ach:server_first_normal", bob).join();
        assertTrue(repo.claimOrResumeServerFirstRecipients(
                "ach:server_first_normal", List.of(alice, bob, charlie), 3000L).join().isEmpty());
    }

    @Test
    void rewardPlans_serverMilestoneState_pendingQueries_andIdempotentCurrencyWithWeeklyAccrual() {
        SqlitePlayerDataRepository repo = openRepository("plans_idem.db");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        // Durable reward plans CRUD + server milestone state
        assertTrue(repo.saveRewardPlanIfAbsent(alice, "Alice", "plan_1", "{\"v\":1}", 100L).join());
        assertFalse(repo.saveRewardPlanIfAbsent(alice, "Alice", "plan_1", "{\"v\":2}", 200L).join());
        assertEquals("{\"v\":1}", repo.loadRewardPlan(alice, "plan_1").join().orElseThrow());
        repo.updateRewardPlanProgress(alice, "plan_1", "{\"v\":1,\"step\":1}", 300L).join();
        assertEquals(Map.of("plan_1", "{\"v\":1,\"step\":1}"), repo.loadAllRewardPlans(alice).join());
        repo.deleteRewardPlan(alice, "plan_1").join();
        assertTrue(repo.loadRewardPlan(alice, "plan_1").join().isEmpty());

        repo.saveServerMilestoneState("m1:gw_target", "5", 400L).join();
        assertEquals("5", repo.loadServerMilestoneState("m1:gw_target").join().orElseThrow());

        repo.claimOrResumeServerFirstRecipients("achievement:sf1", List.of(alice, bob), 500L).join();
        repo.completeServerFirstRecipient("achievement:sf1", alice).join();
        assertEquals(Set.of(bob), repo.loadPendingServerFirstRecipients("achievement:sf1").join());
        assertEquals(Set.of("achievement:sf1"), repo.loadPendingServerFirstMilestones(bob).join());
        assertTrue(repo.loadPendingServerFirstMilestones(alice).join().isEmpty());

        // Atomic idempotent currency deposit + weekly accrual when weeklyCap == 0 (#31)
        PointTransactionResult first = repo.addCurrencyWithCapsIdempotent(
                alice, "Alice", "silver", 40L, 200L, 0L, "2026-W47", "idem:k1", 600L).join();
        assertTrue(first.success());
        assertEquals(40L, first.newBalance());

        // Duplicate idempotency key succeeds without double-crediting
        PointTransactionResult dup = repo.addCurrencyWithCapsIdempotent(
                alice, "Alice", "silver", 40L, 200L, 0L, "2026-W47", "idem:k1", 700L).join();
        assertTrue(dup.success());
        assertEquals(40L, dup.newBalance());

        // Now enforce weeklyCap=60 on the same week -> previous 40L accrual counts! (+20 allowed, +30 capped)
        PointTransactionResult overCap = repo.addCurrencyWithCapsIdempotent(
                alice, "Alice", "silver", 30L, 200L, 60L, "2026-W47", "idem:k2", 800L).join();
        assertFalse(overCap.success());
        assertEquals(FailureReason.BALANCE_OVERFLOW, overCap.failureReason());

        PointTransactionResult fitCap = repo.addCurrencyWithCapsIdempotent(
                alice, "Alice", "silver", 20L, 200L, 60L, "2026-W47", "idem:k2", 900L).join();
        assertTrue(fitCap.success());
        assertEquals(60L, fitCap.newBalance());
    }
}
