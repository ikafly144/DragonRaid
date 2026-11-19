package net.sabafly.dragonraid.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.achievement.AchievementTriggerSpec;
import net.sabafly.dragonraid.api.achievement.AchievementUnlockedEvent;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.event.RaidMechanicResolvedEvent;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import org.junit.jupiter.api.Test;

class DefaultRaidAchievementServiceTest {

    @Test
    void evaluatesAllSixTriggersDeduplicatesAndEnforcesServerFirstAndPrerequisites() {
        ScriptHookRegistry scriptHooks = mock(ScriptHookRegistry.class);
        when(scriptHooks.evaluateCondition(eq("custom_gate"), any())).thenReturn(true);

        List<AchievementUnlockedEvent> emitted = new ArrayList<>();
        DefaultRaidAchievementService service = new DefaultRaidAchievementService(
                null,
                scriptHooks,
                null,
                emitted::add,
                () -> 10_000L,
                Logger.getLogger("test")
        );

        service.replacePackDefinitions(List.of(
                new AchievementDefinition("portal_touch", "pack", "t1", "d1", "ENDER_EYE", false, Set.of(),
                        List.of(AchievementTriggerSpec.endPortalTouch("world"))),
                new AchievementDefinition("first_normal", "pack", "t2", "d2", "DRAGON_HEAD", false, Set.of("portal_touch"),
                        List.of(AchievementTriggerSpec.serverFirst("dragon_normal", EncounterDifficulty.NORMAL))),
                new AchievementDefinition("clear_hard", "pack", "t3", "d3", "NETHERITE_SWORD", false, Set.of(),
                        List.of(AchievementTriggerSpec.encounterClear("dragon_hard", EncounterDifficulty.HARD))),
                new AchievementDefinition("deathless_clear", "pack", "t4", "d4", "TOTEM_OF_UNDYING", false, Set.of(),
                        List.of(AchievementTriggerSpec.noDeath("dragon_normal", EncounterDifficulty.NORMAL))),
                new AchievementDefinition("mechanic_master", "pack", "t5", "d5", "NETHER_STAR", false, Set.of(),
                        List.of(AchievementTriggerSpec.mechanicPerfect("EXAFLARE_WAVES", "ancient_dragon"))),
                new AchievementDefinition("script_special", "pack", "t6", "d6", "BOOK", false, Set.of(),
                        List.of(AchievementTriggerSpec.customScript("SECRET_SIGN", "custom_gate", Map.of("rank", "S"))))
        ));

        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        // 1. Wrong world -> does not unlock
        assertTrue(service.evaluateEndPortalTouch(alice, "Alice", "world_nether").join().isEmpty());
        // 2. Overworld portal touch -> unlocks once; repeated touches do not emit duplicate events
        assertEquals(1, service.evaluateEndPortalTouch(alice, "Alice", "world").join().size());
        assertTrue(service.evaluateEndPortalTouch(alice, "Alice", "world").join().isEmpty());
        assertTrue(service.hasUnlocked(alice, "portal_touch"));

        // 3. First Normal victory -> Alice has prerequisite so claims SERVER_FIRST; Bob does not
        ParticipantCombatMetrics aliceMetrics = new ParticipantCombatMetrics(
                alice, "Alice", Map.of(), Map.of(EnemyRole.PRIMARY_BOSS, 1000.0), Map.of("obsidian_crystal", 3), 1, 0, 0);
        ParticipantCombatMetrics bobMetrics = new ParticipantCombatMetrics(
                bob, "Bob", Map.of(), Map.of(), Map.of(), 0, 2, 1);

        EncounterResultSnapshot normalClear = new EncounterResultSnapshot(
                "dragon_normal", EncounterDifficulty.NORMAL, EncounterOutcome.VICTORY, 90_000L, true, 2,
                Map.of(alice, aliceMetrics, bob, bobMetrics));
        service.evaluateEncounterCompleted(normalClear).join();

        assertTrue(service.hasUnlocked(alice, "first_normal"));
        assertTrue(service.hasUnlocked(alice, "deathless_clear"));
        assertFalse(service.hasUnlocked(bob, "first_normal"));
        assertFalse(service.hasUnlocked(bob, "deathless_clear"));

        // 4. Subsequent clear by Bob (after he touches portal) must NOT award SERVER_FIRST again
        service.evaluateEndPortalTouch(bob, "Bob", "world").join();
        ParticipantCombatMetrics bobDeathless = new ParticipantCombatMetrics(
                bob, "Bob", Map.of(), Map.of(), Map.of(), 0, 0, 0);
        service.evaluateEncounterCompleted(new EncounterResultSnapshot(
                "dragon_normal", EncounterDifficulty.NORMAL, EncounterOutcome.VICTORY, 80_000L, true, 1,
                Map.of(bob, bobDeathless))).join();
        assertFalse(service.hasUnlocked(bob, "first_normal"));
        assertTrue(service.hasUnlocked(bob, "deathless_clear"));

        // 5. Clear difficulty condition check (#41): dragon_hard on NORMAL must NOT unlock clear_hard; HARD must unlock
        service.evaluateEncounterCompleted(new EncounterResultSnapshot(
                "dragon_hard", EncounterDifficulty.NORMAL, EncounterOutcome.VICTORY, 90_000L, true, 1,
                Map.of(bob, bobDeathless))).join();
        assertFalse(service.hasUnlocked(bob, "clear_hard"));

        service.evaluateEncounterCompleted(new EncounterResultSnapshot(
                "dragon_hard", EncounterDifficulty.HARD, EncounterOutcome.VICTORY, 95_000L, true, 1,
                Map.of(bob, bobDeathless))).join();
        assertTrue(service.hasUnlocked(bob, "clear_hard"));

        // 6. Mechanic perfect & custom script triggers
        service.onMechanicResolved(new RaidMechanicResolvedEvent(
                "EXAFLARE_WAVES", "ancient_dragon", Set.of(), true, Map.of("eligiblePlayers", List.of(alice))));
        assertTrue(service.hasUnlocked(alice, "mechanic_master"));

        assertEquals(1, service.evaluateCustomTrigger(alice, "Alice", "SECRET_SIGN", Map.of("score", 100)).join().size());
        assertTrue(service.hasUnlocked(alice, "script_special"));
        assertTrue(emitted.stream().anyMatch(e -> e.achievement().id().equals("first_normal") && e.serverFirst()));
    }

