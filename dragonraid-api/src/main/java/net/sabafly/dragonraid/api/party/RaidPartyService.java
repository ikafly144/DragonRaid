package net.sabafly.dragonraid.api.party;

import java.util.Optional;
import java.util.UUID;

/**
 * Platform SPI for party lifecycle management (creation, invites, leadership, membership, and disbanding).
 */
public interface RaidPartyService {

    RaidParty createParty(UUID leaderId);

    Optional<RaidParty> partyOf(UUID playerId);

    boolean invite(UUID leaderId, UUID targetId);

    boolean acceptInvite(UUID playerId, UUID partyId);

    boolean leave(UUID playerId);

    boolean kick(UUID leaderId, UUID targetId);

    boolean transferLeadership(UUID currentLeaderId, UUID newLeaderId);

    boolean disband(UUID leaderId);
}
