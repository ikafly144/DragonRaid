package net.sabafly.dragonraid.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
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

    @Test
    void failsClosedWhenItemServiceMissingForItemLevelGatedContent() {
        DefaultContentUnlockService unlockService = new DefaultContentUnlockService();
        DefaultContentFinderService finder = new DefaultContentFinderService(unlockService);
        finder.registerContent(new ContentEntrySpec(
                "ilvl_gated",
                "content.ilvl_gated",
                "bp_gated",
                "",
                1,
                4,
                100
        ));
        finder.registerContent(new ContentEntrySpec(
                "ungated",
                "content.ungated",
                "bp_ungated",
                "",
                1,
                4,
                0
        ));

        UUID solo = UUID.randomUUID();
        assertFalse(finder.requestEntry(solo, "ilvl_gated"));
        assertTrue(finder.requestEntry(solo, "ungated"));
    }

    @Test
    void persistsUnlocksViaRepositoryAndReplacesPackContentsCleanly() {
        PlayerProgressionRepository repo = mock(PlayerProgressionRepository.class);
        UUID alice = UUID.randomUUID();
        when(repo.loadUnlockedContents(alice)).thenReturn(CompletableFuture.completedFuture(Set.of("pack_normal")));
        when(repo.grantContentUnlock(eq(alice), eq("Alice"), eq("pack_hard"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(repo.revokeContentUnlock(alice, "pack_normal"))
                .thenReturn(CompletableFuture.completedFuture(true));

        DefaultContentUnlockService unlockService = new DefaultContentUnlockService(repo);
        unlockService.loadPlayer(alice).join();
        assertTrue(unlockService.isUnlocked(alice, "pack_normal"));

        assertTrue(unlockService.unlock(alice, "Alice", "pack_hard"));
        assertFalse(unlockService.unlock(alice, "Alice", "pack_hard"));
        verify(repo).grantContentUnlock(eq(alice), eq("Alice"), eq("pack_hard"), anyLong());

        assertTrue(unlockService.revoke(alice, "pack_normal"));
        assertFalse(unlockService.isUnlocked(alice, "pack_normal"));
        verify(repo).revokeContentUnlock(alice, "pack_normal");

        DefaultContentFinderService finder = new DefaultContentFinderService(unlockService);
        finder.registerContent(new ContentEntrySpec("static_trial", "t", "bp_static", "", 1, 4, 0));
        finder.replacePackContents(List.of(
                new ContentEntrySpec("pack_v1", "p1", "bp1", "pack_hard", 1, 8, 0)
        ));
        assertTrue(finder.getContent("static_trial").isPresent());
        assertTrue(finder.getContent("pack_v1").isPresent());

        // Hot-reload replaces only pack entries while keeping static registrations intact
        finder.replacePackContents(List.of(
                new ContentEntrySpec("pack_v2", "p2", "bp2", "pack_hard", 1, 8, 0)
        ));
        assertTrue(finder.getContent("static_trial").isPresent());
        assertTrue(finder.getContent("pack_v1").isEmpty());
        assertTrue(finder.getContent("pack_v2").isPresent());
    }

    @Test
    void doesNotCacheFailedUnlockAndSerializesLoadAgainstRevoke() {
        PlayerProgressionRepository repo = mock(PlayerProgressionRepository.class);
        UUID alice = UUID.randomUUID();
        when(repo.grantContentUnlock(eq(alice), eq("Alice"), eq("raid_hard"), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("disk full")))
                .thenReturn(CompletableFuture.completedFuture(true));

        DefaultContentUnlockService service = new DefaultContentUnlockService(repo);
        // #26: failed DB persistence leaves cache unpopulated so retry succeeds
        assertThrows(Exception.class, () -> service.unlockAsync(alice, "Alice", "raid_hard").join());
        assertFalse(service.isUnlocked(alice, "raid_hard"));
        assertTrue(service.unlockAsync(alice, "Alice", "raid_hard").join());
        assertTrue(service.isUnlocked(alice, "raid_hard"));
        verify(repo, times(2)).grantContentUnlock(eq(alice), eq("Alice"), eq("raid_hard"), anyLong());

        // #27: loadPlayer overlaps revoke -> revoke executes sequentially AFTER loadPlayer finishes
        CompletableFuture<Set<String>> slowLoad = new CompletableFuture<>();
        when(repo.loadUnlockedContents(alice)).thenReturn(slowLoad);
        when(repo.revokeContentUnlock(alice, "raid_hard")).thenReturn(CompletableFuture.completedFuture(true));

        CompletableFuture<Set<String>> loadFuture = service.loadPlayer(alice);
        CompletableFuture<Boolean> revokeFuture = service.revokeAsync(alice, "raid_hard");
        slowLoad.complete(Set.of("raid_hard"));

        loadFuture.join();
        assertTrue(revokeFuture.join());
        assertFalse(service.isUnlocked(alice, "raid_hard"));
    }

    @Test
    void hotReloadingPackContentsNeverExposesStaticFallbackWindow() {
        DefaultContentUnlockService unlock = new DefaultContentUnlockService();
        DefaultContentFinderService finder = new DefaultContentFinderService(unlock);
        finder.registerContent(new ContentEntrySpec("raid", "t", "bp", "", 1, 8, 0));
        finder.replacePackContents(List.of(
                new ContentEntrySpec("raid", "t", "bp", "unlock_req", 1, 8, 0)
        ));
        UUID player = UUID.randomUUID();
        assertFalse(finder.requestEntry(player, "raid"));

        // Repeated reloads with the same ID atomically swap without an ungated gap
        finder.replacePackContents(List.of(
                new ContentEntrySpec("raid", "t2", "bp", "unlock_req", 1, 8, 0)
        ));
        assertFalse(finder.requestEntry(player, "raid"));
        assertEquals("t2", finder.getContent("raid").orElseThrow().displayNameKey());
    }
}
