package net.sabafly.dragonraid.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.achievement.AchievementTriggerType;
import net.sabafly.dragonraid.api.achievement.AchievementUnlockedEvent;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotifier;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardConditionSpec;
import net.sabafly.dragonraid.api.reward.RewardCurrencySink;
import net.sabafly.dragonraid.api.reward.RewardEntrySpec;
import net.sabafly.dragonraid.api.reward.RewardItemDelivery;
import net.sabafly.dragonraid.api.reward.RewardPool;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.RollRange;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.achievement.DefaultRaidAchievementService;
import net.sabafly.dragonraid.core.DefaultRaidServiceRegistry;
import net.sabafly.dragonraid.finder.DefaultContentFinderService;
import net.sabafly.dragonraid.finder.DefaultContentUnlockService;
import net.sabafly.dragonraid.storage.PointTransactionResult;
import net.sabafly.dragonraid.storage.SqlitePlayerDataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RewardDispatchListenerTest {

    @TempDir
    Path tempDir;

    @Test
    void dispatchEncounterRewards_usesPerPlayerFirstClearInMixedParty() {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        AtomicLong clock = new AtomicLong(10_000L);
        ScriptHookRegistry scriptHooks = mock(ScriptHookRegistry.class);
        RaidServiceRegistry registry = new DefaultRaidServiceRegistry();

        try (SqlitePlayerDataRepository repo = new SqlitePlayerDataRepository(
                tempDir.resolve("mixed_fc.db"), 2, Runnable::run)) {
            DefaultContentUnlockService unlockService = new DefaultContentUnlockService(repo, clock::get, Logger.getLogger("test"));
            DefaultContentFinderService finderService = new DefaultContentFinderService(unlockService);
            RewardCurrencySink sink = (uid, name, currencyId, amount) ->
                    repo.addCurrency(uid, name, currencyId, amount).thenApply(PointTransactionResult::success);
            RewardItemDelivery itemDelivery = mock(RewardItemDelivery.class);
            ContentUnlockNotifier notifier = (uid, note) -> {};

            DefaultRaidRewardService rewardService = new DefaultRaidRewardService(
                    unlockService, finderService, notifier, sink, itemDelivery,
                    scriptHooks, repo, registry, clock::get, Logger.getLogger("test"));
            DefaultRaidAchievementService achService = new DefaultRaidAchievementService(
                    repo, scriptHooks, registry, ev -> {}, clock::get, Logger.getLogger("test"));

            RewardTable table = new RewardTable("clear_tbl", List.of(
                    RewardPool.allOf("base", null, List.of(), List.of(
                            RewardEntrySpec.currency("dragon_token", RollRange.exact(5), 1, List.of(), List.of()))),
                    RewardPool.allOf("first", null, List.of(RewardConditionSpec.firstClear()), List.of(
                            RewardEntrySpec.currency("dragon_token", RollRange.exact(15), 1, List.of(), List.of())))));
            rewardService.replacePackTables(List.of(table), List.of());

            ContentPackCatalogSnapshot snapshotCatalog = new ContentPackCatalogSnapshot(
                    1L,
                    Map.of("raid_n", new ContentEntrySpec("raid_n", "k", "raid_n", "", 1, 8, 0, List.of("clear_tbl"))),
                    Map.of(),
                    Map.of("clear_tbl", table),
                    List.of());
            RewardDispatchListener listener = new RewardDispatchListener(
                    rewardService, finderService, achService, unlockService, repo, clock::get, Logger.getLogger("test"), () -> snapshotCatalog);

            EncounterResultSnapshot mixedVictory = new EncounterResultSnapshot(
                    "raid_n",
                    EncounterDifficulty.NORMAL,
                    EncounterOutcome.VICTORY,
                    60_000L,
                    true,
                    2,
                    Map.of(
                            aliceId, new ParticipantCombatMetrics(aliceId, "Alice", Map.of(), Map.of(EnemyRole.PRIMARY_BOSS, 100.0), Map.of(), 0, 0, 0),
                            bobId, new ParticipantCombatMetrics(bobId, "Bob", Map.of(), Map.of(EnemyRole.PRIMARY_BOSS, 100.0), Map.of(), 0, 0, 0)),
                    Set.of(aliceId));

            listener.dispatchEncounterRewards(mixedVictory).join();

            assertEquals(20L, repo.getCurrencyBalance(aliceId, "dragon_token").join());
            assertEquals(5L, repo.getCurrencyBalance(bobId, "dragon_token").join());
        }
    }

    @Test
    void dispatchEncounterRewards_resumesServerFirstForFailedParticipantWithoutDuplicatingSucceededOne() {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        AtomicLong clock = new AtomicLong(20_000L);
        ScriptHookRegistry scriptHooks = mock(ScriptHookRegistry.class);
        RaidServiceRegistry registry = new DefaultRaidServiceRegistry();
        AtomicBoolean failBobOnce = new AtomicBoolean(true);

        try (SqlitePlayerDataRepository repo = new SqlitePlayerDataRepository(
                tempDir.resolve("sf_resume.db"), 2, Runnable::run)) {
            DefaultContentUnlockService unlockService = new DefaultContentUnlockService(repo, clock::get, Logger.getLogger("test"));
            DefaultContentFinderService finderService = new DefaultContentFinderService(unlockService);
            RewardCurrencySink flakySink = (uid, name, currencyId, amount) -> {
                if (bobId.equals(uid) && failBobOnce.compareAndSet(true, false)) {
                    return CompletableFuture.completedFuture(false);
                }
                return repo.addCurrency(uid, name, currencyId, amount).thenApply(PointTransactionResult::success);
            };
            RewardItemDelivery itemDelivery = mock(RewardItemDelivery.class);
            ContentUnlockNotifier notifier = (uid, note) -> {};

            DefaultRaidRewardService rewardService = new DefaultRaidRewardService(
                    unlockService, finderService, notifier, flakySink, itemDelivery,
                    scriptHooks, repo, registry, clock::get, Logger.getLogger("test"));

            RewardTable sfTable = new RewardTable("sf_tbl", List.of(
                    RewardPool.allOf("sf_pool", "sf_claim", List.of(RewardConditionSpec.serverFirst()), List.of(
                            RewardEntrySpec.currency("sf_coin", RollRange.exact(10), 1, List.of(), List.of())))));
            rewardService.replacePackTables(List.of(sfTable), List.of());

            ContentPackCatalogSnapshot catalog = new ContentPackCatalogSnapshot(
                    1L,
                    Map.of("raid_sf", new ContentEntrySpec("raid_sf", "k", "raid_sf", "", 1, 8, 0, List.of("sf_tbl"))),
                    Map.of(),
                    Map.of("sf_tbl", sfTable),
                    List.of());
            RewardDispatchListener listener = new RewardDispatchListener(
                    rewardService, finderService, null, unlockService, repo, clock::get, Logger.getLogger("test"), () -> catalog);

            EncounterResultSnapshot victory = new EncounterResultSnapshot(
                    "raid_sf",
                    EncounterDifficulty.NORMAL,
                    EncounterOutcome.VICTORY,
                    50_000L,
                    true,
                    2,
                    Map.of(
                            aliceId, new ParticipantCombatMetrics(aliceId, "Alice", Map.of(), Map.of(), Map.of(), 0, 0, 0),
                            bobId, new ParticipantCombatMetrics(bobId, "Bob", Map.of(), Map.of(), Map.of(), 0, 0, 0)),
                    Set.of(aliceId, bobId));

            // Attempt 1: Alice succeeds, Bob fails -> Bob remains pending and unclaimed
            List<RewardApplyResult> attempt1 = listener.dispatchEncounterRewards(victory).join();
            assertEquals(10L, repo.getCurrencyBalance(aliceId, "sf_coin").join());
            assertEquals(0L, repo.getCurrencyBalance(bobId, "sf_coin").join());
            assertTrue(attempt1.stream().anyMatch(r -> r.failedGrants().size() == 1));

            // Attempt 2: Resumes only Bob without duplicating Alice
            listener.dispatchEncounterRewards(victory).join();
            assertEquals(10L, repo.getCurrencyBalance(aliceId, "sf_coin").join());
            assertEquals(10L, repo.getCurrencyBalance(bobId, "sf_coin").join());

            // Attempt 3: Both completed -> no further server-first grant
            listener.dispatchEncounterRewards(victory).join();
            assertEquals(10L, repo.getCurrencyBalance(aliceId, "sf_coin").join());
            assertEquals(10L, repo.getCurrencyBalance(bobId, "sf_coin").join());
        }
    }

    @Test
    void dispatchAchievementRewards_usesCapturedTableSnapshotAcrossHotReload() {
        UUID aliceId = UUID.randomUUID();
        AtomicLong clock = new AtomicLong(30_000L);
        ScriptHookRegistry scriptHooks = mock(ScriptHookRegistry.class);
        RaidServiceRegistry registry = new DefaultRaidServiceRegistry();

        try (SqlitePlayerDataRepository repo = new SqlitePlayerDataRepository(
                tempDir.resolve("hot_reload.db"), 2, Runnable::run)) {
            DefaultContentUnlockService unlockService = new DefaultContentUnlockService(repo, clock::get, Logger.getLogger("test"));
            DefaultContentFinderService finderService = new DefaultContentFinderService(unlockService);
            RewardCurrencySink sink = (uid, name, currencyId, amount) ->
                    repo.addCurrency(uid, name, currencyId, amount).thenApply(PointTransactionResult::success);
            RewardItemDelivery itemDelivery = mock(RewardItemDelivery.class);
            ContentUnlockNotifier notifier = (uid, note) -> {};

            DefaultRaidRewardService rewardService = new DefaultRaidRewardService(
                    unlockService, finderService, notifier, sink, itemDelivery,
                    scriptHooks, repo, registry, clock::get, Logger.getLogger("test"));

            RewardTable gen1Table = new RewardTable("ach_rew", List.of(
                    RewardPool.allOf("p", null, List.of(), List.of(
                            RewardEntrySpec.currency("coin", RollRange.exact(10), 1, List.of(), List.of())))));
            RewardTable gen2Table = new RewardTable("ach_rew", List.of(
                    RewardPool.allOf("p", null, List.of(), List.of(
                            RewardEntrySpec.currency("coin", RollRange.exact(50), 1, List.of(), List.of())))));
            rewardService.replacePackTables(List.of(gen1Table), List.of());

            AchievementDefinition def = new AchievementDefinition(
                    "ach_1", "pack", "t", "d", "DIAMOND", false, Set.of(), List.of(), List.of("ach_rew"));
            AchievementUnlockedEvent gen1Event = new AchievementUnlockedEvent(
                    aliceId,
                    "Alice",
                    def,
                    AchievementTriggerType.CUSTOM_SCRIPT,
                    false,
                    Map.of(),
                    Instant.ofEpochMilli(clock.get()),
                    Map.of("ach_rew", gen1Table));

            // Hot reload updates live service to gen-2 (50 coins) before gen-1 event listener dispatches
            rewardService.replacePackTables(List.of(gen2Table), List.of());

            RewardDispatchListener listener = new RewardDispatchListener(
                    rewardService, finderService, null, unlockService, repo, clock::get, Logger.getLogger("test"), () -> ContentPackCatalogSnapshot.empty());
            listener.dispatchAchievementRewards(gen1Event).join();

            assertEquals(10L, repo.getCurrencyBalance(aliceId, "coin").join());
            assertFalse(rewardService.evaluate("ach_rew",
                    net.sabafly.dragonraid.api.reward.RewardContext.builder(aliceId).playerName("Alice").build())
                    .grants().isEmpty());
        }
    }

    @Test
    void propagatesServerFirstCompleteFailure_preventsInMemoryConcurrentDoubleGrant_resumesOnJoin_andChecksGeneration()
            throws Exception {
        UUID aliceId = UUID.randomUUID();
        AtomicLong clock = new AtomicLong(40_000L);
        ScriptHookRegistry scriptHooks = mock(ScriptHookRegistry.class);
        RaidServiceRegistry registry = new DefaultRaidServiceRegistry();

        // #19: completeServerFirstRecipient failure must propagate out of dispatchEncounterRewards
        net.sabafly.dragonraid.api.progression.PlayerProgressionRepository failingCompleteRepo =
                mock(net.sabafly.dragonraid.api.progression.PlayerProgressionRepository.class);
        when(failingCompleteRepo.loadClaimedRewards(aliceId)).thenReturn(CompletableFuture.completedFuture(Set.of()));
        when(failingCompleteRepo.claimOrResumeServerFirstRecipients(
                org.mockito.ArgumentMatchers.eq("reward_clear_server_first:raid_err"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(CompletableFuture.completedFuture(Set.of(aliceId)));
        when(failingCompleteRepo.completeServerFirstRecipient("reward_clear_server_first:raid_err", aliceId))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("recipient complete db error")));
        DefaultContentFinderService finderErr = new DefaultContentFinderService(new DefaultContentUnlockService());
        DefaultRaidRewardService rewErr = new DefaultRaidRewardService();
        RewardTable emptySfTable = new RewardTable("err_tbl", List.of(
                RewardPool.allOf("p", null, List.of(), List.of(RewardEntrySpec.empty(1)))));
        ContentPackCatalogSnapshot errCat = new ContentPackCatalogSnapshot(
                1L,
                Map.of("raid_err", new ContentEntrySpec("raid_err", "k", "raid_err", "", 1, 8, 0, List.of("err_tbl"))),
                Map.of(),
                Map.of("err_tbl", emptySfTable),
                List.of());
        RewardDispatchListener errListener = new RewardDispatchListener(
                rewErr, finderErr, null, null, failingCompleteRepo, clock::get, Logger.getLogger("test"), () -> errCat);
        EncounterResultSnapshot errSnap = new EncounterResultSnapshot(
                "raid_err", EncounterDifficulty.NORMAL, EncounterOutcome.VICTORY, 10_000L, true, 1,
                Map.of(aliceId, new ParticipantCombatMetrics(aliceId, "Alice", Map.of(), Map.of(), Map.of(), 0, 0, 0)),
                Set.of(aliceId));
        org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> errListener.dispatchEncounterRewards(errSnap).join());

        // #20 & #21: in-memory atomic in-flight reservation + fail restore + join-time resume without duplicating repeatable drops
        CompletableFuture<Boolean> delayedItem = new CompletableFuture<>();
        AtomicInteger sfItemRequests = new AtomicInteger();
        AtomicInteger repCurrencyCalls = new AtomicInteger();
        DefaultContentUnlockService unlockInMem = new DefaultContentUnlockService();
        DefaultContentFinderService finderInMem = new DefaultContentFinderService(unlockInMem);
        RewardCurrencySink inMemSink = (uid, name, cid, amt) -> {
            repCurrencyCalls.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        };
        RewardItemDelivery slowDelivery = mock(RewardItemDelivery.class);
        when(slowDelivery.deliverVanillaItemDetailed(aliceId, "NETHERITE_INGOT", 1))
                .thenAnswer(inv -> {
                    int call = sfItemRequests.incrementAndGet();
                    return call == 1
                            ? delayedItem.thenApply(ok -> ok
                                    ? net.sabafly.dragonraid.api.reward.ItemDeliveryResult.full(1)
                                    : net.sabafly.dragonraid.api.reward.ItemDeliveryResult.failed(1))
                            : CompletableFuture.completedFuture(
                                    net.sabafly.dragonraid.api.reward.ItemDeliveryResult.full(1));
                });

        DefaultRaidRewardService rewInMem = new DefaultRaidRewardService(
                unlockInMem, finderInMem, null, inMemSink, slowDelivery,
                scriptHooks, null, registry, clock::get, Logger.getLogger("test"));
        RewardTable mixedSfTable = new RewardTable("sf_keyless_tbl", List.of(
                RewardPool.allOf("rep", null, List.of(), List.of(
                        RewardEntrySpec.currency("rep_coin", RollRange.exact(5), 1, List.of(), List.of()))),
                RewardPool.allOf("sf_pool", null, List.of(RewardConditionSpec.serverFirst()), List.of(
                        RewardEntrySpec.vanillaItem("NETHERITE_INGOT", RollRange.exact(1), 1, List.of(), List.of())))
        ));
        rewInMem.replacePackTables(List.of(mixedSfTable), List.of());

        // #41: catalog returns gen1 initially without entry, advances to gen2 during fallback -> retry picks gen2
        AtomicInteger supplyCount = new AtomicInteger();
        ContentPackCatalogSnapshot gen1Empty = new ContentPackCatalogSnapshot(1L, Map.of(), Map.of(), Map.of(), List.of());
        ContentPackCatalogSnapshot gen2Full = new ContentPackCatalogSnapshot(
                2L,
                Map.of("raid_mem", new ContentEntrySpec("raid_mem", "k", "raid_mem", "", 1, 8, 0, List.of("sf_keyless_tbl"))),
                Map.of(),
                Map.of("sf_keyless_tbl", mixedSfTable),
                List.of());
        RewardDispatchListener inMemListener = new RewardDispatchListener(
                rewInMem, finderInMem, null, unlockInMem, null, clock::get, Logger.getLogger("test"),
                () -> supplyCount.getAndIncrement() == 0 ? gen1Empty : gen2Full);

        EncounterResultSnapshot memSnap = new EncounterResultSnapshot(
                "raid_mem", EncounterDifficulty.NORMAL, EncounterOutcome.VICTORY, 10_000L, true, 1,
                Map.of(aliceId, new ParticipantCombatMetrics(aliceId, "Alice", Map.of(), Map.of(), Map.of(), 0, 0, 0)),
                Set.of(aliceId));

        CompletableFuture<List<RewardApplyResult>> firstInFlight = inMemListener.dispatchEncounterRewards(memSnap);
        // Overlapping concurrent dispatch while #1 is in-flight -> Alice must NOT receive second serverFirst=true
        CompletableFuture<List<RewardApplyResult>> overlappingFuture = inMemListener.dispatchEncounterRewards(memSnap);
        assertEquals(1, sfItemRequests.get()); // still only 1 NETHERITE_INGOT attempt

        // Fail #1's delayed delivery -> Alice returns to inMemoryPendingServerFirst; bounded timeouts prevent hang
        delayedItem.complete(false);
        assertEquals(1, firstInFlight.get(5, java.util.concurrent.TimeUnit.SECONDS).size());
        List<RewardApplyResult> overlapping = overlappingFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, overlapping.size());
        assertEquals(1, sfItemRequests.get());
        int repCallsAfterTwoDispatches = repCurrencyCalls.get();

        // Join-time resume delivers ONLY pending claim-keyed SF item (no extra rep_coin!)
        List<RewardApplyResult> joinResumed = inMemListener.resumePendingServerFirst(aliceId, "Alice").join();
        assertEquals(1, joinResumed.size());
        assertEquals(1, joinResumed.getFirst().appliedGrants().size());
        assertEquals(repCallsAfterTwoDispatches, repCurrencyCalls.get());
        assertEquals(2, sfItemRequests.get());

        // Subsequent join finds nothing pending
        assertTrue(inMemListener.resumePendingServerFirst(aliceId, "Alice").join().isEmpty());
    }
}
