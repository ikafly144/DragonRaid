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
}
