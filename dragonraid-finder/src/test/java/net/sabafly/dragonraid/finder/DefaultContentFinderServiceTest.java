package net.sabafly.dragonraid.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import org.junit.jupiter.api.Test;

class DefaultContentFinderServiceTest {

    @Test
    void validatesUnlockPartyLeadershipSizeAndItemLevel() {
        DefaultContentUnlockService unlockService = new DefaultContentUnlockService();
        RaidPartyService partyService = mock(RaidPartyService.class);
        RaidItemService itemService = mock(RaidItemService.class);
        DefaultContentFinderService finder = new DefaultContentFinderService(unlockService, partyService, itemService);

        finder.registerContent(new ContentEntrySpec(
                "end_raid_alpha",
                "content.end_raid_alpha",
                "bp_alpha",
                "end_portal_touched",
                2,
                4,
                100
        ));

        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        RaidParty party = new RaidParty(UUID.randomUUID(), leader, List.of(leader, member));
        when(partyService.partyOf(leader)).thenReturn(Optional.of(party));
        when(partyService.partyOf(member)).thenReturn(Optional.of(party));
        when(itemService.averageItemLevel(leader)).thenReturn(120);
        when(itemService.averageItemLevel(member)).thenReturn(110);

        // Neither unlocked yet
        assertFalse(finder.requestEntry(leader, "end_raid_alpha"));

        // Only leader unlocked -> rejected
        unlockService.unlock(leader, "end_portal_touched");
        assertFalse(finder.requestEntry(leader, "end_raid_alpha"));

        // Non-leader cannot submit party entry
        unlockService.unlock(member, "end_portal_touched");
        assertFalse(finder.requestEntry(member, "end_raid_alpha"));

        // All gates satisfied -> accepted
        assertTrue(finder.requestEntry(leader, "end_raid_alpha"));
        assertEquals(2, unlockService.unlockedContents(leader).size() + unlockService.unlockedContents(member).size());
    }
}
