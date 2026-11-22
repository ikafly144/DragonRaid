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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.sabafly.dragonraid.api.finder.ContentEligibilityResult;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockPolicy;
import net.sabafly.dragonraid.api.finder.DutyEnvironmentChecker;
import net.sabafly.dragonraid.api.finder.EligibilityFailureReason;
import net.sabafly.dragonraid.api.finder.ReadyCheckListener;
import net.sabafly.dragonraid.api.finder.ReadyCheckResponse;
import net.sabafly.dragonraid.api.finder.ReadyCheckSession;
import net.sabafly.dragonraid.api.finder.ReadyCheckStatus;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.party.PartyChangeEvent;
import net.sabafly.dragonraid.api.party.PartyChangeListener;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.api.service.ReadOnlyRaidServiceRegistry;
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

        assertFalse(finder.requestEntry(leader, "end_raid_alpha"));

        unlockService.unlock(leader, "end_portal_touched");
        assertFalse(finder.requestEntry(leader, "end_raid_alpha"));

        unlockService.unlock(member, "end_portal_touched");
        assertFalse(finder.requestEntry(member, "end_raid_alpha"));

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

        assertThrows(UnsupportedOperationException.class, () -> unlockService.unlock(alice, "Alice", "pack_hard"));
        assertThrows(UnsupportedOperationException.class, () -> unlockService.revoke(alice, "pack_normal"));

        assertTrue(unlockService.unlockAsync(alice, "Alice", "pack_hard").join());
        assertFalse(unlockService.unlockAsync(alice, "Alice", "pack_hard").join());
        verify(repo).grantContentUnlock(eq(alice), eq("Alice"), eq("pack_hard"), anyLong());

        assertTrue(unlockService.revokeAsync(alice, "pack_normal").join());
        assertFalse(unlockService.isUnlocked(alice, "pack_normal"));
        verify(repo).revokeContentUnlock(alice, "pack_normal");

        DefaultContentFinderService finder = new DefaultContentFinderService(unlockService);
        finder.registerContent(new ContentEntrySpec("static_trial", "t", "bp_static", "", 1, 4, 0));
        finder.replacePackContents(List.of(
                new ContentEntrySpec("pack_v1", "p1", "bp1", "pack_hard", 1, 8, 0)
        ));
        assertTrue(finder.getContent("static_trial").isPresent());
        assertTrue(finder.getContent("pack_v1").isPresent());

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
        assertThrows(Exception.class, () -> service.unlockAsync(alice, "Alice", "raid_hard").join());
        assertFalse(service.isUnlocked(alice, "raid_hard"));
        assertTrue(service.unlockAsync(alice, "Alice", "raid_hard").join());
        assertTrue(service.isUnlocked(alice, "raid_hard"));
        verify(repo, times(2)).grantContentUnlock(eq(alice), eq("Alice"), eq("raid_hard"), anyLong());

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

        finder.replacePackContents(List.of(
                new ContentEntrySpec("raid", "t2", "bp", "unlock_req", 1, 8, 0)
        ));
        assertFalse(finder.requestEntry(player, "raid"));
        assertEquals("t2", finder.getContent("raid").orElseThrow().displayNameKey());
    }

    @Test
    void evaluatesLeaderOnlyUnlockPolicyMultiIssuesAndBusySlot() {
        DefaultContentUnlockService unlock = new DefaultContentUnlockService();
        InMemoryPartyStub partyService = new InMemoryPartyStub();
        RaidItemService itemService = mock(RaidItemService.class);
        AtomicBoolean slotBusy = new AtomicBoolean(true);
        Set<UUID> online = new HashSet<>();

        DutyEnvironmentChecker env = new DutyEnvironmentChecker() {
            @Override
            public boolean isPlayerOnline(UUID playerId) {
                return online.contains(playerId);
            }

            @Override
            public boolean isPlayerInEncounter(UUID playerId) {
                return false;
            }

            @Override
            public boolean isEncounterSlotBusy() {
                return slotBusy.get();
            }

            @Override
            public boolean hasBlueprint(String blueprintId) {
                return !"missing_bp".equals(blueprintId);
            }
        };

        DefaultContentFinderService finder = new DefaultContentFinderService(
                unlock, partyService, itemService, env, System::currentTimeMillis, 30_000L, false);

        finder.registerContent(new ContentEntrySpec(
                "leader_key_raid",
                "raid.name",
                "missing_bp",
                "key_normal",
                2,
                4,
                150,
                List.of(),
                ContentUnlockPolicy.LEADER_ONLY,
                true,
                "raid.desc",
                "DRAGON_EGG",
                "raid"
        ));

        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        online.add(leader);
        RaidParty party = partyService.createParty(leader);
        assertTrue(partyService.invite(leader, member));
        assertTrue(partyService.acceptInvite(member, party.partyId()));
        unlock.unlock(leader, "key_normal");
        when(itemService.averageItemLevel(leader)).thenReturn(160);
        when(itemService.averageItemLevel(member)).thenReturn(120);

        ContentEligibilityResult result = finder.evaluateEligibility(leader, "leader_key_raid");
        assertFalse(result.eligible());
        Set<EligibilityFailureReason> reasons = new HashSet<>();
        result.issues().forEach(i -> reasons.add(i.reason()));
        assertTrue(reasons.contains(EligibilityFailureReason.BLUEPRINT_MISSING));
        assertTrue(reasons.contains(EligibilityFailureReason.ENCOUNTER_BUSY));
        assertTrue(reasons.contains(EligibilityFailureReason.MEMBER_OFFLINE));
        assertTrue(reasons.contains(EligibilityFailureReason.MEMBER_LOW_ITEM_LEVEL));
        assertFalse(reasons.contains(EligibilityFailureReason.MEMBER_MISSING_UNLOCK));
        assertTrue(result.content().hiddenUntilUnlocked());

        // Fix everything and verify LEADER_ONLY unlocks allow entry even without member unlock
        online.add(member);
        slotBusy.set(false);
        when(itemService.averageItemLevel(member)).thenReturn(150);
        finder.registerContent(new ContentEntrySpec(
                "leader_key_raid",
                "raid.name",
                "valid_bp",
                "key_normal",
                2,
                4,
                150,
                List.of(),
                ContentUnlockPolicy.LEADER_ONLY,
                true,
                "raid.desc",
                "DRAGON_EGG",
                "raid"
        ));
        assertTrue(finder.evaluateEligibility(leader, "leader_key_raid").eligible());
    }

    @Test
    void runsReadyCheckStateMachineSoloAndPartyAcceptDeclineTimeoutAndPartyChangeCancel() {
        DefaultContentUnlockService unlock = new DefaultContentUnlockService();
        InMemoryPartyStub partyService = new InMemoryPartyStub();
        AtomicLong clock = new AtomicLong(5_000L);
        AtomicBoolean slotBusy = new AtomicBoolean(false);

        DutyEnvironmentChecker env = new DutyEnvironmentChecker() {
            @Override
            public boolean isPlayerOnline(UUID playerId) {
                return true;
            }

            @Override
            public boolean isPlayerInEncounter(UUID playerId) {
                return false;
            }

            @Override
            public boolean isEncounterSlotBusy() {
                return slotBusy.get();
            }

            @Override
            public boolean hasBlueprint(String blueprintId) {
                return true;
            }
        };

        // 1) Solo default (skipReadyCheckForSolo = false): requires player accept
        DefaultContentFinderService finder = new DefaultContentFinderService(
                unlock, partyService, null, env, clock::get, 30_000L, false);
        partyService.addListener(finder);
        finder.registerContent(new ContentEntrySpec("solo_trial", "t", "bp", "", 1, 8, 0));

        List<String> events = new ArrayList<>();
        finder.addReadyCheckListener(new ReadyCheckListener() {
            @Override
            public void onSessionStarted(ReadyCheckSession session) {
                events.add("STARTED:" + session.contentId());
            }

            @Override
            public void onResponseUpdated(ReadyCheckSession session, UUID responderId, ReadyCheckResponse response) {
                events.add("UPDATED:" + responderId + ":" + response);
            }

            @Override
            public void onSessionCommenced(ReadyCheckSession session) {
                events.add("COMMENCED:" + session.contentId());
            }

            @Override
            public void onSessionEnded(ReadyCheckSession session, UUID actorId, String reason) {
                events.add("ENDED:" + session.status() + ":" + reason);
            }
        });

        UUID solo = UUID.randomUUID();
        ReadyCheckSession soloSession = finder.startReadyCheck(solo, "solo_trial").orElseThrow();
        assertEquals(ReadyCheckStatus.PENDING, soloSession.status());
        assertEquals(ReadyCheckResponse.PENDING, soloSession.responses().get(solo));
        assertTrue(finder.respondReadyCheck(solo, true));
        assertTrue(finder.activeReadyCheck(solo).isEmpty());
        assertTrue(events.contains("COMMENCED:solo_trial"));

        // 2) Solo immediate skip when skipReadyCheckForSolo = true
        DefaultContentFinderService skipFinder = new DefaultContentFinderService(
                unlock, partyService, null, env, clock::get, 30_000L, true);
        skipFinder.registerContent(new ContentEntrySpec("solo_trial", "t", "bp", "", 1, 8, 0));
        List<ReadyCheckSession> skippedSessions = new ArrayList<>();
        skipFinder.addReadyCheckListener(new ReadyCheckListener() {
            @Override
            public void onSessionCommenced(ReadyCheckSession session) {
                skippedSessions.add(session);
            }
        });
        assertEquals(ReadyCheckStatus.COMMENCED, skipFinder.startReadyCheck(solo, "solo_trial").orElseThrow().status());
        assertEquals(1, skippedSessions.size());

        // 3) Party: leader must also accept; decline ends; timeout expires; party disconnect cancels
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        RaidParty party = partyService.createParty(leader);
        assertTrue(partyService.invite(leader, member));
        assertTrue(partyService.acceptInvite(member, party.partyId()));

        events.clear();
        ReadyCheckSession partySession = finder.startReadyCheck(leader, "solo_trial").orElseThrow();
        assertEquals(ReadyCheckResponse.PENDING, partySession.responses().get(leader));
        assertEquals(ReadyCheckResponse.PENDING, partySession.responses().get(member));

        // Duplicate start rejected while ready check active
        assertTrue(finder.startReadyCheck(leader, "solo_trial").isEmpty());

        assertTrue(finder.respondReadyCheck(member, true));
        assertTrue(finder.activeReadyCheck(leader).isPresent());
        // If encounter slot becomes busy right before final accept -> TOCTOU guard cancels instead of commencing
        slotBusy.set(true);
        assertTrue(finder.respondReadyCheck(leader, true));
        assertTrue(events.contains("ENDED:CANCELLED:eligibility_changed"));

        // Timeout check
        slotBusy.set(false);
        events.clear();
        assertTrue(finder.startReadyCheck(leader, "solo_trial").isPresent());
        clock.addAndGet(30_001L);
        finder.tickReadyChecks();
        assertTrue(finder.activeReadyCheck(leader).isEmpty());
        assertTrue(events.contains("ENDED:TIMED_OUT:timed_out"));

        // Member disconnect cancels active ready check via PartyChangeListener
        events.clear();
        assertTrue(finder.startReadyCheck(leader, "solo_trial").isPresent());
        assertTrue(partyService.markDisconnected(member));
        assertTrue(finder.activeReadyCheck(leader).isEmpty());
        assertTrue(events.stream().anyMatch(s -> s.startsWith("ENDED:CANCELLED:party_changed:")));
    }

    @Test
    void leaderOnlyUnlockEvaluatesLeaderWhenMemberChecksAndLazyTimeoutNotifiesListenersAndReadOnlyBlocksMutations() {
        DefaultContentUnlockService unlock = new DefaultContentUnlockService();
        InMemoryPartyStub partyService = new InMemoryPartyStub();
        AtomicLong clock = new AtomicLong(1_000L);
        DutyEnvironmentChecker env = new DutyEnvironmentChecker() {
            @Override
            public boolean isPlayerOnline(UUID playerId) {
                return true;
            }

            @Override
            public boolean isPlayerInEncounter(UUID playerId) {
                return false;
            }

            @Override
            public boolean isEncounterSlotBusy() {
                return false;
            }

            @Override
            public boolean hasBlueprint(String blueprintId) {
                return true;
            }
        };
        DefaultContentFinderService finder = new DefaultContentFinderService(
                unlock, partyService, null, env, clock::get, 10_000L, false);
        finder.registerContent(new ContentEntrySpec(
                "leader_only_savage", "savage", "bp", "key_savage", 1, 8, 0,
                List.of(), ContentUnlockPolicy.LEADER_ONLY, false, "", "DRAGON_EGG", "raid"));

        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        unlock.unlock(leader, "key_savage");
        RaidParty party = partyService.createParty(leader);
        assertTrue(partyService.invite(leader, member));
        assertTrue(partyService.acceptInvite(member, party.partyId()));

        // Member lacks leadership, NOT missing-unlock (because leader holds key_savage)
        ContentEligibilityResult memberEval = finder.evaluateEligibility(member, "leader_only_savage");
        assertFalse(memberEval.eligible());
        Set<EligibilityFailureReason> reasons = new HashSet<>();
        memberEval.issues().forEach(i -> reasons.add(i.reason()));
        assertTrue(reasons.contains(EligibilityFailureReason.NOT_PARTY_LEADER));
        assertFalse(reasons.contains(EligibilityFailureReason.MEMBER_MISSING_UNLOCK));

        // Lazy timeout dispatch via respondReadyCheck & activeReadyCheck without calling tickReadyChecks()
        List<String> endedEvents = new ArrayList<>();
        finder.addReadyCheckListener(new ReadyCheckListener() {
            @Override
            public void onSessionEnded(ReadyCheckSession session, UUID actorId, String reason) {
                endedEvents.add(session.status() + ":" + reason);
            }
        });
        assertTrue(finder.startReadyCheck(leader, "leader_only_savage").isPresent());
        clock.addAndGet(10_001L);
        assertFalse(finder.respondReadyCheck(leader, true));
        assertEquals(List.of("TIMED_OUT:timed_out"), endedEvents);

        endedEvents.clear();
        assertTrue(finder.startReadyCheck(leader, "leader_only_savage").isPresent());
        clock.addAndGet(10_001L);
        assertTrue(finder.activeReadyCheck(leader).isEmpty());
        assertEquals(List.of("TIMED_OUT:timed_out"), endedEvents);

        // ReadOnlyRaidServiceRegistry allows read-only queries and blocks all ReadyCheck mutations
        RaidServiceRegistry rawRegistry = new RaidServiceRegistry() {
            @Override
            public <T> void register(Class<T> serviceType, T implementation) {
            }

            @Override
            public <T> Optional<T> get(Class<T> serviceType) {
                return serviceType == ContentFinderService.class
                        ? Optional.of(serviceType.cast(finder))
                        : Optional.empty();
            }
        };
        RaidServiceRegistry readOnlyRegistry = ReadOnlyRaidServiceRegistry.wrap(rawRegistry);
        ContentFinderService roFinder = readOnlyRegistry.get(ContentFinderService.class).orElseThrow();
        assertTrue(roFinder.evaluateEligibility(leader, "leader_only_savage").eligible());
        assertTrue(roFinder.activeReadyCheck(leader).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> roFinder.startReadyCheck(leader, "leader_only_savage"));
        assertThrows(UnsupportedOperationException.class, () -> roFinder.respondReadyCheck(leader, true));
        assertThrows(UnsupportedOperationException.class, () -> roFinder.cancelReadyCheck(leader));
        assertThrows(UnsupportedOperationException.class, roFinder::tickReadyChecks);
        assertThrows(UnsupportedOperationException.class, () -> roFinder.addReadyCheckListener(new ReadyCheckListener() {}));
    }

    private static final class InMemoryPartyStub implements RaidPartyService {
        private final java.util.Map<UUID, RaidParty> parties = new java.util.HashMap<>();
        private final java.util.Map<UUID, UUID> byPlayer = new java.util.HashMap<>();
        private final java.util.Map<UUID, Set<UUID>> invites = new java.util.HashMap<>();
        private final Set<UUID> disconnected = new HashSet<>();
        private final List<PartyChangeListener> listeners = new ArrayList<>();

        @Override
        public RaidParty createParty(UUID leaderId) {
            RaidParty p = new RaidParty(UUID.randomUUID(), leaderId, List.of(leaderId));
            parties.put(p.partyId(), p);
            byPlayer.put(leaderId, p.partyId());
            return p;
        }

        @Override
        public Optional<RaidParty> partyOf(UUID playerId) {
            return Optional.ofNullable(byPlayer.get(playerId)).map(parties::get);
        }

        @Override
        public Optional<RaidParty> partyById(UUID partyId) {
            return Optional.ofNullable(parties.get(partyId));
        }

        @Override
        public boolean invite(UUID leaderId, UUID targetId) {
            UUID pid = byPlayer.get(leaderId);
            if (pid == null) {
                return false;
            }
            invites.computeIfAbsent(targetId, ignored -> new HashSet<>()).add(pid);
            return true;
        }

        @Override
        public boolean acceptInvite(UUID playerId, UUID partyId) {
            Set<UUID> set = invites.get(playerId);
            if (set == null || !set.remove(partyId)) {
                return false;
            }
            RaidParty old = parties.get(partyId);
            List<UUID> next = new ArrayList<>(old.memberIds());
            next.add(playerId);
            RaidParty updated = new RaidParty(partyId, old.leaderId(), next);
            parties.put(partyId, updated);
            byPlayer.put(playerId, partyId);
            return true;
        }

        @Override
        public boolean leave(UUID playerId) {
            return false;
        }

        @Override
        public boolean kick(UUID leaderId, UUID targetId) {
            return false;
        }

        @Override
        public boolean transferLeadership(UUID currentLeaderId, UUID newLeaderId) {
            return false;
        }

        @Override
        public boolean disband(UUID leaderId) {
            return false;
        }

        @Override
        public boolean markDisconnected(UUID playerId) {
            UUID pid = byPlayer.get(playerId);
            if (pid == null) {
                return false;
            }
            disconnected.add(playerId);
            RaidParty snap = parties.get(pid);
            PartyChangeEvent event = new PartyChangeEvent(
                    PartyChangeEvent.Type.DISCONNECTED, pid, snap, snap, playerId, playerId);
            listeners.forEach(l -> l.onPartyChanged(event));
            return true;
        }

        @Override
        public boolean isDisconnected(UUID playerId) {
            return disconnected.contains(playerId);
        }

        @Override
        public void addListener(PartyChangeListener listener) {
            listeners.add(listener);
        }
    }
}
