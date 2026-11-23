package net.sabafly.dragonraid.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.sabafly.dragonraid.api.party.PartyChangeEvent;
import net.sabafly.dragonraid.api.party.PartyChatChannel;
import net.sabafly.dragonraid.api.party.PartyMembershipLockChecker;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.api.service.ReadOnlyRaidServiceRegistry;
import org.junit.jupiter.api.Test;

class DefaultRaidPartyServiceTest {

    @Test
    void managesFullPartyLifecycle() {
        DefaultRaidPartyService service = new DefaultRaidPartyService();
        UUID leader = UUID.randomUUID();
        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();

        RaidParty created = service.createParty(leader);
        assertEquals(leader, created.leaderId());
        assertEquals(List.of(leader), created.memberIds());
        assertEquals(created, service.partyById(created.partyId()).orElseThrow());

        assertTrue(service.invite(leader, memberA));
        assertEquals(1, service.pendingInvites(memberA).size());
        assertTrue(service.acceptInvite(memberA, created.partyId()));
        assertEquals(2, service.partyOf(leader).orElseThrow().size());

        assertTrue(service.invite(leader, memberB));
        assertTrue(service.acceptInvite(memberB, created.partyId()));
        assertTrue(service.transferLeadership(leader, memberA));
        assertEquals(memberA, service.partyOf(leader).orElseThrow().leaderId());

        assertFalse(service.kick(leader, memberB));
        assertTrue(service.kick(memberA, memberB));
        assertTrue(service.partyOf(memberB).isEmpty());

        assertTrue(service.leave(leader));
        assertTrue(service.partyOf(leader).isEmpty());
        assertEquals(1, service.partyOf(memberA).orElseThrow().size());

        assertTrue(service.disband(memberA));
        assertTrue(service.partyOf(memberA).isEmpty());
        assertTrue(service.partyById(created.partyId()).isEmpty());
    }

    @Test
    void clearsStaleInvitesOnDisbandAndCreateParty() {
        DefaultRaidPartyService service = new DefaultRaidPartyService();
        UUID leaderA = UUID.randomUUID();
        UUID leaderB = UUID.randomUUID();

        RaidParty partyA = service.createParty(leaderA);
        assertTrue(service.invite(leaderA, leaderB));
        assertTrue(service.disband(leaderA));
        assertFalse(service.acceptInvite(leaderB, partyA.partyId()));

        RaidParty partyA2 = service.createParty(leaderA);
        assertTrue(service.invite(leaderA, leaderB));

        // Creating a party discards any earlier invites addressed to leaderB
        RaidParty partyB = service.createParty(leaderB);
        assertTrue(service.disband(leaderB));
        assertFalse(partyB.partyId().equals(partyA2.partyId()));
        assertFalse(service.acceptInvite(leaderB, partyA2.partyId()));
    }

    @Test
    void handlesInviteDeclineTimeoutAndMaxPartySize() {
        AtomicLong clock = new AtomicLong(1_000L);
        DefaultRaidPartyService service = new DefaultRaidPartyService(
                2,
                5_000L,
                120_000L,
                PartyMembershipLockChecker.UNLOCKED,
                clock::get
        );
        UUID leader = UUID.randomUUID();
        UUID target1 = UUID.randomUUID();
        UUID target2 = UUID.randomUUID();

        RaidParty party = service.createParty(leader);
        assertTrue(service.invite(leader, target1));
        assertTrue(service.declineInvite(target1, party.partyId()));
        assertTrue(service.pendingInvites(target1).isEmpty());
        assertFalse(service.acceptInvite(target1, party.partyId()));

        // Re-invite and let it expire after 5000ms
        assertTrue(service.invite(leader, target1));
        clock.addAndGet(5_001L);
        assertEquals(1, service.expireInvites());
        assertFalse(service.acceptInvite(target1, party.partyId()));

        // Fill up to maxPartySize=2, third invite is blocked
        assertTrue(service.invite(leader, target1));
        assertTrue(service.acceptInvite(target1, party.partyId()));
        assertFalse(service.invite(leader, target2));
    }

