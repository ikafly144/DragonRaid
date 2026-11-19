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
}
