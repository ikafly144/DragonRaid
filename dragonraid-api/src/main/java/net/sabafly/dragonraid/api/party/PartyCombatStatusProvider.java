package net.sabafly.dragonraid.api.party;

import java.util.UUID;

/**
 * Read-only SPI exposing active raid combat status (alive/downed/spectator, remaining revives)
 * to the party HUD module without violating the strict star dependency topology.
 */
public interface PartyCombatStatusProvider {

    PartyCombatStatusProvider NONE = new PartyCombatStatusProvider() {
        @Override
        public boolean isInActiveEncounter(UUID playerId) {
            return false;
        }

        @Override
        public PartyMemberCombatState combatState(UUID playerId, boolean online) {
            return online ? PartyMemberCombatState.OUT_OF_COMBAT : PartyMemberCombatState.OFFLINE;
        }

        @Override
        public int remainingRevives(UUID playerId) {
            return 0;
        }
    };

    boolean isInActiveEncounter(UUID playerId);

    PartyMemberCombatState combatState(UUID playerId, boolean online);

    int remainingRevives(UUID playerId);
}
