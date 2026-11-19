package net.sabafly.dragonraid.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotification;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotifier;
import net.sabafly.dragonraid.api.reward.CurrencyGrant;
import net.sabafly.dragonraid.api.reward.CustomItemGrant;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardConditionSpec;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardCurrencySink;
import net.sabafly.dragonraid.api.reward.RewardEntrySpec;
import net.sabafly.dragonraid.api.reward.RewardFunctionSpec;
import net.sabafly.dragonraid.api.reward.RewardItemDelivery;
import net.sabafly.dragonraid.api.reward.RewardMetricType;
import net.sabafly.dragonraid.api.reward.RewardPool;
import net.sabafly.dragonraid.api.reward.RewardPoolMode;
import net.sabafly.dragonraid.api.reward.RewardRoundingMode;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.RewardTableExtension;
import net.sabafly.dragonraid.api.reward.RollRange;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultRaidRewardServiceTest {

    @Test
    void pureEvaluationIsDeterministicWithSeedAndReproducesPerformanceScaling() {
        DefaultRaidRewardService service = new DefaultRaidRewardService();

        RewardTable relics = new RewardTable("relic_subtable", List.of(
                RewardPool.weighted(
                        "relics",
                        RollRange.exact(1),
                        List.of(),
                        List.of(
                                RewardEntrySpec.customItem("dragonscale_shield", RollRange.exact(1), 60, List.of(), List.of()),
                                RewardEntrySpec.vanillaItem("DIAMOND", RollRange.between(2, 4), 40, List.of(), List.of())
                        )
                )
        ));

        RewardTable clearTable = new RewardTable("clear_normal", List.of(
                RewardPool.allOf(
                        "base_currency",
                        null,
                        List.of(),
                        List.of(
                                RewardEntrySpec.currency(
                                        "dragon_token",
                                        RollRange.exact(10),
                                        1,
                                        List.of(),
                                        List.of(
                                                RewardFunctionSpec.addAmount(RollRange.exact(15), List.of(RewardConditionSpec.firstClear())),
                                                RewardFunctionSpec.addMetricScaled(RewardMetricType.CRYSTALS_DESTROYED, "", 1, 2, 8, List.of()),
                                                RewardFunctionSpec.addMetricScaled(RewardMetricType.REVIVES_COMPLETED, "", 1, 2, 6, List.of()),
                                                RewardFunctionSpec.addMetricScaled(RewardMetricType.BOSS_DAMAGE, "", 200, 1, 10, List.of())
                                        )
                                )
                        )
                ),
                new RewardPool(
                        "weighted_drops",
                        RewardPoolMode.WEIGHTED,
                        RollRange.between(1, 2),
                        0.5,
                        null,
                        List.of(RewardConditionSpec.noDeath()),
                        List.of(
                                RewardEntrySpec.tableRef("relic_subtable", 80, 2.0, List.of(), List.of()),
                                RewardEntrySpec.empty(20)
                        ),
                        List.of()
                )
        ));

        service.replacePackTables(List.of(relics, clearTable), List.of());

        UUID alice = UUID.randomUUID();
        ParticipantCombatMetrics metrics = new ParticipantCombatMetrics(
                alice, "Alice",
                Map.of("ancient_dragon", 1350.0),
                Map.of(EnemyRole.PRIMARY_BOSS, 1350.0),
                Map.of("obsidian_crystal", 3),
                2,
                0,
                0
        );
        EncounterResultSnapshot snapshot = new EncounterResultSnapshot(
                "ender_dragon_normal",
                EncounterDifficulty.NORMAL,
                EncounterOutcome.VICTORY,
                95_000L,
                true,
                2,
                Map.of(alice, metrics)
        );
        RewardContext ctx = RewardContext.builder(alice)
                .playerName("Alice")
                .sourceType("ENCOUNTER_CLEAR")
                .sourceId("ender_dragon_normal")
                .encounterSnapshot(snapshot)
                .participantMetrics(metrics)
                .difficulty(EncounterDifficulty.NORMAL)
                .firstClear(true)
                .serverFirst(false)
                .noDeath(true)
                .partySize(2)
                .durationMillis(95_000L)
                .luck(2.0)
                .build();

        RewardBundle run1 = service.evaluate("clear_normal", ctx, new Random(42L));
        RewardBundle run2 = service.evaluate("clear_normal", ctx, new Random(42L));

        assertEquals(run1, run2);
        // base(10) + firstClear(15) + crystals(3*2=6) + revives(2*2=4) + floor(1350/200)=6 -> 41
        assertEquals(41L, run1.totalCurrencies().get("dragon_token"));
        // noDeath=false excludes the weighted drop pool
        RewardContext deadCtx = RewardContext.builder(alice).noDeath(false).difficulty(EncounterDifficulty.NORMAL).build();
        RewardBundle deadRun = service.evaluate("clear_normal", deadCtx, new Random(42L));
        assertEquals(1, deadRun.grants().size());
        assertEquals(10L, deadRun.totalCurrencies().get("dragon_token"));
    }

    @Test
    void supportsAllConditionsFunctionsExtensionsAndCycleDetection() {
        ScriptHookRegistry scriptHooks = mock(ScriptHookRegistry.class);
        when(scriptHooks.evaluateCondition(eq("weekend_check"), any())).thenReturn(true);

        DefaultRaidRewardService service = new DefaultRaidRewardService(
                new PureRewardTableEvaluator(scriptHooks, null),
                new DefaultRewardApplier()
        );

        RewardTable baseTable = new RewardTable("main_loot", List.of(
                RewardPool.allOf(
                        "core",
                        "one_time_sf",
                        List.of(
                                RewardConditionSpec.serverFirst(),
                                RewardConditionSpec.difficulty(EncounterDifficulty.HARD),
                                RewardConditionSpec.partySize(1, 4),
                                RewardConditionSpec.maxDurationMs(120_000L),
                                RewardConditionSpec.minBossDamage(500.0),
                                RewardConditionSpec.minCrystalsDestroyed(1),
                                RewardConditionSpec.minRevivesCompleted(1),
                                RewardConditionSpec.hasAchievement("ach_1"),
                                RewardConditionSpec.hasUnlock("content_1"),
                                RewardConditionSpec.notClaimed("other_key"),
                                RewardConditionSpec.attributeEquals("phase_perfect", "true"),
                                RewardConditionSpec.customScript("weekend_check", Map.of()),
                                RewardConditionSpec.firstClear().negated()
                        ),
                        List.of(
                                RewardEntrySpec.currency(
                                        "dragon_token",
                                        RollRange.exact(5),
                                        1,
                                        List.of(),
                                        List.of(
                                                RewardFunctionSpec.addMetricScaled(RewardMetricType.ATTRIBUTE, "combo", 2, 1, 10, List.of()),
                                                RewardFunctionSpec.multiplyAmount(1.5, RewardRoundingMode.CEIL, List.of()),
                                                RewardFunctionSpec.clampAmount(1, 20, List.of())
                                        )
                                )
                        )
                )
        ));

        RewardTableExtension highPrioExt = new RewardTableExtension(
                "ext_b",
                "pack_b",
                "main_loot",
                200,
                false,
                List.of(RewardPool.allOf("b_pool", null, List.of(), List.of(RewardEntrySpec.currency("coin_b", RollRange.exact(2), 1, List.of(), List.of()))))
        );
        RewardTableExtension lowPrioExt = new RewardTableExtension(
                "ext_a",
                "pack_a",
                "main_loot",
                50,
                false,
                List.of(RewardPool.allOf("a_pool", null, List.of(), List.of(RewardEntrySpec.currency("coin_a", RollRange.exact(1), 1, List.of(), List.of()))))
        );
        RewardTableExtension optionalMissing = new RewardTableExtension(
                "ext_opt",
                "pack_c",
                "non_existent_table",
                10,
                true,
                List.of(RewardPool.allOf("opt", null, List.of(), List.of(RewardEntrySpec.empty(1))))
        );

        service.replacePackTables(List.of(baseTable), List.of(highPrioExt, lowPrioExt, optionalMissing));

        RewardTable merged = service.getTable("main_loot").orElseThrow();
        assertEquals(List.of("core", "a_pool", "b_pool"), merged.pools().stream().map(RewardPool::id).toList());

        UUID alice = UUID.randomUUID();
        ParticipantCombatMetrics metrics = new ParticipantCombatMetrics(
                alice, "Alice", Map.of(), Map.of(EnemyRole.PRIMARY_BOSS, 800.0), Map.of("obsidian_crystal", 2), 1, 0, 0);
        RewardContext ctx = RewardContext.builder(alice)
                .difficulty(EncounterDifficulty.HARD)
                .firstClear(false)
                .serverFirst(true)
                .partySize(3)
                .durationMillis(60_000L)
                .participantMetrics(metrics)
                .unlockedAchievements(Set.of("ach_1"))
                .unlockedContents(Set.of("content_1"))
                .claimedKeys(Set.of())
                .attributes(Map.of("phase_perfect", "true", "combo", 6))
                .build();

        RewardBundle bundle = service.evaluate("main_loot", ctx, new Random(7L));
        // base 5 + floor(6/2)=3 => 8; * 1.5 ceil => 12; clamp(1,20) => 12
        assertEquals(12L, bundle.totalCurrencies().get("dragon_token"));
        assertEquals(1L, bundle.totalCurrencies().get("coin_a"));
        assertEquals(2L, bundle.totalCurrencies().get("coin_b"));
        assertEquals(Set.of("one_time_sf"), bundle.claimKeys());

        // If claimKey already in context.claimedKeys(), core pool is purely skipped while extension pools still fire
        RewardContext alreadyClaimedCtx = RewardContext.builder(alice)
                .difficulty(EncounterDifficulty.HARD)
                .serverFirst(true)
                .claimedKeys(Set.of("one_time_sf"))
                .build();
        RewardBundle skippedBundle = service.evaluate("main_loot", alreadyClaimedCtx, new Random(7L));
        assertFalse(skippedBundle.totalCurrencies().containsKey("dragon_token"));
        assertEquals(1L, skippedBundle.totalCurrencies().get("coin_a"));

        // Cycle detection in evaluator
        RewardTable cycA = new RewardTable("cyc_a", List.of(
                RewardPool.allOf("p1", null, List.of(), List.of(RewardEntrySpec.tableRef("cyc_b", 1, 0.0, List.of(), List.of())))
        ));
        RewardTable cycB = new RewardTable("cyc_b", List.of(
                RewardPool.allOf("p2", null, List.of(), List.of(RewardEntrySpec.tableRef("cyc_a", 1, 0.0, List.of(), List.of())))
        ));
        service.replacePackTables(List.of(cycA, cycB), List.of());
        assertThrows(IllegalStateException.class, () -> service.evaluate("cyc_a", ctx, new Random(1L)));
    }

    @Test
    void applierGuardsClaimKeysAtomicallyAndNotifiesOnlyOnFreshUnlock() {
        ContentUnlockService unlockService = mock(ContentUnlockService.class);
        ContentFinderService finderService = mock(ContentFinderService.class);
        ContentUnlockNotifier notifier = mock(ContentUnlockNotifier.class);
        RewardCurrencySink currencySink = mock(RewardCurrencySink.class);
        RewardItemDelivery itemDelivery = mock(RewardItemDelivery.class);
        ScriptHookRegistry scriptHooks = mock(ScriptHookRegistry.class);
        PlayerProgressionRepository repo = mock(PlayerProgressionRepository.class);

        UUID alice = UUID.randomUUID();
        Set<String> dbClaimed = ConcurrentHashMap.newKeySet();
        when(repo.tryClaimReward(eq(alice), eq("Alice"), any(), any(Long.class))).thenAnswer(inv -> {
            String key = inv.getArgument(2);
            return CompletableFuture.completedFuture(dbClaimed.add(key));
        });
        when(finderService.getContent("dragon_normal"))
                .thenReturn(Optional.of(new ContentEntrySpec("dragon_normal", "content.dragon_normal", "bp", "dragon_normal", 1, 8, 0)));
        when(unlockService.unlock(alice, "Alice", "dragon_normal")).thenReturn(true, false);
        when(currencySink.grantCurrency(eq(alice), eq("Alice"), eq("dragon_token"), any(Long.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        AtomicInteger hooks = new AtomicInteger();
        when(scriptHooks.executeAction(eq("bonus_hook"), any())).thenAnswer(inv -> {
            hooks.incrementAndGet();
            return true;
        });

        DefaultRaidRewardService service = new DefaultRaidRewardService(
                unlockService,
                finderService,
                notifier,
                currencySink,
                itemDelivery,
                scriptHooks,
                repo,
                null,
                () -> 1234L,
                Logger.getLogger("test")
        );

        RewardTable portalUnlock = new RewardTable("portal_unlock", List.of(
                RewardPool.allOf("unlock", null, List.of(), List.of(
                        RewardEntrySpec.unlockContent("dragon_normal", true, "ENDER_EYE", "minecraft:ui.toast.challenge_complete")
                ))
        ));
        RewardTable mixedClear = new RewardTable("mixed_clear", List.of(
                RewardPool.allOf("repeatable", null, List.of(), List.of(
                        RewardEntrySpec.currency("dragon_token", RollRange.exact(15), 1, List.of(), List.of()),
                        RewardEntrySpec.vanillaItem("DIAMOND", RollRange.exact(3), 1, List.of(), List.of()),
                        RewardEntrySpec.scriptHook("bonus_hook", Map.of("k", "v"))
                )),
                RewardPool.allOf("one_shot", "once_crest", List.of(), List.of(
                        RewardEntrySpec.customItem("dragonscale_shield", RollRange.exact(1), 1, List.of(), List.of())
                ))
        ));
        service.registerTable(portalUnlock);
        service.registerTable(mixedClear);

        RewardContext ctx = RewardContext.builder(alice).playerName("Alice").build();
        service.evaluateAndApply("portal_unlock", ctx, new Random(1L)).join();
        service.evaluateAndApply("portal_unlock", ctx, new Random(1L)).join();

        ArgumentCaptor<ContentUnlockNotification> notifCaptor = ArgumentCaptor.forClass(ContentUnlockNotification.class);
        verify(notifier, times(1)).notifyContentUnlocked(eq(alice), notifCaptor.capture());
        assertEquals("content.dragon_normal", notifCaptor.getValue().displayNameKey());

        // Evaluate bundle twice and apply both (simulates stale context racing on one-shot claimKey)
        RewardBundle bundle1 = service.evaluate("mixed_clear", ctx, new Random(2L));
        RewardApplyResult firstApply = service.apply(bundle1, ctx).join();
        assertEquals(Set.of("once_crest"), firstApply.newlyClaimedKeys());
        assertTrue(firstApply.skippedClaimKeys().isEmpty());
        assertEquals(4, firstApply.appliedGrants().size());
        assertTrue(firstApply.appliedGrants().stream().anyMatch(g -> g instanceof CustomItemGrant));

        RewardApplyResult secondApply = service.apply(bundle1, ctx).join();
        assertTrue(secondApply.newlyClaimedKeys().isEmpty());
        assertEquals(Set.of("once_crest"), secondApply.skippedClaimKeys());
        assertEquals(3, secondApply.appliedGrants().size());
        assertFalse(secondApply.appliedGrants().stream().anyMatch(g -> g instanceof CustomItemGrant));
        assertTrue(secondApply.appliedGrants().stream().anyMatch(g -> g instanceof CurrencyGrant));

        verify(itemDelivery, times(2)).deliverVanillaItem(alice, "DIAMOND", 3);
        verify(itemDelivery, times(1)).deliverCustomItem(alice, "dragonscale_shield", 1);
        assertEquals(2, hooks.get());
    }
}
