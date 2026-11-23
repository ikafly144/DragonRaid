package net.sabafly.dragonraid.api.party;

import java.util.UUID;

/**
 * Predicate SPI indicating whether a player is currently locked inside an active raid encounter
 * or post-wipe instance standby and therefore must use {@code /cf leave} instead of mutating party membership.
 */
@FunctionalInterface
public interface PartyMembershipLockChecker {

    PartyMembershipLockChecker UNLOCKED = playerId -> false;

    boolean isPartyMutationLocked(UUID playerId);
}