    @Test
    void transfersLeadershipImmediatelyOnLeaderDisconnectAndSupportsGraceRejoin() {
        AtomicLong clock = new AtomicLong(10_000L);
        DefaultRaidPartyService service = new DefaultRaidPartyService(
                8,
                60_000L,
                120_000L,
                PartyMembershipLockChecker.UNLOCKED,
                clock::get
        );
        List<PartyChangeEvent> events = new ArrayList<>();
        service.addListener(events::add);

        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        RaidParty party = service.createParty(leader);
        assertTrue(service.invite(leader, member));
        assertTrue(service.acceptInvite(member, party.partyId()));

        // Leader disconnects while member is online -> leadership immediately passes to member
        assertTrue(service.markDisconnected(leader));
        assertEquals(member, service.partyOf(member).orElseThrow().leaderId());
        assertTrue(service.isDisconnected(leader));
        assertEquals(Set.of(leader), service.disconnectedMemberIds(party.partyId()));
        assertTrue(events.stream().anyMatch(e -> e.type() == PartyChangeEvent.Type.LEADER_TRANSFERRED && member.equals(e.targetId())));
        assertTrue(events.stream().anyMatch(e -> e.type() == PartyChangeEvent.Type.DISCONNECTED && leader.equals(e.targetId())));

        // Within grace period (60s < 120s), former leader reconnects as a normal member
        clock.addAndGet(60_000L);
        assertTrue(service.reconnect(leader));
        assertFalse(service.isDisconnected(leader));
        assertEquals(member, service.partyOf(leader).orElseThrow().leaderId());

        // Current leader disconnects and lets grace expire -> auto-removed, leadership reverts to remaining member
        assertTrue(service.markDisconnected(member));
        assertEquals(leader, service.partyOf(leader).orElseThrow().leaderId());
        clock.addAndGet(120_001L);
        assertEquals(1, service.expireDisconnectedSeats());
        assertFalse(service.reconnect(member));
        assertTrue(service.partyOf(member).isEmpty());
        assertEquals(List.of(leader), service.partyOf(leader).orElseThrow().memberIds());

        // Remaining solo leader disconnects and expires -> party disbanded
        assertTrue(service.markDisconnected(leader));
        clock.addAndGet(120_001L);
        assertEquals(1, service.expireDisconnectedSeats());
        assertTrue(service.partyById(party.partyId()).isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.type() == PartyChangeEvent.Type.DISBANDED));
    }

    @Test
    void enforcesDutyLockOnMembershipMutationsAndResetsChatChannel() {
        Set<UUID> lockedPlayers = new HashSet<>();
        DefaultRaidPartyService service = new DefaultRaidPartyService(
                8,
                60_000L,
                120_000L,
                lockedPlayers::contains,
                System::currentTimeMillis
        );

        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        RaidParty party = service.createParty(leader);
        assertTrue(service.invite(leader, member));
        assertTrue(service.acceptInvite(member, party.partyId()));

        service.setChatChannel(member, PartyChatChannel.PARTY);
        assertEquals(PartyChatChannel.PARTY, service.chatChannel(member));
        assertEquals(PartyChatChannel.GLOBAL, service.toggleChatChannel(member));
        assertEquals(PartyChatChannel.PARTY, service.toggleChatChannel(member));

        // Lock leader & member inside active encounter/instance
        lockedPlayers.add(leader);
        lockedPlayers.add(member);
        assertFalse(service.leave(member));
        assertFalse(service.kick(leader, member));
        assertFalse(service.disband(leader));
        assertFalse(service.invite(leader, outsider));
        assertTrue(service.isMutationLocked(leader));

        lockedPlayers.add(outsider);
        assertThrows(IllegalStateException.class, () -> service.createParty(outsider));

        // After /cf leave unlocks member, leaving works and resets chat channel to GLOBAL
        lockedPlayers.remove(member);
        assertTrue(service.leave(member));
        assertEquals(PartyChatChannel.GLOBAL, service.chatChannel(member));

        // Listener invocation happens outside synchronized(lock) (calling partyOf inside listener does not deadlock)
        List<UUID> observed = new ArrayList<>();
        service.addListener(ev -> service.partyById(ev.partyId()).ifPresent(p -> observed.add(p.partyId())));
        lockedPlayers.remove(leader);
        lockedPlayers.remove(outsider);
        assertTrue(service.invite(leader, outsider));
        assertFalse(observed.isEmpty());
    }

    @Test
    void readOnlyRegistryAllowsPartyQueriesAndBlocksMutations() {
        DefaultRaidPartyService service = new DefaultRaidPartyService();
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        RaidParty created = service.createParty(leader);
        assertTrue(service.invite(leader, member));

        RaidServiceRegistry rawRegistry = new RaidServiceRegistry() {
            @Override
            public <T> void register(Class<T> serviceType, T implementation) {
            }

            @Override
            public <T> Optional<T> get(Class<T> serviceType) {
                return serviceType == RaidPartyService.class
                        ? Optional.of(serviceType.cast(service))
                        : Optional.empty();
            }
        };
        RaidPartyService roParty = ReadOnlyRaidServiceRegistry.wrap(rawRegistry)
                .get(RaidPartyService.class).orElseThrow();

        assertEquals(created, roParty.partyById(created.partyId()).orElseThrow());
        assertEquals(8, roParty.maxPartySize());
        assertEquals(1, roParty.pendingInvites(member).size());
        assertFalse(roParty.isDisconnected(leader));
        assertTrue(roParty.disconnectedMemberIds(created.partyId()).isEmpty());
        assertFalse(roParty.isMutationLocked(leader));
        assertEquals(PartyChatChannel.GLOBAL, roParty.chatChannel(leader));

        assertThrows(UnsupportedOperationException.class, () -> roParty.declineInvite(member, created.partyId()));
        assertThrows(UnsupportedOperationException.class, roParty::expireInvites);
        assertThrows(UnsupportedOperationException.class, () -> roParty.markDisconnected(leader));
        assertThrows(UnsupportedOperationException.class, () -> roParty.reconnect(leader));
        assertThrows(UnsupportedOperationException.class, roParty::expireDisconnectedSeats);
        assertThrows(UnsupportedOperationException.class, () -> roParty.setChatChannel(leader, PartyChatChannel.PARTY));
        assertThrows(UnsupportedOperationException.class, () -> roParty.toggleChatChannel(leader));
        assertThrows(UnsupportedOperationException.class, () -> roParty.addListener(ev -> {}));
    }

    @Test
    void queriesRemainSideEffectFreeCreateDispatchesPriorExpiryOnFailureAndAcceptResetsChannel() {
        AtomicLong clock = new AtomicLong(1_000L);
        DefaultRaidPartyService service = new DefaultRaidPartyService(
                8, 5_000L, 10_000L, PartyMembershipLockChecker.UNLOCKED, clock::get);
        List<PartyChangeEvent> events = new ArrayList<>();
        service.addListener(events::add);

        UUID l1 = UUID.randomUUID();
        UUID m1 = UUID.randomUUID();
        RaidParty p1 = service.createParty(l1);
        assertTrue(service.invite(l1, m1));
        assertTrue(service.acceptInvite(m1, p1.partyId()));
        service.setChatChannel(m1, PartyChatChannel.PARTY);
        assertEquals(PartyChatChannel.PARTY, service.chatChannel(m1));

        // m1 disconnects -> let seat expire -> pure queries do NOT mutate or emit events
        assertTrue(service.markDisconnected(m1));
        UUID invitee = UUID.randomUUID();
        assertTrue(service.invite(l1, invitee));
        clock.addAndGet(11_000L);
        events.clear();

        assertTrue(service.pendingInvites(invitee).isEmpty());
        assertFalse(service.isDisconnected(m1));
        assertTrue(service.disconnectedMemberIds(p1.partyId()).isEmpty());
        assertTrue(events.isEmpty(), "Read queries must not emit PartyChangeEvents: " + events);

        // createParty failing on l1 (already in p1) STILL dispatches completed SEAT_EXPIRED for m1
        assertThrows(IllegalStateException.class, () -> service.createParty(l1));
        assertTrue(events.stream().anyMatch(e -> e.type() == PartyChangeEvent.Type.SEAT_EXPIRED && m1.equals(e.targetId())));

        // Explicit expireInvites cleans expired invite
        assertEquals(1, service.expireInvites());

        // m1 joining p1 again starts in GLOBAL chat channel cleanly
        assertTrue(service.invite(l1, m1));
        assertTrue(service.acceptInvite(m1, p1.partyId()));
        assertEquals(PartyChatChannel.GLOBAL, service.chatChannel(m1));
    }
}
