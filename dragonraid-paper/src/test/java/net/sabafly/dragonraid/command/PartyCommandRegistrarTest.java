package net.sabafly.dragonraid.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.sabafly.dragonraid.api.party.PartyChatChannel;
import net.sabafly.dragonraid.api.party.PartyHudService;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.party.DefaultRaidPartyService;
import net.sabafly.dragonraid.party.PartyChatDispatcher;
import net.sabafly.dragonraid.permission.RaidPermissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartyCommandRegistrarTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path tempDir;

    private PartyCommandHandler mockHandler;
    private CommandDispatcher<CommandSourceStack> mockDispatcher;

    @BeforeEach
    void setUp() {
        mockHandler = mock(PartyCommandHandler.class);
        PartyCommandRegistrar registrar = new PartyCommandRegistrar(mockHandler, () -> List.of("Alice", "Bob"));
        mockDispatcher = new CommandDispatcher<>();
        registrar.registerInto(mockDispatcher);
    }

    @Test
    void routesPartyAliasPcAndEnforcesPermissions() throws Exception {
        Player alice = mock(Player.class);
        when(alice.getName()).thenReturn("Alice");
        when(alice.hasPermission(RaidPermissions.PARTY)).thenReturn(true);
        CommandSourceStack source = sourceOf(alice);

        mockDispatcher.execute("party", source);
        mockDispatcher.execute("p create", source);
        mockDispatcher.execute("p invite Bob", source);
        mockDispatcher.execute("p accept Alice", source);
        mockDispatcher.execute("p accept", source);
        mockDispatcher.execute("p decline", source);
        mockDispatcher.execute("p leave", source);
        mockDispatcher.execute("p kick Bob", source);
        mockDispatcher.execute("p leader Bob", source);
        mockDispatcher.execute("p disband", source);
        mockDispatcher.execute("p chat", source);
        mockDispatcher.execute("p chat party", source);
        mockDispatcher.execute("p chat global", source);
        mockDispatcher.execute("p hud", source);
        mockDispatcher.execute("p hud on", source);
        mockDispatcher.execute("p hud off", source);
        mockDispatcher.execute("pc hello party world!", source);

        verify(mockHandler).showPartyInfo(alice);
        verify(mockHandler).createParty(alice);
        verify(mockHandler).invitePlayer(alice, "Bob");
        verify(mockHandler).acceptInvite(alice, "Alice");
        verify(mockHandler).acceptInvite(alice, null);
        verify(mockHandler).declineInvite(alice, null);
        verify(mockHandler).leaveParty(alice);
        verify(mockHandler).kickPlayer(alice, "Bob");
        verify(mockHandler).transferLeadership(alice, "Bob");
        verify(mockHandler).disbandParty(alice);
        verify(mockHandler).toggleChatChannel(alice);
        verify(mockHandler).setChatChannel(alice, "party");
        verify(mockHandler).setChatChannel(alice, "global");
        verify(mockHandler).toggleHud(alice);
        verify(mockHandler).setHudEnabled(alice, true);
        verify(mockHandler).setHudEnabled(alice, false);
        verify(mockHandler).sendPartyChat(alice, "hello party world!");

        Player unauthorized = mock(Player.class);
        assertThrows(CommandSyntaxException.class, () -> mockDispatcher.execute("p create", sourceOf(unauthorized)));
        verify(mockHandler, never()).createParty(unauthorized);
    }

    @Test
    void inviteWhenSoloAutoCreatesPartyAndDutyLockRejectsMutations() throws Exception {
        ConfigManager cm = new ConfigManager(tempDir, name -> PartyCommandRegistrarTest.class.getClassLoader().getResourceAsStream(name));
        cm.initialize();
        MessageService msg = new MessageService(cm);
        msg.reload();

        Set<UUID> locked = new HashSet<>();
        DefaultRaidPartyService partyService = new DefaultRaidPartyService(
                8, 60_000L, 120_000L, locked::contains, System::currentTimeMillis);
        PartyHudService hudService = mock(PartyHudService.class);

        Map<UUID, Player> byId = new ConcurrentHashMap<>();
        Map<String, Player> byName = new ConcurrentHashMap<>();
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        Player alice = realPlayer(aliceId, "Alice");
        Player bob = realPlayer(bobId, "Bob");
        byId.put(aliceId, alice);
        byId.put(bobId, bob);
        byName.put("Alice", alice);
        byName.put("Bob", bob);

        PartyChatDispatcher chatDispatcher = new PartyChatDispatcher(partyService, msg, byId::get);
        DefaultPartyCommandHandler handler = new DefaultPartyCommandHandler(
                partyService,
                hudService,
                chatDispatcher,
                msg,
                n -> Optional.ofNullable(byName.get(n)),
                id -> Optional.ofNullable(byId.get(id))
        );
        CommandDispatcher<CommandSourceStack> realDispatcher = new CommandDispatcher<>();
        new PartyCommandRegistrar(handler, () -> List.of("Alice", "Bob")).registerInto(realDispatcher);

        // Auto-create party on `/p invite Bob` while Alice is solo
        realDispatcher.execute("p invite Bob", sourceOf(alice));
        RaidParty created = partyService.partyOf(aliceId).orElseThrow();
        assertEquals(aliceId, created.leaderId());
        assertEquals(1, partyService.pendingInvites(bobId).size());

        // Accept and toggle chat & send /pc
        realDispatcher.execute("p accept", sourceOf(bob));
        assertEquals(2, partyService.partyOf(aliceId).orElseThrow().size());
        realDispatcher.execute("p chat party", sourceOf(alice));
        assertEquals(PartyChatChannel.PARTY, partyService.chatChannel(aliceId));
        realDispatcher.execute("pc ready up", sourceOf(bob));
        org.mockito.ArgumentCaptor<Component> aliceCaptor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(alice, org.mockito.Mockito.atLeastOnce()).sendMessage(aliceCaptor.capture());
        assertTrue(aliceCaptor.getAllValues().stream().map(PLAIN::serialize)
                .anyMatch(s -> s.contains("[PT]") && s.contains("ready up")));

        // Lock inside active duty -> /p leave rejected with guidance message
        locked.add(bobId);
        realDispatcher.execute("p leave", sourceOf(bob));
        assertTrue(partyService.partyOf(bobId).isPresent());
        org.mockito.ArgumentCaptor<Component> captor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(bob, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().map(PLAIN::serialize).anyMatch(s -> s.contains("/cf leave")));

        locked.remove(bobId);
        realDispatcher.execute("p leave", sourceOf(bob));
        assertFalse(partyService.partyOf(bobId).isPresent());
    }

    private static CommandSourceStack sourceOf(CommandSender sender) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    private static Player realPlayer(UUID id, String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        when(p.locale()).thenReturn(Locale.US);
        when(p.hasPermission(RaidPermissions.PARTY)).thenReturn(true);
        return p;
    }
}
