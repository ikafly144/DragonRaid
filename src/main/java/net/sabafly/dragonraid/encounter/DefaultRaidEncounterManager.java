package net.sabafly.dragonraid.encounter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.EncounterSettings;
import net.sabafly.dragonraid.config.PluginConfig.ProtectionSettings;
import net.sabafly.dragonraid.config.PluginConfig.ReviveSettings;
import net.sabafly.dragonraid.config.PluginConfig.ScalingSettings;
import net.sabafly.dragonraid.gimmick.NormalGimmickExecutor;
import net.sabafly.dragonraid.gimmick.RaidCrystalManager;
import net.sabafly.dragonraid.hud.RaidHudService;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.protection.BlockPosKey;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.scaling.RaidScalingCalculator;
import net.sabafly.dragonraid.scaling.ScaledEncounterParameters;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.storage.Difficulty;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingBlockRollback;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingPlayerCleanup;
import net.sabafly.dragonraid.timeline.RaidTimelineEngine;
import net.sabafly.dragonraid.timeline.TimelineListener;
import net.sabafly.dragonraid.visual.SoulMarkerService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe {@link RaidEncounterManager} coordinating pull countdowns, late-joiner locks,
 * downed/revive mechanics, wipe detection, temporary block rollback, and disconnect/rejoin restoration.
 */
public final class DefaultRaidEncounterManager implements RaidEncounterManager {

    private static final long RIGHT_CLICK_HOLD_WINDOW_MS = 350L;
    private static final long FEEDBACK_COOLDOWN_MS = 2000L;
    private static final double MONITOR_STEP_SECONDS = 0.1; // 2 ticks

    private final Object stateLock = new Object();

    private final ConfigManager configManager;
    private final MessageService messageService;
    private final RaidScheduler scheduler;
    private final RaidRegionService regionService;
    private final TemporaryBlockTracker blockTracker;
    private final SoulMarkerService soulMarkerService;
    private final @Nullable RaidRecoveryRepository recoveryRepository;
    private final @Nullable PlayerDataRepository playerDataRepository;
    private final @Nullable DragonBossController bossController;
    private final @Nullable RaidHudService hudService;
    private final @Nullable RaidCrystalManager crystalManager;
    private final @Nullable NormalGimmickExecutor gimmickExecutor;
    private final @Nullable RaidInstanceManager instanceManager;
    private final Logger logger;
    private final Supplier<@Nullable World> endWorldSupplier;
    private final Supplier<Collection<? extends Player>> onlinePlayersSupplier;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final LongSupplier clockMillis;
    private final Predicate<World> livingDragonPredicate;

    private volatile RaidState state = RaidState.IDLE;
    private volatile @Nullable BossConfig.Difficulty activeDifficulty;
    private volatile @Nullable BossConfig activeBossConfig;
    private volatile @Nullable ScaledEncounterParameters activeScaling;
    private volatile @Nullable RaidTimelineEngine activeTimeline;
    private volatile @Nullable UUID announcedFirstClearDragonId;
    private final AtomicInteger remainingRevives = new AtomicInteger(0);
    private final AtomicLong encounterGeneration = new AtomicLong(0L);
    private final AtomicInteger arenaUpdatesInFlight = new AtomicInteger(0);
    private volatile long combatStartedMillis;

    private final Set<UUID> readyPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RaidParticipant> participants = new ConcurrentHashMap<>();
    private final Map<UUID, SavedCleanupRecord> pendingCleanupPlayers = new ConcurrentHashMap<>();
    private final Map<BlockPosKey, PendingBlockRollback> unrecoveredPersistedRollbacks = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRightClickMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFeedbackMillis = new ConcurrentHashMap<>();

    private volatile @Nullable TaskHandle countdownTask;
    private volatile @Nullable TaskHandle monitorTask;
    private volatile @Nullable TaskHandle wipeTask;

    public DefaultRaidEncounterManager(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            TemporaryBlockTracker blockTracker,
            SoulMarkerService soulMarkerService,
            Logger logger) {
        this(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                logger
        );
    }

    public DefaultRaidEncounterManager(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            TemporaryBlockTracker blockTracker,
            SoulMarkerService soulMarkerService,
            @Nullable RaidRecoveryRepository recoveryRepository,
            Logger logger) {
        this(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                recoveryRepository,
                null,
                null,
                null,
                null,
                null,
                logger,
                () -> Bukkit.getWorld(configManager.pluginConfig().world().endWorldName()),
                Bukkit::getOnlinePlayers,
                Bukkit::getPlayer,
                System::currentTimeMillis,
                DefaultRaidEncounterManager::hasLivingDragonInWorld
        );
    }

    public DefaultRaidEncounterManager(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            TemporaryBlockTracker blockTracker,
            SoulMarkerService soulMarkerService,
            @Nullable RaidRecoveryRepository recoveryRepository,
            @Nullable PlayerDataRepository playerDataRepository,
            @Nullable DragonBossController bossController,
            @Nullable RaidHudService hudService,
            @Nullable RaidCrystalManager crystalManager,
            @Nullable NormalGimmickExecutor gimmickExecutor,
            Logger logger) {
        this(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                recoveryRepository,
                playerDataRepository,
                bossController,
                hudService,
                crystalManager,
                gimmickExecutor,
                null,
                logger
        );
    }

    public DefaultRaidEncounterManager(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            TemporaryBlockTracker blockTracker,
            SoulMarkerService soulMarkerService,
            @Nullable RaidRecoveryRepository recoveryRepository,
            @Nullable PlayerDataRepository playerDataRepository,
            @Nullable DragonBossController bossController,
            @Nullable RaidHudService hudService,
            @Nullable RaidCrystalManager crystalManager,
            @Nullable NormalGimmickExecutor gimmickExecutor,
            @Nullable RaidInstanceManager instanceManager,
            Logger logger) {
        this(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                recoveryRepository,
                playerDataRepository,
                bossController,
                hudService,
                crystalManager,
                gimmickExecutor,
                instanceManager,
                logger,
                () -> Bukkit.getWorld(configManager.pluginConfig().world().endWorldName()),
                Bukkit::getOnlinePlayers,
                Bukkit::getPlayer,
                System::currentTimeMillis,
                DefaultRaidEncounterManager::hasLivingDragonInWorld
        );
    }

    DefaultRaidEncounterManager(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            TemporaryBlockTracker blockTracker,
            SoulMarkerService soulMarkerService,
            @Nullable RaidRecoveryRepository recoveryRepository,
            Logger logger,
            Supplier<@Nullable World> endWorldSupplier,
            Supplier<Collection<? extends Player>> onlinePlayersSupplier,
            Function<UUID, @Nullable Player> playerLookup,
            LongSupplier clockMillis,
            Predicate<World> livingDragonPredicate) {
        this(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                recoveryRepository,
                null,
                null,
                null,
                null,
                null,
                logger,
                endWorldSupplier,
                onlinePlayersSupplier,
                playerLookup,
                clockMillis,
                livingDragonPredicate
        );
    }

    DefaultRaidEncounterManager(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            TemporaryBlockTracker blockTracker,
            SoulMarkerService soulMarkerService,
            @Nullable RaidRecoveryRepository recoveryRepository,
            @Nullable PlayerDataRepository playerDataRepository,
            @Nullable DragonBossController bossController,
            @Nullable RaidHudService hudService,
            @Nullable RaidCrystalManager crystalManager,
            @Nullable NormalGimmickExecutor gimmickExecutor,
            Logger logger,
            Supplier<@Nullable World> endWorldSupplier,
            Supplier<Collection<? extends Player>> onlinePlayersSupplier,
            Function<UUID, @Nullable Player> playerLookup,
            LongSupplier clockMillis,
            Predicate<World> livingDragonPredicate) {
        this(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                recoveryRepository,
                playerDataRepository,
                bossController,
                hudService,
                crystalManager,
                gimmickExecutor,
                null,
                logger,
                endWorldSupplier,
                onlinePlayersSupplier,
                playerLookup,
                clockMillis,
                livingDragonPredicate
        );
    }

