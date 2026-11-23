package net.sabafly.dragonraid.api.party;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a pending party invitation with an expiration timestamp.
 */
public record PartyInvite(
        UUID partyId,
        UUID inviterId,
        UUID targetId,
        long createdAtMillis,
        long expiresAtMillis
) {

    public PartyInvite {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(inviterId, "inviterId");
        Objects.requireNonNull(targetId, "targetId");
    }

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
    }
}
