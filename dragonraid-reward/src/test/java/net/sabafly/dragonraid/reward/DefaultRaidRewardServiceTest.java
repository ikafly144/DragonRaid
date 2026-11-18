package net.sabafly.dragonraid.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.junit.jupiter.api.Test;

class DefaultRaidRewardServiceTest {

    @Test
    void computesContributionRewardsFromCompletedEncounterEvent() {
        DefaultRaidRewardService service = new DefaultRaidRewardService();
        UUID alice = UUID.randomUUID();
        ParticipantCombatMetrics aliceMetrics = new ParticipantCombatMetrics(
                alice,
                "Alice",
                Map.of("ancient_dragon", 820.0),
                Map.of(EnemyRole.PRIMARY_BOSS, 820.0),
                Map.of("obsidian_crystal", 2),
                1,
                0,
                0
        );
        EncounterResultSnapshot snapshot = new EncounterResultSnapshot(
                "dragon_hard",
                EncounterDifficulty.HARD,
                EncounterOutcome.VICTORY,
                120_000L,
                true,
                2,
                Map.of(alice, aliceMetrics)
        );

        service.onEncounterCompleted(new RaidEncounterCompletedEvent(snapshot));
        RewardCalculationResult result = service.lastReward(alice).orElseThrow();
        assertEquals(25, result.baseTokens());
        assertEquals(4, result.crystalBonus());
        assertEquals(2, result.reviveBonus());
        assertEquals(4, result.damageBonus());
        assertEquals(15, result.firstClearBonus());
        assertEquals(50, result.totalTokens());

        List<RewardCalculationResult> wipeRewards = service.calculateRewards(new EncounterResultSnapshot(
                "dragon_normal",
                EncounterDifficulty.NORMAL,
                EncounterOutcome.WIPE,
                30_000L,
                false,
                2,
                Map.of(alice, aliceMetrics)
        ));
        assertTrue(wipeRewards.isEmpty());
    }
}