    DefaultRaidEncounterManager(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            TemporaryBlockTracker blockTracker,
            SoulMarkerService soulMarkerService,
            @Nullable RaidRecoveryRepository recoveryRepository,
            @Nullable PlayerDataRepository playerDataRepository,
            @Nullable DragonBossController bossController,
            @Nullable RaidHudService hudService,
            @Nullable RaidCrystalManager crystalManager,
            @Nullable NormalGimmickExecutor gimmickExecutor,
            @Nullable RaidInstanceManager instanceManager,
            Logger logger,
            Supplier<@Nullable World> endWorldSupplier,
            Supplier<Collection<? extends Player>> onlinePlayersSupplier,
            Function<UUID, @Nullable Player> playerLookup,
            LongSupplier clockMillis,
            Predicate<World> livingDragonPredicate) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.regionService = Objects.requireNonNull(regionService, "regionService");
        this.blockTracker = Objects.requireNonNull(blockTracker, "blockTracker");
        this.soulMarkerService = Objects.requireNonNull(soulMarkerService, "soulMarkerService");
        this.recoveryRepository = recoveryRepository;
        this.playerDataRepository = playerDataRepository;
        this.bossController = bossController;
        this.hudService = hudService;
        this.crystalManager = crystalManager;
        this.gimmickExecutor = gimmickExecutor;
        this.instanceManager = instanceManager;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.endWorldSupplier = Objects.requireNonNull(endWorldSupplier, "endWorldSupplier");
        this.onlinePlayersSupplier = Objects.requireNonNull(onlinePlayersSupplier, "onlinePlayersSupplier");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.livingDragonPredicate = Objects.requireNonNull(livingDragonPredicate, "livingDragonPredicate");
        if (this.crystalManager != null && this.bossController != null) {
            this.crystalManager.setOnAllCrystalsDestroyedCallback(() -> this.bossController.stagger(5.0));
        }
        loadPendingCleanupsFromStorage();
    }

    /**
     * Loads any pending temporary block rollbacks persisted prior to a restart/reload and
     * dispatches chunk-scoped restorations via {@link RaidScheduler}, removing restored rows.
     */
    public void recoverPendingBlockRollbacks(Function<UUID, @Nullable World> worldLookup) {
        Objects.requireNonNull(worldLookup, "worldLookup");
        if (recoveryRepository == null) {
            return;
        }
        List<PendingBlockRollback> pending = recoveryRepository.loadPendingBlockRollbacksSync();
        if (pending.isEmpty()) {
            return;
        }
        if (!configManager.pluginConfig().protection().rollbackTemporaryBlocksOnEnd()) {
            recoveryRepository.replacePendingBlockRollbacksSync(List.of());
            return;
        }
        for (PendingBlockRollback entry : pending) {
            unrecoveredPersistedRollbacks.putIfAbsent(entry.toPosKey(), entry);
        }
        blockTracker.rollbackPendingEntries(scheduler, worldLookup, pending, restoredKeys -> {
                    for (BlockPosKey key : restoredKeys) {
                        unrecoveredPersistedRollbacks.remove(key);
                    }
                    recoveryRepository.deletePendingBlockRollbacksAsync(restoredKeys);
                })
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Failed to recover pending temporary block rollbacks", ex);
                    return List.of();
                });
    }

    @Override
    public RaidState state() {
        return state;
    }

    @Override
    public boolean isProtectionActive() {
        RaidState current = state;
        return current == RaidState.COUNTDOWN
                || current == RaidState.ACTIVE
                || current == RaidState.WIPING
                || current == RaidState.ENDING;
    }

    @Override
    public boolean canModifyArenaRegion() {
        synchronized (stateLock) {
            return state == RaidState.IDLE
                    && blockTracker.totalCount() == 0
                    && unrecoveredPersistedRollbacks.isEmpty();
        }
    }

    @Override
    public boolean tryBeginArenaRegionUpdate() {
        synchronized (stateLock) {
            if (!canModifyArenaRegion()) {
                return false;
            }
            arenaUpdatesInFlight.incrementAndGet();
            return true;
        }
    }

    @Override
    public void endArenaRegionUpdate() {
        arenaUpdatesInFlight.updateAndGet(v -> Math.max(0, v - 1));
    }

    @Override
    public Optional<ScaledEncounterParameters> activeScaling() {
        return Optional.ofNullable(activeScaling);
    }

    @Override
    public int remainingRevives() {
        return remainingRevives.get();
    }

    @Override
    public boolean isParticipant(UUID playerId) {
        return participants.containsKey(playerId);
    }

    @Override
    public Optional<ParticipantState> participantState(UUID playerId) {
        RaidParticipant p = participants.get(playerId);
        return p == null ? Optional.empty() : Optional.of(p.state());
    }

    @Override
    public boolean isDowned(UUID playerId) {
        RaidParticipant p = participants.get(playerId);
        return p != null && p.state() == ParticipantState.DOWNED;
    }

    @Override
    public boolean isDeadSpectator(UUID playerId) {
        RaidParticipant p = participants.get(playerId);
        return p != null && p.state() == ParticipantState.DEAD_SPECTATOR;
    }

    @Override
    public void toggleReady(Player player) {
        Objects.requireNonNull(player, "player");
        if (configManager.pluginConfig().encounter().altarGuiOnly()) {
            messageService.send(player, "command.altar-only-mode");
            return;
        }
        if (state != RaidState.IDLE) {
            messageService.send(player, "command.ready-not-available");
            return;
        }
        UUID id = player.getUniqueId();
        boolean nowReady;
        if (readyPlayers.contains(id)) {
            readyPlayers.remove(id);
            nowReady = false;
        } else {
            readyPlayers.add(id);
            nowReady = true;
        }
        String stateKey = nowReady ? "command.ready-state-true" : "command.ready-state-false";
        Component stateComp = messageService.render(player, stateKey);
        messageService.send(player, "command.ready-toggled", Placeholder.component("state", stateComp));
    }

    @Override
    public void triggerStart(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (configManager.pluginConfig().encounter().altarGuiOnly()) {
            messageService.send(sender, "command.altar-only-mode");
            return;
        }
        startPull(sender, BossConfig.Difficulty.NORMAL, false, false);
    }

    @Override
    public boolean startAltarPull(Player initiator, BossConfig.Difficulty difficulty) {
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(difficulty, "difficulty");
        return startPull(initiator, difficulty, true, false);
    }

    @Override
    public boolean isFirstClearEncounterAvailable() {
        if (state != RaidState.IDLE || !configManager.pluginConfig().world().replaceFirstDragon()) {
            return false;
        }
        World endWorld = endWorldSupplier.get();
        if (endWorld == null || !livingDragonPredicate.test(endWorld)) {
            return false;
        }
        DragonBattle battle = endWorld.getEnderDragonBattle();
        return battle == null || !battle.hasBeenPreviouslyKilled();
    }

    @Override
    public void adminStart(CommandSender sender, BossConfig.Difficulty difficulty) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(difficulty, "difficulty");
        startPull(sender, difficulty, true, false);
    }

    private boolean startPull(
            CommandSender sender,
            BossConfig.Difficulty difficulty,
            boolean forced,
            boolean triggeredByArenaEntry) {
        World endWorld = endWorldSupplier.get();
        if (endWorld == null) {
            if (sender != null) {
                messageService.send(sender, "command.start-no-players");
            }
            return false;
        }

        List<Player> candidates = collectPullCandidates(endWorld);
        if (candidates.isEmpty()) {
            if (!triggeredByArenaEntry && sender != null) {
                messageService.send(sender, "command.start-no-players");
            }
            return false;
        }
        ScalingSettings scaling = configManager.pluginConfig().scaling();
        if (candidates.size() < scaling.minPlayers()) {
            if (!triggeredByArenaEntry && sender != null) {
                messageService.send(
                        sender,
                        "command.start-too-few-players",
                        Placeholder.unparsed("current", String.valueOf(candidates.size())),
                        Placeholder.unparsed("min", String.valueOf(scaling.minPlayers()))
                );
            }
            return false;
        }
        if (scaling.enforceMaxPlayers() && candidates.size() > scaling.maxPlayers()) {
            if (!triggeredByArenaEntry && sender != null) {
                messageService.send(
                        sender,
                        "command.start-too-many-players",
                        Placeholder.unparsed("current", String.valueOf(candidates.size())),
                        Placeholder.unparsed("max", String.valueOf(scaling.maxPlayers()))
                );
            }
            return false;
        }

        int countdownSeconds;
        final long countdownGeneration;
        boolean pendingRollbacks = false;
        synchronized (stateLock) {
            if (state != RaidState.IDLE || arenaUpdatesInFlight.get() > 0 || hasPendingBlockRollbacks()) {
                pendingRollbacks = state == RaidState.IDLE && hasPendingBlockRollbacks();
                if (!triggeredByArenaEntry && sender != null) {
                    messageService.send(sender, pendingRollbacks ? "command.rollback-pending" : "command.already-running");
                }
                return false;
            }
            state = RaidState.COUNTDOWN;
            activeDifficulty = difficulty;
            countdownGeneration = encounterGeneration.incrementAndGet();
            countdownSeconds = configManager.pluginConfig().encounter().pullCountdownSeconds();
        }

        if (sender != null && !triggeredByArenaEntry) {
            messageService.send(
                    sender,
                    "command.start-triggered",
                    Placeholder.unparsed("difficulty", difficulty.name())
            );
        }

        if (countdownSeconds <= 0) {
            lockInAndActivate(difficulty, endWorld, countdownGeneration);
            return true;
        }

        AtomicInteger remaining = new AtomicInteger(countdownSeconds);
        AtomicReference<TaskHandle> ownHandle = new AtomicReference<>();
        broadcastCountdownTick(endWorld, difficulty, candidates.size(), remaining.get());
        TaskHandle scheduled = scheduler.runGlobalTimer(() -> {
            if (state != RaidState.COUNTDOWN || encounterGeneration.get() != countdownGeneration) {
                cancelHandle(ownHandle.get());
                return;
            }
            World world = endWorldSupplier.get();
            if (world == null) {
                cancelPullInternal(null, countdownGeneration);
                return;
            }
            List<Player> currentCandidates = collectPullCandidates(world);
            if (currentCandidates.isEmpty() || (triggeredByArenaEntry && !forced && countPlayersInCombatArena(currentCandidates) == 0)) {
                cancelPullInternal(world, countdownGeneration);
                return;
            }
            int next = remaining.decrementAndGet();
            if (next <= 0) {
                synchronized (stateLock) {
                    if (state != RaidState.COUNTDOWN || encounterGeneration.get() != countdownGeneration) {
                        cancelHandle(ownHandle.get());
                        return;
                    }
                    cancelHandle(countdownTask);
                    countdownTask = null;
                }
                lockInAndActivate(difficulty, world, countdownGeneration);
                return;
            }
            broadcastCountdownTick(world, difficulty, currentCandidates.size(), next);
        }, 20L, 20L);
        ownHandle.set(scheduled);
        synchronized (stateLock) {
            if (state == RaidState.COUNTDOWN && encounterGeneration.get() == countdownGeneration) {
                countdownTask = scheduled;
            } else {
                cancelHandle(scheduled);
            }
        }
        return true;
    }

    private boolean hasPendingBlockRollbacks() {
        return blockTracker.totalCount() > 0 || !unrecoveredPersistedRollbacks.isEmpty();
    }

    private void cancelPullInternal(@Nullable World world, long expectedGeneration) {
        cancelPullInternal(world, expectedGeneration, "raid.pull-cancelled", null);
    }

    private void cancelPullInternal(
            @Nullable World world,
            long expectedGeneration,
            String messageKey,
            @Nullable Integer minPlayers) {
        synchronized (stateLock) {
            if (state != RaidState.COUNTDOWN || encounterGeneration.get() != expectedGeneration) {
                return;
            }
            cancelHandle(countdownTask);
            countdownTask = null;
            state = RaidState.IDLE;
            activeDifficulty = null;
        }
        if (world != null) {
            for (Player player : onlinePlayersInWorld(world)) {
                scheduler.runAtEntity(player, () -> {
                    if (minPlayers != null) {
                        messageService.send(player, messageKey, Placeholder.unparsed("min", String.valueOf(minPlayers)));
                    } else {
                        messageService.send(player, messageKey);
                    }
                });
            }
        }
    }

    private void broadcastCountdownTick(World endWorld, BossConfig.Difficulty difficulty, int playerCount, int seconds) {
        for (Player player : onlinePlayersInWorld(endWorld)) {
            scheduler.runAtEntity(player, () -> {
                Component titleComp = messageService.render(
                        player,
                        "raid.pull-countdown-title",
                        Placeholder.unparsed("seconds", String.valueOf(seconds))
                );
                Component subtitleComp = messageService.render(
                        player,
                        "raid.pull-countdown-subtitle",
                        Placeholder.unparsed("difficulty", difficulty.name()),
                        Placeholder.unparsed("players", String.valueOf(playerCount))
                );
                player.showTitle(Title.title(titleComp, subtitleComp));
                player.playSound(Sound.sound(
                        Key.key("minecraft:block.note_block.hat"),
                        Sound.Source.MASTER,
                        0.8f,
                        seconds <= 3 ? 1.4f : 1.0f));
            });
        }
    }

    private void lockInAndActivate(BossConfig.Difficulty difficulty, World endWorld, long expectedGeneration) {
        List<Player> candidates = collectPullCandidates(endWorld);
        ScalingSettings scaling = configManager.pluginConfig().scaling();
        if (candidates.isEmpty() || candidates.size() < scaling.minPlayers()) {
            cancelPullInternal(
                    endWorld,
                    expectedGeneration,
                    candidates.isEmpty() ? "raid.pull-cancelled" : "raid.pull-cancelled-min-players",
                    candidates.isEmpty() ? null : scaling.minPlayers()
            );
            return;
        }

        List<Player> party = candidates;
        List<Player> overflow = List.of();
        if (scaling.enforceMaxPlayers() && candidates.size() > scaling.maxPlayers()) {
            List<Player> prioritized = new ArrayList<>(candidates.size());
            for (Player p : candidates) {
                if (isPlayerInCombatArea(p)) {
                    prioritized.add(p);
                }
            }
            for (Player p : candidates) {
                if (!isPlayerInCombatArea(p)) {
                    prioritized.add(p);
                }
            }
            party = List.copyOf(prioritized.subList(0, scaling.maxPlayers()));
            overflow = List.copyOf(prioritized.subList(scaling.maxPlayers(), prioritized.size()));
        }

        BossConfig bossConfig = difficulty == BossConfig.Difficulty.HARD
                ? configManager.hardBossConfig()
                : configManager.normalBossConfig();
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                scaling,
                bossConfig,
                party.size()
        );

        final long activationGen;
        synchronized (stateLock) {
            if (state != RaidState.COUNTDOWN || encounterGeneration.get() != expectedGeneration) {
                return;
            }
            activationGen = encounterGeneration.incrementAndGet();
            participants.clear();
            readyPlayers.clear();
            this.activeDifficulty = difficulty;
            this.activeBossConfig = bossConfig;
            this.activeScaling = scaled;
            this.remainingRevives.set(scaled.maxRevives());
            this.combatStartedMillis = clockMillis.getAsLong();
            this.state = RaidState.ACTIVE;
            for (Player member : party) {
                UUID memberId = member.getUniqueId();
                SavedCleanupRecord staleCleanup = pendingCleanupPlayers.get(memberId);
                RaidParticipant participant = new RaidParticipant(memberId, member.getName());
                participant.setOnline(true);
                participants.put(memberId, participant);
                scheduler.runAtEntity(member, () -> {
                    if (staleCleanup != null) {
                        staleCleanup.applyTo(member);
                        pendingCleanupPlayers.remove(memberId, staleCleanup);
                        deletePlayerCleanupAsync(memberId);
                    }
                    synchronized (stateLock) {
                        if ((state != RaidState.ACTIVE && state != RaidState.WIPING)
                                || encounterGeneration.get() != activationGen
                                || participants.get(memberId) != participant) {
                            return;
                        }
                        participant.captureOriginalState(
                                member.getGameMode(),
                                member.isInvisible(),
                                member.isCollidable(),
                                member.isInvulnerable()
                        );
                        persistPlayerCleanupAsync(participant, true);
                        updateSnapshot(member);
                        participant.markInitialSnapshotReady();
                    }
                });
            }
        }

        if (!overflow.isEmpty()) {
            Location safeSpawn = regionService.safeZoneSpawnLocation(endWorld);
            for (Player extra : overflow) {
                scheduler.runAtEntity(extra, () -> {
                    extra.teleportAsync(safeSpawn);
                    messageService.send(extra, "raid.late-join-locked");
                });
            }
        }

        for (Player member : party) {
            scheduler.runAtEntity(member, () -> {
                Component bossName = messageService.render(member, bossConfig.displayNameKey());
                Component titleComp = messageService.render(member, "raid.raid-start-title");
                Component subtitleComp = messageService.render(
                        member,
                        "raid.raid-start-subtitle",
                        Placeholder.component("boss", bossName),
                        Placeholder.unparsed("difficulty", difficulty.name()),
                        Placeholder.unparsed("players", String.valueOf(scaled.partySize()))
                );
                member.showTitle(Title.title(titleComp, subtitleComp));
                member.playSound(Sound.sound(
                        Key.key("minecraft:entity.ender_dragon.growl"),
                        Sound.Source.HOSTILE,
                        0.7f,
                        1.0f));
            });
        }

        startM3EncounterSystems(endWorld, party, bossConfig, scaled, activationGen);

        AtomicReference<TaskHandle> monitorHandle = new AtomicReference<>();
        TaskHandle scheduledMonitor = scheduler.runGlobalTimer(() -> {
            if (state != RaidState.ACTIVE || encounterGeneration.get() != activationGen) {
                cancelHandle(monitorHandle.get());
                return;
            }
            tickActiveEncounter();
        }, 2L, 2L);
        monitorHandle.set(scheduledMonitor);
        synchronized (stateLock) {
            if (state == RaidState.ACTIVE && encounterGeneration.get() == activationGen) {
                monitorTask = scheduledMonitor;
            } else {
                cancelHandle(scheduledMonitor);
            }
        }
    }

    /**
     * Global 2-tick coordinator reading {@link PlayerSnapshot}s and scheduling entity-thread updates.
     */
    void tickActiveEncounter() {
        if (state != RaidState.ACTIVE) {
            return;
        }
        World fallbackEnd = endWorldSupplier.get();
        World endWorld = instanceManager != null ? instanceManager.activeCombatWorld(fallbackEnd) : fallbackEnd;
        if (endWorld == null) {
            return;
        }
        ScaledEncounterParameters scaled = activeScaling;
        if (scaled == null) {
            return;
        }
        ReviveSettings reviveSettings = configManager.pluginConfig().revive();
        long now = clockMillis.getAsLong();

        // Refresh snapshots on each End player's own entity scheduler for Folia safety.
        List<Player> endPlayers = onlinePlayersInWorld(endWorld);
        for (Player p : endPlayers) {
            scheduler.runAtEntity(p, () -> updateSnapshot(p));
        }

        // 1. Check void falls for ALIVE online participants.
        for (RaidParticipant participant : participants.values()) {
            if (!participant.isOnline() || participant.state() != ParticipantState.ALIVE || participant.isRescueInProgress()) {
                continue;
            }
            Player player = playerLookup.apply(participant.playerId());
            PlayerSnapshot snap = snapshots.get(participant.playerId());
            if (player == null || !player.isOnline() || snap == null || snap.location() == null) {
                continue;
            }
            if (snap.belowVoid()) {
                initiateVoidRescue(participant, player, snap.location(), reviveSettings.voidRescueYOffset());
            }
        }

        // 2. Process DOWNED participants: timeout & 2.5s revive channeling + soul marker updates.
        Set<UUID> channelingRevivers = new HashSet<>();
        for (RaidParticipant downed : participants.values()) {
            if (downed.state() != ParticipantState.DOWNED) {
                continue;
            }
            if (scaled.downedTimeoutSeconds() > 0) {
                long elapsedMs = now - downed.downedAtMillis();
                if (elapsedMs >= scaled.downedTimeoutSeconds() * 1000L) {
                    expireToDeadSpectator(downed, endPlayers);
                    continue;
                }
            }

            Location markerLoc = downed.downedLocation();
            if (markerLoc == null) {
                continue;
            }

            Player channellingAlly = findEligibleReviver(markerLoc, downed.playerId(), reviveSettings);
            if (channellingAlly != null) {
                if (remainingRevives.get() <= 0) {
                    downed.setReviveProgressSeconds(0.0);
                    downed.setCurrentReviverId(null);
                    sendRateLimitedMessage(channellingAlly, "revive.no-revives-left", now);
                } else {
                    channelingRevivers.add(channellingAlly.getUniqueId());
                    if (!channellingAlly.getUniqueId().equals(downed.currentReviverId())) {
                        downed.setCurrentReviverId(channellingAlly.getUniqueId());
                        downed.setReviveProgressSeconds(0.0);
                    }
                    double newProgress = downed.reviveProgressSeconds() + MONITOR_STEP_SECONDS;
                    downed.setReviveProgressSeconds(newProgress);
                    double ratio = Math.min(1.0, newProgress / reviveSettings.channelSeconds());
                    sendRevivingActionBar(channellingAlly, downed.name(), ratio);
                    if (newProgress >= reviveSettings.channelSeconds()) {
                        completeRevive(downed, channellingAlly, reviveSettings, endPlayers);
                        continue;
                    }
                }
            } else {
                downed.setReviveProgressSeconds(0.0);
                downed.setCurrentReviverId(null);
            }

            Integer remSec = scaled.downedTimeoutSeconds() > 0
                    ? (int) Math.max(0L, Math.ceil((scaled.downedTimeoutSeconds() * 1000L - (now - downed.downedAtMillis())) / 1000.0))
                    : null;
            double ratio = Math.min(1.0, downed.reviveProgressSeconds() / reviveSettings.channelSeconds());
            soulMarkerService.spawnOrUpdateMarker(
                    downed.playerId(),
                    downed.name(),
                    markerLoc,
                    remSec,
                    ratio,
                    endPlayers
            );
        }

        // 3. Tick M3 Boss AI, Crystal Regeneration, Timeline, Mechanics, and Integrated HUD.
        tickM3Systems(endWorld, endPlayers, channelingRevivers);

        // 4. Evaluate wipe condition.
        evaluateWipeCondition(endWorld, reviveSettings);
    }

    private @Nullable Player findEligibleReviver(
            Location markerLoc,
            UUID downedId,
            ReviveSettings reviveSettings) {
        double radiusSq = reviveSettings.interactRadius() * reviveSettings.interactRadius();
        World markerWorld = markerLoc.getWorld();
        if (markerWorld == null) {
            return null;
        }

        Player best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (RaidParticipant candidate : participants.values()) {
            if (!candidate.isOnline()
                    || candidate.state() != ParticipantState.ALIVE
                    || candidate.playerId().equals(downedId)) {
                continue;
            }
            PlayerSnapshot snap = snapshots.get(candidate.playerId());
            Player player = playerLookup.apply(candidate.playerId());
            if (snap == null || player == null || !player.isOnline() || snap.location() == null) {
                continue;
            }
            if (!markerWorld.equals(snap.location().getWorld())) {
                continue;
            }
            double distSq = snap.location().distanceSquared(markerLoc);
            if (distSq > radiusSq) {
                continue;
            }
            boolean channeling = (reviveSettings.requireSneak() && snap.sneaking())
                    || (reviveSettings.allowRightClickHold() && snap.holdingRightClick());
            if (channeling && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = player;
            }
        }
        return best;
    }

    private void initiateVoidRescue(RaidParticipant participant, Player player, Location voidLoc, double yOffset) {
        participant.setRescueInProgress(true);
        long expectedGeneration = encounterGeneration.get();
        regionService.findNearestSafeArenaEdgeAsync(voidLoc, yOffset).whenComplete((safeLoc, error) -> {
            Location target = (error == null && safeLoc != null)
                    ? safeLoc
                    : regionService.clampToArenaAnchor(voidLoc, yOffset);
            scheduler.runAtEntity(player, () -> {
                participant.setRescueInProgress(false);
                if (state != RaidState.ACTIVE
                        || encounterGeneration.get() != expectedGeneration
                        || participants.get(participant.playerId()) != participant
                        || !player.isOnline()) {
                    return;
                }
                player.setFallDistance(0.0f);
                player.teleportAsync(target);
                messageService.send(player, "revive.void-rescued");
                transitionToDowned(participant, player, target);
            }, () -> participant.setRescueInProgress(false));
        });
    }

    private void transitionToDowned(RaidParticipant participant, Player player, Location markerLocation) {
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE || participant.state() != ParticipantState.ALIVE) {
                return;
            }
            participant.setState(ParticipantState.DOWNED);
            participant.setDownedLocation(markerLocation);
            participant.setDownedAtMillis(clockMillis.getAsLong());
            participant.setReviveProgressSeconds(0.0);
            participant.setCurrentReviverId(null);
        }

        if (player.isOnline()) {
            long expectedGeneration = encounterGeneration.get();
            scheduler.runAtEntity(player, () -> {
                synchronized (stateLock) {
                    if ((state != RaidState.ACTIVE && state != RaidState.WIPING)
                            || encounterGeneration.get() != expectedGeneration
                            || participants.get(participant.playerId()) != participant
                            || participant.state() != ParticipantState.DOWNED) {
                        return;
                    }
                    participant.captureOriginalState(
                            player.getGameMode(),
                            player.isInvisible(),
                            player.isCollidable(),
                            player.isInvulnerable()
                    );
                    persistPlayerCleanupAsync(participant, true);
                }
                player.setHealth(Math.min(maxHealth(player), Math.max(2.0, player.getHealth())));
                player.setFireTicks(0);
                player.setFallDistance(0.0f);
                player.setInvulnerable(true);
                player.setInvisible(true);
                player.setCollidable(false);

                Component titleComp = messageService.render(player, "revive.downed-self-title");
                Component subComp = messageService.render(player, "revive.downed-self-subtitle");
                player.showTitle(Title.title(titleComp, subComp));
                player.playSound(Sound.sound(
                        Key.key("minecraft:entity.wither.hurt"),
                        Sound.Source.PLAYER,
                        0.8f,
                        0.6f));
                updateSnapshot(player);
            });
        }

        World endWorld = endWorldSupplier.get();
        if (endWorld != null) {
            List<Player> viewers = onlinePlayersInWorld(endWorld);
            for (Player viewer : viewers) {
                scheduler.runAtEntity(viewer, () -> messageService.send(
                        viewer,
                        "revive.downed-broadcast",
                        Placeholder.unparsed("player", participant.name())
                ));
            }
            ScaledEncounterParameters scaled = activeScaling;
            Integer remSec = (scaled != null && scaled.downedTimeoutSeconds() > 0)
                    ? scaled.downedTimeoutSeconds()
                    : null;
            soulMarkerService.spawnOrUpdateMarker(
                    participant.playerId(),
                    participant.name(),
                    markerLocation,
                    remSec,
                    0.0,
                    viewers
            );
        }

        evaluateWipeCondition(endWorld, configManager.pluginConfig().revive());
    }

    private void completeRevive(
            RaidParticipant participant,
            Player reviver,
            ReviveSettings reviveSettings,
            List<Player> viewers) {
        int left;
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE || participant.state() != ParticipantState.DOWNED) {
                return;
            }
            if (remainingRevives.get() <= 0) {
                return;
            }
            left = remainingRevives.decrementAndGet();
            participant.setState(ParticipantState.ALIVE);
            participant.setDownedLocation(null);
            participant.setReviveProgressSeconds(0.0);
            participant.setCurrentReviverId(null);
        }

        soulMarkerService.removeMarker(participant.playerId(), viewers);

        Player target = playerLookup.apply(participant.playerId());
        if (target != null && target.isOnline()) {
            long expectedGeneration = encounterGeneration.get();
            scheduler.runAtEntity(target, () -> {
                if (state != RaidState.ACTIVE
                        || encounterGeneration.get() != expectedGeneration
                        || participants.get(participant.playerId()) != participant
                        || participant.state() != ParticipantState.ALIVE) {
                    return;
                }
                restorePlayerFlags(participant, target);
                double maxHp = maxHealth(target);
                target.setHealth(Math.max(1.0, Math.min(maxHp, maxHp * reviveSettings.healPercent())));
                int invulTicks = (int) Math.round(reviveSettings.invulnerabilitySeconds() * 20.0);
                if (invulTicks > 0) {
                    target.setInvulnerable(true);
                    scheduler.runAtEntityLater(target, () -> {
                        if (encounterGeneration.get() == expectedGeneration
                                && participants.get(participant.playerId()) == participant
                                && participant.state() == ParticipantState.ALIVE) {
                            target.setInvulnerable(participant.previousInvulnerable());
                        }
                    }, invulTicks);
                }
                target.playSound(Sound.sound(
                        Key.key("minecraft:item.totem.use"),
                        Sound.Source.PLAYER,
                        0.7f,
                        1.3f));
                updateSnapshot(target);
            });
        }

        for (Player viewer : viewers) {
            scheduler.runAtEntity(viewer, () -> messageService.send(
                    viewer,
                    "revive.revived-success",
                    Placeholder.unparsed("target", participant.name()),
                    Placeholder.unparsed("reviver", reviver.getName()),
                    Placeholder.unparsed("remaining", String.valueOf(left))
            ));
        }
    }

    private void expireToDeadSpectator(RaidParticipant participant, List<Player> viewers) {
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE || participant.state() != ParticipantState.DOWNED) {
                return;
            }
            participant.setState(ParticipantState.DEAD_SPECTATOR);
            participant.setDownedLocation(null);
            participant.setReviveProgressSeconds(0.0);
            participant.setCurrentReviverId(null);
        }

        soulMarkerService.removeMarker(participant.playerId(), viewers);

        Player target = playerLookup.apply(participant.playerId());
        if (target != null && target.isOnline()) {
            long expectedGeneration = encounterGeneration.get();
            scheduler.runAtEntity(target, () -> {
                if (state != RaidState.ACTIVE
                        || encounterGeneration.get() != expectedGeneration
                        || participants.get(participant.playerId()) != participant
                        || participant.state() != ParticipantState.DEAD_SPECTATOR) {
                    return;
                }
                target.setGameMode(GameMode.SPECTATOR);
                target.setInvisible(participant.previousInvisible());
                target.setCollidable(participant.previousCollidable());
                target.setInvulnerable(participant.previousInvulnerable());
                updateSnapshot(target);
            });
        }

        for (Player viewer : viewers) {
            scheduler.runAtEntity(viewer, () -> messageService.send(
                    viewer,
                    "revive.expired-spectator",
                    Placeholder.unparsed("player", participant.name())
            ));
        }
    }

    private void evaluateWipeCondition(@Nullable World endWorld, ReviveSettings reviveSettings) {
        if (state != RaidState.ACTIVE) {
            return;
        }
        int onlineAlive = 0;
        int onlineAliveInArena = 0;
        boolean allInitialSnapshotsReady = true;
        for (RaidParticipant p : participants.values()) {
            if (!p.isOnline() || p.state() != ParticipantState.ALIVE) {
                continue;
            }
            onlineAlive++;
            if (!p.isInitialSnapshotReady()) {
                allInitialSnapshotsReady = false;
            }
            if (p.isRescueInProgress()) {
                onlineAliveInArena++;
                continue;
            }
            PlayerSnapshot snap = snapshots.get(p.playerId());
            if (snap != null && snap.inArena()) {
                onlineAliveInArena++;
            }
        }
        boolean noAlivePlayers = onlineAlive == 0;
        boolean allLeftArena = reviveSettings.wipeIfAllLeaveArena()
                && allInitialSnapshotsReady
                && onlineAlive > 0
                && onlineAliveInArena == 0;
        if (noAlivePlayers || allLeftArena) {
            beginWipe(endWorld);
        }
    }

    private void beginWipe(@Nullable World endWorld) {
        long wipeGeneration;
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE) {
                return;
            }
            state = RaidState.WIPING;
            cancelHandle(monitorTask);
            monitorTask = null;
            // Keep encounterGeneration unchanged until finishWipe leaves WIPING -> ENDING
            // so pending DOWNED entity tasks for the final downed player remain valid during WIPING.
            wipeGeneration = encounterGeneration.get();
        }

        RaidTimelineEngine timeline = this.activeTimeline;
        this.activeTimeline = null;
        if (timeline != null) {
            timeline.stop();
        }
        if (gimmickExecutor != null) {
            gimmickExecutor.cancelActiveMechanic();
        }
        if (hudService != null && endWorld != null) {
            hudService.updateCastBossBar(null, 0.0, 0.0, onlinePlayersInWorld(endWorld));
        }

        for (RaidParticipant participant : participants.values()) {
            Player player = playerLookup.apply(participant.playerId());
            if (player != null && player.isOnline()) {
                scheduler.runAtEntity(player, () -> {
                    Component titleComp = messageService.render(player, "raid.wipe-title");
                    Component subComp = messageService.render(player, "raid.wipe-subtitle");
                    player.showTitle(Title.title(titleComp, subComp));
                    player.playSound(Sound.sound(
                            Key.key("minecraft:entity.wither.death"),
                            Sound.Source.HOSTILE,
                            0.7f,
                            0.7f));
                });
            }
        }

        int delaySeconds = configManager.pluginConfig().revive().wipeDelaySeconds();
        if (delaySeconds <= 0) {
            finishWipe(endWorld, wipeGeneration);
        } else {
            TaskHandle scheduledWipe = scheduler.runGlobalLater(() -> finishWipe(endWorld, wipeGeneration), delaySeconds * 20L);
            synchronized (stateLock) {
                if (state == RaidState.WIPING && encounterGeneration.get() == wipeGeneration) {
                    wipeTask = scheduledWipe;
                } else {
                    cancelHandle(scheduledWipe);
                }
            }
        }
    }

    private void finishWipe(@Nullable World endWorld, long expectedGeneration) {
        long endingGeneration;
        synchronized (stateLock) {
            if (state != RaidState.WIPING || encounterGeneration.get() != expectedGeneration) {
                return;
            }
            cancelHandle(wipeTask);
            wipeTask = null;
            endingGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
        }

        World targetWorld = endWorld != null ? endWorld : endWorldSupplier.get();
        List<Player> viewers = targetWorld != null ? onlinePlayersInWorld(targetWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        cleanupM3Systems(viewers, true);

        Location safeSpawn = targetWorld != null ? regionService.safeZoneSpawnLocation(targetWorld) : null;
        restoreAndResetParticipants(safeSpawn);
        CompletableFuture<Void> rollbackFuture = rollbackBlocksIfConfigured(targetWorld);
        completeEndingWhenRolledBack(endingGeneration, rollbackFuture);
    }

    @Override
    public void adminReset(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        World mainEndWorld = endWorldSupplier.get();
        World combatWorld = instanceManager != null ? instanceManager.activeCombatWorld(mainEndWorld) : mainEndWorld;
        long resetGeneration;
        synchronized (stateLock) {
            cancelHandle(countdownTask);
            cancelHandle(monitorTask);
            cancelHandle(wipeTask);
            countdownTask = null;
            monitorTask = null;
            wipeTask = null;
            resetGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
        }

        List<Player> viewers = combatWorld != null ? onlinePlayersInWorld(combatWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        cleanupM3Systems(viewers, true);
        Location safeSpawn = mainEndWorld != null ? regionService.safeZoneSpawnLocation(mainEndWorld) : null;
        restoreAndResetParticipants(safeSpawn);
        readyPlayers.clear();
        if (instanceManager != null) {
            instanceManager.destroyActiveInstance(safeSpawn);
        }
        CompletableFuture<Void> rollbackFuture = rollbackBlocksIfConfigured(combatWorld);
        completeEndingWhenRolledBack(resetGeneration, rollbackFuture);
        messageService.send(sender, "command.admin-reset");
    }

    @Override
    public void onDragonDefeated(EnderDragon dragon) {
        Objects.requireNonNull(dragon, "dragon");
        if (!regionService.isRaidWorld(dragon.getWorld())) {
            return;
        }
        if (bossController != null) {
            EnderDragon activeBoss = bossController.activeDragon();
            if (activeBoss != null && !activeBoss.getUniqueId().equals(dragon.getUniqueId())) {
                return;
            }
        }
        BossConfig.Difficulty difficulty;
        long defeatGeneration;
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE) {
                return;
            }
            cancelHandle(monitorTask);
            monitorTask = null;
            defeatGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
            difficulty = activeDifficulty != null ? activeDifficulty : BossConfig.Difficulty.NORMAL;
        }

        long durationMs = Math.max(0L, clockMillis.getAsLong() - combatStartedMillis);
        String formattedTime = formatDuration(durationMs);

        World endWorld = dragon.getWorld();
        List<Player> viewers = onlinePlayersInWorld(endWorld);
        soulMarkerService.removeAllMarkers(viewers);
        prepareVictoryM3Cleanup(dragon, endWorld, viewers, difficulty, durationMs);

        // On victory, restore participant flags where they stand so they can collect drops/egg.
        restoreAndResetParticipants(null);

        for (Player viewer : viewers) {
            scheduler.runAtEntity(viewer, () -> {
                Component titleComp = messageService.render(viewer, "raid.victory-title");
                Component subComp = messageService.render(
                        viewer,
                        "raid.victory-subtitle",
                        Placeholder.unparsed("time", formattedTime),
                        Placeholder.unparsed("difficulty", difficulty.name())
                );
                viewer.showTitle(Title.title(titleComp, subComp));
                viewer.playSound(Sound.sound(
                        Key.key("minecraft:ui.toast.challenge_complete"),
                        Sound.Source.MASTER,
                        0.9f,
                        1.0f));
            });
        }

        CompletableFuture<Void> rollbackFuture = rollbackBlocksIfConfigured(endWorld);
        if (instanceManager != null && instanceManager.isInstanceWorld(endWorld)) {
            World mainEnd = endWorldSupplier.get();
            Location returnLoc = mainEnd != null ? regionService.safeZoneSpawnLocation(mainEnd) : null;
            scheduler.runGlobalLater(() -> instanceManager.destroyActiveInstance(returnLoc), 100L);
        }
        completeEndingWhenRolledBack(defeatGeneration, rollbackFuture);
    }

    private void completeEndingWhenRolledBack(long expectedGeneration, CompletableFuture<Void> rollbackFuture) {
        rollbackFuture.whenComplete((ignored, error) -> {
            synchronized (stateLock) {
                if (state == RaidState.ENDING
                        && encounterGeneration.get() == expectedGeneration
                        && !hasPendingBlockRollbacks()) {
                    activeDifficulty = null;
                    activeBossConfig = null;
                    activeScaling = null;
                    activeTimeline = null;
                    remainingRevives.set(0);
                    state = RaidState.IDLE;
                }
            }
        });
    }

    @Override
    public void onPlayerMove(PlayerMoveEvent event) {
        Objects.requireNonNull(event, "event");
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        updateSnapshot(player, to);

        UUID id = player.getUniqueId();
        RaidParticipant participant = participants.get(id);
        RaidState currentState = state;

        // Lock DOWNED participant X/Y/Z while allowing yaw/pitch rotation (active & wipe delay).
        if ((currentState == RaidState.ACTIVE || currentState == RaidState.WIPING)
                && participant != null
                && participant.state() == ParticipantState.DOWNED) {
            Location anchor = participant.downedLocation() != null ? participant.downedLocation() : from;
            if (to.getWorld() != null && to.getWorld().equals(anchor.getWorld())
                    && (Double.compare(to.getX(), anchor.getX()) != 0
                    || Double.compare(to.getY(), anchor.getY()) != 0
                    || Double.compare(to.getZ(), anchor.getZ()) != 0)) {
                Location pinned = anchor.clone();
                pinned.setYaw(to.getYaw());
                pinned.setPitch(to.getPitch());
                event.setTo(pinned);
            }
            return;
        }

        // Late-joiner lock when entering the arena while raid is active/wiping/ending.
        EncounterSettings enc = configManager.pluginConfig().encounter();
        if ((currentState == RaidState.ACTIVE || currentState == RaidState.WIPING || currentState == RaidState.ENDING)
                && participant == null
                && enc.lockLateJoinersToSafeZone()
                && regionService.isInArena(to)
                && !regionService.isInSafeZone(to)) {
            World world = to.getWorld();
            if (world != null) {
                event.setCancelled(true);
                player.teleportAsync(regionService.safeZoneSpawnLocation(world));
                sendRateLimitedMessage(player, "raid.late-join-locked", clockMillis.getAsLong());
            }
            return;
        }

        // Initialize first-clear idle dragon once if players are already in the End on startup.
        if (currentState == RaidState.IDLE
                && announcedFirstClearDragonId == null
                && to.getWorld() != null
                && regionService.isRaidWorld(to.getWorld())) {
            maybeInitializeFirstClear(player, to.getWorld());
        }

        // Auto-start pull when stepping into the combat arena from outside or from the safe zone.
        boolean inCombatAreaNow = regionService.isInArena(to) && !regionService.isInSafeZone(to);
        boolean wasInCombatArea = regionService.isInArena(from) && !regionService.isInSafeZone(from);
        if (currentState == RaidState.IDLE
                && enc.autoStartOnArenaEnter()
                && !enc.altarGuiOnly()
                && inCombatAreaNow
                && !wasInCombatArea) {
            World world = to.getWorld();
            if (world != null && livingDragonPredicate.test(world)) {
                startPull(player, BossConfig.Difficulty.NORMAL, false, true);
            }
        }
    }

    @Override
    public void onPlayerJoin(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        soulMarkerService.invalidateViewer(id);
        updateSnapshot(player);

        // If a previous raid ended while this player was offline, restore flags and optionally move to safe zone.
        SavedCleanupRecord cleanup = pendingCleanupPlayers.get(id);
        if (cleanup != null) {
            scheduler.runAtEntity(player, () -> {
                cleanup.applyTo(player);
                pendingCleanupPlayers.remove(id, cleanup);
                deletePlayerCleanupAsync(id);
                World endWorld = endWorldSupplier.get();
                if (cleanup.teleportToSafeZone() && endWorld != null && regionService.isRaidWorld(player.getWorld())) {
                    player.teleportAsync(regionService.safeZoneSpawnLocation(endWorld));
                }
                updateSnapshot(player);
            });
            return;
        }

        RaidParticipant participant = participants.get(id);
        if (participant != null && (state == RaidState.ACTIVE || state == RaidState.WIPING)) {
            participant.setOnline(true);
            participant.setName(player.getName());
            scheduler.runAtEntity(player, () -> {
                switch (participant.state()) {
                    case ALIVE -> restorePlayerFlags(participant, player);
                    case DOWNED -> {
                        player.setInvulnerable(true);
                        player.setInvisible(true);
                        player.setCollidable(false);
                        Location loc = participant.downedLocation();
                        if (loc != null) {
                            player.teleportAsync(loc);
                        }
                    }
                    case DEAD_SPECTATOR -> {
                        player.setGameMode(GameMode.SPECTATOR);
                        player.setInvisible(participant.previousInvisible());
                        player.setCollidable(participant.previousCollidable());
                        player.setInvulnerable(participant.previousInvulnerable());
                    }
                }
                updateSnapshot(player);
            });
            return;
        }

        // Late-joiner entering directly into the End world during an active raid.
        if ((state == RaidState.ACTIVE || state == RaidState.WIPING || state == RaidState.ENDING)
                && configManager.pluginConfig().encounter().lockLateJoinersToSafeZone()
                && regionService.isRaidWorld(player.getWorld())
                && !regionService.isInSafeZone(player.getLocation())) {
            World endWorld = player.getWorld();
            scheduler.runAtEntity(player, () -> {
                player.teleportAsync(regionService.safeZoneSpawnLocation(endWorld));
                messageService.send(player, "raid.late-join-locked");
            });
            return;
        }

        if (state == RaidState.IDLE && regionService.isRaidWorld(player.getWorld())) {
            maybeInitializeFirstClear(player, player.getWorld());
        }
    }

    @Override
    public void onPlayerQuit(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        soulMarkerService.invalidateViewer(id);
        if (hudService != null) {
            hudService.removeViewer(player);
        }
        readyPlayers.remove(id);
        snapshots.remove(id);
        lastRightClickMillis.remove(id);
        lastFeedbackMillis.remove(id);

        RaidParticipant participant = participants.get(id);
        if (participant == null) {
            return;
        }
        participant.setOnline(false);
        if (state != RaidState.ACTIVE) {
            return;
        }
        if (configManager.pluginConfig().revive().downOnDisconnect()
                && participant.state() == ParticipantState.ALIVE) {
            Location markerLoc = regionService.isInArena(player.getLocation())
                    ? player.getLocation()
                    : regionService.clampToArenaAnchor(player.getLocation(), 0.0);
            transitionToDowned(participant, player, markerLoc);
        } else {
            evaluateWipeCondition(endWorldSupplier.get(), configManager.pluginConfig().revive());
        }
    }

    @Override
    public void onPlayerChangedWorld(Player player) {
        Objects.requireNonNull(player, "player");
        updateSnapshot(player);
        UUID id = player.getUniqueId();
        if (!regionService.isRaidWorld(player.getWorld())) {
            readyPlayers.remove(id);
            if (hudService != null) {
                hudService.removeViewer(player);
            }
            return;
        }
        if ((state == RaidState.ACTIVE || state == RaidState.WIPING || state == RaidState.ENDING)
                && !isParticipant(id)
                && configManager.pluginConfig().encounter().lockLateJoinersToSafeZone()
                && !regionService.isInSafeZone(player.getLocation())) {
            World endWorld = player.getWorld();
            scheduler.runAtEntity(player, () -> {
                player.teleportAsync(regionService.safeZoneSpawnLocation(endWorld));
                messageService.send(player, "raid.late-join-locked");
            });
            return;
        }
        if (state == RaidState.IDLE) {
            maybeInitializeFirstClear(player, player.getWorld());
        }
    }

    @Override
    public void onPlayerDamage(Player player, EntityDamageEvent event) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(event, "event");
        RaidState currentState = state;
        if (currentState != RaidState.ACTIVE && currentState != RaidState.WIPING) {
            return;
        }
        RaidParticipant participant = participants.get(player.getUniqueId());
        if (participant == null) {
            return;
        }
        if (participant.state() != ParticipantState.ALIVE) {
            event.setCancelled(true);
            return;
        }
        if (currentState != RaidState.ACTIVE) {
            return;
        }
        if (participant.isRescueInProgress()) {
            event.setCancelled(true);
            return;
        }
        double remainingHealth = player.getHealth() - event.getFinalDamage();
        if (remainingHealth > 0.0) {
            return;
        }
        if (shouldAllowVanillaTotem(player, event)) {
            return;
        }
        event.setCancelled(true);
        Location markerLoc = regionService.isInArena(player.getLocation())
                ? player.getLocation()
                : regionService.clampToArenaAnchor(player.getLocation(), 0.0);
        transitionToDowned(participant, player, markerLoc);
    }

    @Override
    public void onParticipantDeath(Player player, PlayerDeathEvent event) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(event, "event");
        if (!isProtectionActive()) {
            return;
        }
        if (!isParticipant(player.getUniqueId()) && !regionService.isInArena(player.getLocation())) {
            return;
        }
        ProtectionSettings prot = configManager.pluginConfig().protection();
        boolean applied = false;
        if (prot.keepInventoryOnRaidDeath()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            applied = true;
        }
        if (prot.keepExperienceOnRaidDeath()) {
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            applied = true;
        }
        if (applied) {
            messageService.send(player, "raid.item-loss-protected");
        }
    }

    @Override
    public void recordRightClickPulse(Player player) {
        Objects.requireNonNull(player, "player");
        lastRightClickMillis.put(player.getUniqueId(), clockMillis.getAsLong());
        PlayerSnapshot existing = snapshots.get(player.getUniqueId());
        if (existing != null) {
            snapshots.put(player.getUniqueId(), new PlayerSnapshot(
                    existing.playerId(),
                    existing.name(),
                    existing.worldName(),
                    existing.location(),
                    existing.sneaking(),
                    true,
                    existing.inArena(),
                    existing.inSafeZone(),
                    existing.belowVoid()
            ));
        }
    }

    @Override
    public void recordShieldPulse(Player player) {
        Objects.requireNonNull(player, "player");
        if (state == RaidState.ACTIVE && gimmickExecutor != null) {
            gimmickExecutor.recordShieldPulse(player);
        }
    }

    @Override
    public void onCrystalDamage(EnderCrystal crystal, EntityDamageEvent event) {
        Objects.requireNonNull(crystal, "crystal");
        Objects.requireNonNull(event, "event");
        if (crystalManager == null || !crystalManager.isTrackedCrystal(crystal)) {
            return;
        }
        Entity damager = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
        if (crystalManager.onCrystalDamage(crystal, event.getDamage(), damager)) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onEntityExplode(Entity entity, Cancellable event) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(event, "event");
        if (entity instanceof EnderCrystal && crystalManager != null && crystalManager.isTrackedCrystal(entity)) {
            event.setCancelled(true);
            return;
        }
        if (entity instanceof EnderDragon && isProtectionActive() && regionService.isRaidWorld(entity.getWorld())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void shutdown() {
        synchronized (stateLock) {
            cancelHandle(countdownTask);
            cancelHandle(monitorTask);
            cancelHandle(wipeTask);
            countdownTask = null;
            monitorTask = null;
            wipeTask = null;
            encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
            for (RaidParticipant participant : participants.values()) {
                UUID id = participant.playerId();
                if (participant.isStateCaptured()) {
                    pendingCleanupPlayers.put(id, new SavedCleanupRecord(
                            participant.previousGameMode(),
                            participant.previousInvisible(),
                            participant.previousCollidable(),
                            participant.previousInvulnerable(),
                            true
                    ));
                }
            }
        }
        World mainEndWorld = endWorldSupplier.get();
        World combatWorld = instanceManager != null ? instanceManager.activeCombatWorld(mainEndWorld) : mainEndWorld;
        List<Player> viewers = combatWorld != null ? onlinePlayersInWorld(combatWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        CompletableFuture<Void> m3CleanupFuture = cleanupM3Systems(viewers, true);
        if (instanceManager != null) {
            Location returnLoc = mainEndWorld != null ? regionService.safeZoneSpawnLocation(mainEndWorld) : null;
            instanceManager.destroyActiveInstance(returnLoc);
        }
        if (!m3CleanupFuture.isDone()) {
            try {
                m3CleanupFuture.get(500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ex) {
                logger.log(Level.FINE, "Timed out or failed waiting for M3 entity cleanup on shutdown", ex);
            }
        }
        if (recoveryRepository != null) {
            List<PendingPlayerCleanup> cleanups = new ArrayList<>(pendingCleanupPlayers.size());
            for (Map.Entry<UUID, SavedCleanupRecord> entry : pendingCleanupPlayers.entrySet()) {
                cleanups.add(entry.getValue().toPendingCleanup(entry.getKey()));
            }
            recoveryRepository.replacePendingPlayerCleanupsSync(cleanups);

            Map<BlockPosKey, PendingBlockRollback> mergedBlocks = new LinkedHashMap<>(unrecoveredPersistedRollbacks);
            if (configManager.pluginConfig().protection().rollbackTemporaryBlocksOnEnd()) {
                for (PendingBlockRollback active : blockTracker.exportPendingRollbacks()) {
                    mergedBlocks.putIfAbsent(active.toPosKey(), active);
                }
            }
            recoveryRepository.replacePendingBlockRollbacksSync(mergedBlocks.values());
        }
        participants.clear();
        readyPlayers.clear();
        snapshots.clear();
        blockTracker.clear();
        state = RaidState.IDLE;
    }

    private boolean shouldAllowVanillaTotem(Player player, EntityDamageEvent event) {
        ScaledEncounterParameters scaled = activeScaling;
        if (scaled == null || !scaled.allowVanillaTotem()) {
            return false;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            return false;
        }
        EntityEquipment eq = player.getEquipment();
        if (eq == null) {
            return false;
        }
        ItemStack main = eq.getItemInMainHand();
        ItemStack off = eq.getItemInOffHand();
        return main.getType() == Material.TOTEM_OF_UNDYING || off.getType() == Material.TOTEM_OF_UNDYING;
    }

    private void restoreAndResetParticipants(@Nullable Location teleportTarget) {
        for (RaidParticipant participant : List.copyOf(participants.values())) {
            UUID id = participant.playerId();
            Player player = playerLookup.apply(id);
            SavedCleanupRecord cleanupRecord = participant.isStateCaptured()
                    ? new SavedCleanupRecord(
                            participant.previousGameMode(),
                            participant.previousInvisible(),
                            participant.previousCollidable(),
                            participant.previousInvulnerable(),
                            teleportTarget != null
                    )
                    : null;
            if (player != null && player.isOnline()) {
                if (cleanupRecord != null) {
                    pendingCleanupPlayers.put(id, cleanupRecord);
                }
                scheduler.runAtEntity(player, () -> {
                    restorePlayerFlags(participant, player);
                    if (player.getHealth() <= 0.0) {
                        player.setHealth(Math.min(maxHealth(player), 1.0));
                    } else if (teleportTarget != null) {
                        player.setHealth(maxHealth(player));
                    }
                    player.setFireTicks(0);
                    player.setFallDistance(0.0f);
                    if (teleportTarget != null && regionService.isRaidWorld(player.getWorld())) {
                        player.teleportAsync(teleportTarget);
                    }
                    if (cleanupRecord != null) {
                        pendingCleanupPlayers.remove(id, cleanupRecord);
                    } else {
                        pendingCleanupPlayers.remove(id);
                    }
                    deletePlayerCleanupAsync(id);
                    updateSnapshot(player);
                }, () -> {
                    if (cleanupRecord != null && recoveryRepository != null) {
                        recoveryRepository.upsertPendingPlayerCleanupAsync(cleanupRecord.toPendingCleanup(id));
                    }
                });
            } else if (cleanupRecord != null) {
                pendingCleanupPlayers.put(id, cleanupRecord);
                if (recoveryRepository != null) {
                    recoveryRepository.upsertPendingPlayerCleanupAsync(cleanupRecord.toPendingCleanup(id));
                }
            }
        }
        participants.clear();
    }

    private void restorePlayerFlags(RaidParticipant participant, Player player) {
        if (!participant.isStateCaptured()) {
            return;
        }
        GameMode prevMode = participant.previousGameMode();
        if (prevMode != null && player.getGameMode() != prevMode) {
            player.setGameMode(prevMode);
        }
        player.setInvisible(participant.previousInvisible());
        player.setCollidable(participant.previousCollidable());
        player.setInvulnerable(participant.previousInvulnerable());
    }

    private CompletableFuture<Void> rollbackBlocksIfConfigured(@Nullable World endWorld) {
        if (!configManager.pluginConfig().protection().rollbackTemporaryBlocksOnEnd()) {
            blockTracker.clear();
            unrecoveredPersistedRollbacks.clear();
            return CompletableFuture.completedFuture(null);
        }
        if (endWorld == null) {
            return CompletableFuture.completedFuture(null);
        }
        UUID worldId = endWorld.getUID();
        List<PendingBlockRollback> priorForWorld = new ArrayList<>();
        for (PendingBlockRollback entry : unrecoveredPersistedRollbacks.values()) {
            if (entry.worldId().equals(worldId)) {
                priorForWorld.add(entry);
            }
        }
        for (PendingBlockRollback entry : blockTracker.exportPendingRollbacks()) {
            if (entry.worldId().equals(worldId)) {
                unrecoveredPersistedRollbacks.put(entry.toPosKey(), entry);
            }
        }
        java.util.function.Consumer<List<BlockPosKey>> removeRestored = restoredKeys -> {
            for (BlockPosKey key : restoredKeys) {
                unrecoveredPersistedRollbacks.remove(key);
            }
        };
        CompletableFuture<?> activeFuture = blockTracker.rollbackAll(scheduler, endWorld, removeRestored);
        CompletableFuture<?> retryFuture = priorForWorld.isEmpty()
                ? CompletableFuture.completedFuture(List.of())
                : blockTracker.rollbackPendingEntries(
                        scheduler,
                        id -> worldId.equals(id) ? endWorld : null,
                        priorForWorld,
                        removeRestored);
        return CompletableFuture.allOf(activeFuture, retryFuture)
                .handle((ignored, ex) -> {
                    if (ex != null) {
                        logger.log(Level.WARNING, "Failed to roll back temporary raid blocks", ex);
                    }
                    return null;
                });
    }

    private void updateSnapshot(Player player) {
        updateSnapshot(player, player.getLocation());
    }

    private void updateSnapshot(Player player, Location loc) {
        UUID id = player.getUniqueId();
        boolean holdingRight = player.isHandRaised()
                || (clockMillis.getAsLong() - lastRightClickMillis.getOrDefault(id, 0L) <= RIGHT_CLICK_HOLD_WINDOW_MS);
        snapshots.put(id, new PlayerSnapshot(
                id,
                player.getName(),
                loc.getWorld() != null ? loc.getWorld().getName() : null,
                loc.clone(),
                player.isSneaking(),
                holdingRight,
                regionService.isInArena(loc),
                regionService.isInSafeZone(loc),
                regionService.isBelowVoidThreshold(loc)
        ));
        RaidParticipant participant = participants.get(id);
        if (participant != null) {
            participant.markInitialSnapshotReady();
        }
    }

    private List<Player> onlinePlayersInWorld(World world) {
        List<Player> list = new ArrayList<>();
        for (Player player : onlinePlayersSupplier.get()) {
            if (player != null && player.isOnline() && world.equals(player.getWorld())) {
                list.add(player);
            }
        }
        return list;
    }

    private List<Player> collectPullCandidates(World endWorld) {
        List<Player> candidates = new ArrayList<>();
        World activeInst = instanceManager != null ? instanceManager.activeCombatWorld(null) : null;
        List<Player> pool = new ArrayList<>(onlinePlayersInWorld(endWorld));
        if (activeInst != null && !activeInst.equals(endWorld)) {
            pool.addAll(onlinePlayersInWorld(activeInst));
        }
        for (Player player : pool) {
            PlayerSnapshot snap = snapshots.get(player.getUniqueId());
            boolean inArena = snap != null ? snap.inArena() : regionService.isInArena(player.getLocation());
            boolean inSafe = snap != null ? snap.inSafeZone() : regionService.isInSafeZone(player.getLocation());
            if (inArena || inSafe) {
                candidates.add(player);
            }
        }
        return candidates;
    }

    private boolean isPlayerInCombatArea(Player player) {
        PlayerSnapshot snap = snapshots.get(player.getUniqueId());
        boolean inArena = snap != null ? snap.inArena() : regionService.isInArena(player.getLocation());
        boolean inSafe = snap != null ? snap.inSafeZone() : regionService.isInSafeZone(player.getLocation());
        return inArena && !inSafe;
    }

    private void loadPendingCleanupsFromStorage() {
        if (recoveryRepository == null) {
            return;
        }
        Map<UUID, PendingPlayerCleanup> loaded = recoveryRepository.loadPendingPlayerCleanupsSync();
        for (PendingPlayerCleanup cleanup : loaded.values()) {
            GameMode mode = null;
            if (cleanup.gameModeName() != null) {
                try {
                    mode = GameMode.valueOf(cleanup.gameModeName());
                } catch (IllegalArgumentException ignored) {
                    // Ignore unrecognized enum names safely.
                }
            }
            pendingCleanupPlayers.put(cleanup.playerId(), new SavedCleanupRecord(
                    mode,
                    cleanup.invisible(),
                    cleanup.collidable(),
                    cleanup.invulnerable(),
                    cleanup.teleportToSafeZone()
            ));
        }
    }

    private void persistPlayerCleanupAsync(RaidParticipant participant, boolean teleportToSafeZone) {
        if (recoveryRepository == null || !participant.isStateCaptured()) {
            return;
        }
        SavedCleanupRecord record = new SavedCleanupRecord(
                participant.previousGameMode(),
                participant.previousInvisible(),
                participant.previousCollidable(),
                participant.previousInvulnerable(),
                teleportToSafeZone
        );
        recoveryRepository.upsertPendingPlayerCleanupAsync(record.toPendingCleanup(participant.playerId()));
    }

    private void deletePlayerCleanupAsync(UUID playerId) {
        if (recoveryRepository != null) {
            recoveryRepository.deletePendingPlayerCleanupAsync(playerId);
        }
    }

    private int countPlayersInCombatArena(List<Player> players) {
        int count = 0;
        for (Player player : players) {
            PlayerSnapshot snap = snapshots.get(player.getUniqueId());
            boolean inArena = snap != null ? snap.inArena() : regionService.isInArena(player.getLocation());
            boolean inSafe = snap != null ? snap.inSafeZone() : regionService.isInSafeZone(player.getLocation());
            if (inArena && !inSafe) {
                count++;
            }
        }
        return count;
    }

    private void sendRevivingActionBar(Player reviver, String targetName, double ratio) {
        int percent = Math.max(0, Math.min(100, (int) Math.round(ratio * 100.0)));
        int filled = Math.max(0, Math.min(10, (int) Math.round(ratio * 10.0)));
        String bar = "■".repeat(filled) + "□".repeat(10 - filled);
        scheduler.runAtEntity(reviver, () -> {
            Component comp = messageService.render(
                    reviver,
                    "revive.reviving-actionbar",
                    Placeholder.unparsed("player", targetName),
                    Placeholder.unparsed("bar", bar),
                    Placeholder.unparsed("percent", String.valueOf(percent))
            );
            reviver.sendActionBar(comp);
        });
    }

    private void sendRateLimitedMessage(Player player, String key, long now) {
        Long last = lastFeedbackMillis.get(player.getUniqueId());
        if (last != null && now - last < FEEDBACK_COOLDOWN_MS) {
            return;
        }
        lastFeedbackMillis.put(player.getUniqueId(), now);
        scheduler.runAtEntity(player, () -> messageService.send(player, key));
    }

    private static double maxHealth(Player player) {
        if (Bukkit.getServer() != null) {
            AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                return attr.getValue();
            }
        }
        return legacyMaxHealth(player);
    }

    @SuppressWarnings("deprecation")
    private static double legacyMaxHealth(Player player) {
        return player.getMaxHealth();
    }

    private static void cancelHandle(@Nullable TaskHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    private static boolean hasLivingDragonInWorld(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null) {
            return false;
        }
        EnderDragon dragon = battle.getEnderDragon();
        return dragon != null && !dragon.isDead();
    }

    private void startM3EncounterSystems(
            World fallbackEndWorld,
            List<Player> party,
            BossConfig bossConfig,
            ScaledEncounterParameters scaled,
            long expectedGeneration) {
        if (bossController == null || gimmickExecutor == null || crystalManager == null) {
            return;
        }
        if (instanceManager != null) {
            instanceManager.getOrCreateInstance().whenComplete((instance, err) -> {
                if (err != null || instance == null) {
                    logger.log(Level.WARNING, "Failed to prepare temporary raid instance; falling back to End world", err);
                    activateM3InCombatWorld(fallbackEndWorld, bossConfig, scaled, expectedGeneration);
                    return;
                }
                World instWorld = instance.world();
                Location entrySpawn = regionService.safeZoneSpawnLocation(instWorld);
                for (Player member : party) {
                    if (member != null && member.isOnline() && !instWorld.equals(member.getWorld())) {
                        scheduler.runAtEntity(member, () -> {
                            member.teleportAsync(entrySpawn);
                            updateSnapshot(member, entrySpawn);
                        });
                    }
                }
                activateM3InCombatWorld(instWorld, bossConfig, scaled, expectedGeneration);
            });
            return;
        }
        activateM3InCombatWorld(fallbackEndWorld, bossConfig, scaled, expectedGeneration);
    }

    private void activateM3InCombatWorld(
            World endWorld,
            BossConfig bossConfig,
            ScaledEncounterParameters scaled,
            long expectedGeneration) {
        if (bossController == null || gimmickExecutor == null || crystalManager == null) {
            return;
        }
        final RaidTimelineEngine timeline;
        final CompletableFuture<Void> bossReadyFuture;
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE || encounterGeneration.get() != expectedGeneration) {
                return;
            }
            crystalManager.clearAll();
            gimmickExecutor.resetState();

            timeline = new RaidTimelineEngine(bossConfig, new TimelineListener() {
                @Override
                public void onPhaseChanged(BossConfig.PhaseConfig newPhase) {
                    gimmickExecutor.cancelActiveMechanic();
                    bossController.setPosture(NormalGimmickExecutor.defaultPostureForPhase(newPhase));
                    List<Player> viewers = onlinePlayersInWorld(endWorld);
                    if (hudService != null) {
                        hudService.updateCastBossBar(null, 0.0, 0.0, viewers);
                        hudService.announcePhaseTransition(newPhase, viewers);
                    }
                }

                @Override
                public void onCastStart(
                        BossConfig.TimelineStep step,
                        @Nullable BossConfig.AbilitySettings ability,
                        double totalCastSeconds) {
                    List<Player> viewers = onlinePlayersInWorld(endWorld);
                    List<Player> alive = aliveOnlineParticipants();
                    gimmickExecutor.startCast(step, ability, endWorld, alive, viewers, scaled, bossController);
                    if (hudService != null) {
                        hudService.updateCastBossBar(ability, totalCastSeconds, totalCastSeconds, viewers);
                    }
                }

                @Override
                public void onCastTick(
                        BossConfig.TimelineStep step,
                        @Nullable BossConfig.AbilitySettings ability,
                        double remainingSeconds,
                        double totalCastSeconds) {
                    if (hudService != null) {
                        hudService.updateCastBossBar(ability, remainingSeconds, totalCastSeconds, onlinePlayersInWorld(endWorld));
                    }
                }

                @Override
                public void onCastComplete(
                        BossConfig.TimelineStep step,
                        @Nullable BossConfig.AbilitySettings ability) {
                    List<Player> viewers = onlinePlayersInWorld(endWorld);
                    if (hudService != null) {
                        hudService.updateCastBossBar(null, 0.0, 0.0, viewers);
                    }
                    RaidTimelineEngine engine = activeTimeline;
                    if (engine != null) {
                        gimmickExecutor.completeCast(
                                step,
                                ability,
                                aliveOnlineParticipants(),
                                viewers,
                                scaled,
                                bossController,
                                engine
                        );
                    }
                }

                @Override
                public void onCastInterrupted(
                        BossConfig.TimelineStep step,
                        @Nullable BossConfig.AbilitySettings ability) {
                    if (hudService != null) {
                        hudService.updateCastBossBar(null, 0.0, 0.0, onlinePlayersInWorld(endWorld));
                    }
                    gimmickExecutor.cancelActiveMechanic();
                }

                @Override
                public void onInstantAction(
                        BossConfig.TimelineStep step,
                        @Nullable BossConfig.AbilitySettings ability) {
                    gimmickExecutor.executeInstantAction(step, ability, endWorld, scaled, onlinePlayersInWorld(endWorld));
                }
            });
            this.activeTimeline = timeline;
            timeline.start();
            bossReadyFuture = bossController.bindOrSpawn(endWorld, scaled.scaledBossHp());
        }
        if (bossReadyFuture != null) {
            bossReadyFuture.exceptionally(ex -> {
                logger.log(Level.SEVERE, "Boss spawn/binding failed; aborting raid encounter", ex);
                abortOnBossSpawnFailure(endWorld, expectedGeneration);
                return null;
            });
        }
        if (hudService != null && state == RaidState.ACTIVE && encounterGeneration.get() == expectedGeneration) {
            BossConfig.PhaseConfig phase = timeline.currentPhase();
            if (phase != null) {
                hudService.updateMainBossBar(bossConfig, phase, bossController.hpRatio(), onlinePlayersInWorld(endWorld));
            }
        }
    }

    private void abortOnBossSpawnFailure(World endWorld, long expectedGeneration) {
        long endingGeneration;
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE || encounterGeneration.get() != expectedGeneration) {
                return;
            }
            cancelHandle(monitorTask);
            monitorTask = null;
            endingGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
        }
        List<Player> viewers = onlinePlayersInWorld(endWorld);
        soulMarkerService.removeAllMarkers(viewers);
        cleanupM3Systems(viewers, true);
        restoreAndResetParticipants(regionService.safeZoneSpawnLocation(endWorld));
        CompletableFuture<Void> rollbackFuture = rollbackBlocksIfConfigured(endWorld);
        completeEndingWhenRolledBack(endingGeneration, rollbackFuture);
    }

    private void tickM3Systems(World endWorld, List<Player> endPlayers, Set<UUID> channelingRevivers) {
        if (bossController == null) {
            return;
        }
        bossController.tick(MONITOR_STEP_SECONDS);
        if (crystalManager != null) {
            double regen = crystalManager.tick(MONITOR_STEP_SECONDS, bossController.bossLocation(), endPlayers);
            if (regen > 0.0) {
                bossController.healBoss(regen);
            }
        }
        List<Player> alive = aliveOnlineParticipants();
        if (gimmickExecutor != null) {
            gimmickExecutor.sampleShieldStates(alive);
        }
        RaidTimelineEngine timeline = this.activeTimeline;
        if (timeline != null) {
            timeline.tick(MONITOR_STEP_SECONDS, bossController.hpRatio());
        }
        BossConfig bossConfig = this.activeBossConfig;
        BossConfig.PhaseConfig currentPhase = timeline != null ? timeline.currentPhase() : null;
        if (gimmickExecutor != null) {
            gimmickExecutor.tickActiveTelegraphs(alive, endPlayers, bossController);
            if (bossConfig != null) {
                gimmickExecutor.tickSoftEnrage(MONITOR_STEP_SECONDS, bossConfig, currentPhase, endWorld, alive, endPlayers);
            }
        }
        if (hudService != null && bossConfig != null && currentPhase != null) {
            hudService.updateMainBossBar(bossConfig, currentPhase, bossController.hpRatio(), endPlayers);
            List<Player> participantPlayers = onlineParticipantPlayers();
            hudService.updateActionBars(
                    participantPlayers,
                    id -> {
                        RaidParticipant p = participants.get(id);
                        return p != null ? p.state() : ParticipantState.ALIVE;
                    },
                    remainingRevives.get(),
                    playerId -> {
                        if (gimmickExecutor == null) {
                            return null;
                        }
                        Player player = playerLookup.apply(playerId);
                        return player != null ? gimmickExecutor.extraStatusFor(player) : null;
                    },
                    channelingRevivers::contains
            );
        }
    }

    private void prepareVictoryM3Cleanup(
            EnderDragon dragon,
            World endWorld,
            List<Player> viewers,
            BossConfig.Difficulty difficulty,
            long durationMs) {
        if (playerDataRepository != null) {
            Difficulty storageDifficulty = difficulty == BossConfig.Difficulty.HARD ? Difficulty.HARD : Difficulty.NORMAL;
            for (RaidParticipant participant : participants.values()) {
                playerDataRepository.recordClear(
                        participant.playerId(),
                        participant.name(),
                        storageDifficulty,
                        durationMs
                ).exceptionally(ex -> {
                    logger.log(Level.WARNING, "Failed to record raid clear for " + participant.name(), ex);
                    return null;
                });
            }
        }
        RaidTimelineEngine timeline = this.activeTimeline;
        this.activeTimeline = null;
        if (timeline != null) {
            timeline.stop();
        }
        if (gimmickExecutor != null) {
            gimmickExecutor.resetState();
        }
        if (crystalManager != null) {
            crystalManager.clearAll();
        }
        if (hudService != null) {
            hudService.clearAll(viewers);
        }
        if (bossController != null) {
            bossController.prepareForVanillaDefeat();
        }
        if (configManager.pluginConfig().world().preserveVanillaProgression() && dragon.getDragonBattle() == null) {
            World mainEnd = endWorldSupplier.get();
            World progressionWorld = mainEnd != null ? mainEnd : endWorld;
            DragonBattle battle = progressionWorld.getEnderDragonBattle();
            if (battle != null) {
                Location portalAnchor = battle.getEndPortalLocation() != null
                        ? battle.getEndPortalLocation()
                        : new Location(progressionWorld, 0.0, 64.0, 0.0);
                scheduler.runAtRegion(portalAnchor, () -> {
                    if (battle.getEndPortalLocation() == null) {
                        battle.generateEndPortal(true);
                    }
                    battle.spawnNewGateway();
                    if (!battle.hasBeenPreviouslyKilled()) {
                        battle.setPreviouslyKilled(true);
                    }
                });
            }
        }
    }

    private CompletableFuture<Void> cleanupM3Systems(List<Player> viewers, boolean removeDragonIfSummoned) {
        RaidTimelineEngine timeline = this.activeTimeline;
        this.activeTimeline = null;
        if (timeline != null) {
            timeline.stop();
        }
        if (gimmickExecutor != null) {
            gimmickExecutor.resetState();
        }
        CompletableFuture<Void> crystalFuture = crystalManager != null
                ? crystalManager.clearAll()
                : CompletableFuture.completedFuture(null);
        if (hudService != null) {
            hudService.clearAll(viewers);
        }
        CompletableFuture<Void> bossFuture = bossController != null
                ? bossController.cleanup(removeDragonIfSummoned)
                : CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(
                crystalFuture != null ? crystalFuture : CompletableFuture.completedFuture(null),
                bossFuture != null ? bossFuture : CompletableFuture.completedFuture(null)
        );
    }

    private void maybeInitializeFirstClear(Player player, World endWorld) {
        if (!configManager.pluginConfig().world().replaceFirstDragon() || bossController == null) {
            return;
        }
        DragonBattle battle = endWorld.getEnderDragonBattle();
        if (battle == null || battle.hasBeenPreviouslyKilled()) {
            return;
        }
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon == null || dragon.isDead()) {
            return;
        }
        bossController.holdFirstClearIdle(endWorld);
        UUID dragonId = dragon.getUniqueId();
        if (!dragonId.equals(announcedFirstClearDragonId)) {
            announcedFirstClearDragonId = dragonId;
            scheduler.runAtEntity(player, () -> messageService.send(player, "raid.first-clear-init"));
        }
    }

    private List<Player> aliveOnlineParticipants() {
        List<Player> list = new ArrayList<>();
        for (RaidParticipant p : participants.values()) {
            if (p.isOnline() && p.state() == ParticipantState.ALIVE) {
                Player player = playerLookup.apply(p.playerId());
                if (player != null && player.isOnline()) {
                    list.add(player);
                }
            }
        }
        return list;
    }

    private List<Player> onlineParticipantPlayers() {
        List<Player> list = new ArrayList<>();
        for (RaidParticipant p : participants.values()) {
            if (p.isOnline()) {
                Player player = playerLookup.apply(p.playerId());
                if (player != null && player.isOnline()) {
                    list.add(player);
                }
            }
        }
        return list;
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long millis = (durationMs % 1000L) / 10L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, millis);
    }

    private record PlayerSnapshot(
            UUID playerId,
            String name,
            @Nullable String worldName,
            @Nullable Location location,
            boolean sneaking,
            boolean holdingRightClick,
            boolean inArena,
            boolean inSafeZone,
            boolean belowVoid
    ) {
    }

    private record SavedCleanupRecord(
            @Nullable GameMode gameMode,
            boolean invisible,
            boolean collidable,
            boolean invulnerable,
            boolean teleportToSafeZone
    ) {
        void applyTo(Player player) {
            if (gameMode != null && player.getGameMode() != gameMode) {
                player.setGameMode(gameMode);
            }
            player.setInvisible(invisible);
            player.setCollidable(collidable);
            player.setInvulnerable(invulnerable);
        }

        PendingPlayerCleanup toPendingCleanup(UUID playerId) {
            return new PendingPlayerCleanup(
                    playerId,
                    gameMode != null ? gameMode.name() : null,
                    invisible,
                    collidable,
                    invulnerable,
                    teleportToSafeZone
            );
        }
    }
}
