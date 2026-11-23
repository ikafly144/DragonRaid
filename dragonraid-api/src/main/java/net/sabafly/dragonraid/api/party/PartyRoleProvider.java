package net.sabafly.dragonraid.api.party;

import java.util.Optional;
import java.util.UUID;

/**
 * Extension SPI allowing future scripts or integrations to provide optional role badges in the party list HUD.
 */
@FunctionalInterface
public interface PartyRoleProvider {

    PartyRoleProvider NOOP = playerId -> Optional.empty();

    Optional<PartyRoleTag> roleFor(UUID playerId);
}
