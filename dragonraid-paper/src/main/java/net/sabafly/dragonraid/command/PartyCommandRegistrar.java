package net.sabafly.dragonraid.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.sabafly.dragonraid.permission.RaidPermissions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers {@code /party} (alias {@code /p}) and {@code /pc <message>} Brigadier commands.
 */
public final class PartyCommandRegistrar {

    public static final String PARTY_COMMAND = "party";
    public static final String PARTY_ALIAS = "p";
    public static final List<String> PARTY_ALIASES = List.of(PARTY_ALIAS);
    public static final String PARTY_DESCRIPTION = "Manage DragonRaid party and chat channel";

    public static final String PC_COMMAND = "pc";
    public static final String PC_DESCRIPTION = "Send a message to DragonRaid party chat";

    private final PartyCommandHandler handler;
    private final Supplier<Collection<String>> playerNameSupplier;

    public PartyCommandRegistrar(PartyCommandHandler handler) {
        this(handler, PartyCommandRegistrar::onlinePlayerNames);
    }

    public PartyCommandRegistrar(
            PartyCommandHandler handler,
            Supplier<Collection<String>> playerNameSupplier) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.playerNameSupplier = Objects.requireNonNull(playerNameSupplier, "playerNameSupplier");
    }

    public void register(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        register(plugin.getLifecycleManager(), LifecycleEvents.COMMANDS);
    }

    public <O extends LifecycleEventOwner> void register(
            LifecycleEventManager<O> lifecycleManager,
            LifecycleEventType<? super O, ? extends ReloadableRegistrarEvent<Commands>, ?> commandEventType) {
        Objects.requireNonNull(lifecycleManager, "lifecycleManager");
        Objects.requireNonNull(commandEventType, "commandEventType");
        lifecycleManager.registerEventHandler(commandEventType, event -> registerCommands(event.registrar()));
    }

    public void registerCommands(Commands commands) {
        Objects.requireNonNull(commands, "commands");
        commands.register(buildPartyBuilder(PARTY_COMMAND).build(), PARTY_DESCRIPTION, PARTY_ALIASES);
        commands.register(buildPcBuilder(PC_COMMAND).build(), PC_DESCRIPTION, List.of());
    }

    public void registerInto(CommandDispatcher<CommandSourceStack> dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(buildPartyBuilder(PARTY_COMMAND));
        dispatcher.register(buildPartyBuilder(PARTY_ALIAS));
        dispatcher.register(buildPcBuilder(PC_COMMAND));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildPartyBuilder(String rootLiteral) {
        return Commands.literal(rootLiteral)
                .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.PARTY))
                .executes(ctx -> requirePlayer(ctx.getSource(), handler::showPartyInfo))
                .then(Commands.literal("list")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::showPartyInfo)))
                .then(Commands.literal("create")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::createParty)))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandSupport.suggestMatching(playerNameSupplier.get(), builder))
                                .executes(ctx -> requirePlayer(ctx.getSource(), p ->
                                        handler.invitePlayer(p, StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("accept")
                        .executes(ctx -> requirePlayer(ctx.getSource(), p -> handler.acceptInvite(p, null)))
                        .then(Commands.argument("leader", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandSupport.suggestMatching(playerNameSupplier.get(), builder))
                                .executes(ctx -> requirePlayer(ctx.getSource(), p ->
                                        handler.acceptInvite(p, StringArgumentType.getString(ctx, "leader"))))))
                .then(Commands.literal("decline")
                        .executes(ctx -> requirePlayer(ctx.getSource(), p -> handler.declineInvite(p, null)))
                        .then(Commands.argument("leader", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandSupport.suggestMatching(playerNameSupplier.get(), builder))
                                .executes(ctx -> requirePlayer(ctx.getSource(), p ->
                                        handler.declineInvite(p, StringArgumentType.getString(ctx, "leader"))))))
                .then(Commands.literal("leave")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::leaveParty)))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandSupport.suggestMatching(playerNameSupplier.get(), builder))
                                .executes(ctx -> requirePlayer(ctx.getSource(), p ->
                                        handler.kickPlayer(p, StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("leader")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandSupport.suggestMatching(playerNameSupplier.get(), builder))
                                .executes(ctx -> requirePlayer(ctx.getSource(), p ->
                                        handler.transferLeadership(p, StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("disband")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::disbandParty)))
                .then(Commands.literal("chat")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::toggleChatChannel))
                        .then(Commands.literal("party")
                                .executes(ctx -> requirePlayer(ctx.getSource(), p -> handler.setChatChannel(p, "party"))))
                        .then(Commands.literal("global")
                                .executes(ctx -> requirePlayer(ctx.getSource(), p -> handler.setChatChannel(p, "global")))))
                .then(Commands.literal("hud")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::toggleHud))
                        .then(Commands.literal("on")
                                .executes(ctx -> requirePlayer(ctx.getSource(), p -> handler.setHudEnabled(p, true))))
                        .then(Commands.literal("off")
                                .executes(ctx -> requirePlayer(ctx.getSource(), p -> handler.setHudEnabled(p, false)))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildPcBuilder(String rootLiteral) {
        return Commands.literal(rootLiteral)
                .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.PARTY))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> requirePlayer(ctx.getSource(), p ->
                                handler.sendPartyChat(p, StringArgumentType.getString(ctx, "message")))));
    }

    private int requirePlayer(CommandSourceStack source, java.util.function.Consumer<Player> action) {
        return CommandSupport.requirePlayer(source, handler::onPlayerOnly, action);
    }

    private static Collection<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .toList();
    }
}
