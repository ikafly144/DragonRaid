package net.sabafly.dragonraid.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.party.DisconnectedMemberSeat;
import net.sabafly.dragonraid.api.party.PartyChangeEvent;
import net.sabafly.dragonraid.api.party.PartyChangeListener;
import net.sabafly.dragonraid.api.party.PartyChatChannel;
import net.sabafly.dragonraid.api.party.PartyInvite;
import net.sabafly.dragonraid.api.party.PartyMembershipLockChecker;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe {@link RaidPartyService} managing party lifecycle, expiring invites,
 * disconnect grace seats with immediate leader failover, duty-lock protection,
 * per-player chat channels, and lock-free listener dispatch.
 */
public final class DefaultRaidPartyService implements RaidPartyService {

    public static final int DEFAULT_MAX_PARTY_SIZE = 8;
    public static final long DEFAULT_INVITE_TIMEOUT_MILLIS = 60_000L;
    public static final long DEFAULT_DISCONNECT_GRACE_MILLIS = 120_000L;

    private static final Logger LOGGER = Logger.getLogger(DefaultRaidPartyService.class.getName());

    private final Object lock = new Object();
    private final int maxPartySize;
    private final long inviteTimeoutMillis;
    private final long disconnectGraceMillis;
    private final PartyMembershipLockChecker lockChecker;
    private final LongSupplier clockMillis;

    private final Map<UUID, MutableParty> partiesById = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partyIdByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, PartyInvite>> invitesByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, DisconnectedMemberSeat> disconnectedSeatsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PartyChatChannel> chatChannelsByPlayer = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PartyChangeListener> listeners = new CopyOnWriteArrayList<>();

    public DefaultRaidPartyService() {
        this(
                DEFAULT_MAX_PARTY_SIZE,
                DEFAULT_INVITE_TIMEOUT_MILLIS,
                DEFAULT_DISCONNECT_GRACE_MILLIS,
                PartyMembershipLockChecker.UNLOCKED,
                System::currentTimeMillis
        );
    }

    public DefaultRaidPartyService(
            int maxPartySize,
            long inviteTimeoutMillis,
            long disconnectGraceMillis) {
        this(
                maxPartySize,
                inviteTimeoutMillis,
                disconnectGraceMillis,
                PartyMembershipLockChecker.UNLOCKED,
                System::currentTimeMillis
        );
    }

    public DefaultRaidPartyService(
            int maxPartySize,
            long inviteTimeoutMillis,
            long disconnectGraceMillis,
            PartyMembershipLockChecker lockChecker,
            LongSupplier clockMillis) {
        if (maxPartySize < 1) {
            throw new IllegalArgumentException("maxPartySize must be >= 1");
        }
        if (inviteTimeoutMillis < 0L) {
            throw new IllegalArgumentException("inviteTimeoutMillis must be >= 0");
        }
        if (disconnectGraceMillis < 0L) {
            throw new IllegalArgumentException("disconnectGraceMillis must be >= 0");
        }
        this.maxPartySize = maxPartySize;
        this.inviteTimeoutMillis = inviteTimeoutMillis;
        this.disconnectGraceMillis = disconnectGraceMillis;
        this.lockChecker = Objects.requireNonNull(lockChecker, "lockChecker");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    @Override
    public int maxPartySize() {
        return maxPartySize;
    }

    @Override
    public boolean isMutationLocked(UUID playerId) {
        return playerId != null && lockChecker.isPartyMutationLocked(playerId);
    }

    @Override
    public RaidParty createParty(UUID leaderId) {
        Objects.requireNonNull(leaderId, "leaderId");
        RaidParty created = null;
        IllegalStateException failure = null;
        final List<PartyChangeEvent> events = new ArrayList<>(1);
        synchronized (lock) {
            expireDisconnectedSeatsLocked(clockMillis.getAsLong(), events);
            if (partyIdByPlayer.containsKey(leaderId)) {
                failure = new IllegalStateException("Player is already in a party: " + leaderId);
            } else if (lockChecker.isPartyMutationLocked(leaderId)) {
                failure = new IllegalStateException("Player is locked inside an active raid duty: " + leaderId);
            } else {
                UUID partyId = UUID.randomUUID();
                MutableParty party = new MutableParty(partyId, leaderId);
                partiesById.put(partyId, party);
                partyIdByPlayer.put(leaderId, partyId);
                invitesByTarget.remove(leaderId);
                created = party.snapshot();
                events.add(new PartyChangeEvent(PartyChangeEvent.Type.CREATED, partyId, null, created, leaderId, leaderId));
            }
        }
        dispatchEvents(events);
        if (failure != null) {
            throw failure;
        }
        return created;
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
    public Optional<RaidParty> partyById(UUID partyId) {
        if (partyId == null) {
            return Optional.empty();
        }
        synchronized (lock) {
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
        List<PartyChangeEvent> events = new ArrayList<>(2);
        boolean sent = false;
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            purgeExpiredInvitesLocked(now);
            expireDisconnectedSeatsLocked(now, events);
            UUID partyId = partyIdByPlayer.get(leaderId);
            if (partyId != null
                    && !lockChecker.isPartyMutationLocked(leaderId)
                    && !lockChecker.isPartyMutationLocked(targetId)) {
                MutableParty party = partiesById.get(partyId);
                if (party != null
                        && party.leaderId.equals(leaderId)
                        && !partyIdByPlayer.containsKey(targetId)
                        && party.memberIds.size() < maxPartySize
                        && !anyMemberLockedLocked(party)) {
                    long expiresAt = inviteTimeoutMillis > 0L ? now + inviteTimeoutMillis : 0L;
                    PartyInvite invite = new PartyInvite(partyId, leaderId, targetId, now, expiresAt);
                    invitesByTarget.computeIfAbsent(targetId, k -> new LinkedHashMap<>()).put(partyId, invite);
                    RaidParty snap = party.snapshot();
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.INVITED, partyId, snap, snap, leaderId, targetId));
                    sent = true;
                }
            }
        }
        dispatchEvents(events);
        return sent;
    }

