package net.sabafly.dragonraid.api.finder;

import java.util.UUID;

/**
 * Environment/platform read SPI used by {@link ContentFinderService} to inspect player presence,
 * active duty/instance occupancy, and registered encounter blueprints without depending on Paper or Core.
 */
public interface DutyEnvironmentChecker {

    DutyEnvironmentChecker ALWAYS_READY = new DutyEnvironmentChecker() {
        @Override
        public boolean isPlayerOnline(UUID playerId) {
            return true;
        }

        @Override
        public boolean isPlayerInEncounter(UUID playerId) {
            return false;
        }

        @Override
        public boolean isEncounterSlotBusy() {
            return false;
        }

        @Override
        public boolean hasBlueprint(String blueprintId) {
            return true;
        }
    };

    boolean isPlayerOnline(UUID playerId);

    boolean isPlayerInEncounter(UUID playerId);

    boolean isEncounterSlotBusy();

    boolean hasBlueprint(String blueprintId);
}
