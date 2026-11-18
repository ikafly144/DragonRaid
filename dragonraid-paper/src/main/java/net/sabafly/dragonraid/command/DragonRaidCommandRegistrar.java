package net.sabafly.dragonraid.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.sabafly.dragonraid.command.RaidCommandHandler.Difficulty;
import net.sabafly.dragonraid.command.RaidCommandHandler.PointsAction;
import net.sabafly.dragonraid.command.RaidCommandHandler.RegionAction;
import net.sabafly.dragonraid.permission.RaidPermissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Builds and registers the {@code /dragonraid} (alias {@code /dr}) Brigadier command tree
 * with Paper/Folia {@link LifecycleEvents#COMMANDS}.
 */
public final class DragonRaidCommandRegistrar {

    public static final String PRIMARY_COMMAND = "dragonraid";
    public static final String ALIAS_COMMAND = "dr";
    public static final List<String> ALIASES = List.of(ALIAS_COMMAND);
    public static final String COMMAND_DESCRIPTION = "DragonRaid main command";

    private static final List<String> DEFAULT_CURRENCIES = List.of(
        "dragon_token",
        "abyssal_scale",
        "celestial_fragment"
    );

    private final RaidCommandHandler handler;
    private final Supplier<Collection<String>> playerNameSupplier;
    private final Supplier<Collection<String>> currencySupplier;

    /**
     * Creates a registrar using online Bukkit players and default currency IDs for tab completion.
     *
     * @param handler the command handler port
     */
    public DragonRaidCommandRegistrar(RaidCommandHandler handler) {
        this(handler, DragonRaidCommandRegistrar::onlinePlayerNames, () -> DEFAULT_CURRENCIES);
    }

    /**
     * Creates a registrar with custom tab-completion suppliers (primarily for tests or dynamic currencies).
     *
     * @param handler the command handler port
     * @param playerNameSupplier supplier of suggested player names
     * @param currencySupplier supplier of suggested currency identifiers
     */
    public DragonRaidCommandRegistrar(
        RaidCommandHandler handler,
        Supplier<Collection<String>> playerNameSupplier,
        Supplier<Collection<String>> currencySupplier
    ) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.playerNameSupplier = Objects.requireNonNull(playerNameSupplier, "playerNameSupplier");
        this.currencySupplier = Objects.requireNonNull(currencySupplier, "currencySupplier");
    }

    /**
     * Registers the {@code /dragonraid} and {@code /dr} commands with a plugin's lifecycle manager.
     *
     * @param plugin the owning JavaPlugin
     */
    public void register(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        register(plugin.getLifecycleManager());
    }

    /**
     * Registers the {@code /dragonraid} and {@code /dr} commands on any Paper/Folia
     * {@link LifecycleEventManager} (both {@link JavaPlugin} and {@code BootstrapContext}).
     *
     * @param lifecycleManager the lifecycle event manager
     * @param <O> the lifecycle event owner type
     */
    public <O extends LifecycleEventOwner> void register(LifecycleEventManager<O> lifecycleManager) {
        register(lifecycleManager, LifecycleEvents.COMMANDS);
    }

    /**
     * Registers the {@code /dragonraid} and {@code /dr} commands on any Paper/Folia
     * {@link LifecycleEventManager} using an explicit {@link LifecycleEventType}.
     *
     * @param lifecycleManager the lifecycle event manager
     * @param commandEventType the commands lifecycle event type
     * @param <O> the lifecycle event owner type
     */
    public <O extends LifecycleEventOwner> void register(
        LifecycleEventManager<O> lifecycleManager,
        LifecycleEventType<? super O, ? extends ReloadableRegistrarEvent<Commands>, ?> commandEventType
    ) {
        Objects.requireNonNull(lifecycleManager, "lifecycleManager");
        Objects.requireNonNull(commandEventType, "commandEventType");
        lifecycleManager.registerEventHandler(
            commandEventType,
            event -> registerCommands(event.registrar())
        );
    }

    /**
     * Registers the command tree directly with a Paper {@link Commands} registrar.
     *
     * @param commands the Paper Brigadier commands registrar
     */
    public void registerCommands(Commands commands) {
        Objects.requireNonNull(commands, "commands");
        commands.register(buildCommand(), COMMAND_DESCRIPTION, ALIASES);
    }

    /**
     * Registers both {@code /dragonraid} and {@code /dr} directly into a Brigadier dispatcher.
     *
     * @param dispatcher the Brigadier command dispatcher
     */
    public void registerInto(CommandDispatcher<CommandSourceStack> dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(buildRootBuilder(PRIMARY_COMMAND));
        dispatcher.register(buildRootBuilder(ALIAS_COMMAND));
    }

    /**
     * Builds the root {@link LiteralCommandNode} for {@code /dragonraid}.
     *
     * @return the built Brigadier command node
     */
    public LiteralCommandNode<CommandSourceStack> buildCommand() {
        return buildRootBuilder(PRIMARY_COMMAND).build();
    }

    /**
     * Builds a {@link LiteralArgumentBuilder} for the given root command literal.
     *
     * @param rootLiteral {@code "dragonraid"} or {@code "dr"}
     * @return the configured Brigadier builder
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildRootBuilder(String rootLiteral) {
        Objects.requireNonNull(rootLiteral, "rootLiteral");
        return Commands.literal(rootLiteral)
            .requires(source -> RaidPermissions.hasAnyCommandAccess(source.getSender()))
            .executes(ctx -> {
                handler.showHelp(ctx.getSource().getSender());
                return Command.SINGLE_SUCCESS;
            })
            .then(buildShopNode())
            .then(buildStatsNode())
            .then(buildAdminNode());
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildShopNode() {
        return Commands.literal("shop")
            .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.SHOP))
            .executes(ctx -> requirePlayer(ctx.getSource(), handler::openShop));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildStatsNode() {
        return Commands.literal("stats")
            .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.STATS)
                || RaidPermissions.hasStatsOthersPermission(source.getSender()))
            .executes(ctx -> requirePlayer(ctx.getSource(), player -> handler.showStats(player, null)))
            .then(Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> suggestMatching(playerNameSupplier.get(), builder))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    String target = StringArgumentType.getString(ctx, "player");
                    if (!isTargetingSelf(sender, target) && !RaidPermissions.hasStatsOthersPermission(sender)) {
                        handler.onNoPermission(sender);
                        return 0;
                    }
                    handler.showStats(sender, target);
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAdminNode() {
        return Commands.literal("admin")
            .requires(source -> RaidPermissions.hasAnyAdminPermission(source.getSender()))
            .then(buildAdminRegionNode())
            .then(buildAdminStartNode())
            .then(buildAdminResetNode())
            .then(buildAdminPointsNode())
            .then(buildAdminReloadNode());
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAdminRegionNode() {
        return Commands.literal("region")
            .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.ADMIN_REGION))
            .then(buildRegionActionNode(RegionAction.INFO, false))
            .then(buildRegionActionNode(RegionAction.SET_ARENA, true))
            .then(buildRegionActionNode(RegionAction.SET_SAFEZONE, true))
            .then(Commands.literal("set-arena-radius")
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0))
                    .executes(ctx -> {
                        double radius = DoubleArgumentType.getDouble(ctx, "radius");
                        handler.adminSetArenaRadius(ctx.getSource().getSender(), radius);
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("set-void-y")
                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                    .executes(ctx -> {
                        double y = DoubleArgumentType.getDouble(ctx, "y");
                        handler.adminSetVoidY(ctx.getSource().getSender(), y);
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(buildBoundsNode("set-arena-bounds", true))
            .then(buildBoundsNode("set-safezone-bounds", false));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildBoundsNode(String literal, boolean isArena) {
        return Commands.literal(literal)
            .then(Commands.argument("minX", DoubleArgumentType.doubleArg())
                .then(Commands.argument("minY", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("minZ", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("maxX", DoubleArgumentType.doubleArg())
                            .then(Commands.argument("maxY", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("maxZ", DoubleArgumentType.doubleArg())
                                    .executes(ctx -> {
                                        CommandSender sender = ctx.getSource().getSender();
                                        double minX = DoubleArgumentType.getDouble(ctx, "minX");
                                        double minY = DoubleArgumentType.getDouble(ctx, "minY");
                                        double minZ = DoubleArgumentType.getDouble(ctx, "minZ");
                                        double maxX = DoubleArgumentType.getDouble(ctx, "maxX");
                                        double maxY = DoubleArgumentType.getDouble(ctx, "maxY");
                                        double maxZ = DoubleArgumentType.getDouble(ctx, "maxZ");
                                        if (isArena) {
                                            handler.adminSetArenaBounds(sender, minX, minY, minZ, maxX, maxY, maxZ);
                                        } else {
                                            handler.adminSetSafeZoneBounds(sender, minX, minY, minZ, maxX, maxY, maxZ);
                                        }
                                        return Command.SINGLE_SUCCESS;
                                    })))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildRegionActionNode(RegionAction action, boolean requirePlayer) {
        return Commands.literal(action.cliName())
            .executes(ctx -> {
                CommandSourceStack source = ctx.getSource();
                CommandSender sender = source.getSender();
                if (requirePlayer && !(sender instanceof Player)) {
                    handler.onPlayerOnly(sender);
                    return 0;
                }
                Location location = source.getLocation();
                if (location == null && sender instanceof Player player) {
                    location = player.getLocation();
                }
                handler.adminRegion(sender, location, action);
                return Command.SINGLE_SUCCESS;
            });
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAdminStartNode() {
        return Commands.literal("start")
            .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.ADMIN_START))
            .then(buildDifficultyNode(Difficulty.NORMAL))
            .then(buildDifficultyNode(Difficulty.HARD));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildDifficultyNode(Difficulty difficulty) {
        return Commands.literal(difficulty.cliName())
            .executes(ctx -> {
                handler.adminStart(ctx.getSource().getSender(), difficulty);
                return Command.SINGLE_SUCCESS;
            });
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAdminResetNode() {
        return Commands.literal("reset")
            .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.ADMIN_RESET))
            .executes(ctx -> {
                handler.adminReset(ctx.getSource().getSender());
                return Command.SINGLE_SUCCESS;
            });
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAdminPointsNode() {
        return Commands.literal("points")
            .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.ADMIN_POINTS))
            .then(buildPointsActionNode(PointsAction.ADD, 1))
            .then(buildPointsActionNode(PointsAction.SET, 0))
            .then(buildPointsActionNode(PointsAction.TAKE, 1));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildPointsActionNode(PointsAction action, int minAmount) {
        return Commands.literal(action.cliName())
            .then(Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> suggestMatching(playerNameSupplier.get(), builder))
                .then(Commands.argument("amount", IntegerArgumentType.integer(minAmount))
                    .executes(ctx -> {
                        String target = StringArgumentType.getString(ctx, "player");
                        int amount = IntegerArgumentType.getInteger(ctx, "amount");
                        handler.adminPoints(ctx.getSource().getSender(), action, target, null, amount);
                        return Command.SINGLE_SUCCESS;
                    }))
                .then(Commands.argument("currency", StringArgumentType.word())
                    .suggests((ctx, builder) -> suggestMatching(currencySupplier.get(), builder))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(minAmount))
                        .executes(ctx -> {
                            String target = StringArgumentType.getString(ctx, "player");
                            String currency = StringArgumentType.getString(ctx, "currency");
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            handler.adminPoints(ctx.getSource().getSender(), action, target, currency, amount);
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAdminReloadNode() {
        return Commands.literal("reload")
            .requires(source -> RaidPermissions.hasPermission(source.getSender(), RaidPermissions.ADMIN_RELOAD))
            .executes(ctx -> {
                handler.adminReload(ctx.getSource().getSender());
                return Command.SINGLE_SUCCESS;
            });
    }

    private int requirePlayer(CommandSourceStack source, Consumer<Player> action) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            handler.onPlayerOnly(sender);
            return 0;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }

    private static boolean isTargetingSelf(CommandSender sender, String target) {
        return sender instanceof Player player && player.getName().equalsIgnoreCase(target);
    }

    private static CompletableFuture<Suggestions> suggestMatching(
        Collection<String> candidates,
        SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    private static Collection<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .toList();
    }
}
