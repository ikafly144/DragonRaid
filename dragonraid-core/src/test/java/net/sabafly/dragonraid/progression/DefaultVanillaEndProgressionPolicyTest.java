package net.sabafly.dragonraid.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.progression.EndEntryGateMode;
import net.sabafly.dragonraid.api.progression.EndEntryGateSpec;
import net.sabafly.dragonraid.api.progression.VanillaClearHookSpec;
import net.sabafly.dragonraid.api.progression.VanillaEndAccessDecision;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.progression.VanillaProgressionService;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.storage.SqlitePlayerDataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultVanillaEndProgressionPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void allowsByDefaultWhenNoRulesRegisteredAndEnforcesGateModesByPriority() {
        ContentUnlockService unlockService = mock(ContentUnlockService.class);
        RaidAchievementService achievementService = mock(RaidAchievementService.class);
        ScriptHookRegistry scriptHookRegistry = mock(ScriptHookRegistry.class);
        UUID player = UUID.randomUUID();

        DefaultVanillaEndProgressionPolicy policy = new DefaultVanillaEndProgressionPolicy(
                null, unlockService, achievementService, null, scriptHookRegistry, null, Logger.getLogger("test"));

        assertTrue(policy.evaluateVanillaEndAccess(player).allowed());

        policy.replacePackRules(List.of(
                new VanillaProgressionRule(
                        "second_gate",
                        200,
                        new EndEntryGateSpec(EndEntryGateMode.REQUIRE_PLAYER_ACHIEVEMENT, "ach_portal", "", true, true),
                        List.of()
                ),
                new VanillaProgressionRule(
                        "first_gate",
                        100,
                        new EndEntryGateSpec(EndEntryGateMode.REQUIRE_PLAYER_UNLOCK, "raid_normal", "custom.locked", true, false),
                        List.of()
                )
        ));

        VanillaEndAccessDecision deniedFirst = policy.evaluateVanillaEndAccess(player);
        assertFalse(deniedFirst.allowed());
        assertEquals("custom.locked", deniedFirst.denialMessageKey());
        assertFalse(deniedFirst.pushBackOnDeny());

        when(unlockService.isUnlocked(player, "raid_normal")).thenReturn(true);
        VanillaEndAccessDecision deniedSecond = policy.evaluateVanillaEndAccess(player);
        assertFalse(deniedSecond.allowed());
        assertEquals("progression.end-locked-achievement-required", deniedSecond.denialMessageKey());

        when(achievementService.hasUnlocked(player, "ach_portal")).thenReturn(true);
        assertTrue(policy.evaluateVanillaEndAccess(player).allowed());

        // Script hook gate
        policy.registerRule(new VanillaProgressionRule(
                "script_gate",
                50,
                new EndEntryGateSpec(EndEntryGateMode.SCRIPT_HOOK, "can_enter_end", "", true, true),
                List.of()
        ));
        when(scriptHookRegistry.evaluateCondition(eq("can_enter_end"), any())).thenReturn(false);
        assertFalse(policy.canEnterVanillaEnd(player));
        when(scriptHookRegistry.evaluateCondition(eq("can_enter_end"), any())).thenReturn(true);
        assertTrue(policy.canEnterVanillaEnd(player));
    }

    @Test
    void enforcesServerFirstGateExecutesClearHooksOnceAndPersistsAcrossRestart() {
        Path dbFile = tempDir.resolve("policy.db");
        VanillaProgressionService vanillaService = mock(VanillaProgressionService.class);
        when(vanillaService.synchronizeDragonDefeated(any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(vanillaService.getEndGatewayCount(any()))
                .thenReturn(CompletableFuture.completedFuture(0));
        when(vanillaService.expandEndGateways(any(), anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(1));
        when(vanillaService.expandEndGatewaysToTarget(any(), anyInt(), anyInt()))
                .thenCallRealMethod();

        ScriptHookRegistry scriptHookRegistry = mock(ScriptHookRegistry.class);
        AtomicInteger hookCalls = new AtomicInteger();
        when(scriptHookRegistry.executeAction(eq("on_first_clear"), any())).thenAnswer(inv -> {
            hookCalls.incrementAndGet();
            return true;
        });

        UUID player = UUID.randomUUID();
        VanillaProgressionRule rule = new VanillaProgressionRule(
                "server_first_gate",
                10,
                new EndEntryGateSpec(EndEntryGateMode.REQUIRE_SERVER_FIRST, "dragon_normal", "", true, true),
                List.of(new VanillaClearHookSpec(
                        "dragon_normal",
                        EncounterDifficulty.NORMAL,
                        false,
                        true,
                        "world_the_end",
                        true,
                        true,
                        true,
                        1,
                        20,
                        "on_first_clear",
                        Map.of()
                ))
        );

        EncounterResultSnapshot victory = new EncounterResultSnapshot(
                "dragon_normal",
                EncounterDifficulty.NORMAL,
                EncounterOutcome.VICTORY,
                60_000L,
                true,
                1,
                Map.of(player, new ParticipantCombatMetrics(
                        player, "Alice", Map.of(), Map.of(EnemyRole.PRIMARY_BOSS, 1200.0), Map.of(), 0, 0, 0))
        );

        try (SqlitePlayerDataRepository repo = new SqlitePlayerDataRepository(dbFile, 2, Runnable::run)) {
            DefaultVanillaEndProgressionPolicy policy = new DefaultVanillaEndProgressionPolicy(
                    repo, null, null, vanillaService, scriptHookRegistry, null, Logger.getLogger("test"));
            policy.replacePackRules(List.of(rule));

            assertFalse(policy.canEnterVanillaEnd(player));

            policy.onEncounterClearedAsync(victory, true).join();

            assertTrue(policy.canEnterVanillaEnd(player));
            assertTrue(policy.isServerMilestoneAchieved("dragon_normal"));
            verify(vanillaService, times(1)).synchronizeDragonDefeated("world_the_end", true, true, true);
            verify(vanillaService, times(1)).expandEndGateways("world_the_end", 1, 20);
            assertEquals(1, hookCalls.get());

            // Second clear must NOT re-trigger serverFirstOnly clear hook
            policy.onEncounterClearedAsync(victory, true).join();
            verify(vanillaService, times(1)).synchronizeDragonDefeated("world_the_end", true, true, true);
            assertEquals(1, hookCalls.get());
        }

        // Simulate restart with fresh policy instance loading milestones from SQLite
        try (SqlitePlayerDataRepository reopened = new SqlitePlayerDataRepository(dbFile, 2, Runnable::run)) {
            DefaultVanillaEndProgressionPolicy reloaded = new DefaultVanillaEndProgressionPolicy(
                    reopened, null, null, vanillaService, scriptHookRegistry, null, Logger.getLogger("test"));
            reloaded.replacePackRules(List.of(rule));
            reloaded.loadServerMilestones().join();

            assertTrue(reloaded.canEnterVanillaEnd(player));
            reloaded.onEncounterClearedAsync(victory, true).join();
            // Still only 1 invocation total across restart
            verify(vanillaService, times(1)).synchronizeDragonDefeated("world_the_end", true, true, true);
        }
    }

    @org.junit.jupiter.api.Test
    void failClosed_scriptHookAndPackLoadFailure_andBypassRequiresAllRulesAllow(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path localTempDir) {
        java.nio.file.Path dbFile = localTempDir.resolve("gate_checks.db");
        net.sabafly.dragonraid.api.progression.VanillaProgressionService vanillaService =
                org.mockito.Mockito.mock(net.sabafly.dragonraid.api.progression.VanillaProgressionService.class);
        try (net.sabafly.dragonraid.storage.SqlitePlayerDataRepository repo =
                new net.sabafly.dragonraid.storage.SqlitePlayerDataRepository(dbFile, 2, Runnable::run)) {
            net.sabafly.dragonraid.api.script.ScriptHookRegistry emptyRegistry =
                    org.mockito.Mockito.mock(net.sabafly.dragonraid.api.script.ScriptHookRegistry.class);
            DefaultVanillaEndProgressionPolicy policy = new DefaultVanillaEndProgressionPolicy(
                    repo, null, null, vanillaService, emptyRegistry, null, java.util.logging.Logger.getLogger("test"));

            java.util.UUID player = java.util.UUID.randomUUID();
            // #4 SCRIPT_HOOK missing/null/throwing fails closed
            policy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "script_gate",
                    10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                            net.sabafly.dragonraid.api.progression.EndEntryGateMode.SCRIPT_HOOK,
                            "missing_hook",
                            "denial.script",
                            true,
                            true),
                    java.util.List.of())));
            org.junit.jupiter.api.Assertions.assertFalse(policy.canEnterVanillaEnd(player));

            // Throwing condition hook also fails closed
            org.mockito.Mockito.when(emptyRegistry.evaluateCondition(
                            org.mockito.ArgumentMatchers.eq("throwing_hook"), org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new IllegalStateException("boom"));
            policy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "throwing_gate",
                    10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                            net.sabafly.dragonraid.api.progression.EndEntryGateMode.SCRIPT_HOOK,
                            "throwing_hook",
                            "denial.script",
                            true,
                            true),
                    java.util.List.of())));
            org.junit.jupiter.api.Assertions.assertFalse(policy.canEnterVanillaEnd(player));

            // #5 Rule 1 denies with allowBypass=true, Rule 2 denies with allowBypass=false -> overall allowBypass=false
            net.sabafly.dragonraid.api.progression.VanillaProgressionRule bypassable =
                    new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                            "r_bypassable",
                            10,
                            new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                                    net.sabafly.dragonraid.api.progression.EndEntryGateMode.DENY,
                                    "",
                                    "denial.one",
                                    true,
                                    false),
                            java.util.List.of());
            net.sabafly.dragonraid.api.progression.VanillaProgressionRule strict =
                    new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                            "r_strict",
                            20,
                            new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                                    net.sabafly.dragonraid.api.progression.EndEntryGateMode.DENY,
                                    "",
                                    "denial.two",
                                    false,
                                    true),
                            java.util.List.of());
            policy.replacePackRules(java.util.List.of(bypassable, strict));
            net.sabafly.dragonraid.api.progression.VanillaEndAccessDecision decision =
                    policy.evaluateVanillaEndAccess(player);
            org.junit.jupiter.api.Assertions.assertFalse(decision.allowed());
            org.junit.jupiter.api.Assertions.assertFalse(decision.allowBypassPermission());
            org.junit.jupiter.api.Assertions.assertTrue(decision.pushBackOnDeny());

            // #18 packLoadFailed denies entry until markPackLoadSucceeded()
            policy.replacePackRules(java.util.List.of());
            assertTrue(policy.canEnterVanillaEnd(player));
            policy.markPackLoadFailed("broken.yml");
            assertTrue(policy.isPackLoadFailed());
            org.junit.jupiter.api.Assertions.assertFalse(policy.canEnterVanillaEnd(player));
            policy.markPackLoadSucceeded();
            org.junit.jupiter.api.Assertions.assertFalse(policy.isPackLoadFailed());
            assertTrue(policy.canEnterVanillaEnd(player));
        }
    }

    @org.junit.jupiter.api.Test
    void clearHooks_preserveStableKeyAcrossReorder_migrateLegacyKey_andResumePartialFailure(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path localTempDir) {
        java.nio.file.Path dbFile = localTempDir.resolve("clear_hooks.db");
        net.sabafly.dragonraid.api.progression.VanillaProgressionService vanillaService =
                org.mockito.Mockito.mock(net.sabafly.dragonraid.api.progression.VanillaProgressionService.class);
        org.mockito.Mockito.when(vanillaService.expandEndGateways(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(1));
        java.util.concurrent.atomic.AtomicInteger syncAttempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(vanillaService.synchronizeDragonDefeated(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> {
                    if (syncAttempts.incrementAndGet() == 1) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("transient sync failure"));
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(true);
                });

        try (net.sabafly.dragonraid.storage.SqlitePlayerDataRepository repo =
                new net.sabafly.dragonraid.storage.SqlitePlayerDataRepository(dbFile, 2, Runnable::run)) {
            DefaultVanillaEndProgressionPolicy policy = new DefaultVanillaEndProgressionPolicy(
                    repo, null, null, vanillaService, null, null, java.util.logging.Logger.getLogger("test"));

            net.sabafly.dragonraid.api.progression.VanillaClearHookSpec hookA =
                    new net.sabafly.dragonraid.api.progression.VanillaClearHookSpec(
                            "hook_a",
                            "ender_dragon_normal",
                            net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL,
                            false,
                            true,
                            "world_the_end",
                            true,
                            true,
                            false,
                            1,
                            20,
                            null,
                            java.util.Map.of());
            net.sabafly.dragonraid.api.progression.VanillaClearHookSpec hookB =
                    new net.sabafly.dragonraid.api.progression.VanillaClearHookSpec(
                            "hook_b",
                            "ender_dragon_hard",
                            net.sabafly.dragonraid.api.script.EncounterDifficulty.HARD,
                            false,
                            true,
                            "world_the_end",
                            true,
                            false,
                            false,
                            0,
                            20,
                            null,
                            java.util.Map.of());

            policy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "rule_1",
                    10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                            net.sabafly.dragonraid.api.progression.EndEntryGateMode.ALLOW, "", "", true, false),
                    java.util.List.of(hookB, hookA))));

            // #11 hasMatchingClearHook distinguishes encounterId + difficulty
            assertTrue(policy.hasMatchingClearHook(
                    "ender_dragon_normal", net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL));
            org.junit.jupiter.api.Assertions.assertFalse(policy.hasMatchingClearHook(
                    "ender_dragon_normal", net.sabafly.dragonraid.api.script.EncounterDifficulty.HARD));

            java.util.UUID alice = java.util.UUID.randomUUID();
            net.sabafly.dragonraid.api.event.EncounterResultSnapshot victory =
                    new net.sabafly.dragonraid.api.event.EncounterResultSnapshot(
                            "ender_dragon_normal",
                            net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL,
                            net.sabafly.dragonraid.api.event.EncounterOutcome.VICTORY,
                            120_000L,
                            true,
                            1,
                            java.util.Map.of(alice, new net.sabafly.dragonraid.api.event.ParticipantCombatMetrics(
                                    alice, "Alice", java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), 0, 0, 0)),
                            java.util.Set.of(alice));

            // #8 String overload uses firstClear=false and skips difficulty-scoped hooks
            policy.onEncounterCleared("ender_dragon_normal", java.util.List.of(alice));
            org.junit.jupiter.api.Assertions.assertEquals(0, syncAttempts.get());

            // #6 First attempt fails on synchronizeDragonDefeated -> remains resumable
            try {
                policy.onEncounterClearedAsync(victory, false).join();
            } catch (java.util.concurrent.CompletionException ignored) {
            }
            org.junit.jupiter.api.Assertions.assertEquals(1, syncAttempts.get());

            // Second attempt resumes and completes; third attempt does not re-execute even after reordering hooks (#7)
            policy.onEncounterClearedAsync(victory, false).join();
            org.junit.jupiter.api.Assertions.assertEquals(2, syncAttempts.get());

            policy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "rule_1",
                    10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(net.sabafly.dragonraid.api.progression.EndEntryGateMode.ALLOW, "", "", true, false),
                    java.util.List.of(hookA, hookB))));
            policy.onEncounterClearedAsync(victory, false).join();
            org.junit.jupiter.api.Assertions.assertEquals(2, syncAttempts.get());

            // #7 Pre-existing legacy positional milestone key ("progression_hook:<ruleId>:0:<encounterId>") migrates
            repo.tryClaimServerFirst("progression_hook:legacy_rule:0:legacy_enc", java.util.List.of(alice), 100L).join();
            net.sabafly.dragonraid.api.progression.VanillaClearHookSpec legacyHook =
                    new net.sabafly.dragonraid.api.progression.VanillaClearHookSpec(
                            null,
                            "legacy_enc",
                            null,
                            false,
                            true,
                            "world_the_end",
                            true,
                            false,
                            false,
                            0,
                            20,
                            null,
                            java.util.Map.of());
            policy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "legacy_rule",
                    10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(net.sabafly.dragonraid.api.progression.EndEntryGateMode.ALLOW, "", "", true, false),
                    java.util.List.of(legacyHook))));
            policy.loadServerMilestones().join();
            policy.onEncounterCleared("legacy_enc", java.util.List.of(alice));
            // Must not run synchronizeDragonDefeated again because legacy key migrated
            org.junit.jupiter.api.Assertions.assertEquals(2, syncAttempts.get());

            // #8 Legacy key checked independently per hook (not skipped due to sibling stable keys)
            repo.tryClaimServerFirst("progression_hook:legacy_split:0:split_enc", java.util.List.of(alice), 200L).join();
            net.sabafly.dragonraid.api.progression.VanillaClearHookSpec splitDone =
                    new net.sabafly.dragonraid.api.progression.VanillaClearHookSpec(
                            null, "split_enc", null, false, true, "world_the_end",
                            true, false, false, 0, 20, null, java.util.Map.of());
            net.sabafly.dragonraid.api.progression.VanillaClearHookSpec splitNew =
                    new net.sabafly.dragonraid.api.progression.VanillaClearHookSpec(
                            "split_new", "split_enc", null, false, true, "world_the_end",
                            false, false, false, 1, 20, null, java.util.Map.of());
            policy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "legacy_split", 10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                            net.sabafly.dragonraid.api.progression.EndEntryGateMode.ALLOW, "", "", true, false),
                    java.util.List.of(splitDone, splitNew))));
            policy.loadServerMilestones().join();
            org.mockito.Mockito.when(vanillaService.expandEndGatewaysToTarget(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                            new net.sabafly.dragonraid.api.progression.VanillaProgressionService.GatewayExpansionResult(1, 5, 20)));
            org.mockito.Mockito.when(vanillaService.getEndGatewayCount(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(4));
            policy.onEncounterCleared("split_enc", java.util.List.of(alice));
            org.junit.jupiter.api.Assertions.assertEquals(2, syncAttempts.get());
            org.mockito.Mockito.verify(vanillaService).expandEndGatewaysToTarget("world_the_end", 5, 20);
        }
    }

    @org.junit.jupiter.api.Test
    void worldProgressionD3_firstClearResume_missingServiceFailure_andGatewayTargetRestartResume(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path localTempDir) {
        java.nio.file.Path dbFile = localTempDir.resolve("d3_gateway.db");
        net.sabafly.dragonraid.api.progression.VanillaProgressionService vanillaService =
                org.mockito.Mockito.mock(net.sabafly.dragonraid.api.progression.VanillaProgressionService.class);
        java.util.concurrent.atomic.AtomicInteger syncCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger gateways = new java.util.concurrent.atomic.AtomicInteger(2);

        org.mockito.Mockito.when(vanillaService.synchronizeDragonDefeated(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> {
                    syncCalls.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(true);
                });
        org.mockito.Mockito.when(vanillaService.getEndGatewayCount(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> java.util.concurrent.CompletableFuture.completedFuture(gateways.get()));
        java.util.concurrent.atomic.AtomicInteger expandCalls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(vanillaService.expandEndGatewaysToTarget(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    if (expandCalls.incrementAndGet() == 1) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("gateway transient failure"));
                    }
                    int target = inv.getArgument(1);
                    int maxGw = inv.getArgument(2);
                    int before = gateways.get();
                    gateways.set(Math.max(before, target));
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new net.sabafly.dragonraid.api.progression.VanillaProgressionService.GatewayExpansionResult(
                                    target - before, target, maxGw));
                });

        // #1 / #24 canonical type-tagged stableKey differentiates int vs String & preserves List order while sorting Sets
        net.sabafly.dragonraid.api.progression.VanillaClearHookSpec intParamHook =
                new net.sabafly.dragonraid.api.progression.VanillaClearHookSpec(
                        null, "enc", null, false, true, "world_the_end", true, false, false, 0, 20, "hook",
                        java.util.Map.of("x", 1, "tags", java.util.Set.of("b", "a"), "ord", java.util.List.of(1, 2)));
        net.sabafly.dragonraid.api.progression.VanillaClearHookSpec strParamHook =
                new net.sabafly.dragonraid.api.progression.VanillaClearHookSpec(
                        null, "enc", null, false, true, "world_the_end", true, false, false, 0, 20, "hook",
                        java.util.Map.of("x", "1", "tags", java.util.Set.of("a", "b"), "ord", java.util.List.of(1, 2)));
        org.junit.jupiter.api.Assertions.assertNotEquals(intParamHook.stableKey(), strParamHook.stableKey());

        try (net.sabafly.dragonraid.storage.SqlitePlayerDataRepository repo =
                new net.sabafly.dragonraid.storage.SqlitePlayerDataRepository(dbFile, 2, Runnable::run)) {
            // #5 Missing VanillaProgressionService fails hook without persisting :done
            DefaultVanillaEndProgressionPolicy noSvcPolicy = new DefaultVanillaEndProgressionPolicy(
                    repo, null, null, null, null, null, java.util.logging.Logger.getLogger("test"));
            net.sabafly.dragonraid.api.progression.VanillaClearHookSpec firstOnlyGatewayHook =
                    new net.sabafly.dragonraid.api.progression.VanillaClearHookSpec(
                            "gw_hook", "enc_d3", net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL,
                            true, true, "world_the_end", false, false, false, 3, 20, null, java.util.Map.of());
            noSvcPolicy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "r1", 10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                            net.sabafly.dragonraid.api.progression.EndEntryGateMode.ALLOW, "", "", true, false),
                    java.util.List.of(firstOnlyGatewayHook))));
            java.util.UUID alice = java.util.UUID.randomUUID();
            net.sabafly.dragonraid.api.event.EncounterResultSnapshot firstSnap =
                    new net.sabafly.dragonraid.api.event.EncounterResultSnapshot(
                            "enc_d3", net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL,
                            net.sabafly.dragonraid.api.event.EncounterOutcome.VICTORY, 50_000L, true, 1,
                            java.util.Map.of(alice, new net.sabafly.dragonraid.api.event.ParticipantCombatMetrics(
                                    alice, "Alice", java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), 0, 0, 0)),
                            java.util.Set.of(alice));
            noSvcPolicy.onEncounterClearedAsync(firstSnap, true).join();
            assertTrue(repo.loadAllServerMilestones().join().stream().noneMatch(k -> k.endsWith(":done")));

            // Now wire policy with real vanillaService -> :done not persisted yet, D3 allows legacy before unclaimed firstClear=false
            DefaultVanillaEndProgressionPolicy policy = new DefaultVanillaEndProgressionPolicy(
                    repo, null, null, vanillaService, null, null, java.util.logging.Logger.getLogger("test"));
            policy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "r1", 10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                            net.sabafly.dragonraid.api.progression.EndEntryGateMode.ALLOW, "", "", true, false),
                    java.util.List.of(firstOnlyGatewayHook))));
            policy.loadServerMilestones().join();

            // D3: unclaimed firstClearOnly=true on firstClear=true -> applicable; once :claimed saved, firstClear=false resumes (#7) & suppresses legacy (D3)
            assertTrue(policy.hasApplicableWorldProgressionHook(
                    "enc_d3", net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL, true));
            policy.onEncounterClearedAsync(firstSnap, true).join();
            assertTrue(repo.loadAllServerMilestones().join().stream().noneMatch(k -> k.endsWith(":done")));
            // Claimed & target=5 saved, expandCalls #1 failed; repeat clear with firstClear=false resumes to target=5 (#7, #9, #10)
            net.sabafly.dragonraid.api.event.EncounterResultSnapshot repeatSnap =
                    new net.sabafly.dragonraid.api.event.EncounterResultSnapshot(
                            "enc_d3", net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL,
                            net.sabafly.dragonraid.api.event.EncounterOutcome.VICTORY, 50_000L, false, 1,
                            java.util.Map.of(alice, new net.sabafly.dragonraid.api.event.ParticipantCombatMetrics(
                                    alice, "Alice", java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), 0, 0, 0)),
                            java.util.Set.of());
            assertTrue(policy.hasApplicableWorldProgressionHook(
                    "enc_d3", net.sabafly.dragonraid.api.script.EncounterDifficulty.NORMAL, false));

            // Simulate server restart with new policy instance before repeat clear
            DefaultVanillaEndProgressionPolicy restartedPolicy = new DefaultVanillaEndProgressionPolicy(
                    repo, null, null, vanillaService, null, null, java.util.logging.Logger.getLogger("test"));
            restartedPolicy.replacePackRules(java.util.List.of(new net.sabafly.dragonraid.api.progression.VanillaProgressionRule(
                    "r1", 10,
                    new net.sabafly.dragonraid.api.progression.EndEntryGateSpec(
                            net.sabafly.dragonraid.api.progression.EndEntryGateMode.ALLOW, "", "", true, false),
                    java.util.List.of(firstOnlyGatewayHook))));
            restartedPolicy.loadServerMilestones().join();
            gateways.set(3); // Even if baseline moved after restart, target remains 5 (not 3 + 3 = 6!)
            restartedPolicy.onEncounterClearedAsync(repeatSnap, false).join();
            assertEquals(5, gateways.get());
            assertTrue(repo.loadAllServerMilestones().join().stream().anyMatch(k -> k.endsWith(":done")));
        }
    }
}