    @Override
    public boolean acceptInvite(UUID playerId, UUID partyId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(partyId, "partyId");
        List<PartyChangeEvent> events = new ArrayList<>(2);
        boolean accepted = false;
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            purgeExpiredInvitesLocked(now);
            expireDisconnectedSeatsLocked(now, events);
            if (!lockChecker.isPartyMutationLocked(playerId) && !partyIdByPlayer.containsKey(playerId)) {
                Map<UUID, PartyInvite> targetInvites = invitesByTarget.get(playerId);
                PartyInvite invite = targetInvites == null ? null : targetInvites.get(partyId);
                MutableParty party = invite == null ? null : partiesById.get(partyId);
                if (invite != null && party != null
                        && party.memberIds.size() < maxPartySize
                        && !anyMemberLockedLocked(party)) {
                    targetInvites.remove(partyId);
                    if (targetInvites.isEmpty()) {
                        invitesByTarget.remove(playerId);
                    }
                    RaidParty before = party.snapshot();
                    party.memberIds.add(playerId);
                    partyIdByPlayer.put(playerId, partyId);
                    invitesByTarget.remove(playerId);
                    chatChannelsByPlayer.remove(playerId);
                    RaidParty after = party.snapshot();
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.JOINED, partyId, before, after, playerId, playerId));
                    accepted = true;
                }
            }
        }
        dispatchEvents(events);
        return accepted;
    }

    @Override
    public boolean declineInvite(UUID playerId, UUID partyId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(partyId, "partyId");
        List<PartyChangeEvent> events = new ArrayList<>(1);
        boolean declined = false;
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            purgeExpiredInvitesLocked(now);
            Map<UUID, PartyInvite> targetInvites = invitesByTarget.get(playerId);
            if (targetInvites != null) {
                PartyInvite removed = targetInvites.remove(partyId);
                if (targetInvites.isEmpty()) {
                    invitesByTarget.remove(playerId);
                }
                if (removed != null) {
                    MutableParty party = partiesById.get(partyId);
                    RaidParty snap = party != null ? party.snapshot() : null;
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.INVITE_DECLINED, partyId, snap, snap, playerId, playerId));
                    declined = true;
                }
            }
        }
        dispatchEvents(events);
        return declined;
    }

    @Override
    public List<PartyInvite> pendingInvites(UUID targetId) {
        if (targetId == null) {
            return List.of();
        }
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            Map<UUID, PartyInvite> invites = invitesByTarget.get(targetId);
            if (invites == null || invites.isEmpty()) {
                return List.of();
            }
            return invites.values().stream()
                    .filter(invite -> !invite.isExpired(now) && partiesById.containsKey(invite.partyId()))
                    .sorted(Comparator.comparingLong(PartyInvite::createdAtMillis))
                    .toList();
        }
    }

    @Override
    public int expireInvites() {
        synchronized (lock) {
            return purgeExpiredInvitesLocked(clockMillis.getAsLong());
        }
    }

    @Override
    public boolean leave(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        List<PartyChangeEvent> events = new ArrayList<>(3);
        boolean left = false;
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            expireDisconnectedSeatsLocked(now, events);
            UUID partyId = partyIdByPlayer.get(playerId);
            MutableParty party = partyId == null ? null : partiesById.get(partyId);
            if (party != null && !lockChecker.isPartyMutationLocked(playerId)) {
                partyIdByPlayer.remove(playerId);
                disconnectedSeatsByPlayer.remove(playerId);
                chatChannelsByPlayer.remove(playerId);
                RaidParty before = party.snapshot();
                party.memberIds.remove(playerId);
                if (party.memberIds.isEmpty()) {
                    partiesById.remove(partyId);
                    removeInvitesForPartyLocked(partyId);
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.LEFT, partyId, before, null, playerId, playerId));
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.DISBANDED, partyId, before, null, playerId, null));
                } else {
                    UUID newLeader = party.leaderId;
                    boolean transferred = false;
                    if (party.leaderId.equals(playerId)) {
                        newLeader = selectNextLeaderLocked(party);
                        party.leaderId = newLeader;
                        transferred = true;
                    }
                    RaidParty after = party.snapshot();
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.LEFT, partyId, before, after, playerId, playerId));
                    if (transferred) {
                        events.add(new PartyChangeEvent(PartyChangeEvent.Type.LEADER_TRANSFERRED, partyId, before, after, playerId, newLeader));
                    }
                }
                left = true;
            }
        }
        dispatchEvents(events);
        return left;
    }

    @Override
    public boolean kick(UUID leaderId, UUID targetId) {
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(targetId, "targetId");
        if (leaderId.equals(targetId)) {
            return false;
        }
        List<PartyChangeEvent> events = new ArrayList<>(2);
        boolean kicked = false;
        synchronized (lock) {
            expireDisconnectedSeatsLocked(clockMillis.getAsLong(), events);
            UUID partyId = partyIdByPlayer.get(leaderId);
            if (partyId != null && partyId.equals(partyIdByPlayer.get(targetId))
                    && !lockChecker.isPartyMutationLocked(leaderId)
                    && !lockChecker.isPartyMutationLocked(targetId)) {
                MutableParty party = partiesById.get(partyId);
                if (party != null && party.leaderId.equals(leaderId)) {
                    RaidParty before = party.snapshot();
                    party.memberIds.remove(targetId);
                    partyIdByPlayer.remove(targetId);
                    disconnectedSeatsByPlayer.remove(targetId);
                    chatChannelsByPlayer.remove(targetId);
                    RaidParty after = party.snapshot();
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.KICKED, partyId, before, after, leaderId, targetId));
                    kicked = true;
                }
            }
        }
        dispatchEvents(events);
        return kicked;
    }

    @Override
    public boolean transferLeadership(UUID currentLeaderId, UUID newLeaderId) {
        Objects.requireNonNull(currentLeaderId, "currentLeaderId");
        Objects.requireNonNull(newLeaderId, "newLeaderId");
        if (currentLeaderId.equals(newLeaderId)) {
            return false;
        }
        List<PartyChangeEvent> events = new ArrayList<>(2);
        boolean transferred = false;
        synchronized (lock) {
            expireDisconnectedSeatsLocked(clockMillis.getAsLong(), events);
            UUID partyId = partyIdByPlayer.get(currentLeaderId);
            if (partyId != null && partyId.equals(partyIdByPlayer.get(newLeaderId))
                    && !disconnectedSeatsByPlayer.containsKey(newLeaderId)) {
                MutableParty party = partiesById.get(partyId);
                if (party != null && party.leaderId.equals(currentLeaderId)) {
                    RaidParty before = party.snapshot();
                    party.leaderId = newLeaderId;
                    RaidParty after = party.snapshot();
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.LEADER_TRANSFERRED, partyId, before, after, currentLeaderId, newLeaderId));
                    transferred = true;
                }
            }
        }
        dispatchEvents(events);
        return transferred;
    }

    @Override
    public boolean disband(UUID leaderId) {
        Objects.requireNonNull(leaderId, "leaderId");
        List<PartyChangeEvent> events = new ArrayList<>(2);
        boolean disbanded = false;
        synchronized (lock) {
            expireDisconnectedSeatsLocked(clockMillis.getAsLong(), events);
            UUID partyId = partyIdByPlayer.get(leaderId);
            MutableParty party = partyId == null ? null : partiesById.get(partyId);
            if (party != null
                    && party.leaderId.equals(leaderId)
                    && !lockChecker.isPartyMutationLocked(leaderId)
                    && !anyMemberLockedLocked(party)) {
                RaidParty before = party.snapshot();
                partiesById.remove(partyId);
                removeInvitesForPartyLocked(partyId);
                for (UUID memberId : party.memberIds) {
                    partyIdByPlayer.remove(memberId);
                    disconnectedSeatsByPlayer.remove(memberId);
                    chatChannelsByPlayer.remove(memberId);
                }
                events.add(new PartyChangeEvent(PartyChangeEvent.Type.DISBANDED, partyId, before, null, leaderId, null));
                disbanded = true;
            }
        }
        dispatchEvents(events);
        return disbanded;
    }

    @Override
    public boolean markDisconnected(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        List<PartyChangeEvent> events = new ArrayList<>(3);
        boolean marked = false;
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            expireDisconnectedSeatsLocked(now, events);
            UUID partyId = partyIdByPlayer.get(playerId);
            MutableParty party = partyId == null ? null : partiesById.get(partyId);
            if (party != null && !disconnectedSeatsByPlayer.containsKey(playerId)) {
                long expiresAt = disconnectGraceMillis > 0L ? now + disconnectGraceMillis : 0L;
                disconnectedSeatsByPlayer.put(playerId, new DisconnectedMemberSeat(playerId, partyId, now, expiresAt));
                RaidParty before = party.snapshot();
                if (party.leaderId.equals(playerId)) {
                    UUID onlineSuccessor = findFirstOnlineMemberLocked(party, playerId);
                    if (onlineSuccessor != null) {
                        party.leaderId = onlineSuccessor;
                        RaidParty mid = party.snapshot();
                        events.add(new PartyChangeEvent(
                                PartyChangeEvent.Type.LEADER_TRANSFERRED,
                                partyId,
                                before,
                                mid,
                                playerId,
                                onlineSuccessor
                        ));
                    }
                }
                RaidParty after = party.snapshot();
                events.add(new PartyChangeEvent(PartyChangeEvent.Type.DISCONNECTED, partyId, before, after, playerId, playerId));
                marked = true;
            }
        }
        dispatchEvents(events);
        return marked;
    }

    @Override
    public boolean reconnect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        List<PartyChangeEvent> events = new ArrayList<>(2);
        boolean reconnected = false;
        synchronized (lock) {
            expireDisconnectedSeatsLocked(clockMillis.getAsLong(), events);
            DisconnectedMemberSeat seat = disconnectedSeatsByPlayer.remove(playerId);
            if (seat != null) {
                UUID currentPartyId = partyIdByPlayer.get(playerId);
                MutableParty party = currentPartyId == null ? null : partiesById.get(currentPartyId);
                if (party != null && currentPartyId.equals(seat.partyId())) {
                    RaidParty snap = party.snapshot();
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.RECONNECTED, currentPartyId, snap, snap, playerId, playerId));
                    reconnected = true;
                }
            }
        }
        dispatchEvents(events);
        return reconnected;
    }

    @Override
    public int expireDisconnectedSeats() {
        List<PartyChangeEvent> events = new ArrayList<>();
        int expired;
        synchronized (lock) {
            expired = expireDisconnectedSeatsLocked(clockMillis.getAsLong(), events);
        }
        dispatchEvents(events);
        return expired;
    }

    @Override
    public boolean isDisconnected(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            DisconnectedMemberSeat seat = disconnectedSeatsByPlayer.get(playerId);
            return seat != null && !seat.isExpired(now) && partiesById.containsKey(seat.partyId());
        }
    }

    @Override
    public Set<UUID> disconnectedMemberIds(UUID partyId) {
        if (partyId == null) {
            return Set.of();
        }
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            MutableParty party = partiesById.get(partyId);
            if (party == null) {
                return Set.of();
            }
            List<UUID> ids = new ArrayList<>();
            for (UUID memberId : party.memberIds) {
                DisconnectedMemberSeat seat = disconnectedSeatsByPlayer.get(memberId);
                if (seat != null && !seat.isExpired(now) && partyId.equals(seat.partyId())) {
                    ids.add(memberId);
                }
            }
            return Set.copyOf(ids);
        }
    }

    @Override
    public PartyChatChannel chatChannel(UUID playerId) {
        if (playerId == null) {
            return PartyChatChannel.GLOBAL;
        }
        synchronized (lock) {
            if (!partyIdByPlayer.containsKey(playerId)) {
                return PartyChatChannel.GLOBAL;
            }
            return chatChannelsByPlayer.getOrDefault(playerId, PartyChatChannel.GLOBAL);
        }
    }

    @Override
    public void setChatChannel(UUID playerId, PartyChatChannel channel) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(channel, "channel");
        synchronized (lock) {
            if (channel == PartyChatChannel.GLOBAL || !partyIdByPlayer.containsKey(playerId)) {
                chatChannelsByPlayer.remove(playerId);
                return;
            }
            chatChannelsByPlayer.put(playerId, channel);
        }
    }

    @Override
    public void addListener(PartyChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.addIfAbsent(listener);
    }

    @Override
    public void removeListener(PartyChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private int purgeExpiredInvitesLocked(long nowMillis) {
        int removed = 0;
        var targetIt = invitesByTarget.entrySet().iterator();
        while (targetIt.hasNext()) {
            Map<UUID, PartyInvite> map = targetIt.next().getValue();
            var inviteIt = map.entrySet().iterator();
            while (inviteIt.hasNext()) {
                PartyInvite invite = inviteIt.next().getValue();
                if (invite.isExpired(nowMillis) || !partiesById.containsKey(invite.partyId())) {
                    inviteIt.remove();
                    removed++;
                }
            }
            if (map.isEmpty()) {
                targetIt.remove();
            }
        }
        return removed;
    }

    private int expireDisconnectedSeatsLocked(long nowMillis, List<PartyChangeEvent> events) {
        List<DisconnectedMemberSeat> expired = new ArrayList<>();
        for (DisconnectedMemberSeat seat : disconnectedSeatsByPlayer.values()) {
            if (seat.isExpired(nowMillis)) {
                expired.add(seat);
            }
        }
        if (expired.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (DisconnectedMemberSeat seat : expired) {
            disconnectedSeatsByPlayer.remove(seat.playerId());
            UUID partyId = partyIdByPlayer.remove(seat.playerId());
            chatChannelsByPlayer.remove(seat.playerId());
            count++;
            if (partyId == null) {
                continue;
            }
            MutableParty party = partiesById.get(partyId);
            if (party == null) {
                continue;
            }
            RaidParty before = party.snapshot();
            party.memberIds.remove(seat.playerId());
            if (party.memberIds.isEmpty()) {
                partiesById.remove(partyId);
                removeInvitesForPartyLocked(partyId);
                events.add(new PartyChangeEvent(PartyChangeEvent.Type.SEAT_EXPIRED, partyId, before, null, seat.playerId(), seat.playerId()));
                events.add(new PartyChangeEvent(PartyChangeEvent.Type.DISBANDED, partyId, before, null, seat.playerId(), null));
            } else {
                UUID newLeader = party.leaderId;
                boolean transferred = false;
                if (party.leaderId.equals(seat.playerId())) {
                    newLeader = selectNextLeaderLocked(party);
                    party.leaderId = newLeader;
                    transferred = true;
                }
                RaidParty after = party.snapshot();
                events.add(new PartyChangeEvent(PartyChangeEvent.Type.SEAT_EXPIRED, partyId, before, after, seat.playerId(), seat.playerId()));
                if (transferred) {
                    events.add(new PartyChangeEvent(PartyChangeEvent.Type.LEADER_TRANSFERRED, partyId, before, after, seat.playerId(), newLeader));
                }
            }
        }
        return count;
    }

    private boolean anyMemberLockedLocked(MutableParty party) {
        for (UUID memberId : party.memberIds) {
            if (lockChecker.isPartyMutationLocked(memberId)) {
                return true;
            }
        }
        return false;
    }

    private @Nullable UUID findFirstOnlineMemberLocked(MutableParty party, UUID excludeId) {
        for (UUID memberId : party.memberIds) {
            if (!memberId.equals(excludeId) && !disconnectedSeatsByPlayer.containsKey(memberId)) {
                return memberId;
            }
        }
        return null;
    }

    private UUID selectNextLeaderLocked(MutableParty party) {
        for (UUID memberId : party.memberIds) {
            if (!disconnectedSeatsByPlayer.containsKey(memberId)) {
                return memberId;
            }
        }
        return party.memberIds.getFirst();
    }

    private void removeInvitesForPartyLocked(UUID partyId) {
        invitesByTarget.entrySet().removeIf(entry -> {
            Map<UUID, PartyInvite> invites = entry.getValue();
            invites.remove(partyId);
            return invites.isEmpty();
        });
    }

    private void dispatchEvents(List<PartyChangeEvent> events) {
        if (events.isEmpty() || listeners.isEmpty()) {
            return;
        }
        for (PartyChangeEvent event : events) {
            for (PartyChangeListener listener : listeners) {
                try {
                    listener.onPartyChanged(event);
                } catch (RuntimeException ex) {
                    LOGGER.log(Level.WARNING, "PartyChangeListener failed for event " + event.type(), ex);
                }
            }
        }
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
