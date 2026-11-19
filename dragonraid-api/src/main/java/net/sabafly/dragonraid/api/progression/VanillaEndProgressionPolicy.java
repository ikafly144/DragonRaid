package net.sabafly.dragonraid.api.progression;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;

/**
 * Policy SPI controlling vanilla End dimension gating and post-clear vanilla End progression hooks
 * (such as synchronizing vanilla DragonBattle defeat and expanding End Gateways).
 */
public interface VanillaEndProgressionPolicy {

    /**
     * Returns {@code true} if {@code playerId} is permitted to enter the vanilla End dimension directly.
     */
    boolean canEnterVanillaEnd(UUID playerId);

    /**
     * Hook invoked when a raid encounter completes with victory, allowing scripted/data-driven rules
     * to unlock vanilla End access or synchronize DragonBattle/gateway state.
     */
    void onEncounterCleared(String encounterId, Collection<UUID> participants);

    default VanillaEndAccessDecision evaluateVanillaEndAccess(UUID playerId) {
        return canEnterVanillaEnd(playerId)
                ? VanillaEndAccessDecision.allow()
                : VanillaEndAccessDecision.deny("progression.end-locked-generic", java.util.Map.of(), true, true);
    }

    default void onEncounterCleared(EncounterResultSnapshot snapshot, boolean serverFirst) {
        if (snapshot != null) {
            onEncounterCleared(snapshot.encounterId(), snapshot.participantMetrics().keySet());
        }
    }

    default void registerRule(VanillaProgressionRule rule) {
    }

    default void replacePackRules(Collection<VanillaProgressionRule> packRules) {
    }

    default Collection<VanillaProgressionRule> rules() {
        return List.of();
    }

    default boolean isServerMilestoneAchieved(String milestoneKey) {
        return false;
    }

    default CompletableFuture<Void> loadServerMilestones() {
        return CompletableFuture.completedFuture(null);
    }
}
