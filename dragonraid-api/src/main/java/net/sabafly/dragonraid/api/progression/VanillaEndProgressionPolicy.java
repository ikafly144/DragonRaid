package net.sabafly.dragonraid.api.progression;

import java.util.Collection;
import java.util.UUID;

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
}
