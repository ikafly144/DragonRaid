package io.github.ikafly144.dragonraid.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import io.github.ikafly144.dragonraid.command.RaidCommandHandler.Difficulty;
import io.github.ikafly144.dragonraid.command.RaidCommandHandler.PointsAction;
import io.github.ikafly144.dragonraid.command.RaidCommandHandler.RegionAction;
import io.github.ikafly144.dragonraid.permission.RaidPermissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.LifecycleEventHandlerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DragonRaidCommandRegistrarTest {

    private RaidCommandHandler handler;
    private DragonRaidCommandRegistrar registrar;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        handler = mock(RaidCommandHandler.class);
        registrar = new DragonRaidCommandRegistrar(
            handler,
            () -> List.of("Steve", "Alex", "ikafly144"),
            () -> List.of("dragon_token", "abyssal_scale", "celestial_fragment")
        );
        dispatcher = new CommandDispatcher<>();
        registrar.registerInto(dispatcher);
    }

    private static CommandSourceStack sourceOf(CommandSender sender) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    private static Player playerWithAllPermissions(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.hasPermission(any(String.class))).thenReturn(true);
        return player;
    }

    private static Player playerWithPermissions(String name, String... permissions) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        for (String perm : permissions) {
            when(player.hasPermission(perm)).thenReturn(true);
        }
        return player;
    }

    @Test
    @SuppressWarnings("unchecked")
    void lifecycleManagerHelperRegistersDragonRaidAndDrAlias() {
        LifecycleEventManager<LifecycleEventOwner> lifecycleManager = mock(LifecycleEventManager.class);
        LifecycleEventType<
            LifecycleEventOwner,
            ReloadableRegistrarEvent<Commands>,
            LifecycleEventHandlerConfiguration<LifecycleEventOwner>
        > commandEventType = mock(LifecycleEventType.class);

        registrar.register(lifecycleManager, commandEventType);

        ArgumentCaptor<LifecycleEventHandler<ReloadableRegistrarEvent<Commands>>> captor =
            ArgumentCaptor.forClass(LifecycleEventHandler.class);
        verify(lifecycleManager).registerEventHandler(eq(commandEventType), captor.capture());

        ReloadableRegistrarEvent<Commands> event = mock(ReloadableRegistrarEvent.class);
        Commands commands = mock(Commands.class);
        when(event.registrar()).thenReturn(commands);

        captor.getValue().run(event);

        verify(commands).register(
            any(),
            eq(DragonRaidCommandRegistrar.COMMAND_DESCRIPTION),
            eq(List.of("dr"))
        );
    }

    @Test
    void rootCommandRoutesToShowHelpAndDeniesWithoutAccess() throws Exception {
        Player player = playerWithPermissions("Steve", RaidPermissions.USE);
        dispatcher.execute("dragonraid", sourceOf(player));
        dispatcher.execute("dr", sourceOf(player));
        verify(handler, org.mockito.Mockito.times(2)).showHelp(player);

        Player unauthorized = mock(Player.class);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("dr", sourceOf(unauthorized)));
        verify(handler, never()).showHelp(unauthorized);
    }

    @Test
    void shopRequiresPermissionAndPlayer() throws Exception {
        Player player = playerWithPermissions("Steve", RaidPermissions.SHOP);
        dispatcher.execute("dr shop", sourceOf(player));
        verify(handler).openShop(player);

        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(RaidPermissions.SHOP)).thenReturn(true);
        int result = dispatcher.execute("dr shop", sourceOf(console));
        assertEquals(0, result);
        verify(handler).onPlayerOnly(console);
        verify(handler, org.mockito.Mockito.times(1)).openShop(any());

        Player denied = playerWithPermissions("Alex", RaidPermissions.USE);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("dr shop", sourceOf(denied)));
    }

    @Test
    void statsHandlesSelfTargetAndOthersPermission() throws Exception {
        Player steve = playerWithPermissions("Steve", RaidPermissions.STATS);
        dispatcher.execute("dr stats", sourceOf(steve));
        verify(handler).showStats(steve, null);

        // Querying self by explicit name only needs STATS
        dispatcher.execute("dr stats steve", sourceOf(steve));
        verify(handler).showStats(steve, "steve");

        // Querying another player without STATS_OTHERS triggers onNoPermission
        int deniedResult = dispatcher.execute("dr stats Alex", sourceOf(steve));
        assertEquals(0, deniedResult);
        verify(handler).onNoPermission(steve);
        verify(handler, never()).showStats(steve, "Alex");

        // Querying another player with STATS_OTHERS succeeds
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(RaidPermissions.STATS_OTHERS)).thenReturn(true);
        dispatcher.execute("dr stats Alex", sourceOf(console));
        verify(handler).showStats(console, "Alex");

        // Console without target player triggers onPlayerOnly
        when(console.hasPermission(RaidPermissions.STATS)).thenReturn(true);
        int consoleSelfResult = dispatcher.execute("dr stats", sourceOf(console));
        assertEquals(0, consoleSelfResult);
        verify(handler).onPlayerOnly(console);
    }

    @Test
    void readyAndStartDelegateAndEnforcePermissions() throws Exception {
        Player player = playerWithPermissions("Steve", RaidPermissions.READY, RaidPermissions.START);
        dispatcher.execute("dr ready", sourceOf(player));
        dispatcher.execute("dragonraid start", sourceOf(player));
        verify(handler).ready(player);
        verify(handler).start(player);

        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(RaidPermissions.READY)).thenReturn(true);
        when(console.hasPermission(RaidPermissions.START)).thenReturn(true);
        assertEquals(0, dispatcher.execute("dr ready", sourceOf(console)));
        verify(handler).onPlayerOnly(console);
        dispatcher.execute("dr start", sourceOf(console));
        verify(handler).start(console);

        Player noReady = playerWithPermissions("Alex", RaidPermissions.USE);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("dr ready", sourceOf(noReady)));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("dr start", sourceOf(noReady)));
    }

    @Test
    void adminRegionRoutesAllActionsAndRequiresPlayerForSetters() throws Exception {
        Player admin = playerWithPermissions("Admin", RaidPermissions.ADMIN_REGION);
        Location loc = new Location(null, 10.0, 64.0, -5.0);
        CommandSourceStack playerSource = sourceOf(admin);
        when(playerSource.getLocation()).thenReturn(loc);

        dispatcher.execute("dr admin region info", playerSource);
        dispatcher.execute("dr admin region set-arena", playerSource);
        dispatcher.execute("dr admin region set-safezone", playerSource);

        verify(handler).adminRegion(admin, loc, RegionAction.INFO);
        verify(handler).adminRegion(admin, loc, RegionAction.SET_ARENA);
        verify(handler).adminRegion(admin, loc, RegionAction.SET_SAFEZONE);

        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(RaidPermissions.ADMIN)).thenReturn(true);
        dispatcher.execute("dr admin region info", sourceOf(console));
        verify(handler).adminRegion(console, null, RegionAction.INFO);

        assertEquals(0, dispatcher.execute("dr admin region set-arena", sourceOf(console)));
        assertEquals(0, dispatcher.execute("dr admin region set-safezone", sourceOf(console)));
        verify(handler, org.mockito.Mockito.times(2)).onPlayerOnly(console);

        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin region invalid", playerSource));

        Player nonAdmin = playerWithPermissions("Steve", RaidPermissions.USE);
        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin region info", sourceOf(nonAdmin)));
    }

    @Test
    void adminStartResetReloadRouteAndEnforcePermissions() throws Exception {
        Player admin = playerWithAllPermissions("Admin");
        CommandSourceStack source = sourceOf(admin);

        dispatcher.execute("dr admin start normal", source);
        dispatcher.execute("dr admin start hard", source);
        dispatcher.execute("dr admin reset", source);
        dispatcher.execute("dr admin reload", source);

        verify(handler).adminStart(admin, Difficulty.NORMAL);
        verify(handler).adminStart(admin, Difficulty.HARD);
        verify(handler).adminReset(admin);
        verify(handler).adminReload(admin);

        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin start nightmare", source));

        Player nonAdmin = playerWithPermissions("Steve", RaidPermissions.USE);
        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin start normal", sourceOf(nonAdmin)));
        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin reset", sourceOf(nonAdmin)));
        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin reload", sourceOf(nonAdmin)));
    }

    @Test
    void adminPointsSupportsOptionalCurrencyAndValidatesAmounts() throws Exception {
        Player admin = playerWithPermissions("Admin", RaidPermissions.ADMIN_POINTS);
        CommandSourceStack source = sourceOf(admin);

        dispatcher.execute("dr admin points add Steve 25", source);
        dispatcher.execute("dr admin points set Steve 0", source);
        dispatcher.execute("dr admin points take Alex 10", source);
        dispatcher.execute("dr admin points add Steve dragon_token 50", source);
        dispatcher.execute("dr admin points set Alex abyssal_scale 0", source);
        dispatcher.execute("dr admin points take Steve celestial_fragment 5", source);

        verify(handler).adminPoints(admin, PointsAction.ADD, "Steve", null, 25);
        verify(handler).adminPoints(admin, PointsAction.SET, "Steve", null, 0);
        verify(handler).adminPoints(admin, PointsAction.TAKE, "Alex", null, 10);
        verify(handler).adminPoints(admin, PointsAction.ADD, "Steve", "dragon_token", 50);
        verify(handler).adminPoints(admin, PointsAction.SET, "Alex", "abyssal_scale", 0);
        verify(handler).adminPoints(admin, PointsAction.TAKE, "Steve", "celestial_fragment", 5);

        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin points add Steve 0", source));
        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin points take Steve -5", source));
        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin points set Steve -1", source));
        assertThrows(CommandSyntaxException.class,
            () -> dispatcher.execute("dr admin points add Steve dragon_token 0", source));
    }

    @Test
    void tabCompletionSuggestsPlayersAndCurrencies() throws Exception {
        Player admin = playerWithAllPermissions("Admin");
        CommandSourceStack source = sourceOf(admin);

        Suggestions statsSuggestions = dispatcher
            .getCompletionSuggestions(dispatcher.parse("dr stats st", source))
            .get();
        assertEquals(List.of("Steve"), statsSuggestions.getList().stream().map(s -> s.getText()).toList());

        Suggestions currencySuggestions = dispatcher
            .getCompletionSuggestions(dispatcher.parse("dr admin points add Steve dra", source))
            .get();
        assertTrue(currencySuggestions.getList().stream().anyMatch(s -> s.getText().equals("dragon_token")));
    }
}
