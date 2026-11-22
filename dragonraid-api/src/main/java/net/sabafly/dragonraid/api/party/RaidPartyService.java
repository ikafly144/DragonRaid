package net.sabafly.dragonraid.api.party;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Platform SPI for party lifecycle management (creation, invites, leadership, membership,
 * disconnect grace retention, chat channel routing, and disbanding).
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

    default Optional<RaidParty> partyById(UUID partyId) {
        return Optional.empty();
    }

    default int maxPartySize() {
        return 8;
    }

    default boolean declineInvite(UUID playerId, UUID partyId) {
        return false;
    }

    default List<PartyInvite> pendingInvites(UUID targetId) {
        return List.of();
    }

    default int expireInvites() {
        return 0;
    }

    default boolean markDisconnected(UUID playerId) {
        return false;
    }

    default boolean reconnect(UUID playerId) {
        return false;
    }

    default int expireDisconnectedSeats() {
        return 0;
    }

    default boolean isDisconnected(UUID playerId) {
        return false;
    }

    default Set<UUID> disconnectedMemberIds(UUID partyId) {
        return Set.of();
    }

    default boolean isMutationLocked(UUID playerId) {
        return false;
    }

    default PartyChatChannel chatChannel(UUID playerId) {
        return PartyChatChannel.GLOBAL;
    }

    default void setChatChannel(UUID playerId, PartyChatChannel channel) {
    }

    default PartyChatChannel toggleChatChannel(UUID playerId) {
        PartyChatChannel next = chatChannel(playerId) == PartyChatChannel.PARTY
                ? PartyChatChannel.GLOBAL
                : PartyChatChannel.PARTY;
        setChatChannel(playerId, next);
        return next;
    }

    default void addListener(PartyChangeListener listener) {
    }

    default void removeListener(PartyChangeListener listener) {
    }
}
