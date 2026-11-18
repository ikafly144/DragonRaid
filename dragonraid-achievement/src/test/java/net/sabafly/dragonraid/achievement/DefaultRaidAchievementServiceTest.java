package net.sabafly.dragonraid.achievement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.api.event.RaidMechanicResolvedEvent;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.junit.jupiter.api.Test;

class DefaultRaidAchievementServiceTest {

    @Test
    void unlocksEncounterAndMechanicAchievementsFromEvents() {
        DefaultRaidAchievementService service = new DefaultRaidAchievementService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        ParticipantCombatMetrics aliceMetrics = new ParticipantCombatMetrics(
                alice, "Alice", Map.of(), Map.of(EnemyRole.PRIMARY_BOSS, 1000.0), Map.of("obsidian_crystal", 3), 1, 0, 0);
        ParticipantCombatMetrics bobMetrics = new ParticipantCombatMetrics(
                bob, "Bob", Map.of(), Map.of(), Map.of(), 0, 2, 3);

        service.onEncounterCompleted(new RaidEncounterCompletedEvent(new EncounterResultSnapshot(
                "dragon_hard",
                EncounterDifficulty.HARD,
                EncounterOutcome.VICTORY,
                90_000L,
                true,
                2,
                Map.of(alice, aliceMetrics, bob, bobMetrics)
        )));

        assertTrue(service.unlockedFor(alice).contains(DefaultRaidAchievementService.ACH_FIRST_SLAYER));
        assertTrue(service.unlockedFor(alice).contains(DefaultRaidAchievementService.ACH_DEATHLESS));
        assertTrue(service.unlockedFor(alice).contains(DefaultRaidAchievementService.ACH_HARD_CONQUEROR));
        assertTrue(service.unlockedFor(alice).contains(DefaultRaidAchievementService.ACH_CRYSTAL_BREAKER));
        assertFalse(service.unlockedFor(bob).contains(DefaultRaidAchievementService.ACH_DEATHLESS));

        service.onMechanicResolved(new RaidMechanicResolvedEvent(
                "EXAFLARE_WAVES",
                "ancient_dragon",
                Set.of(),
                true,
                Map.of("eligiblePlayers", List.of(alice))
        ));
        assertTrue(service.unlockedFor(alice).contains(DefaultRaidAchievementService.ACH_FLAWLESS_MECHANIC));
    }
}
