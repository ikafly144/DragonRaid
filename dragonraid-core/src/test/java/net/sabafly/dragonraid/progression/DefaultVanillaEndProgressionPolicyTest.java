package net.sabafly.dragonraid.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        when(vanillaService.expandEndGateways(any(), anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(1));

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
        }
    }
}
