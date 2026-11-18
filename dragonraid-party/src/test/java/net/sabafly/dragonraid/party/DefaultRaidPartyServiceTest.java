package net.sabafly.dragonraid.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.sabafly.dragonraid.api.party.RaidParty;
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

        assertTrue(service.invite(leader, memberA));
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
    }
}
