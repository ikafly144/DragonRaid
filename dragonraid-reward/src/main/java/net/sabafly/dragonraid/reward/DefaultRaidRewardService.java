package net.sabafly.dragonraid.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Default {@link RaidRewardService} calculating base and per-enemy contribution tokens from
 * {@link RaidEncounterCompletedEvent}.
 */
public final class DefaultRaidRewardService implements RaidRewardService, Listener {

    private final ConcurrentHashMap<UUID, RewardCalculationResult> lastRewards = new ConcurrentHashMap<>();

    @EventHandler
    public void onEncounterCompleted(RaidEncounterCompletedEvent event) {
        Objects.requireNonNull(event, "event");
        for (RewardCalculationResult reward : calculateRewards(event.snapshot())) {
            lastRewards.put(reward.playerId(), reward);
        }
    }

    @Override
    public List<RewardCalculationResult> calculateRewards(EncounterResultSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.outcome() != EncounterOutcome.VICTORY) {
            return List.of();
        }
        int base = snapshot.difficulty() == EncounterDifficulty.HARD ? 25 : 10;
        int firstClearBonus = snapshot.firstClear() ? 15 : 0;
        List<RewardCalculationResult> results = new ArrayList<>(snapshot.participantMetrics().size());
        for (ParticipantCombatMetrics metrics : snapshot.participantMetrics().values()) {
            int crystalBonus = Math.min(8, metrics.totalCrystalsDestroyed() * 2);
            int reviveBonus = Math.min(6, metrics.revivesCompleted() * 2);
            int damageBonus = Math.min(10, (int) Math.floor(metrics.totalBossDamage() / 200.0));
            int total = base + crystalBonus + reviveBonus + damageBonus + firstClearBonus;
            results.add(new RewardCalculationResult(
                    metrics.playerId(),
                    base,
                    crystalBonus,
                    reviveBonus,
                    damageBonus,
                    firstClearBonus,
                    total
            ));
        }
        return List.copyOf(results);
    }

    @Override
    public Optional<RewardCalculationResult> lastReward(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastRewards.get(playerId));
    }
}
