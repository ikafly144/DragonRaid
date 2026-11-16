package net.sabafly.dragonraid.command;

import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.config.RegionConfig.ArenaRegion;
import net.sabafly.dragonraid.config.RegionConfig.Point3D;
import net.sabafly.dragonraid.config.RegionConfig.SafeZoneRegion;
import net.sabafly.dragonraid.config.RegionConfig.Shape;
import net.sabafly.dragonraid.config.RegionConfig.SpawnPoint;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.PlayerRaidStats;
import net.sabafly.dragonraid.storage.PointTransactionResult;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Default M1 implementation of {@link RaidCommandHandler} wiring configuration,
 * i18n messages, Folia-safe thread dispatch, and SQLite player storage.
 */
public final class DefaultRaidCommandHandler implements RaidCommandHandler {

    private static final String DEFAULT_VIRTUAL_CURRENCY = "virtual_points";

    private final ConfigManager configManager;
    private final MessageService messageService;
    private final RaidScheduler scheduler;
    private final PlayerDataRepository repository;
    private final Logger logger;
    private final Function<String, Optional<Player>> onlinePlayerLookup;

    /**
     * Creates a command handler backed by Bukkit's online player registry.
     *
     * @param configManager configuration manager
     * @param messageService MiniMessage localization service
     * @param scheduler Folia scheduler abstraction
     * @param repository async player data repository
     * @param logger plugin logger
     */
    public DefaultRaidCommandHandler(
        ConfigManager configManager,
        MessageService messageService,
        RaidScheduler scheduler,
        PlayerDataRepository repository,
        Logger logger
    ) {
        this(
            configManager,
            messageService,
            scheduler,
            repository,
            logger,
            name -> Optional.ofNullable(Bukkit.getPlayerExact(name))
        );
    }

    /**
     * Creates a command handler with an explicit online player lookup function (primarily for unit tests).
     *
     * @param configManager configuration manager
     * @param messageService MiniMessage localization service
     * @param scheduler Folia scheduler abstraction
     * @param repository async player data repository
     * @param logger plugin logger
     * @param onlinePlayerLookup resolver from exact player name to online {@link Player}
     */
    public DefaultRaidCommandHandler(
        ConfigManager configManager,
        MessageService messageService,
        RaidScheduler scheduler,
        PlayerDataRepository repository,
        Logger logger,
        Function<String, Optional<Player>> onlinePlayerLookup
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.onlinePlayerLookup = Objects.requireNonNull(onlinePlayerLookup, "onlinePlayerLookup");
    }

    @Override
    public void openShop(Player player) {
        Objects.requireNonNull(player, "player");
        messageService.send(player, "command.milestone-shop");
    }

    @Override
    public void showHelp(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        messageService.send(sender, "command.help");
    }

