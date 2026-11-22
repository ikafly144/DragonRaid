package net.sabafly.dragonraid.command;

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
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.sabafly.dragonraid.permission.RaidPermissions;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers {@code /cf} (aliases {@code /finder}, {@code /duty}) Brigadier commands.
 */
public final class ContentFinderCommandRegistrar {

    public static final String PRIMARY_COMMAND = "cf";
    public static final String ALIAS_FINDER = "finder";
    public static final String ALIAS_DUTY = "duty";
    public static final List<String> ALIASES = List.of(ALIAS_FINDER, ALIAS_DUTY);
    public static final String COMMAND_DESCRIPTION = "Open or control DragonRaid Content Finder";

    private final ContentFinderCommandHandler handler;
    private final Supplier<Collection<String>> contentIdSupplier;

    public ContentFinderCommandRegistrar(
            ContentFinderCommandHandler handler,
            Supplier<Collection<String>> contentIdSupplier) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.contentIdSupplier = Objects.requireNonNull(contentIdSupplier, "contentIdSupplier");
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
        commands.register(buildRootBuilder(PRIMARY_COMMAND).build(), COMMAND_DESCRIPTION, ALIASES);
    }

    public void registerInto(CommandDispatcher<CommandSourceStack> dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(buildRootBuilder(PRIMARY_COMMAND));
        dispatcher.register(buildRootBuilder(ALIAS_FINDER));
        dispatcher.register(buildRootBuilder(ALIAS_DUTY));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildRootBuilder(String rootLiteral) {
        return Commands.literal(rootLiteral)
                .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.FINDER))
                .executes(ctx -> requirePlayer(ctx.getSource(), handler::openFinder))
                .then(Commands.literal("apply")
                        .then(Commands.argument("contentId", StringArgumentType.string())
                                .suggests((ctx, builder) -> CommandSupport.suggestMatchingEscaped(contentIdSupplier.get(), builder))
                                .executes(ctx -> requirePlayer(ctx.getSource(), p ->
                                        handler.applyContent(p, StringArgumentType.getString(ctx, "contentId"))))))
                .then(Commands.literal("accept")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::acceptReadyCheck)))
                .then(Commands.literal("decline")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::declineReadyCheck)))
                .then(Commands.literal("cancel")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::cancelReadyCheck)))
                .then(Commands.literal("retry")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::retryEncounter)))
                .then(Commands.literal("leave")
                        .executes(ctx -> requirePlayer(ctx.getSource(), handler::leaveEncounter)));
    }

    private int requirePlayer(CommandSourceStack source, Consumer<Player> action) {
        return CommandSupport.requirePlayer(source, handler::onPlayerOnly, action);
    }
}
