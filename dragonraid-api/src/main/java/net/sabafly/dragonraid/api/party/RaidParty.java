package net.sabafly.dragonraid.api.party;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot of an active DragonRaid party.
 */
public record RaidParty(
        UUID partyId,
        UUID leaderId,
        List<UUID> memberIds
) {

    public RaidParty {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(leaderId, "leaderId");
        memberIds = List.copyOf(Objects.requireNonNull(memberIds, "memberIds"));
        if (!memberIds.contains(leaderId)) {
            throw new IllegalArgumentException("leaderId must be included in memberIds");
        }
    }

    public int size() {
        return memberIds.size();
    }
}