    @Override
    public void showStats(CommandSender sender, @Nullable String targetPlayerName) {
        Objects.requireNonNull(sender, "sender");
        if (targetPlayerName == null) {
            if (!(sender instanceof Player player)) {
                messageService.send(sender, "command.player-only");
                return;
            }
            loadAndSendStats(sender, player.getUniqueId(), player.getName());
            return;
        }

        Optional<Player> online = onlinePlayerLookup.apply(targetPlayerName);
        if (online.isPresent()) {
            Player target = online.get();
            loadAndSendStats(sender, target.getUniqueId(), target.getName());
            return;
        }

        repository.findByName(targetPlayerName)
            .thenCompose(optionalStats -> {
                if (optionalStats.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.<StatsSnapshot>empty());
                }
                PlayerRaidStats stats = optionalStats.get();
                return repository.getCurrencyBalances(stats.uuid())
                    .thenApply(balances -> Optional.of(new StatsSnapshot(stats, balances)));
            })
            .whenComplete((snapshot, error) -> replyOnSenderThread(sender, () -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Failed to load raid stats for " + targetPlayerName, error);
                    messageService.send(sender, "command.stats-load-failed");
                    return;
                }
                if (snapshot.isEmpty()) {
                    messageService.send(
                        sender,
                        "command.player-not-found",
                        Placeholder.unparsed("target", targetPlayerName)
                    );
                    return;
                }
                sendStatsLines(sender, snapshot.get().stats(), snapshot.get().currencies());
            }));
    }

    @Override
    public void ready(Player player) {
        Objects.requireNonNull(player, "player");
        if (configManager.pluginConfig().encounter().altarGuiOnly()) {
            messageService.send(player, "command.altar-only-mode");
            return;
        }
        messageService.send(player, "command.milestone-ready");
    }

    @Override
    public void start(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (configManager.pluginConfig().encounter().altarGuiOnly()) {
            messageService.send(sender, "command.altar-only-mode");
            return;
        }
        messageService.send(sender, "command.milestone-start");
    }

    @Override
    public void adminRegion(CommandSender sender, @Nullable Location location, RegionAction action) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(action, "action");
        RegionConfig current = configManager.pluginConfig().regions();
        switch (action) {
            case INFO -> {
                messageService.send(
                    sender,
                    "command.region-info",
                    Placeholder.unparsed("region", "arena"),
                    Placeholder.unparsed("details", formatArena(current.arena()))
                );
                messageService.send(
                    sender,
                    "command.region-info",
                    Placeholder.unparsed("region", "safe-zone"),
                    Placeholder.unparsed("details", formatSafeZone(current.safeZone(), current.voidYThreshold()))
                );
            }
            case SET_ARENA -> {
                if (location == null) {
                    onPlayerOnly(sender);
                    return;
                }
                double x = location.getX();
                double y = location.getY();
                double z = location.getZ();
                persistRegions(sender, "arena", latest -> new RegionConfig(
                        recenterArena(latest.arena(), x, y, z),
                        latest.safeZone(),
                        latest.voidYThreshold()
                ));
            }
            case SET_SAFEZONE -> {
                if (location == null) {
                    onPlayerOnly(sender);
                    return;
                }
                double x = location.getX();
                double y = location.getY();
                double z = location.getZ();
                float yaw = location.getYaw();
                float pitch = location.getPitch();
                persistRegions(sender, "safe-zone", latest -> new RegionConfig(
                        latest.arena(),
                        recenterSafeZone(latest.safeZone(), x, y, z, yaw, pitch),
                        latest.voidYThreshold()
                ));
            }
        }
    }

    @Override
    public void adminStart(CommandSender sender, Difficulty difficulty) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(difficulty, "difficulty");
        messageService.send(
            sender,
            "command.milestone-admin-start",
            Placeholder.unparsed("difficulty", difficulty.cliName())
        );
    }

    @Override
    public void adminReset(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        messageService.send(sender, "command.milestone-admin-reset");
    }

    @Override
    public void adminPoints(
        CommandSender sender,
        PointsAction action,
        String targetPlayerName,
        @Nullable String currency,
        int amount
    ) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetPlayerName, "targetPlayerName");

        String normalizedCurrency = currency == null || currency.isBlank()
            ? null
            : currency.trim().toLowerCase(Locale.ROOT);
        if (normalizedCurrency != null
            && !configManager.rewardsConfig().currencies().containsKey(normalizedCurrency)) {
            String validCurrencies = String.join(", ", configManager.rewardsConfig().currencies().keySet());
            messageService.send(
                sender,
                "command.invalid-usage",
                Placeholder.unparsed(
                    "usage",
                    "/dr admin points <add|set|take> <player> [" + validCurrencies + "] <amount>"
                )
            );
            return;
        }
        String currencyLabel = normalizedCurrency == null ? DEFAULT_VIRTUAL_CURRENCY : normalizedCurrency;

        resolvePlayerIdentity(targetPlayerName)
            .thenCompose(identityOpt -> {
                if (identityOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.<PointsUpdateOutcome>empty());
                }
                PlayerIdentity identity = identityOpt.get();
                return executePointMutation(identity, action, normalizedCurrency, amount)
                    .thenApply(result -> Optional.of(new PointsUpdateOutcome(identity.name(), result)));
            })
            .whenComplete((outcomeOpt, error) -> replyOnSenderThread(sender, () -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Failed to update points for " + targetPlayerName, error);
                    messageService.send(sender, "command.points-load-failed");
                    return;
                }
                if (outcomeOpt.isEmpty()) {
                    messageService.send(
                        sender,
                        "command.player-not-found",
                        Placeholder.unparsed("target", targetPlayerName)
                    );
                    return;
                }
                PointsUpdateOutcome outcome = outcomeOpt.get();
                PointTransactionResult result = outcome.result();
                if (!result.success()) {
                    handlePointFailure(sender, targetPlayerName, result);
                    return;
                }
                messageService.send(
                    sender,
                    "command.points-updated",
                    Placeholder.unparsed("player", outcome.playerName()),
                    Placeholder.unparsed("currency", currencyLabel),
                    Placeholder.unparsed("amount", String.valueOf(result.newBalance())),
                    Placeholder.unparsed("action", action.cliName())
                );
            }));
    }

    @Override
    public void adminReload(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        CompletableFuture.runAsync(() -> {
            configManager.reload();
            messageService.reload();
        }, scheduler.asyncExecutor()).whenComplete((ignored, error) -> replyOnSenderThread(sender, () -> {
            if (error == null) {
                messageService.send(sender, "command.reload-success");
            } else {
                Throwable cause = unwrapCause(error);
                logger.log(Level.WARNING, "Failed to reload DragonRaid configuration", cause);
                messageService.send(sender, "command.reload-failed",
                        Placeholder.unparsed("error", formatErrorDetail(cause)));
            }
        }));
    }

    @Override
    public void onPlayerOnly(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        messageService.send(sender, "command.player-only");
    }

    @Override
    public void onNoPermission(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        messageService.send(sender, "command.no-permission");
    }

    private void loadAndSendStats(CommandSender sender, UUID targetUuid, String displayName) {
        CompletableFuture<PlayerRaidStats> statsFuture = repository.findByUuid(targetUuid)
            .thenApply(opt -> opt.orElseGet(() -> PlayerRaidStats.empty(targetUuid, displayName)));
        CompletableFuture<Map<String, Long>> currenciesFuture = repository.getCurrencyBalances(targetUuid);

        statsFuture.thenCombine(currenciesFuture, StatsSnapshot::new)
            .whenComplete((snapshot, error) -> replyOnSenderThread(sender, () -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Failed to load raid stats for " + displayName, error);
                    messageService.send(sender, "command.stats-load-failed");
                    return;
                }
                sendStatsLines(sender, snapshot.stats(), snapshot.currencies());
            }));
    }

    private void sendStatsLines(CommandSender sender, PlayerRaidStats stats, Map<String, Long> currencies) {
        String playerName = stats.lastKnownName() != null ? stats.lastKnownName() : stats.uuid().toString();
        messageService.send(sender, "command.stats-header", Placeholder.unparsed("player", playerName));
        messageService.send(
            sender,
            "command.stats-clears",
            Placeholder.unparsed("normal", String.valueOf(stats.normalClears())),
            Placeholder.unparsed("hard", String.valueOf(stats.hardClears()))
        );
        messageService.send(
            sender,
            "command.stats-best-time",
            Placeholder.unparsed("normal_time", formatDuration(stats.bestNormalTimeMs())),
            Placeholder.unparsed("hard_time", formatDuration(stats.bestHardTimeMs()))
        );
        messageService.send(
            sender,
            "command.stats-points",
            Placeholder.unparsed("points", formatPoints(stats.virtualPoints(), currencies))
        );
    }

    private CompletableFuture<Optional<PlayerIdentity>> resolvePlayerIdentity(String targetPlayerName) {
        Optional<Player> online = onlinePlayerLookup.apply(targetPlayerName);
        if (online.isPresent()) {
            Player player = online.get();
            return CompletableFuture.completedFuture(
                Optional.of(new PlayerIdentity(player.getUniqueId(), player.getName(), true))
            );
        }
        return repository.findByName(targetPlayerName)
            .thenApply(opt -> opt.map(stats -> new PlayerIdentity(
                stats.uuid(),
                stats.lastKnownName() != null ? stats.lastKnownName() : targetPlayerName,
                false
            )));
    }

    private CompletableFuture<PointTransactionResult> executePointMutation(
        PlayerIdentity identity,
        PointsAction action,
        @Nullable String currency,
        int amount
    ) {
        if (action == PointsAction.TAKE && identity.online()) {
            return repository.getOrCreate(identity.uuid(), identity.name())
                .thenCompose(ignored -> mutatePoints(identity, action, currency, amount));
        }
        return mutatePoints(identity, action, currency, amount);
    }

    private CompletableFuture<PointTransactionResult> mutatePoints(
        PlayerIdentity identity,
        PointsAction action,
        @Nullable String currency,
        int amount
    ) {
        if (currency == null) {
            return switch (action) {
                case ADD -> repository.addPoints(identity.uuid(), identity.name(), amount);
                case SET -> repository.setPoints(identity.uuid(), identity.name(), amount);
                case TAKE -> repository.trySpendPoints(identity.uuid(), amount);
            };
        }
        return switch (action) {
            case ADD -> repository.addCurrency(identity.uuid(), identity.name(), currency, amount);
            case SET -> repository.setCurrency(identity.uuid(), identity.name(), currency, amount);
            case TAKE -> repository.trySpendCurrency(identity.uuid(), currency, amount);
        };
    }

    private void handlePointFailure(CommandSender sender, String targetPlayerName, PointTransactionResult result) {
        switch (result.failureReason()) {
            case PLAYER_NOT_FOUND -> messageService.send(
                sender,
                "command.player-not-found",
                Placeholder.unparsed("target", targetPlayerName)
            );
            case INSUFFICIENT_FUNDS -> messageService.send(
                sender,
                "command.points-insufficient",
                Placeholder.unparsed("target", targetPlayerName)
            );
            case INVALID_AMOUNT, BALANCE_OVERFLOW, NONE -> messageService.send(
                sender,
                "command.points-transaction-failed",
                Placeholder.unparsed("reason", String.valueOf(result.failureReason())),
                Placeholder.unparsed("balance", String.valueOf(result.previousBalance()))
            );
        }
    }

    private void persistRegions(CommandSender sender, String regionName, UnaryOperator<RegionConfig> updater) {
        configManager.updateRegionsAsync(updater, scheduler.asyncExecutor())
                .whenComplete((ignored, error) -> replyOnSenderThread(sender, () -> {
                    if (error == null) {
                        messageService.send(sender, "command.region-updated",
                                Placeholder.unparsed("region", regionName));
                    } else {
                        Throwable cause = unwrapCause(error);
                        logger.log(Level.WARNING, "Failed to update region " + regionName, cause);
                        messageService.send(sender, "command.reload-failed",
                                Placeholder.unparsed("error", formatErrorDetail(cause)));
                    }
                }));
    }

    private static Throwable unwrapCause(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static String formatErrorDetail(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void replyOnSenderThread(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            scheduler.runAtEntity(player, action);
        } else {
            scheduler.runGlobal(action);
        }
    }

    private static ArenaRegion recenterArena(ArenaRegion current, double x, double y, double z) {
        Point3D newCenter = new Point3D(x, y, z);
        Point3D newMin = shiftPoint(current.min(), current.center(), newCenter);
        Point3D newMax = shiftPoint(current.max(), current.center(), newCenter);
        int minY = current.minY();
        int maxY = current.maxY();
        if (current.shape() == Shape.CUBOID) {
            minY = (int) Math.floor(newMin.y());
            maxY = (int) Math.ceil(newMax.y());
        }
        return new ArenaRegion(current.shape(), newCenter, current.radius(), newMin, newMax, minY, maxY);
    }

    private static SafeZoneRegion recenterSafeZone(
            SafeZoneRegion current, double x, double y, double z, float yaw, float pitch) {
        Point3D newCenter = new Point3D(x, y, z);
        Point3D newMin = shiftPoint(current.min(), current.center(), newCenter);
        Point3D newMax = shiftPoint(current.max(), current.center(), newCenter);
        SpawnPoint newSpawn = new SpawnPoint(x, y, z, yaw, pitch);
        int minY = current.minY();
        int maxY = current.maxY();
        if (current.shape() == Shape.CUBOID) {
            minY = (int) Math.floor(newMin.y());
            maxY = (int) Math.ceil(newMax.y());
        }
        return new SafeZoneRegion(
            current.shape(),
            newCenter,
            current.radius(),
            newMin,
            newMax,
            minY,
            maxY,
            newSpawn
        );
    }

    private static @Nullable Point3D shiftPoint(@Nullable Point3D point, Point3D oldOrigin, Point3D newOrigin) {
        if (point == null) {
            return null;
        }
        return new Point3D(
            newOrigin.x() + (point.x() - oldOrigin.x()),
            newOrigin.y() + (point.y() - oldOrigin.y()),
            newOrigin.z() + (point.z() - oldOrigin.z())
        );
    }

    private static String formatArena(ArenaRegion arena) {
        return formatGeometry(
                arena.shape(),
                arena.center(),
                arena.radius(),
                arena.min(),
                arena.max(),
                arena.minY(),
                arena.maxY()
        );
    }

    private static String formatSafeZone(SafeZoneRegion safeZone, double voidY) {
        String base = formatGeometry(
                safeZone.shape(),
                safeZone.center(),
                safeZone.radius(),
                safeZone.min(),
                safeZone.max(),
                safeZone.minY(),
                safeZone.maxY()
        );
        return base + String.format(
            Locale.ROOT,
            " spawn=(%.1f, %.1f, %.1f) voidY=%.1f",
            safeZone.spawnPoint().x(),
            safeZone.spawnPoint().y(),
            safeZone.spawnPoint().z(),
            voidY
        );
    }

    private static String formatGeometry(
            Shape shape,
            Point3D center,
            double radius,
            Point3D min,
            Point3D max,
            int minY,
            int maxY) {
        if (shape == Shape.CYLINDER) {
            return String.format(
                Locale.ROOT,
                "CYLINDER center=(%.1f, %.1f, %.1f) r=%.1f y=[%d..%d]",
                center.x(),
                center.y(),
                center.z(),
                radius,
                minY,
                maxY
            );
        }
        return String.format(
            Locale.ROOT,
            "CUBOID min=(%.1f, %.1f, %.1f) max=(%.1f, %.1f, %.1f)",
            min.x(),
            min.y(),
            min.z(),
            max.x(),
            max.y(),
            max.z()
        );
    }

    private static String formatDuration(@Nullable Long durationMs) {
        if (durationMs == null) {
            return "--:--";
        }
        long totalSeconds = durationMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long millis = (durationMs % 1000L) / 10L;
        return String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, millis);
    }

    private static String formatPoints(long virtualPoints, Map<String, Long> currencies) {
        if (currencies == null || currencies.isEmpty()) {
            return String.valueOf(virtualPoints);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(virtualPoints);
        sb.append(" (");
        boolean first = true;
        for (Map.Entry<String, Long> entry : currencies.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        sb.append(')');
        return sb.toString();
    }

    private record PlayerIdentity(UUID uuid, String name, boolean online) {
    }

    private record StatsSnapshot(PlayerRaidStats stats, Map<String, Long> currencies) {
    }

    private record PointsUpdateOutcome(String playerName, PointTransactionResult result) {
    }
}
