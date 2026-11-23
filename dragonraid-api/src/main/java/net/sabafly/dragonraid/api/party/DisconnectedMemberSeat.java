package net.sabafly.dragonraid.api.party;

import java.util.Objects;
import java.util.UUID;

/**
 * Grace-period seat reservation for a party member who temporarily disconnected.
 */
public record DisconnectedMemberSeat(
        UUID playerId,
        UUID partyId,
        long disconnectedAtMillis,
        long expiresAtMillis
) {

    public DisconnectedMemberSeat {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(partyId, "partyId");
    }

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
    }
}
