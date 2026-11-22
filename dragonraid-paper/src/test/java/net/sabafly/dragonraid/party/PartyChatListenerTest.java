package net.sabafly.dragonraid.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.sabafly.dragonraid.api.party.PartyChatChannel;
import net.sabafly.dragonraid.api.party.PartyHudService;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PartyChatListenerTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path tempDir;

    private MessageService messageService;
    private DefaultRaidPartyService partyService;
    private Map<UUID, Player> players;

    @BeforeEach
    void setUp() {
        ConfigManager cm = new ConfigManager(tempDir, name -> PartyChatListenerTest.class.getClassLoader().getResourceAsStream(name));
        cm.initialize();
        messageService = new MessageService(cm);
        messageService.reload();
        partyService = new DefaultRaidPartyService();
        players = new ConcurrentHashMap<>();
    }

    @Test
    void interceptsAsyncChatInPartyModeRoutesOnlyToOnlinePartyMembersAndRevertsWhenSolo() {
        UUID leaderId = UUID.randomUUID();
        UUID allyId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        Player leader = mockPlayer(leaderId, "Alice");
        Player ally = mockPlayer(allyId, "Bob");
        Player outsider = mockPlayer(outsiderId, "Eve");
        players.put(leaderId, leader);
        players.put(allyId, ally);
        players.put(outsiderId, outsider);

        RaidParty party = partyService.createParty(leaderId);
        assertTrue(partyService.invite(leaderId, allyId));
        assertTrue(partyService.acceptInvite(allyId, party.partyId()));

        PartyChatDispatcher dispatcher = new PartyChatDispatcher(partyService, messageService, players::get);
        PartyChatListener listener = new PartyChatListener(partyService, dispatcher);

        // GLOBAL channel -> does not cancel global chat
        AsyncChatEvent globalEvent = new AsyncChatEvent(
                true, leader, Set.of(), ChatRenderer.defaultRenderer(),
                Component.text("hello global"), Component.text("hello global"), mock(SignedMessage.class));
        listener.onAsyncChat(globalEvent);
        assertFalse(globalEvent.isCancelled());
        verify(ally, never()).sendMessage(any(Component.class));

        // Switch leader to PARTY channel -> cancels global chat and delivers only to party members
        partyService.setChatChannel(leaderId, PartyChatChannel.PARTY);
        AsyncChatEvent partyEvent = new AsyncChatEvent(
                true, leader, Set.of(), ChatRenderer.defaultRenderer(),
                Component.text("gather mid!"), Component.text("gather mid!"), mock(SignedMessage.class));
        listener.onAsyncChat(partyEvent);
        assertTrue(partyEvent.isCancelled());

        ArgumentCaptor<Component> msgCaptor = ArgumentCaptor.forClass(Component.class);
        verify(ally).sendMessage(msgCaptor.capture());
        String rendered = PLAIN.serialize(msgCaptor.getValue());
        assertTrue(rendered.contains("[PT]") && rendered.contains("★Alice") && rendered.contains("gather mid!"));
        verify(outsider, never()).sendMessage(any(Component.class));

        // Disband resets channel cleanly back to GLOBAL
        assertTrue(partyService.disband(leaderId));
        assertEquals(PartyChatChannel.GLOBAL, partyService.chatChannel(leaderId));
    }

    @Test
    void quitTriggersImmediateLeaderFailoverNotificationAndReconnectRestoresMember() {
        UUID leaderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Player leader = mockPlayer(leaderId, "Alice");
        Player member = mockPlayer(memberId, "Bob");
        players.put(leaderId, leader);
        players.put(memberId, member);

        RaidParty party = partyService.createParty(leaderId);
        assertTrue(partyService.invite(leaderId, memberId));
        assertTrue(partyService.acceptInvite(memberId, party.partyId()));

        PartyHudService hudService = mock(PartyHudService.class);
        PartyLifecycleListener lifecycle = new PartyLifecycleListener(partyService, hudService, messageService, players::get);

        // Quit leader -> immediate handoff notification to Bob & HUD clear for Alice
        lifecycle.onPlayerQuit(new PlayerQuitEvent(leader, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        verify(hudService).clearViewer(leaderId);
        assertEquals(memberId, partyService.partyOf(memberId).orElseThrow().leaderId());

        List<String> bobMessages = new ArrayList<>();
        ArgumentCaptor<Component> bobCaptor = ArgumentCaptor.forClass(Component.class);
        verify(member, org.mockito.Mockito.atLeast(2)).sendMessage(bobCaptor.capture());
        bobCaptor.getAllValues().forEach(c -> bobMessages.add(PLAIN.serialize(c)));
        assertTrue(bobMessages.stream().anyMatch(m -> m.contains("Alice")));
        assertTrue(bobMessages.stream().anyMatch(m -> m.contains("Bob")));

        // Reconnect within grace restores Alice and notifies both
        lifecycle.onPlayerJoin(new PlayerJoinEvent(leader, Component.empty()));
        assertFalse(partyService.isDisconnected(leaderId));
        verify(hudService).refreshParty(party.partyId());
    }

    private static Player mockPlayer(UUID id, String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.locale()).thenReturn(Locale.US);
        return p;
    }
}
