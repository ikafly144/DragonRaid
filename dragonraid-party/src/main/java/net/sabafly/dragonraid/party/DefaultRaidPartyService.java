package net.sabafly.dragonraid.party;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;

/**
 * Thread-safe in-memory M1 implementation of {@link RaidPartyService}.
 */
public final class DefaultRaidPartyService implements RaidPartyService {

    private final Object lock = new Object();
    private final Map<UUID, MutableParty> partiesById = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partyIdByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> pendingInvitesByTarget = new ConcurrentHashMap<>();

    @Override
    public RaidParty createParty(UUID leaderId) {
        Objects.requireNonNull(leaderId, "leaderId");
        synchronized (lock) {
            if (partyIdByPlayer.containsKey(leaderId)) {
                throw new IllegalStateException("Player is already in a party: " + leaderId);
            }
            UUID partyId = UUID.randomUUID();
            MutableParty party = new MutableParty(partyId, leaderId);
            partiesById.put(partyId, party);
            partyIdByPlayer.put(leaderId, partyId);
            pendingInvitesByTarget.remove(leaderId);
            return party.snapshot();
        }
    }

    @Override
    public Optional<RaidParty> partyOf(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            UUID partyId = partyIdByPlayer.get(playerId);
            if (partyId == null) {
                return Optional.empty();
            }
            MutableParty party = partiesById.get(partyId);
            return party == null ? Optional.empty() : Optional.of(party.snapshot());
        }
    }

    @Override
    public boolean invite(UUID leaderId, UUID targetId) {
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(targetId, "targetId");
        if (leaderId.equals(targetId)) {
            return false;
        }
        synchronized (lock) {
            UUID partyId = partyIdByPlayer.get(leaderId);
            if (partyId == null) {
                return false;
            }
            MutableParty party = partiesById.get(partyId);
            if (party == null || !party.leaderId.equals(leaderId)) {
                return false;
            }
            if (partyIdByPlayer.containsKey(targetId)) {
                return false;
            }
            pendingInvitesByTarget
                    .computeIfAbsent(targetId, k -> ConcurrentHashMap.newKeySet())
                    .add(partyId);
            return true;
        }
    }

    @Override
    public boolean acceptInvite(UUID playerId, UUID partyId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(partyId, "partyId");
        synchronized (lock) {
            if (partyIdByPlayer.containsKey(playerId)) {
                return false;
            }
            Set<UUID> invites = pendingInvitesByTarget.get(playerId);
            boolean removedInvite = invites != null && invites.remove(partyId);
            if (invites != null && invites.isEmpty()) {
                pendingInvitesByTarget.remove(playerId);
            }
            if (!removedInvite) {
                return false;
            }
            MutableParty party = partiesById.get(partyId);
            if (party == null) {
                return false;
            }
            party.memberIds.add(playerId);
            partyIdByPlayer.put(playerId, partyId);
            pendingInvitesByTarget.remove(playerId);
            return true;
        }
    }

    @Override
    public boolean leave(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            UUID partyId = partyIdByPlayer.remove(playerId);
            if (partyId == null) {
                return false;
            }
            MutableParty party = partiesById.get(partyId);
            if (party == null) {
                return false;
            }
            party.memberIds.remove(playerId);
            if (party.memberIds.isEmpty()) {
                partiesById.remove(partyId);
                removeInvitesForPartyLocked(partyId);
                return true;
            }
            if (party.leaderId.equals(playerId)) {
                party.leaderId = party.memberIds.getFirst();
            }
            return true;
        }
    }

    @Override
    public boolean kick(UUID leaderId, UUID targetId) {
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(targetId, "targetId");
        if (leaderId.equals(targetId)) {
            return false;
        }
        synchronized (lock) {
            UUID partyId = partyIdByPlayer.get(leaderId);
            if (partyId == null || !partyId.equals(partyIdByPlayer.get(targetId))) {
                return false;
            }
            MutableParty party = partiesById.get(partyId);
            if (party == null || !party.leaderId.equals(leaderId)) {
                return false;
            }
            party.memberIds.remove(targetId);
            partyIdByPlayer.remove(targetId);
            return true;
        }
    }

    @Override
    public boolean transferLeadership(UUID currentLeaderId, UUID newLeaderId) {
        Objects.requireNonNull(currentLeaderId, "currentLeaderId");
        Objects.requireNonNull(newLeaderId, "newLeaderId");
        if (currentLeaderId.equals(newLeaderId)) {
            return false;
        }
        synchronized (lock) {
            UUID partyId = partyIdByPlayer.get(currentLeaderId);
            if (partyId == null || !partyId.equals(partyIdByPlayer.get(newLeaderId))) {
                return false;
            }
            MutableParty party = partiesById.get(partyId);
            if (party == null || !party.leaderId.equals(currentLeaderId)) {
                return false;
            }
            party.leaderId = newLeaderId;
            return true;
        }
    }

    @Override
    public boolean disband(UUID leaderId) {
        Objects.requireNonNull(leaderId, "leaderId");
        synchronized (lock) {
            UUID partyId = partyIdByPlayer.get(leaderId);
            if (partyId == null) {
                return false;
            }
            MutableParty party = partiesById.get(partyId);
            if (party == null || !party.leaderId.equals(leaderId)) {
                return false;
            }
            partiesById.remove(partyId);
            removeInvitesForPartyLocked(partyId);
            for (UUID memberId : party.memberIds) {
                partyIdByPlayer.remove(memberId);
            }
            return true;
        }
    }

    private void removeInvitesForPartyLocked(UUID partyId) {
        pendingInvitesByTarget.entrySet().removeIf(entry -> {
            Set<UUID> invites = entry.getValue();
            invites.remove(partyId);
            return invites.isEmpty();
        });
    }

    private static final class MutableParty {
        final UUID partyId;
        UUID leaderId;
        final List<UUID> memberIds = new ArrayList<>();

        MutableParty(UUID partyId, UUID leaderId) {
            this.partyId = partyId;
            this.leaderId = leaderId;
            this.memberIds.add(leaderId);
        }

        RaidParty snapshot() {
            return new RaidParty(partyId, leaderId, memberIds);
        }
    }
}
