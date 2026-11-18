package net.sabafly.dragonraid.reward;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;

/**
 * Event-driven reward calculation service decoupled from core raid orchestration.
 */
public interface RaidRewardService {

    List<RewardCalculationResult> calculateRewards(EncounterResultSnapshot snapshot);

    Optional<RewardCalculationResult> lastReward(UUID playerId);
}