    @Test
    void persistsGrantsThroughRepositoryAndSkipsDuplicateEventEmission() {
        PlayerProgressionRepository repo = mock(PlayerProgressionRepository.class);
        UUID alice = UUID.randomUUID();
        when(repo.loadAchievements(alice)).thenReturn(CompletableFuture.completedFuture(Set.of("portal_touch")));
        when(repo.grantAchievement(eq(alice), eq("Alice"), eq("clear_any"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(true));

        List<AchievementUnlockedEvent> emitted = new ArrayList<>();
        DefaultRaidAchievementService service = new DefaultRaidAchievementService(
                repo, null, null, emitted::add, () -> 5000L, Logger.getLogger("test"));
        service.registerDefinition(new AchievementDefinition(
                "portal_touch", "t1", "d1", null, false, Set.of(), List.of(AchievementTriggerSpec.endPortalTouch())));
        service.registerDefinition(new AchievementDefinition(
                "clear_any", "t2", "d2", null, false, Set.of(), List.of(AchievementTriggerSpec.encounterClear("*", null))));

        service.loadPlayer(alice).join();
        assertTrue(service.hasUnlocked(alice, "portal_touch"));

        // Already persisted -> evaluates without calling repo.grantAchievement again
        assertTrue(service.evaluateEndPortalTouch(alice, "Alice", "world").join().isEmpty());
        verify(repo, times(0)).grantAchievement(eq(alice), any(), eq("portal_touch"), anyLong());

        // New achievement -> persisted and event published once
        service.evaluateEncounterCompleted(new EncounterResultSnapshot(
                "any_raid", EncounterDifficulty.NORMAL, EncounterOutcome.VICTORY, 50_000L, true, 1,
                Map.of(alice, new ParticipantCombatMetrics(alice, "Alice", Map.of(), Map.of(), Map.of(), 0, 0, 0)))).join();
        verify(repo, times(1)).grantAchievement(alice, "Alice", "clear_any", 5000L);
        assertEquals(1, emitted.size());
    }

    @Test
    void resumesFailedServerFirstRecipientWithoutDuplicatingPriorWinnersEvent() {
        PlayerProgressionRepository repo = mock(PlayerProgressionRepository.class);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        when(repo.claimOrResumeServerFirstRecipients(eq("achievement:sf_dragon"), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(Set.of(alice, bob)))
                .thenReturn(CompletableFuture.completedFuture(Set.of(bob)));
        when(repo.grantAchievement(eq(alice), eq("Alice"), eq("sf_dragon"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repo.grantAchievement(eq(bob), eq("Bob"), eq("sf_dragon"), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sqlite write failed")))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repo.completeServerFirstRecipient(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        List<AchievementUnlockedEvent> emitted = new ArrayList<>();
        DefaultRaidAchievementService service = new DefaultRaidAchievementService(
                repo, null, null, emitted::add, () -> 5000L, Logger.getLogger("test"));
        service.registerDefinition(new AchievementDefinition(
                "sf_dragon", "t", "d", null, false, Set.of(),
                List.of(AchievementTriggerSpec.serverFirst("dragon_normal", EncounterDifficulty.NORMAL))));

        EncounterResultSnapshot snap = new EncounterResultSnapshot(
                "dragon_normal", EncounterDifficulty.NORMAL, EncounterOutcome.VICTORY, 60_000L, true, 2,
                Map.of(
                        alice, new ParticipantCombatMetrics(alice, "Alice", Map.of(), Map.of(), Map.of(), 0, 0, 0),
                        bob, new ParticipantCombatMetrics(bob, "Bob", Map.of(), Map.of(), Map.of(), 0, 0, 0)
                ));

        List<AchievementUnlockedEvent> firstPass = service.evaluateEncounterCompleted(snap).join();
        assertEquals(1, firstPass.size());
        assertEquals(alice, firstPass.getFirst().playerId());
        assertTrue(firstPass.getFirst().serverFirst());
        assertFalse(service.hasUnlocked(bob, "sf_dragon"));
        verify(repo, times(1)).completeServerFirstRecipient("achievement:sf_dragon", alice);
        verify(repo, times(0)).completeServerFirstRecipient("achievement:sf_dragon", bob);

        // Retry completes Bob with serverFirst=true while never re-emitting Alice's event
        List<AchievementUnlockedEvent> secondPass = service.evaluateEncounterCompleted(snap).join();
        assertEquals(1, secondPass.size());
        assertEquals(bob, secondPass.getFirst().playerId());
        assertTrue(secondPass.getFirst().serverFirst());
        assertTrue(service.hasUnlocked(bob, "sf_dragon"));
        assertEquals(2, emitted.size());
        verify(repo, times(1)).completeServerFirstRecipient("achievement:sf_dragon", bob);
    }

    @Test
    void releasesPendingGrantLockOnNullMetadataValuesAndSynchronousRepoErrors() {
        PlayerProgressionRepository repo = mock(PlayerProgressionRepository.class);
        UUID alice = UUID.randomUUID();
        when(repo.grantAchievement(eq(alice), eq("Alice"), eq("ach_sync"), anyLong()))
                .thenThrow(new IllegalStateException("rejected executor"))
                .thenReturn(CompletableFuture.completedFuture(true));

        List<AchievementUnlockedEvent> emitted = new ArrayList<>();
        DefaultRaidAchievementService service = new DefaultRaidAchievementService(
                repo, null, null, emitted::add, () -> 5000L, Logger.getLogger("test"));
        service.registerDefinition(new AchievementDefinition(
                "ach_sync", "t", "d", null, false, Set.of(), List.of()));

        Map<String, Object> metadataWithNull = new HashMap<>();
        metadataWithNull.put("k", null);
        // Attempt 1 throws synchronously -> pending lock released
        assertTrue(service.grantAchievement(alice, "Alice", "ach_sync",
                net.sabafly.dragonraid.api.achievement.AchievementTriggerType.CUSTOM_SCRIPT, false, metadataWithNull)
                .join().isEmpty());

        // Attempt 2 succeeds cleanly
        assertTrue(service.grantAchievement(alice, "Alice", "ach_sync",
                net.sabafly.dragonraid.api.achievement.AchievementTriggerType.CUSTOM_SCRIPT, false, metadataWithNull)
                .join().isPresent());
        assertTrue(service.hasUnlocked(alice, "ach_sync"));
        assertEquals(1, emitted.size());
    }
}
