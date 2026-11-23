package net.sabafly.dragonraid.api.party;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable domain event emitted whenever party membership, leadership, invites, or connection state changes.
 */
public record PartyChangeEvent(
        Type type,
        UUID partyId,
        @Nullable RaidParty before,
        @Nullable RaidParty after,
        @Nullable UUID actorId,
        @Nullable UUID targetId
) {

    public enum Type {
        CREATED,
        INVITED,
        INVITE_DECLINED,
        JOINED,
        LEFT,
        KICKED,
        LEADER_TRANSFERRED,
        DISBANDED,
        DISCONNECTED,
        RECONNECTED,
        SEAT_EXPIRED
    }

    public PartyChangeEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(partyId, "partyId");
    }
}
