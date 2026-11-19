package net.sabafly.dragonraid.command;

import net.sabafly.dragonraid.api.achievement.AchievementTriggerType;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.pack.ContentPackLoadReport;
import net.sabafly.dragonraid.api.pack.ContentPackLoader;
import net.sabafly.dragonraid.api.pack.LoadedContentPack;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.progression.VanillaProgressionService;
import net.sabafly.dragonraid.api.reward.ContentUnlockGrant;
import net.sabafly.dragonraid.api.reward.CurrencyGrant;
import net.sabafly.dragonraid.api.reward.CustomItemGrant;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardGrant;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.ScriptHookGrant;
import net.sabafly.dragonraid.api.reward.VanillaItemGrant;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.config.RegionConfig.ArenaRegion;
import net.sabafly.dragonraid.config.RegionConfig.Point3D;
import net.sabafly.dragonraid.config.RegionConfig.SafeZoneRegion;
import net.sabafly.dragonraid.config.RegionConfig.Shape;
import net.sabafly.dragonraid.config.RegionConfig.SpawnPoint;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.PlayerRaidStats;
import net.sabafly.dragonraid.storage.PointTransactionResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
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
    private final @Nullable RaidEncounterManager encounterManager;
    private final @Nullable ContentPackLoader contentPackLoader;
    private final @Nullable ContentUnlockService unlockService;
    private final @Nullable RaidAchievementService achievementService;
    private final @Nullable VanillaEndProgressionPolicy progressionPolicy;
    private final @Nullable VanillaProgressionService vanillaProgressionService;
    private final @Nullable RaidRewardService rewardService;

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
            name -> Optional.ofNullable(Bukkit.getPlayerExact(name)),
            null
        );
    }

    /**
     * Creates a command handler backed by Bukkit's online player registry and an active encounter manager.
     */
    public DefaultRaidCommandHandler(
        ConfigManager configManager,
        MessageService messageService,
        RaidScheduler scheduler,
        PlayerDataRepository repository,
        Logger logger,
        @Nullable RaidEncounterManager encounterManager
    ) {
        this(
            configManager,
            messageService,
            scheduler,
            repository,
            logger,
            name -> Optional.ofNullable(Bukkit.getPlayerExact(name)),
            encounterManager
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
        this(configManager, messageService, scheduler, repository, logger, onlinePlayerLookup, null);
    }

    /**
     * Full constructor injecting all dependencies including {@link RaidEncounterManager}.
     */
    public DefaultRaidCommandHandler(
        ConfigManager configManager,
        MessageService messageService,
        RaidScheduler scheduler,
        PlayerDataRepository repository,
        Logger logger,
        Function<String, Optional<Player>> onlinePlayerLookup,
        @Nullable RaidEncounterManager encounterManager
    ) {
        this(
            configManager,
            messageService,
            scheduler,
            repository,
            logger,
            onlinePlayerLookup,
            encounterManager,
            null,
            null,
            null,
            null,
            null
        );
    }

    /**
     * Full constructor including M2 Content Pack, Achievement, Unlock, and Vanilla Progression services.
     */
    public DefaultRaidCommandHandler(
        ConfigManager configManager,
        MessageService messageService,
        RaidScheduler scheduler,
        PlayerDataRepository repository,
        Logger logger,
        Function<String, Optional<Player>> onlinePlayerLookup,
        @Nullable RaidEncounterManager encounterManager,
        @Nullable ContentPackLoader contentPackLoader,
        @Nullable ContentUnlockService unlockService,
        @Nullable RaidAchievementService achievementService,
        @Nullable VanillaEndProgressionPolicy progressionPolicy,
        @Nullable VanillaProgressionService vanillaProgressionService
    ) {
        this(
            configManager,
            messageService,
            scheduler,
            repository,
            logger,
            onlinePlayerLookup,
            encounterManager,
            contentPackLoader,
            unlockService,
            achievementService,
            progressionPolicy,
            vanillaProgressionService,
            null
        );
    }

    /**
     * Full constructor including M2 Content Pack, Achievement, Unlock, Vanilla Progression, and Loot-Table Reward services.
     */
    public DefaultRaidCommandHandler(
        ConfigManager configManager,
        MessageService messageService,
        RaidScheduler scheduler,
        PlayerDataRepository repository,
        Logger logger,
        Function<String, Optional<Player>> onlinePlayerLookup,
        @Nullable RaidEncounterManager encounterManager,
        @Nullable ContentPackLoader contentPackLoader,
        @Nullable ContentUnlockService unlockService,
        @Nullable RaidAchievementService achievementService,
        @Nullable VanillaEndProgressionPolicy progressionPolicy,
        @Nullable VanillaProgressionService vanillaProgressionService,
        @Nullable RaidRewardService rewardService
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.onlinePlayerLookup = Objects.requireNonNull(onlinePlayerLookup, "onlinePlayerLookup");
        this.encounterManager = encounterManager;
        this.contentPackLoader = contentPackLoader;
        this.unlockService = unlockService;
        this.achievementService = achievementService;
        this.progressionPolicy = progressionPolicy;
        this.vanillaProgressionService = vanillaProgressionService;
        this.rewardService = rewardService;
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
                persistArenaRegions(sender, "arena", latest -> new RegionConfig(
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
    public void adminSetArenaRadius(CommandSender sender, double radius) {
        Objects.requireNonNull(sender, "sender");
        if (!Double.isFinite(radius) || radius <= 0.0) {
            messageService.send(
                    sender,
                    "command.region-invalid-arg",
                    Placeholder.unparsed("details", "radius must be > 0"));
            return;
        }
        persistArenaRegions(sender, "arena", latest -> {
            ArenaRegion cur = latest.arena();
            Point3D center = cur.center();
            ArenaRegion updated = new ArenaRegion(
                    Shape.CYLINDER,
                    center,
                    radius,
                    new Point3D(center.x() - radius, cur.minY(), center.z() - radius),
                    new Point3D(center.x() + radius, cur.maxY(), center.z() + radius),
                    cur.minY(),
                    cur.maxY());
            return new RegionConfig(updated, latest.safeZone(), latest.voidYThreshold());
        });
    }

    @Override
    public void adminSetVoidY(CommandSender sender, double voidY) {
        Objects.requireNonNull(sender, "sender");
        if (!Double.isFinite(voidY)) {
            messageService.send(
                    sender,
                    "command.region-invalid-arg",
                    Placeholder.unparsed("details", "void-y must be finite"));
            return;
        }
        persistRegions(sender, "void-y", latest -> new RegionConfig(
                latest.arena(),
                latest.safeZone(),
                voidY));
    }

    @Override
    public void adminSetArenaBounds(
            CommandSender sender,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        Objects.requireNonNull(sender, "sender");
        if (!areValidBounds(minX, minY, minZ, maxX, maxY, maxZ)) {
            messageService.send(
                    sender,
                    "command.region-invalid-arg",
                    Placeholder.unparsed("details", "min coordinates must be <= max coordinates"));
            return;
        }
        persistArenaRegions(sender, "arena", latest -> {
            Point3D min = new Point3D(minX, minY, minZ);
            Point3D max = new Point3D(maxX, maxY, maxZ);
            Point3D center = new Point3D((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
            ArenaRegion updated = new ArenaRegion(
                    Shape.CUBOID,
                    center,
                    latest.arena().radius(),
                    min,
                    max,
                    (int) Math.floor(minY),
                    (int) Math.ceil(maxY));
            return new RegionConfig(updated, latest.safeZone(), latest.voidYThreshold());
        });
    }

    @Override
    public void adminSetSafeZoneBounds(
            CommandSender sender,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        Objects.requireNonNull(sender, "sender");
        if (!areValidBounds(minX, minY, minZ, maxX, maxY, maxZ)) {
            messageService.send(
                    sender,
                    "command.region-invalid-arg",
                    Placeholder.unparsed("details", "min coordinates must be <= max coordinates"));
            return;
        }
        persistRegions(sender, "safe-zone", latest -> {
            Point3D min = new Point3D(minX, minY, minZ);
            Point3D max = new Point3D(maxX, maxY, maxZ);
            Point3D center = new Point3D((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
            SpawnPoint currentSpawn = latest.safeZone().spawnPoint();
            boolean spawnInside = currentSpawn.x() >= minX && currentSpawn.x() <= maxX
                    && currentSpawn.y() >= minY && currentSpawn.y() <= maxY
                    && currentSpawn.z() >= minZ && currentSpawn.z() <= maxZ;
            SpawnPoint spawn = spawnInside
                    ? currentSpawn
                    : new SpawnPoint(center.x(), Math.min(maxY, minY + 1.0), center.z(),
                            currentSpawn.yaw(), currentSpawn.pitch());
            SafeZoneRegion updated = new SafeZoneRegion(
                    Shape.CUBOID,
                    center,
                    latest.safeZone().radius(),
                    min,
                    max,
                    (int) Math.floor(minY),
                    (int) Math.ceil(maxY),
                    spawn);
            return new RegionConfig(latest.arena(), updated, latest.voidYThreshold());
        });
    }

    private static boolean areValidBounds(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                && minX <= maxX && minY <= maxY && minZ <= maxZ;
    }

    @Override
    public void adminStart(CommandSender sender, Difficulty difficulty) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(difficulty, "difficulty");
        if (encounterManager != null) {
            BossConfig.Difficulty mapped = difficulty == Difficulty.HARD
                    ? BossConfig.Difficulty.HARD
                    : BossConfig.Difficulty.NORMAL;
            encounterManager.adminStart(sender, mapped);
            return;
        }
        messageService.send(
            sender,
            "command.milestone-admin-start",
            Placeholder.unparsed("difficulty", difficulty.cliName())
        );
    }

    @Override
    public void adminReset(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (encounterManager != null) {
            encounterManager.adminReset(sender);
            return;
        }
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
        CompletableFuture.supplyAsync(() -> {
            configManager.reload();
            messageService.reload();
            return contentPackLoader != null ? contentPackLoader.reload() : null;
        }, scheduler.asyncExecutor()).whenComplete((report, error) -> replyOnSenderThread(sender, () -> {
            if (error == null) {
                if (report != null) {
                    messageService.send(
                        sender,
                        "command.reload-success-packs",
                        Placeholder.unparsed("packs", String.valueOf(report.loadedPacks().size())),
                        Placeholder.unparsed("contents", String.valueOf(report.totalContents())),
                        Placeholder.unparsed("achievements", String.valueOf(report.totalAchievements())),
                        Placeholder.unparsed("rewards", String.valueOf(report.totalRewardTables())),
                        Placeholder.unparsed("rules", String.valueOf(report.totalProgressionRules()))
                    );
                } else {
                    messageService.send(sender, "command.reload-success");
                }
            } else {
                Throwable cause = unwrapCause(error);
                logger.log(Level.WARNING, "Failed to reload DragonRaid configuration", cause);
                messageService.send(sender, "command.reload-failed",
                        Placeholder.unparsed("error", formatErrorDetail(cause)));
            }
        }));
    }

    @Override
    public void adminPacks(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (contentPackLoader == null) {
            messageService.send(sender, "command.packs-empty");
            return;
        }
        ContentPackLoadReport report = contentPackLoader.lastReport();
        if (report.loadedPacks().isEmpty()) {
            messageService.send(sender, "command.packs-empty");
        } else {
            messageService.send(
                sender,
                "command.packs-header",
                Placeholder.unparsed("count", String.valueOf(report.loadedPacks().size())),
                Placeholder.unparsed("skipped", String.valueOf(report.skippedPackIds().size()))
            );
            for (LoadedContentPack pack : report.loadedPacks()) {
                messageService.send(
                    sender,
                    "command.packs-entry",
                    Placeholder.unparsed("id", pack.manifest().id()),
                    Placeholder.unparsed("name", pack.manifest().name()),
                    Placeholder.unparsed("version", pack.manifest().version()),
                    Placeholder.unparsed("priority", String.valueOf(pack.manifest().priority())),
                    Placeholder.unparsed("contents", String.valueOf(pack.contents().size())),
                    Placeholder.unparsed("achievements", String.valueOf(pack.achievements().size())),
                    Placeholder.unparsed("rewards", String.valueOf(pack.rewardTables().size())),
                    Placeholder.unparsed("rules", String.valueOf(pack.progressionRules().size()))
                );
            }
        }
    }

    @Override
    public void adminUnlock(
            CommandSender sender,
            UnlockAction action,
            String targetPlayerName,
            @Nullable String contentId) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetPlayerName, "targetPlayerName");
        if (unlockService == null) {
            messageService.send(sender, "command.service-unavailable");
            return;
        }
        if (action != UnlockAction.LIST && (contentId == null || contentId.isBlank())) {
            messageService.send(
                sender,
                "command.invalid-usage",
                Placeholder.unparsed("usage", "/dr admin unlock <grant|revoke|list> <player> [contentId]")
            );
            return;
        }
        String normalizedContent = contentId == null ? null : contentId.trim();

        resolvePlayerIdentity(targetPlayerName)
            .whenComplete((identityOpt, error) -> {
                if (error != null) {
                    replyOnSenderThread(sender, () -> {
                        logger.log(Level.WARNING, "Failed to resolve player for unlock command: " + targetPlayerName, error);
                        messageService.send(sender, "command.unlock-failed");
                    });
                    return;
                }
                if (identityOpt.isEmpty()) {
                    replyOnSenderThread(sender, () -> messageService.send(
                        sender,
                        "command.player-not-found",
                        Placeholder.unparsed("target", targetPlayerName)
                    ));
                    return;
                }
                PlayerIdentity identity = identityOpt.get();
                switch (action) {
                    case LIST -> unlockService.loadPlayer(identity.uuid())
                        .whenComplete((dbUnlocks, loadErr) -> replyOnSenderThread(sender, () -> {
                            if (loadErr != null) {
                                logger.log(Level.WARNING, "Failed to list unlocks for " + identity.name(), loadErr);
                                messageService.send(sender, "command.unlock-failed");
                                return;
                            }
                            Set<String> combined = dbUnlocks == null
                                    ? Set.of()
                                    : new LinkedHashSet<>(dbUnlocks);
                            if (combined.isEmpty()) {
                                messageService.send(
                                    sender,
                                    "command.unlock-list-empty",
                                    Placeholder.unparsed("player", identity.name())
                                );
                            } else {
                                messageService.send(
                                    sender,
                                    "command.unlock-list",
                                    Placeholder.unparsed("player", identity.name()),
                                    Placeholder.unparsed("contents", String.join(", ", combined))
                                );
                            }
                        }));
                    case GRANT -> unlockService.unlockAsync(identity.uuid(), identity.name(), normalizedContent)
                        .whenComplete((newlyUnlocked, grantErr) -> replyOnSenderThread(sender, () -> {
                            if (grantErr != null) {
                                logger.log(Level.WARNING, "Failed to grant unlock for " + identity.name(), grantErr);
                                messageService.send(sender, "command.unlock-failed");
                                return;
                            }
                            messageService.send(
                                sender,
                                Boolean.TRUE.equals(newlyUnlocked) ? "command.unlock-granted" : "command.unlock-already",
                                Placeholder.unparsed("player", identity.name()),
                                Placeholder.unparsed("content", normalizedContent)
                            );
                        }));
                    case REVOKE -> unlockService.revokeAsync(identity.uuid(), normalizedContent)
                        .whenComplete((removed, revokeErr) -> replyOnSenderThread(sender, () -> {
                            if (revokeErr != null) {
                                logger.log(Level.WARNING, "Failed to revoke unlock for " + identity.name(), revokeErr);
                                messageService.send(sender, "command.unlock-failed");
                                return;
                            }
                            messageService.send(
                                sender,
                                Boolean.TRUE.equals(removed) ? "command.unlock-revoked" : "command.unlock-not-found",
                                Placeholder.unparsed("player", identity.name()),
                                Placeholder.unparsed("content", normalizedContent)
                            );
                        }));
                }
            });
    }

    @Override
    public void adminAchievement(
            CommandSender sender,
            AchievementAction action,
            String targetPlayerName,
            @Nullable String achievementId) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetPlayerName, "targetPlayerName");
        if (achievementService == null) {
            messageService.send(sender, "command.service-unavailable");
            return;
        }
        if (action == AchievementAction.GRANT) {
            if (achievementId == null || achievementId.isBlank()) {
                messageService.send(
                    sender,
                    "command.invalid-usage",
                    Placeholder.unparsed("usage", "/dr admin achievement grant <player> <achievementId>")
                );
                return;
            }
            if (achievementService.getDefinition(achievementId.trim()).isEmpty()) {
                messageService.send(
                    sender,
                    "command.achievement-unknown",
                    Placeholder.unparsed("achievement", achievementId.trim())
                );
                return;
            }
        }
        String normalizedAchievement = achievementId == null ? null : achievementId.trim();

        resolvePlayerIdentity(targetPlayerName)
            .whenComplete((identityOpt, error) -> {
                if (error != null) {
                    replyOnSenderThread(sender, () -> {
                        logger.log(Level.WARNING, "Failed to resolve player for achievement command: " + targetPlayerName, error);
                        messageService.send(sender, "command.achievement-failed");
                    });
                    return;
                }
                if (identityOpt.isEmpty()) {
                    replyOnSenderThread(sender, () -> messageService.send(
                        sender,
                        "command.player-not-found",
                        Placeholder.unparsed("target", targetPlayerName)
                    ));
                    return;
                }
                PlayerIdentity identity = identityOpt.get();
                switch (action) {
                    case LIST -> repository.loadAchievements(identity.uuid())
                        .whenComplete((dbAch, loadErr) -> replyOnSenderThread(sender, () -> {
                            if (loadErr != null) {
                                logger.log(Level.WARNING, "Failed to list achievements for " + identity.name(), loadErr);
                                messageService.send(sender, "command.achievement-failed");
                                return;
                            }
                            Set<String> combined = new LinkedHashSet<>(achievementService.unlockedFor(identity.uuid()));
                            if (dbAch != null) {
                                combined.addAll(dbAch);
                            }
                            if (combined.isEmpty()) {
                                messageService.send(
                                    sender,
                                    "command.achievement-list-empty",
                                    Placeholder.unparsed("player", identity.name())
                                );
                            } else {
                                messageService.send(
                                    sender,
                                    "command.achievement-list",
                                    Placeholder.unparsed("player", identity.name()),
                                    Placeholder.unparsed("achievements", String.join(", ", combined))
                                );
                            }
                        }));
                    case GRANT -> achievementService.loadPlayer(identity.uuid())
                        .thenCompose(ignored -> achievementService.grantAchievement(
                            identity.uuid(),
                            identity.name(),
                            normalizedAchievement,
                            AchievementTriggerType.CUSTOM_SCRIPT,
                            false,
                            Map.of("grantedBy", sender.getName() != null ? sender.getName() : "CONSOLE")
                        ))
                        .whenComplete((unlockedOpt, grantErr) -> replyOnSenderThread(sender, () -> {
                            if (grantErr != null) {
                                logger.log(Level.WARNING, "Failed to grant achievement for " + identity.name(), grantErr);
                                messageService.send(sender, "command.achievement-failed");
                                return;
                            }
                            messageService.send(
                                sender,
                                unlockedOpt.isPresent() ? "command.achievement-granted" : "command.achievement-already",
                                Placeholder.unparsed("player", identity.name()),
                                Placeholder.unparsed("achievement", normalizedAchievement)
                            );
                        }));
                }
            });
    }

    @Override
    public void adminProgressionStatus(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        CompletableFuture<Boolean> killedFuture = vanillaProgressionService != null
                ? vanillaProgressionService.isVanillaDragonPreviouslyKilled(null)
                : CompletableFuture.completedFuture(false);
        CompletableFuture<Integer> gatewaysFuture = vanillaProgressionService != null
                ? vanillaProgressionService.getEndGatewayCount(null)
                : CompletableFuture.completedFuture(0);

        killedFuture.thenCombine(gatewaysFuture, (killed, gateways) -> Map.entry(killed, gateways))
            .whenComplete((vanillaState, error) -> replyOnSenderThread(sender, () -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Failed to read vanilla progression status", error);
                    messageService.send(sender, "command.progression-failed");
                    return;
                }
                int ruleCount = progressionPolicy != null ? progressionPolicy.rules().size() : 0;
                messageService.send(
                    sender,
                    "command.progression-header",
                    Placeholder.unparsed("rules", String.valueOf(ruleCount)),
                    Placeholder.unparsed("killed", String.valueOf(vanillaState.getKey())),
                    Placeholder.unparsed("gateways", String.valueOf(vanillaState.getValue()))
                );
                if (progressionPolicy != null) {
                    for (VanillaProgressionRule rule : progressionPolicy.rules()) {
                        String gateSummary = rule.entryGate() != null
                                ? rule.entryGate().mode().name() + "(" + rule.entryGate().targetId() + ")"
                                : "NONE";
                        boolean satisfied = rule.entryGate() != null
                                && !rule.entryGate().targetId().isBlank()
                                && progressionPolicy.isServerMilestoneAchieved(rule.entryGate().targetId());
                        messageService.send(
                            sender,
                            "command.progression-rule",
                            Placeholder.unparsed("id", rule.id()),
                            Placeholder.unparsed("priority", String.valueOf(rule.priority())),
                            Placeholder.unparsed("gate", gateSummary),
                            Placeholder.unparsed("milestone_achieved", String.valueOf(satisfied)),
                            Placeholder.unparsed("hooks", String.valueOf(rule.clearHooks().size()))
                        );
                    }
                }
            }));
    }

    @Override
    public void adminReward(
            CommandSender sender,
            RewardAction action,
            @Nullable String tableId,
            @Nullable String targetPlayerName,
            @Nullable Long seed) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(action, "action");
        if (rewardService == null) {
            messageService.send(sender, "command.service-unavailable");
            return;
        }
        if (action == RewardAction.LIST) {
            var tables = rewardService.tables();
            if (tables.isEmpty()) {
                messageService.send(sender, "command.reward-list-empty");
                return;
            }
            messageService.send(
                sender,
                "command.reward-list-header",
                Placeholder.unparsed("count", String.valueOf(tables.size())),
                Placeholder.unparsed("extensions", String.valueOf(rewardService.extensions().size()))
            );
            for (RewardTable table : tables) {
                long extCount = rewardService.extensions().stream()
                        .filter(ext -> ext.targetTableId().equals(table.id()))
                        .count();
                messageService.send(
                    sender,
                    "command.reward-list-entry",
                    Placeholder.unparsed("id", table.id()),
                    Placeholder.unparsed("pools", String.valueOf(table.pools().size())),
                    Placeholder.unparsed("extensions", String.valueOf(extCount))
                );
            }
            return;
        }

        if (tableId == null || tableId.isBlank() || targetPlayerName == null || targetPlayerName.isBlank()) {
            messageService.send(
                sender,
                "command.invalid-usage",
                Placeholder.unparsed("usage", "/dr admin reward <list|simulate|grant> <tableId> <player> [seed]")
            );
            return;
        }
        String normalizedTableId = tableId.trim();
        Map<String, RewardTable> tableSnapshot = rewardService.effectiveTablesSnapshot();
        if (!tableSnapshot.containsKey(normalizedTableId)) {
            messageService.send(
                sender,
                "command.reward-unknown",
                Placeholder.unparsed("table_id", normalizedTableId)
            );
            return;
        }

        resolvePlayerIdentity(targetPlayerName)
            .whenComplete((identityOpt, error) -> {
                if (error != null) {
                    replyOnSenderThread(sender, () -> {
                        logger.log(Level.WARNING, "Failed to resolve player for reward command: " + targetPlayerName, error);
                        messageService.send(sender, "command.reward-failed");
                    });
                    return;
                }
                if (identityOpt.isEmpty()) {
                    replyOnSenderThread(sender, () -> messageService.send(
                        sender,
                        "command.player-not-found",
                        Placeholder.unparsed("target", targetPlayerName)
                    ));
                    return;
                }
                PlayerIdentity identity = identityOpt.get();
                UUID playerId = identity.uuid();

                CompletableFuture<Set<String>> claimedFuture = repository.loadClaimedRewards(playerId);
                CompletableFuture<Set<String>> achFuture = repository.loadAchievements(playerId);
                CompletableFuture<Set<String>> unlockFuture = repository.loadUnlockedContents(playerId);

                CompletableFuture.allOf(claimedFuture, achFuture, unlockFuture)
                    .thenApply(ignored -> RewardContext.builder(playerId)
                            .playerName(identity.name())
                            .sourceType(action == RewardAction.SIMULATE ? "ADMIN_SIMULATE" : "ADMIN_GRANT")
                            .sourceId(normalizedTableId)
                            .claimedKeys(claimedFuture.join())
                            .unlockedAchievements(achFuture.join())
                            .unlockedContents(unlockFuture.join())
                            .build())
                    .thenCompose(ctx -> {
                        if (action == RewardAction.SIMULATE) {
                            RandomGenerator rng = seed != null ? new Random(seed) : ThreadLocalRandom.current();
                            RewardBundle evaluated = rewardService.evaluate(tableSnapshot, normalizedTableId, ctx, rng);
                            return CompletableFuture.completedFuture(RewardCommandOutcome.simulated(evaluated));
                        }
                        RewardBundle evaluated = rewardService.evaluate(
                                tableSnapshot, normalizedTableId, ctx, ThreadLocalRandom.current());
                        return rewardService.apply(evaluated, ctx)
                                .thenApply(RewardCommandOutcome::applied);
                    })
                    .whenComplete((outcome, rewardErr) -> replyOnSenderThread(sender, () -> {
                        if (rewardErr != null) {
                            logger.log(Level.WARNING, "Failed reward command for " + identity.name(), rewardErr);
                            messageService.send(sender, "command.reward-failed");
                            return;
                        }
                        if (action == RewardAction.SIMULATE) {
                            RewardBundle bundle = outcome.bundle();
                            if (bundle.isEmpty()) {
                                messageService.send(
                                    sender,
                                    "command.reward-simulate-empty",
                                    Placeholder.unparsed("table_id", normalizedTableId),
                                    Placeholder.unparsed("player", identity.name())
                                );
                            } else {
                                messageService.send(
                                    sender,
                                    "command.reward-simulate-result",
                                    Placeholder.unparsed("table_id", normalizedTableId),
                                    Placeholder.unparsed("player", identity.name()),
                                    Placeholder.unparsed("seed", seed != null ? seed.toString() : "random"),
                                    Placeholder.unparsed("grants", formatGrantSummary(bundle.grants())),
                                    Placeholder.unparsed("claims", bundle.claimKeys().isEmpty()
                                            ? "none"
                                            : String.join(", ", bundle.claimKeys()))
                                );
                            }
                        } else {
                            RewardApplyResult result = outcome.applyResult();
                            messageService.send(
                                sender,
                                "command.reward-grant-result",
                                Placeholder.unparsed("table_id", normalizedTableId),
                                Placeholder.unparsed("player", identity.name()),
                                Placeholder.unparsed("applied", String.valueOf(result.appliedGrants().size())),
                                Placeholder.unparsed("skipped_claims", String.valueOf(result.skippedClaimKeys().size())),
                                Placeholder.unparsed("grants", formatGrantSummary(result.appliedGrants()))
                            );
                        }
                    }));
            });
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

    private void persistArenaRegions(CommandSender sender, String regionName, UnaryOperator<RegionConfig> updater) {
        if (encounterManager != null && !encounterManager.tryBeginArenaRegionUpdate()) {
            messageService.send(sender, "command.region-locked-during-raid");
            return;
        }
        UnaryOperator<RegionConfig> guardedUpdater = latest -> {
            if (encounterManager != null && !encounterManager.canModifyArenaRegion()) {
                throw new ArenaRegionLockedException();
            }
            return updater.apply(latest);
        };
        configManager.updateRegionsAsync(guardedUpdater, scheduler.asyncExecutor())
                .whenComplete((ignored, error) -> {
                    if (encounterManager != null) {
                        encounterManager.endArenaRegionUpdate();
                    }
                    replyOnSenderThread(sender, () -> {
                        if (error == null) {
                            messageService.send(sender, "command.region-updated",
                                    Placeholder.unparsed("region", regionName));
                        } else {
                            Throwable cause = unwrapCause(error);
                            if (cause instanceof ArenaRegionLockedException) {
                                messageService.send(sender, "command.region-locked-during-raid");
                                return;
                            }
                            logger.log(Level.WARNING, "Failed to update region " + regionName, cause);
                            messageService.send(sender, "command.reload-failed",
                                    Placeholder.unparsed("error", formatErrorDetail(cause)));
                        }
                    });
                });
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

    private static final class ArenaRegionLockedException extends RuntimeException {
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

    private static String formatGrantSummary(List<RewardGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < grants.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            RewardGrant grant = grants.get(i);
            switch (grant) {
                case CurrencyGrant cg -> sb.append("CURRENCY:").append(cg.currencyId()).append(" x").append(cg.amount());
                case VanillaItemGrant vg -> sb.append("VANILLA_ITEM:").append(vg.materialName()).append(" x").append(vg.amount());
                case CustomItemGrant ig -> sb.append("CUSTOM_ITEM:").append(ig.customItemId()).append(" x").append(ig.amount());
                case ContentUnlockGrant ug -> sb.append("UNLOCK_CONTENT:").append(ug.contentId());
                case ScriptHookGrant sg -> sb.append("SCRIPT_HOOK:").append(sg.hookId());
            }
        }
        return sb.toString();
    }

    private record RewardCommandOutcome(@Nullable RewardBundle bundle, @Nullable RewardApplyResult applyResult) {
        static RewardCommandOutcome simulated(RewardBundle bundle) {
            return new RewardCommandOutcome(bundle, null);
        }

        static RewardCommandOutcome applied(RewardApplyResult applyResult) {
            return new RewardCommandOutcome(null, applyResult);
        }
    }

    private record PlayerIdentity(UUID uuid, String name, boolean online) {
    }

    private record StatsSnapshot(PlayerRaidStats stats, Map<String, Long> currencies) {
    }

    private record PointsUpdateOutcome(String playerName, PointTransactionResult result) {
    }
}
