package net.sabafly.dragonraid.api.party;

import java.util.UUID;

/**
 * Service SPI managing per-player right-sidebar FF14-style party list HUD rendering.
 */
public interface PartyHudService {

    void setHudEnabled(UUID playerId, boolean enabled);

    boolean isHudEnabled(UUID playerId);

    void refreshViewer(UUID viewerId);

    void refreshParty(UUID partyId);

    void clearViewer(UUID viewerId);

    default void tickAll() {
    }

    void shutdown();
}
