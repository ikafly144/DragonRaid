package net.sabafly.dragonraid.party.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.sabafly.dragonraid.api.party.PartyCombatStatusProvider;
import net.sabafly.dragonraid.api.party.PartyMemberCombatState;
import net.sabafly.dragonraid.api.party.PartyRoleProvider;
import net.sabafly.dragonraid.api.party.PartyRoleTag;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.party.DefaultRaidPartyService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PacketEventsPartyHudServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final class RecordingTransport implements PacketEventsPartyHudService.ScoreboardPacketTransport {
        final List<String> ops = new ArrayList<>();
        final AtomicBoolean failAll = new AtomicBoolean(false);
        final AtomicBoolean throwOnUpdateLine = new AtomicBoolean(false);

        @Override
        public boolean createObjective(Player viewer, String objectiveName, Component displayName) {
            if (failAll.get()) {
                return false;
            }
            ops.add(viewer.getName() + ":CREATE:" + PLAIN.serialize(displayName));
            return true;
        }

        @Override
        public boolean updateObjective(Player viewer, String objectiveName, Component displayName) {
            if (failAll.get()) {
                return false;
            }
            ops.add(viewer.getName() + ":UPDATE_OBJ:" + PLAIN.serialize(displayName));
            return true;
        }

        @Override
        public boolean removeObjective(Player viewer, String objectiveName) {
            ops.add(viewer.getName() + ":REMOVE");
            return !failAll.get();
        }

        @Override
        public boolean displaySidebar(Player viewer, int position, String objectiveName) {
            if (failAll.get()) {
                return false;
            }
            ops.add(viewer.getName() + ":DISPLAY:" + position);
            return true;
        }

        @Override
        public boolean updateLine(Player viewer, String objectiveName, String lineKey, int score, Component displayName) {
            if (failAll.get()) {
                return false;
            }
            if (throwOnUpdateLine.get()) {
                throw new IllegalStateException("packet send failed");
            }
            ops.add(viewer.getName() + ":LINE:" + lineKey + "=" + PLAIN.serialize(displayName));
            return true;
        }

        @Override
        public boolean resetLine(Player viewer, String objectiveName, String lineKey) {
            if (failAll.get()) {
                return false;
            }
            ops.add(viewer.getName() + ":RESET:" + lineKey);
            return true;
        }
    }

    @Test
    void sendsMinimalDeltaPacketsRespectsRoleProviderAndCleansUpOnToggleOrDisband() {
        DefaultRaidPartyService partyService = new DefaultRaidPartyService();
        RecordingTransport transport = new RecordingTransport();

        Map<UUID, Player> online = new ConcurrentHashMap<>();
        RaidScheduler scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        UUID leaderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Player leader = mockPlayer(leaderId, "LeaderOne", 20.0, 20.0, false);
        Player member = mockPlayer(memberId, "ShieldTwo", 16.0, 20.0, true);
        online.put(leaderId, leader);
        online.put(memberId, member);

        Map<UUID, PartyMemberCombatState> states = new ConcurrentHashMap<>();
        states.put(leaderId, PartyMemberCombatState.ALIVE);
        states.put(memberId, PartyMemberCombatState.ALIVE);
        PartyCombatStatusProvider combatProvider = new PartyCombatStatusProvider() {
            @Override
            public boolean isInActiveEncounter(UUID playerId) {
                return true;
            }

            @Override
            public PartyMemberCombatState combatState(UUID playerId, boolean isOnline) {
                return isOnline ? states.getOrDefault(playerId, PartyMemberCombatState.OUT_OF_COMBAT) : PartyMemberCombatState.OFFLINE;
            }

            @Override
            public int remainingRevives(UUID playerId) {
                return 3;
            }
        };

        PartyRoleProvider roleProvider = id -> id.equals(memberId)
                ? Optional.of(new PartyRoleTag("tank", Component.text("[TNK]")))
                : Optional.empty();

        PacketEventsPartyHudService hud = new PacketEventsPartyHudService(
                partyService,
                combatProvider,
                roleProvider,
                scheduler,
                online::get,
                transport,
                Logger.getLogger("test"),
                true,
                8
        );
        partyService.addListener(hud);

        RaidParty party = partyService.createParty(leaderId);
        assertTrue(partyService.invite(leaderId, memberId));
        assertTrue(partyService.acceptInvite(memberId, party.partyId()));

        // Initial render occurred on JOINED; verify leader star, role tag, shield icon, and revives footer
        assertTrue(transport.ops.stream().anyMatch(s -> s.startsWith("LeaderOne:CREATE:")));
        assertTrue(transport.ops.stream().anyMatch(s -> s.startsWith("LeaderOne:DISPLAY:1")));
        assertTrue(transport.ops.stream().anyMatch(s -> s.contains("★LeaderOne")));
        assertTrue(transport.ops.stream().anyMatch(s -> s.contains("[TNK] •ShieldTwo") && s.contains("🛡")));
        assertTrue(transport.ops.stream().anyMatch(s -> s.contains("蘇生残: 3")));

        // Zero packets when nothing changed on tickAll
        transport.ops.clear();
        hud.tickAll();
        assertTrue(transport.ops.isEmpty(), "Expected 0 delta packets when state unchanged, got: " + transport.ops);

        // Downed member triggers only changed row updates
        states.put(memberId, PartyMemberCombatState.DOWNED);
        hud.tickAll();
        assertTrue(transport.ops.stream().anyMatch(s -> s.contains("☠DOWN")));
        assertFalse(transport.ops.stream().anyMatch(s -> s.contains("CREATE")));
        assertFalse(transport.ops.stream().anyMatch(s -> s.contains("UPDATE_OBJ")));

        // Disabling HUD removes objective for that viewer only
        transport.ops.clear();
        hud.setHudEnabled(memberId, false);
        assertFalse(hud.isHudEnabled(memberId));
        assertTrue(transport.ops.contains("ShieldTwo:REMOVE"));
        assertFalse(transport.ops.contains("LeaderOne:REMOVE"));

        // Removing member resets shrunken line for leader
        transport.ops.clear();
        assertTrue(partyService.kick(leaderId, memberId));
        assertTrue(transport.ops.stream().anyMatch(s -> s.startsWith("LeaderOne:RESET:")));

        // Disband removes objective for remaining leader
        transport.ops.clear();
        assertTrue(partyService.disband(leaderId));
        assertTrue(transport.ops.contains("LeaderOne:REMOVE"));
    }

    @Test
    void invalidatesCacheOnSendFailureAndRebuildsWhenTransportRecovers() {
        DefaultRaidPartyService partyService = new DefaultRaidPartyService();
        RecordingTransport transport = new RecordingTransport();
        transport.failAll.set(true);

        Map<UUID, Player> online = new ConcurrentHashMap<>();
        RaidScheduler scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        UUID leaderId = UUID.randomUUID();
        Player leader = mockPlayer(leaderId, "LeaderFail", 20.0, 20.0, false);
        online.put(leaderId, leader);

        PacketEventsPartyHudService hud = new PacketEventsPartyHudService(
                partyService,
                PartyCombatStatusProvider.NONE,
                id -> Optional.empty(),
                scheduler,
                online::get,
                transport,
                Logger.getLogger("test"),
                true,
                8
        );
        partyService.addListener(hud);

        RaidParty party = partyService.createParty(leaderId);
        // Initial send failed -> state must not be cached; next tick with identical content must rebuild CREATE
        assertFalse(transport.ops.stream().anyMatch(s -> s.startsWith("LeaderFail:CREATE:")));
        transport.ops.clear();
        transport.failAll.set(false);
        hud.refreshParty(party.partyId());
        assertTrue(transport.ops.stream().anyMatch(s -> s.startsWith("LeaderFail:CREATE:")));
        assertTrue(transport.ops.stream().anyMatch(s -> s.contains("★LeaderFail")));

        // Partial line exception invalidates objective & rebuilds on next refresh
        transport.ops.clear();
        transport.throwOnUpdateLine.set(true);
        when(leader.getHealth()).thenReturn(10.0);
        hud.refreshParty(party.partyId());
        assertTrue(transport.ops.contains("LeaderFail:REMOVE"));

        transport.ops.clear();
        transport.throwOnUpdateLine.set(false);
        hud.refreshParty(party.partyId());
        assertTrue(transport.ops.stream().anyMatch(s -> s.startsWith("LeaderFail:CREATE:")));
        assertTrue(transport.ops.stream().anyMatch(s -> s.contains("10/20")));
    }

    @Test
    void retainsPendingRemovalAfterRemoveFailureUntilTransportRecoversEvenWhenHudDisabled() {
        DefaultRaidPartyService partyService = new DefaultRaidPartyService();
        RecordingTransport transport = new RecordingTransport();

        Map<UUID, Player> online = new ConcurrentHashMap<>();
        RaidScheduler scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        UUID leaderId = UUID.randomUUID();
        Player leader = mockPlayer(leaderId, "LeaderRetry", 20.0, 20.0, false);
        online.put(leaderId, leader);

        PacketEventsPartyHudService hud = new PacketEventsPartyHudService(
                partyService,
                PartyCombatStatusProvider.NONE,
                id -> Optional.empty(),
                scheduler,
                online::get,
                transport,
                Logger.getLogger("test"),
                true,
                8
        );
        partyService.addListener(hud);

        partyService.createParty(leaderId);
        assertTrue(transport.ops.stream().anyMatch(s -> s.startsWith("LeaderRetry:CREATE:")));

        // Update fails AND subsequent removeObjective returns false while transport is failing
        transport.ops.clear();
        transport.failAll.set(true);
        when(leader.getHealth()).thenReturn(12.0);
        hud.tickAll();
        assertTrue(transport.ops.contains("LeaderRetry:REMOVE"));

        // Player disables HUD while transport is still down -> clearViewer must keep pending removal
        transport.ops.clear();
        hud.setHudEnabled(leaderId, false);
        assertTrue(transport.ops.contains("LeaderRetry:REMOVE"));

        // Transport recovers -> tickAll retries REMOVE even though HUD is disabled and renderedStates is empty
        transport.ops.clear();
        transport.failAll.set(false);
        hud.tickAll();
        assertTrue(transport.ops.contains("LeaderRetry:REMOVE"));

        // Subsequent tickAll must NOT keep sending REMOVE once confirmed
        transport.ops.clear();
        hud.tickAll();
        assertTrue(transport.ops.isEmpty(), "Expected no redundant REMOVE after confirmation, got: " + transport.ops);

        // Re-enabling HUD after another failed remove removes stale objective before rebuilding CREATE, then stays delta-quiet
        hud.setHudEnabled(leaderId, true);
        transport.failAll.set(true);
        when(leader.getHealth()).thenReturn(8.0);
        hud.tickAll();
        transport.ops.clear();
        transport.failAll.set(false);
        hud.tickAll();
        int removeIdx = transport.ops.indexOf("LeaderRetry:REMOVE");
        int createIdx = -1;
        for (int i = 0; i < transport.ops.size(); i++) {
            if (transport.ops.get(i).startsWith("LeaderRetry:CREATE:")) {
                createIdx = i;
                break;
            }
        }
        assertTrue(removeIdx >= 0 && createIdx > removeIdx,
                "Expected REMOVE before CREATE on recovery, got: " + transport.ops);
        transport.ops.clear();
        hud.tickAll();
        assertTrue(transport.ops.isEmpty());
    }

    @SuppressWarnings("deprecation")
    private static Player mockPlayer(UUID id, String name, double hp, double maxHp, boolean blocking) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.getHealth()).thenReturn(hp);
        when(p.getMaxHealth()).thenReturn(maxHp);
        when(p.isBlocking()).thenReturn(blocking);
        return p;
    }
}
