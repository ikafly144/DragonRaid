package net.sabafly.dragonraid.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import net.sabafly.dragonraid.permission.RaidPermissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContentFinderCommandRegistrarTest {

    private ContentFinderCommandHandler handler;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        handler = mock(ContentFinderCommandHandler.class);
        ContentFinderCommandRegistrar registrar = new ContentFinderCommandRegistrar(
                handler,
                () -> List.of("ender_dragon_normal", "pack:savage_1")
        );
        dispatcher = new CommandDispatcher<>();
        registrar.registerInto(dispatcher);
    }

    @Test
    void routesContentFinderCommandsWithAliasesEscapedSuggestionsAndPermission() throws Exception {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Alice");
        when(player.hasPermission(RaidPermissions.FINDER)).thenReturn(true);
        CommandSourceStack source = sourceOf(player);

        dispatcher.execute("cf", source);
        dispatcher.execute("finder", source);
        dispatcher.execute("duty apply ender_dragon_normal", source);
        dispatcher.execute("cf apply \"pack:savage_1\"", source);
        dispatcher.execute("cf accept", source);
        dispatcher.execute("cf decline", source);
        dispatcher.execute("cf cancel", source);
        dispatcher.execute("cf retry", source);
        dispatcher.execute("cf leave", source);

        verify(handler, org.mockito.Mockito.times(2)).openFinder(player);
        verify(handler).applyContent(player, "ender_dragon_normal");
        verify(handler).applyContent(player, "pack:savage_1");
        verify(handler).acceptReadyCheck(player);
        verify(handler).declineReadyCheck(player);
        verify(handler).cancelReadyCheck(player);
        verify(handler).retryEncounter(player);
        verify(handler).leaveEncounter(player);

        List<String> suggestions = dispatcher
                .getCompletionSuggestions(dispatcher.parse("cf apply ", source))
                .get().getList().stream().map(Suggestion::getText).toList();
        assertTrue(suggestions.contains("ender_dragon_normal"));
        assertTrue(suggestions.contains("\"pack:savage_1\""));

        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(RaidPermissions.FINDER)).thenReturn(true);
        assertEquals(0, dispatcher.execute("cf retry", sourceOf(console)));
        verify(handler).onPlayerOnly(console);

        Player denied = mock(Player.class);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("cf", sourceOf(denied)));
    }

    private static CommandSourceStack sourceOf(CommandSender sender) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }
}
