package net.sabafly.dragonraid.encounter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.enemy.RaidEnemyDefinition;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterBlueprintRegistry;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.GimmickActionRegistry;
import net.sabafly.dragonraid.api.script.RaidScriptContext;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.EncounterSettings;
import net.sabafly.dragonraid.config.PluginConfig.ProtectionSettings;
import net.sabafly.dragonraid.config.PluginConfig.ReviveSettings;
import net.sabafly.dragonraid.config.PluginConfig.ScalingSettings;
import net.sabafly.dragonraid.core.CombatMetricsTracker;
import net.sabafly.dragonraid.core.DataDrivenEnemyRegistry;
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
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
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
    private final @Nullable EncounterBlueprintRegistry blueprintRegistry;
    private final @Nullable GimmickActionRegistry gimmickActionRegistry;
    private final @Nullable RaidInstanceManager instanceManager;
    private final Logger logger;
    private final Supplier<@Nullable World> endWorldSupplier;
    private final Supplier<Collection<? extends Player>> onlinePlayersSupplier;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final LongSupplier clockMillis;

    private volatile RaidState state = RaidState.IDLE;
    private volatile @Nullable BossConfig.Difficulty activeDifficulty;
    private volatile @Nullable BossConfig activeBossConfig;
    private volatile @Nullable EncounterBlueprint activeBlueprint;
    private volatile @Nullable ScaledEncounterParameters activeScaling;
    private volatile @Nullable RaidTimelineEngine activeTimeline;
    private final AtomicInteger remainingRevives = new AtomicInteger(0);
    private final AtomicLong encounterGeneration = new AtomicLong(0L);
    private final AtomicLong completionSequence = new AtomicLong(0L);
    private long lastPublishedCompletionSeq = 0L;
    private final AtomicInteger arenaUpdatesInFlight = new AtomicInteger(0);
    private volatile long combatStartedMillis;
    private volatile boolean awaitingInstanceEntry;
    private final CombatMetricsTracker combatMetricsTracker = new CombatMetricsTracker();
    private final DataDrivenEnemyRegistry enemyRegistry = new DataDrivenEnemyRegistry();
    private volatile @Nullable EncounterResultSnapshot lastCompletedResult;
    private volatile @Nullable Consumer<EncounterResultSnapshot> encounterCompletedSink;
    private volatile @Nullable VanillaEndProgressionPolicy vanillaEndProgressionPolicy;
    private volatile @Nullable DutyStandbySession standbySession;

    private final Map<UUID, RaidParticipant> participants = new ConcurrentHashMap<>();
    private final Map<UUID, Location> activeReturnLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Set<CompletableFuture<Void>>> inFlightInstanceWithdrawals = new ConcurrentHashMap<>();
    private final Set<UUID> endingWipeMemberIds = new HashSet<>();
    private final Set<UUID> disconnectedDutyMembers = new HashSet<>();
    private final Map<UUID, SavedCleanupRecord> pendingCleanupPlayers = new ConcurrentHashMap<>();
    private final Map<BlockPosKey, PendingBlockRollback> unrecoveredPersistedRollbacks = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRightClickMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFeedbackMillis = new ConcurrentHashMap<>();
    private volatile Function<UUID, @Nullable World> worldLookup =
            id -> Bukkit.getServer() != null ? Bukkit.getWorld(id) : null;

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
                null,
                logger,
                () -> Bukkit.getWorld(configManager.pluginConfig().world().endWorldName()),
                Bukkit::getOnlinePlayers,
                Bukkit::getPlayer,
                System::currentTimeMillis
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
            @Nullable EncounterBlueprintRegistry blueprintRegistry,
            @Nullable GimmickActionRegistry gimmickActionRegistry,
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
                blueprintRegistry,
                gimmickActionRegistry,
                instanceManager,
                logger,
                () -> Bukkit.getWorld(configManager.pluginConfig().world().endWorldName()),
                Bukkit::getOnlinePlayers,
                Bukkit::getPlayer,
                System::currentTimeMillis
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
            LongSupplier clockMillis) {
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
                null,
                logger,
                endWorldSupplier,
                onlinePlayersSupplier,
                playerLookup,
                clockMillis
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
            @Nullable EncounterBlueprintRegistry blueprintRegistry,
            @Nullable GimmickActionRegistry gimmickActionRegistry,
            @Nullable RaidInstanceManager instanceManager,
            Logger logger,
            Supplier<@Nullable World> endWorldSupplier,
            Supplier<Collection<? extends Player>> onlinePlayersSupplier,
            Function<UUID, @Nullable Player> playerLookup,
            LongSupplier clockMillis) {
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
        this.blueprintRegistry = blueprintRegistry;
        this.gimmickActionRegistry = gimmickActionRegistry;
        this.instanceManager = instanceManager;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.endWorldSupplier = Objects.requireNonNull(endWorldSupplier, "endWorldSupplier");
        this.onlinePlayersSupplier = Objects.requireNonNull(onlinePlayersSupplier, "onlinePlayersSupplier");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        loadPendingCleanupsFromStorage();
    }

    public CombatMetricsTracker combatMetricsTracker() {
        return combatMetricsTracker;
    }

    public DataDrivenEnemyRegistry enemyRegistry() {
        return enemyRegistry;
    }

    public @Nullable EncounterResultSnapshot lastCompletedResult() {
        return lastCompletedResult;
    }

    public void setEncounterCompletedSink(@Nullable Consumer<EncounterResultSnapshot> sink) {
        this.encounterCompletedSink = sink;
    }

    void setWorldLookup(Function<UUID, @Nullable World> worldLookup) {
        this.worldLookup = Objects.requireNonNull(worldLookup, "worldLookup");
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
    public boolean startEncounter(EncounterBlueprint blueprint, Collection<? extends Player> players) {
        return startEncounter(blueprint, players, Map.of());
    }

    @Override
    public boolean startEncounter(
            EncounterBlueprint blueprint,
            Collection<? extends Player> players,
            Map<UUID, Location> returnLocations) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(returnLocations, "returnLocations");
        Map<UUID, Player> uniquePlayers = new LinkedHashMap<>();
        for (Player p : players) {
            if (p != null && p.isOnline()) {
                uniquePlayers.putIfAbsent(p.getUniqueId(), p);
            }
        }
        List<Player> party = List.copyOf(uniquePlayers.values());
        if (party.isEmpty()) {
            return false;
        }
        if (standbySession != null || (instanceManager != null && instanceManager.activeInstance() != null)) {
            return false;
        }
        ScalingSettings scaling = configManager.pluginConfig().scaling();
        if (party.size() < scaling.minPlayers()
                || (scaling.enforceMaxPlayers() && party.size() > scaling.maxPlayers())) {
            return false;
        }
        World endWorld = endWorldSupplier.get();
        if (endWorld == null) {
            endWorld = party.getFirst().getWorld();
        }
        if (endWorld == null) {
            return false;
        }
        BossConfig bossConfig = BossConfig.fromBlueprint(blueprint);
        Map<UUID, Location> sanitizedReturns = new LinkedHashMap<>();
        for (Player member : party) {
            Location captured = returnLocations.get(member.getUniqueId());
            if (captured != null && isValidReturnLocation(captured)) {
                sanitizedReturns.put(member.getUniqueId(), captured.clone());
            }
        }
        return activateEncounterWithParty(
                blueprint, bossConfig, endWorld, party, List.of(), true, -1L, sanitizedReturns, false);
    }

    @Override
    public boolean hasActiveDutySession() {
        return state != RaidState.IDLE
                || standbySession != null
                || (instanceManager != null && instanceManager.activeInstance() != null);
    }

    @Override
    public boolean isInDutyOrInstance(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (participants.containsKey(playerId)) {
            return true;
        }
        DutyStandbySession standby = standbySession;
        if (standby != null && standby.memberIds().contains(playerId)) {
            return true;
        }
        SavedCleanupRecord pending = pendingCleanupPlayers.get(playerId);
        if (pending != null && (pending.hasReturnLocation() || pending.teleportToSafeZone())) {
            Player player = playerLookup.apply(playerId);
            if (player != null && player.isOnline() && player.getWorld() != null) {
                World world = player.getWorld();
                if (instanceManager != null) {
                    RaidInstanceManager.RaidInstance activeInst = instanceManager.activeInstance();
                    if (instanceManager.isInstanceWorld(world)
                            || (activeInst != null && world.equals(activeInst.world()))) {
                        return true;
                    }
                }
                if (state != RaidState.IDLE && regionService.isRaidWorld(world)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isInWipeStandby(UUID playerId) {
        DutyStandbySession standby = standbySession;
        return playerId != null && standby != null && standby.memberIds().contains(playerId);
    }

    @Override
    public boolean retryEncounter(Player requester) {
        Objects.requireNonNull(requester, "requester");
        final DutyStandbySession standby;
        final long reservedGeneration;
        final List<Player> retryParty = new ArrayList<>();
        final Map<UUID, Location> retryReturns = new LinkedHashMap<>();
        synchronized (stateLock) {
            standby = this.standbySession;
            if (standby == null || state != RaidState.IDLE
                    || arenaUpdatesInFlight.get() > 0 || hasPendingBlockRollbacks()) {
                return false;
            }
            UUID requesterId = requester.getUniqueId();
            if (!standby.memberIds().contains(requesterId)) {
                return false;
            }
            if (instanceManager != null) {
                RaidInstanceManager.RaidInstance activeInst = instanceManager.activeInstance();
                if (activeInst != null && standby.instance() != null && !standby.instance().equals(activeInst)) {
                    return false;
                }
            }
            for (UUID memberId : standby.memberIds()) {
                if (disconnectedDutyMembers.contains(memberId)) {
                    continue;
                }
                Player onlineMember = playerLookup.apply(memberId);
                if (onlineMember != null && onlineMember.isOnline()) {
                    retryParty.add(onlineMember);
                    Location ret = standby.returnLocations().get(memberId);
                    if (ret == null) {
                        ret = resolveSavedReturnLocation(pendingCleanupPlayers.get(memberId));
                    }
                    if (ret != null) {
                        retryReturns.put(memberId, ret.clone());
                    }
                }
            }
            if (retryParty.isEmpty()) {
                return false;
            }
            ScalingSettings scaling = configManager.pluginConfig().scaling();
            if (retryParty.size() < scaling.minPlayers()
                    || (scaling.enforceMaxPlayers() && retryParty.size() > scaling.maxPlayers())) {
                return false;
            }
            reservedGeneration = encounterGeneration.get();
            this.standbySession = null;
        }
        boolean started = activateEncounterWithParty(
                standby.blueprint(),
                standby.bossConfig(),
                standby.fallbackEndWorld(),
                List.copyOf(retryParty),
                List.of(),
                true,
                reservedGeneration,
                retryReturns,
                true
        );
        if (!started) {
            synchronized (stateLock) {
                if (this.standbySession == null
                        && state == RaidState.IDLE
                        && encounterGeneration.get() == reservedGeneration) {
                    this.standbySession = standby;
                }
            }
        }
        return started;
    }

    @Override
    public boolean leaveEncounter(Player player) {
        return leaveEncounterDetailed(player) == LeaveEncounterResult.LEFT;
    }

    @Override
    public LeaveEncounterResult leaveEncounterDetailed(Player player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        World mainEndWorld = endWorldSupplier.get();
        Location defaultFallback = mainEndWorld != null ? regionService.safeZoneSpawnLocation(mainEndWorld) : null;

        // Case 1: Withdrawing during post-wipe standby in temporary instance
        DutyStandbySession standbySnapshot = null;
        Location standbyReturn = null;
        SavedCleanupRecord standbyReturnRecord = null;
        boolean destroyEmptyStandby = false;
        RaidInstanceManager.RaidInstance standbyInstance = null;
        CompletableFuture<Void> standbyWithdrawalGate = null;
        synchronized (stateLock) {
            DutyStandbySession currentStandby = this.standbySession;
            if (currentStandby != null && currentStandby.memberIds().contains(playerId)) {
                standbyReturn = resolveReturnLocation(
                        playerId,
                        currentStandby.returnLocations().get(playerId),
                        defaultFallback
                );
                if (standbyReturn != null && standbyReturn.getWorld() != null) {
                    standbyReturnRecord = SavedCleanupRecord.returnOnly(standbyReturn);
                    pendingCleanupPlayers.put(playerId, standbyReturnRecord);
                }
                standbyInstance = currentStandby.instance() != null
                        ? currentStandby.instance()
                        : (instanceManager != null ? instanceManager.activeInstance() : null);
                standbyWithdrawalGate = new CompletableFuture<>();
                registerInstanceWithdrawal(standbyInstance, standbyWithdrawalGate);
                Set<UUID> remainingIds = new LinkedHashSet<>(currentStandby.memberIds());
                remainingIds.remove(playerId);
                Map<UUID, Location> remainingReturns = new LinkedHashMap<>(currentStandby.returnLocations());
                remainingReturns.remove(playerId);
                activeReturnLocations.remove(playerId);
                disconnectedDutyMembers.remove(playerId);
                boolean anyRemainingOnline = remainingIds.stream().anyMatch(id -> {
                    if (disconnectedDutyMembers.contains(id)) {
                        return false;
                    }
                    Player p = playerLookup.apply(id);
                    return p != null && p.isOnline();
                });
                if (remainingIds.isEmpty() || !anyRemainingOnline) {
                    this.standbySession = null;
                    this.activeReturnLocations.clear();
                    this.disconnectedDutyMembers.clear();
                    destroyEmptyStandby = true;
                } else {
                    this.standbySession = new DutyStandbySession(
                            currentStandby.blueprint(),
                            currentStandby.bossConfig(),
                            currentStandby.fallbackEndWorld(),
                            Set.copyOf(remainingIds),
                            Map.copyOf(remainingReturns),
                            currentStandby.instance(),
                            currentStandby.rollbackFuture()
                    );
                }
                standbySnapshot = currentStandby;
            }
        }
        if (standbySnapshot != null) {
            Location dest = standbyReturn != null ? standbyReturn : defaultFallback;
            CompletableFuture<Void> gate = standbyWithdrawalGate;
            CompletableFuture<Void> tpFuture = teleportPlayerForWithdrawal(player, null, standbyReturnRecord, dest);
            tpFuture.whenComplete((ignored, err) -> {
                if (gate != null) {
                    gate.complete(null);
                }
            });
            if (destroyEmptyStandby && instanceManager != null) {
                RaidInstanceManager.RaidInstance targetInst = standbyInstance != null
                        ? standbyInstance : instanceManager.activeInstance();
                if (targetInst != null) {
                    Location emptyFallback = dest != null ? dest : defaultFallback;
                    destroyInstanceWhenReady(targetInst, standbySnapshot.rollbackFuture(), emptyFallback);
                }
            }
            return LeaveEncounterResult.LEFT;
        }

        // Case 2: Withdrawing during ACTIVE / WIPING encounter
        RaidParticipant withdrawn = null;
        Location activeReturn = null;
        SavedCleanupRecord cleanupRecord = null;
        boolean triggerFullTeardown = false;
        long currentGen = 0L;
        RaidInstanceManager.RaidInstance activeInst = null;
        CompletableFuture<Void> activeWithdrawalGate = null;
        boolean matchedActive = false;
        synchronized (stateLock) {
            if (state == RaidState.ENDING && participants.containsKey(playerId)) {
                return LeaveEncounterResult.ENDING_IN_PROGRESS;
            }
            if ((state == RaidState.ACTIVE || state == RaidState.WIPING)
                    && participants.containsKey(playerId)) {
                matchedActive = true;
                withdrawn = participants.remove(playerId);
                activeReturn = resolveReturnLocation(
                        playerId,
                        withdrawn != null ? withdrawn.preEntryReturnLocation() : null,
                        defaultFallback
                );
                if (withdrawn != null && withdrawn.isStateCaptured()) {
                    cleanupRecord = SavedCleanupRecord.withFlags(
                            withdrawn.previousGameMode(),
                            withdrawn.previousInvisible(),
                            withdrawn.previousCollidable(),
                            withdrawn.previousInvulnerable(),
                            true,
                            activeReturn
                    );
                } else if (activeReturn != null && activeReturn.getWorld() != null) {
                    cleanupRecord = SavedCleanupRecord.returnOnly(activeReturn);
                }
                if (cleanupRecord != null) {
                    pendingCleanupPlayers.put(playerId, cleanupRecord);
                }
                activeReturnLocations.remove(playerId);
                disconnectedDutyMembers.remove(playerId);
                snapshots.remove(playerId);
                lastRightClickMillis.remove(playerId);
                lastFeedbackMillis.remove(playerId);
                currentGen = encounterGeneration.get();
                activeInst = instanceManager != null ? instanceManager.activeInstance() : null;
                activeWithdrawalGate = new CompletableFuture<>();
                registerInstanceWithdrawal(activeInst, activeWithdrawalGate);
                boolean anyOnlineLeft = participants.values().stream().anyMatch(p -> {
                    if (!p.isOnline() || disconnectedDutyMembers.contains(p.playerId())) {
                        return false;
                    }
                    Player other = playerLookup.apply(p.playerId());
                    return other != null && other.isOnline();
                });
                triggerFullTeardown = participants.isEmpty() || !anyOnlineLeft;
            }
        }

        if (matchedActive) {
            soulMarkerService.invalidateViewer(playerId);
            if (hudService != null) {
                hudService.removeViewer(player);
            }

            World combatWorld = instanceManager != null ? instanceManager.activeCombatWorld(mainEndWorld) : mainEndWorld;
            if (withdrawn != null && withdrawn.state() == ParticipantState.DOWNED) {
                soulMarkerService.removeMarker(
                        playerId,
                        combatWorld != null ? onlinePlayersInWorld(combatWorld) : List.of()
                );
            }
            CompletableFuture<Void> withdrawTp = teleportPlayerForWithdrawal(player, withdrawn, cleanupRecord, activeReturn);
            CompletableFuture<Void> gate = activeWithdrawalGate;
            withdrawTp.whenComplete((ignored, err) -> {
                if (gate != null) {
                    gate.complete(null);
                }
            });

            if (triggerFullTeardown) {
                teardownAfterAllParticipantsLeft(combatWorld, mainEndWorld, defaultFallback, currentGen, activeWithdrawalGate);
            } else if (state == RaidState.ACTIVE) {
                evaluateWipeCondition(combatWorld, configManager.pluginConfig().revive());
            }
            return LeaveEncounterResult.LEFT;
        }

        // Case 3: Retry pending failed withdrawal return
        SavedCleanupRecord pendingReturn = pendingCleanupPlayers.get(playerId);
        if (pendingReturn != null && (pendingReturn.hasReturnLocation() || pendingReturn.teleportToSafeZone())) {
            Location retryDest;
            if (pendingReturn.hasReturnLocation()) {
                retryDest = resolveSavedReturnLocation(pendingReturn);
                if (retryDest == null || retryDest.getWorld() == null) {
                    scheduler.runAtEntity(player, () -> {
                        if (pendingReturn.restoreFlags()) {
                            pendingReturn.applyTo(player);
                        }
                        SavedCleanupRecord unresolvedReturnOnly = pendingReturn.asReturnOnly();
                        pendingCleanupPlayers.compute(playerId,
                                (k, cur) -> (cur == null || cur.equals(pendingReturn)) ? unresolvedReturnOnly : cur);
                        if (recoveryRepository != null
                                && unresolvedReturnOnly.equals(pendingCleanupPlayers.get(playerId))) {
                            recoveryRepository.upsertPendingPlayerCleanupAsync(
                                    unresolvedReturnOnly.toPendingCleanup(playerId));
                        }
                        if (player.isOnline()) {
                            messageService.send(player, "finder.leave-teleport-failed");
                        }
                    });
                    return LeaveEncounterResult.LEFT;
                }
            } else {
                retryDest = resolveReturnLocation(playerId, null, defaultFallback);
            }
            RaidInstanceManager.RaidInstance currentInst = null;
            CompletableFuture<Void> retryGate = null;
            if (instanceManager != null && player.getWorld() != null) {
                RaidInstanceManager.RaidInstance active = instanceManager.activeInstance();
                if (active != null && (instanceManager.isInstanceWorld(player.getWorld())
                        || active.world().equals(player.getWorld()))) {
                    currentInst = active;
                    retryGate = new CompletableFuture<>();
                    registerInstanceWithdrawal(currentInst, retryGate);
                }
            }
            CompletableFuture<Void> tpFuture = teleportPlayerForWithdrawal(player, null, pendingReturn, retryDest);
            CompletableFuture<Void> gate = retryGate;
            if (gate != null) {
                tpFuture.whenComplete((ignored, err) -> gate.complete(null));
            }
            return LeaveEncounterResult.LEFT;
        }

        return LeaveEncounterResult.NOT_IN_DUTY;
    }

    @Override
    public void adminStart(CommandSender sender, BossConfig.Difficulty difficulty) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(difficulty, "difficulty");
        EncounterBlueprint blueprint = resolveBlueprintForDifficulty(difficulty).orElse(null);
        if (blueprint == null) {
            messageService.send(
                    sender,
                    "command.blueprint-not-found",
                    Placeholder.unparsed("difficulty", difficulty.name())
            );
            return;
        }
        startPull(sender, blueprint, difficulty);
    }

    private Optional<EncounterBlueprint> resolveBlueprintForDifficulty(BossConfig.Difficulty difficulty) {
        if (blueprintRegistry == null) {
            return Optional.empty();
        }
        String preferredId = difficulty == BossConfig.Difficulty.HARD ? "dragon_hard" : "dragon_normal";
        Optional<EncounterBlueprint> byId = blueprintRegistry.get(preferredId);
        if (byId.isPresent()) {
            return byId;
        }
        EncounterDifficulty targetDiff = difficulty == BossConfig.Difficulty.HARD
                ? EncounterDifficulty.HARD
                : EncounterDifficulty.NORMAL;
        return blueprintRegistry.all().stream()
                .filter(bp -> bp.difficulty() == targetDiff)
                .findFirst();
    }

    private boolean startPull(
            CommandSender sender,
            EncounterBlueprint blueprint,
            BossConfig.Difficulty difficulty) {
        World endWorld = endWorldSupplier.get();
        if (endWorld == null) {
            if (sender != null) {
                messageService.send(sender, "command.start-no-players");
            }
            return false;
        }

        List<Player> candidates = collectPullCandidates(endWorld);
        if (candidates.isEmpty()) {
            if (sender != null) {
                messageService.send(sender, "command.start-no-players");
            }
            return false;
        }
        ScalingSettings scaling = configManager.pluginConfig().scaling();
        if (candidates.size() < scaling.minPlayers()) {
            if (sender != null) {
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
            if (sender != null) {
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
            if (state != RaidState.IDLE
                    || standbySession != null
                    || (instanceManager != null && instanceManager.activeInstance() != null)
                    || arenaUpdatesInFlight.get() > 0
                    || hasPendingBlockRollbacks()) {
                pendingRollbacks = state == RaidState.IDLE
                        && standbySession == null
                        && (instanceManager == null || instanceManager.activeInstance() == null)
                        && hasPendingBlockRollbacks();
                if (sender != null) {
                    messageService.send(sender, pendingRollbacks ? "command.rollback-pending" : "command.already-running");
                }
                return false;
            }
            state = RaidState.COUNTDOWN;
            activeDifficulty = difficulty;
            activeBlueprint = blueprint;
            countdownGeneration = encounterGeneration.incrementAndGet();
            countdownSeconds = configManager.pluginConfig().encounter().pullCountdownSeconds();
        }

        if (sender != null) {
            messageService.send(
                    sender,
                    "command.start-triggered",
                    Placeholder.unparsed("difficulty", difficulty.name())
            );
        }

        if (countdownSeconds <= 0) {
            lockInAndActivate(blueprint, difficulty, endWorld, countdownGeneration);
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
            if (currentCandidates.isEmpty()) {
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
                lockInAndActivate(blueprint, difficulty, world, countdownGeneration);
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
            activeBlueprint = null;
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

    private void lockInAndActivate(
            EncounterBlueprint blueprint,
            BossConfig.Difficulty difficulty,
            World endWorld,
            long expectedGeneration) {
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

        BossConfig bossConfig = BossConfig.fromBlueprint(blueprint);
        activateEncounterWithParty(blueprint, bossConfig, endWorld, party, overflow, false, expectedGeneration, Map.of(), false);
    }

    private boolean activateEncounterWithParty(
            EncounterBlueprint blueprint,
            BossConfig bossConfig,
            World endWorld,
            List<Player> party,
            List<Player> overflow,
            boolean directStart,
            long expectedGeneration,
            Map<UUID, Location> returnLocations,
            boolean allowReuseActiveInstance) {
        ScalingSettings scaling = configManager.pluginConfig().scaling();
        BossConfig.Difficulty difficulty = bossConfig.difficulty();
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                scaling,
                bossConfig,
                party.size()
        );

        final long activationGen;
        synchronized (stateLock) {
            if (directStart) {
                if (state != RaidState.IDLE
                        || standbySession != null
                        || (!allowReuseActiveInstance && instanceManager != null && instanceManager.activeInstance() != null)
                        || (expectedGeneration >= 0L && encounterGeneration.get() != expectedGeneration)
                        || arenaUpdatesInFlight.get() > 0
                        || hasPendingBlockRollbacks()) {
                    return false;
                }
            } else if (state != RaidState.COUNTDOWN
                    || standbySession != null
                    || encounterGeneration.get() != expectedGeneration) {
                return false;
            }
            activationGen = encounterGeneration.incrementAndGet();
            participants.clear();
            activeReturnLocations.clear();
            endingWipeMemberIds.clear();
            disconnectedDutyMembers.clear();
            this.standbySession = null;
            this.activeDifficulty = difficulty;
            this.activeBossConfig = bossConfig;
            this.activeBlueprint = blueprint;
            this.enemyRegistry.loadFromBlueprint(blueprint);
            this.combatMetricsTracker.reset();
            if (this.bossController != null) {
                this.bossController.setModelSpec(blueprint.primaryBossDefinition().modelSpec());
            }
            this.activeScaling = scaled;
            this.remainingRevives.set(scaled.maxRevives());
            this.combatStartedMillis = clockMillis.getAsLong();
            this.awaitingInstanceEntry = instanceManager != null && bossController != null;
            this.state = RaidState.ACTIVE;
            for (Player member : party) {
                UUID memberId = member.getUniqueId();
                SavedCleanupRecord staleCleanup = pendingCleanupPlayers.get(memberId);
                RaidParticipant participant = new RaidParticipant(memberId, member.getName());
                participant.setOnline(true);
                Location preEntryLoc = returnLocations.get(memberId);
                if (preEntryLoc == null && staleCleanup != null) {
                    preEntryLoc = resolveSavedReturnLocation(staleCleanup);
                }
                if (preEntryLoc != null && isValidReturnLocation(preEntryLoc)) {
                    Location copy = preEntryLoc.clone();
                    participant.setPreEntryReturnLocation(copy);
                    activeReturnLocations.put(memberId, copy);
                }
                participants.put(memberId, participant);
                this.combatMetricsTracker.ensureParticipant(memberId, member.getName());
                scheduler.runAtEntity(member, () -> {
                    if (staleCleanup != null) {
                        staleCleanup.applyTo(member);
                        pendingCleanupPlayers.remove(memberId, staleCleanup);
                    }
                    synchronized (stateLock) {
                        if ((state != RaidState.ACTIVE && state != RaidState.WIPING)
                                || encounterGeneration.get() != activationGen
                                || participants.get(memberId) != participant) {
                            if (staleCleanup != null && !staleCleanup.hasReturnLocation()) {
                                deletePlayerCleanupAsync(memberId);
                            }
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

        startEncounterSystems(endWorld, party, bossConfig, scaled, activationGen);

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
        return true;
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

        // 3. Tick Boss AI, Timeline, and Integrated HUD.
        tickEncounterSystems(endWorld, endPlayers, channelingRevivers);

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
            combatMetricsTracker.recordDowned(participant.playerId(), participant.name());
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
            combatMetricsTracker.recordReviveCompleted(reviver.getUniqueId(), reviver.getName());
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
                && !awaitingInstanceEntry
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
        EncounterBlueprint wipedBlueprint;
        BossConfig wipedBossConfig;
        RaidInstanceManager.RaidInstance wipedInstance;
        Set<UUID> wipedMemberIds = new LinkedHashSet<>();
        Map<UUID, Location> wipedReturnLocs = new LinkedHashMap<>();
        boolean enterInstanceStandby;
        synchronized (stateLock) {
            if (state != RaidState.WIPING || encounterGeneration.get() != expectedGeneration) {
                return;
            }
            cancelHandle(wipeTask);
            wipeTask = null;
            awaitingInstanceEntry = false;
            endingGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
            wipedBlueprint = this.activeBlueprint;
            wipedBossConfig = this.activeBossConfig;
            wipedInstance = instanceManager != null ? instanceManager.activeInstance() : null;
            enterInstanceStandby = wipedInstance != null && wipedBlueprint != null && wipedBossConfig != null;
            endingWipeMemberIds.clear();
            for (RaidParticipant p : participants.values()) {
                UUID memberId = p.playerId();
                wipedMemberIds.add(memberId);
                if (enterInstanceStandby) {
                    endingWipeMemberIds.add(memberId);
                }
                if (!p.isOnline()) {
                    disconnectedDutyMembers.add(memberId);
                }
                Location ret = activeReturnLocations.getOrDefault(memberId, p.preEntryReturnLocation());
                if (ret != null && isValidReturnLocation(ret)) {
                    wipedReturnLocs.put(memberId, ret.clone());
                }
            }
        }

        World targetWorld = enterInstanceStandby
                ? wipedInstance.world()
                : (endWorld != null ? endWorld : endWorldSupplier.get());
        List<Player> viewers = targetWorld != null ? onlinePlayersInWorld(targetWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        cleanupEncounterSystems(viewers, true);

        Location safeSpawn = targetWorld != null ? regionService.safeZoneSpawnLocation(targetWorld) : null;
        int partySizeSnapshot = participants.size();
        Map<UUID, net.sabafly.dragonraid.api.event.ParticipantCombatMetrics> metricsSnapshot =
                combatMetricsTracker.snapshot();
        long completionSeq = completionSequence.incrementAndGet();

        emitEncounterCompleted(EncounterOutcome.WIPE, Math.max(0L, clockMillis.getAsLong() - combatStartedMillis), partySizeSnapshot, metricsSnapshot, completionSeq);
        CompletableFuture<Void> rollbackFuture = rollbackBlocksIfConfigured(targetWorld);
        CompletableFuture<Void> endingFuture = rollbackFuture;

        if (enterInstanceStandby) {
            restoreParticipantsInsideInstanceForRetry(safeSpawn);
            World fallbackWorld = endWorld != null ? endWorld : endWorldSupplier.get();
            boolean publishedStandby = false;
            boolean shouldDestroyInstance = false;
            synchronized (stateLock) {
                if (encounterGeneration.get() == endingGeneration) {
                    endingWipeMemberIds.clear();
                    boolean anyOnlineMember = wipedMemberIds.stream().anyMatch(id -> {
                        if (disconnectedDutyMembers.contains(id)) {
                            return false;
                        }
                        Player p = playerLookup.apply(id);
                        return p != null && p.isOnline();
                    });
                    if (anyOnlineMember && fallbackWorld != null) {
                        this.standbySession = new DutyStandbySession(
                                wipedBlueprint,
                                wipedBossConfig,
                                fallbackWorld,
                                Set.copyOf(wipedMemberIds),
                                Map.copyOf(wipedReturnLocs),
                                wipedInstance,
                                rollbackFuture
                        );
                        disconnectedDutyMembers.retainAll(wipedMemberIds);
                        publishedStandby = true;
                    } else if (instanceManager != null) {
                        activeReturnLocations.clear();
                        disconnectedDutyMembers.clear();
                        shouldDestroyInstance = true;
                    }
                }
            }
            if (publishedStandby) {
                for (UUID memberId : wipedMemberIds) {
                    Player member = playerLookup.apply(memberId);
                    if (member != null && member.isOnline()) {
                        scheduler.runAtEntity(member, () -> messageService.send(member, "finder.wipe-standby-hint"));
                    }
                }
            } else if (shouldDestroyInstance) {
                World mainEnd = endWorldSupplier.get();
                Location mainFallback = mainEnd != null ? regionService.safeZoneSpawnLocation(mainEnd) : null;
                endingFuture = destroyInstanceWhenReady(wipedInstance, rollbackFuture, mainFallback);
            }
        } else {
            restoreAndResetParticipants(safeSpawn);
            synchronized (stateLock) {
                if (encounterGeneration.get() == endingGeneration) {
                    endingWipeMemberIds.clear();
                    disconnectedDutyMembers.clear();
                }
            }
        }

        completeEndingWhenRolledBack(endingGeneration, endingFuture);
    }

    @Override
    public void adminReset(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        World mainEndWorld = endWorldSupplier.get();
        World combatWorld = instanceManager != null ? instanceManager.activeCombatWorld(mainEndWorld) : mainEndWorld;
        RaidInstanceManager.RaidInstance targetInstance =
                instanceManager != null ? instanceManager.activeInstance() : null;
        long resetGeneration;
        boolean wasActiveOrWiping;
        DutyStandbySession standbyBeforeReset;
        Map<UUID, Location> resetReturns = new LinkedHashMap<>();
        synchronized (stateLock) {
            wasActiveOrWiping = (state == RaidState.ACTIVE || state == RaidState.WIPING);
            cancelHandle(countdownTask);
            cancelHandle(monitorTask);
            cancelHandle(wipeTask);
            countdownTask = null;
            monitorTask = null;
            wipeTask = null;
            standbyBeforeReset = this.standbySession;
            this.standbySession = null;
            endingWipeMemberIds.clear();
            disconnectedDutyMembers.clear();
            awaitingInstanceEntry = false;
            resetGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
            resetReturns.putAll(activeReturnLocations);
            if (standbyBeforeReset != null && standbyBeforeReset.returnLocations() != null) {
                standbyBeforeReset.returnLocations().forEach(resetReturns::putIfAbsent);
            }
            for (RaidParticipant participant : participants.values()) {
                if (!resetReturns.containsKey(participant.playerId()) && participant.preEntryReturnLocation() != null) {
                    resetReturns.put(participant.playerId(), participant.preEntryReturnLocation());
                }
            }
        }

        List<Player> viewers = combatWorld != null ? onlinePlayersInWorld(combatWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        cleanupEncounterSystems(viewers, true);
        Location safeSpawn = mainEndWorld != null ? regionService.safeZoneSpawnLocation(mainEndWorld) : null;
        int partySizeSnapshot = participants.size();
        Map<UUID, net.sabafly.dragonraid.api.event.ParticipantCombatMetrics> metricsSnapshot =
                combatMetricsTracker.snapshot();
        long completionSeq = wasActiveOrWiping ? completionSequence.incrementAndGet() : 0L;
        if (instanceManager != null && targetInstance != null) {
            // Defer evacuation until rollback finishes, then await individual teleports before deleting targetInstance.
            restoreAndResetParticipants(null);
        } else {
            restoreAndResetParticipants(safeSpawn);
            for (Map.Entry<UUID, Location> entry : resetReturns.entrySet()) {
                Player standbyPlayer = playerLookup.apply(entry.getKey());
                if (standbyPlayer != null && standbyPlayer.isOnline()
                        && (regionService.isRaidWorld(standbyPlayer.getWorld())
                        || (instanceManager != null && instanceManager.isInstanceWorld(standbyPlayer.getWorld())))) {
                    Location targetDest = resolveReturnLocation(entry.getKey(), entry.getValue(), safeSpawn);
                    if (targetDest != null) {
                        scheduler.runAtEntity(standbyPlayer, () -> standbyPlayer.teleportAsync(targetDest));
                    }
                }
            }
        }
        activeReturnLocations.clear();
        if (wasActiveOrWiping) {
            emitEncounterCompleted(
                    EncounterOutcome.ABORTED,
                    Math.max(0L, clockMillis.getAsLong() - combatStartedMillis),
                    partySizeSnapshot,
                    metricsSnapshot,
                    completionSeq
            );
        }
        if (instanceManager != null && targetInstance == null) {
            instanceManager.destroyActiveInstance(safeSpawn);
        }
        CompletableFuture<Void> standbyRollback = standbyBeforeReset != null
                ? standbyBeforeReset.rollbackFuture()
                : CompletableFuture.completedFuture(null);
        CompletableFuture<Void> rollbackFuture = rollbackBlocksIfConfigured(combatWorld);
        CompletableFuture<Void> combinedRollback = CompletableFuture.allOf(rollbackFuture, standbyRollback);
        CompletableFuture<Void> endingFuture = (instanceManager == null || targetInstance == null)
                ? combinedRollback
                : combinedRollback
                        .handle((ignored, err) -> (Void) null)
                        .thenCompose(ignored -> evacuateToIndividualReturnsThenDestroy(
                                targetInstance, resetReturns, safeSpawn));
        completeEndingWhenRolledBack(resetGeneration, endingFuture);
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
            endingWipeMemberIds.clear();
            disconnectedDutyMembers.clear();
            awaitingInstanceEntry = false;
            defeatGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
            difficulty = activeDifficulty != null ? activeDifficulty : BossConfig.Difficulty.NORMAL;
        }

        long durationMs = Math.max(0L, clockMillis.getAsLong() - combatStartedMillis);
        String formattedTime = formatDuration(durationMs);

        World endWorld = dragon.getWorld();
        List<Player> viewers = onlinePlayersInWorld(endWorld);
        soulMarkerService.removeAllMarkers(viewers);

        int partySizeSnapshot = participants.size();
        Map<UUID, net.sabafly.dragonraid.api.event.ParticipantCombatMetrics> metricsSnapshot =
                combatMetricsTracker.snapshot();
        EncounterBlueprint victoryBlueprint = this.activeBlueprint;
        String victoryEncounterId = victoryBlueprint != null ? victoryBlueprint.id()
                : (difficulty == BossConfig.Difficulty.HARD ? "dragon_hard" : "dragon_normal");
        EncounterDifficulty victoryEncDiff = victoryBlueprint != null ? victoryBlueprint.difficulty()
                : (difficulty == BossConfig.Difficulty.HARD ? EncounterDifficulty.HARD : EncounterDifficulty.NORMAL);

        long victoryCompletionSeq = completionSequence.incrementAndGet();
        boolean dragonHasOwnBattle = dragon.getDragonBattle() != null;
        CompletableFuture<Set<UUID>> firstClearIdsFuture = prepareVictoryCleanup(
                difficulty,
                durationMs,
                victoryEncounterId
        );

        // Capture per-player pre-entry return locations before restoreAndResetParticipants clears participants.
        Map<UUID, Location> victoryReturns = new LinkedHashMap<>(activeReturnLocations);
        for (RaidParticipant participant : participants.values()) {
            if (!victoryReturns.containsKey(participant.playerId()) && participant.preEntryReturnLocation() != null) {
                victoryReturns.put(participant.playerId(), participant.preEntryReturnLocation());
            }
        }
        // On victory, restore participant flags where they stand so they can collect drops/egg.
        restoreAndResetParticipants(null);
        firstClearIdsFuture.whenComplete((firstClearIds, err) -> {
            Set<UUID> safeFirstClears = (err != null || firstClearIds == null) ? Set.of() : firstClearIds;
            scheduler.runGlobal(() -> {
                if (configManager.pluginConfig().world().preserveVanillaProgression() && !dragonHasOwnBattle) {
                    VanillaEndProgressionPolicy policy = this.vanillaEndProgressionPolicy;
                    boolean anyFirstClear = !safeFirstClears.isEmpty();
                    if (policy == null || !policy.hasApplicableWorldProgressionHook(
                            victoryEncounterId, victoryEncDiff, anyFirstClear)) {
                        runLegacyVanillaProgressionFallback(endWorld);
                    }
                }
                emitEncounterCompleted(
                        EncounterOutcome.VICTORY,
                        victoryEncounterId,
                        victoryEncDiff,
                        durationMs,
                        partySizeSnapshot,
                        metricsSnapshot,
                        safeFirstClears,
                        victoryCompletionSeq
                );
            });
        });

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
        CompletableFuture<Void> endingFuture = rollbackFuture;
        if (instanceManager != null && instanceManager.isInstanceWorld(endWorld)) {
            RaidInstanceManager.RaidInstance defeatedInstance = instanceManager.activeInstance();
            World mainEnd = endWorldSupplier.get();
            Location returnLoc = mainEnd != null ? regionService.safeZoneSpawnLocation(mainEnd) : null;
            if (defeatedInstance != null && defeatedInstance.world().equals(endWorld)) {
                endingFuture = rollbackFuture.handle((ignored, err) -> (Void) null)
                        .thenCompose(ignored -> {
                            CompletableFuture<Void> destroyFuture = new CompletableFuture<>();
                            Runnable evacuateAndDestroy = () -> evacuateToIndividualReturnsThenDestroy(
                                    defeatedInstance, victoryReturns, returnLoc)
                                    .whenComplete((v, destroyErr) -> {
                                        activeReturnLocations.clear();
                                        destroyFuture.complete(null);
                                    });
                            TaskHandle handle = scheduler.runGlobalLater(evacuateAndDestroy, 100L);
                            if (handle == null) {
                                evacuateAndDestroy.run();
                            }
                            return destroyFuture;
                        });
            } else {
                activeReturnLocations.clear();
            }
        } else {
            activeReturnLocations.clear();
        }
        completeEndingWhenRolledBack(defeatGeneration, endingFuture);
    }

    private void completeEndingWhenRolledBack(long expectedGeneration, CompletableFuture<Void> rollbackFuture) {
        rollbackFuture.whenComplete((ignored, error) -> {
            synchronized (stateLock) {
                if (state == RaidState.ENDING
                        && encounterGeneration.get() == expectedGeneration
                        && !hasPendingBlockRollbacks()) {
                    activeDifficulty = null;
                    activeBossConfig = null;
                    activeBlueprint = null;
                    activeScaling = null;
                    activeTimeline = null;
                    enemyRegistry.clear();
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
    }

    @Override
    public void onPlayerJoin(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        soulMarkerService.invalidateViewer(id);
        updateSnapshot(player);
        final DutyStandbySession standbyNow;
        synchronized (stateLock) {
            disconnectedDutyMembers.remove(id);
            standbyNow = this.standbySession;
        }

        // If a previous raid ended while this player was offline or a withdrawal teleport failed,
        // restore flags (if needed) and return them to their saved destination or standby instance.
        SavedCleanupRecord cleanup = pendingCleanupPlayers.get(id);
        if (cleanup != null) {
            scheduler.runAtEntity(player, () -> {
                if (cleanup.restoreFlags()) {
                    cleanup.applyTo(player);
                }
                DutyStandbySession currentStandby;
                synchronized (stateLock) {
                    currentStandby = this.standbySession;
                }
                if (standbyNow != null
                        && currentStandby != null
                        && currentStandby.memberIds().contains(id)
                        && Objects.equals(standbyNow.instance(), currentStandby.instance())) {
                    if (cleanup.hasReturnLocation()) {
                        SavedCleanupRecord standbyReturnOnly = cleanup.asReturnOnly();
                        pendingCleanupPlayers.compute(id,
                                (k, cur) -> (cur == null || cur.equals(cleanup)) ? standbyReturnOnly : cur);
                        if (recoveryRepository != null && standbyReturnOnly.equals(pendingCleanupPlayers.get(id))) {
                            recoveryRepository.upsertPendingPlayerCleanupAsync(standbyReturnOnly.toPendingCleanup(id));
                        }
                    } else if (pendingCleanupPlayers.remove(id, cleanup)) {
                        deletePlayerCleanupAsync(id);
                    }
                    if (currentStandby.instance() != null) {
                        Location standbySpawn = regionService.safeZoneSpawnLocation(currentStandby.instance().world());
                        if (standbySpawn != null && standbySpawn.getWorld() != null) {
                            player.teleportAsync(standbySpawn);
                        }
                    }
                    updateSnapshot(player);
                    return;
                }
                Location targetDest = null;
                if (cleanup.hasReturnLocation()) {
                    Location savedReturn = resolveSavedReturnLocation(cleanup);
                    if (savedReturn == null || savedReturn.getWorld() == null) {
                        SavedCleanupRecord unresolvedReturnOnly = cleanup.asReturnOnly();
                        pendingCleanupPlayers.compute(id,
                                (k, cur) -> (cur == null || cur.equals(cleanup)) ? unresolvedReturnOnly : cur);
                        if (recoveryRepository != null && unresolvedReturnOnly.equals(pendingCleanupPlayers.get(id))) {
                            recoveryRepository.upsertPendingPlayerCleanupAsync(
                                    unresolvedReturnOnly.toPendingCleanup(id));
                        }
                        updateSnapshot(player);
                        return;
                    }
                    targetDest = savedReturn;
                } else if (cleanup.teleportToSafeZone()) {
                    World endWorld = endWorldSupplier.get();
                    boolean inRaidOrInstance = regionService.isRaidWorld(player.getWorld())
                            || (instanceManager != null && instanceManager.isInstanceWorld(player.getWorld()));
                    if (endWorld != null && inRaidOrInstance) {
                        targetDest = regionService.safeZoneSpawnLocation(endWorld);
                    }
                }
                if (targetDest != null && targetDest.getWorld() != null) {
                    SavedCleanupRecord returnOnlyRecord = SavedCleanupRecord.returnOnly(targetDest);
                    pendingCleanupPlayers.compute(id,
                            (k, cur) -> (cur == null || cur.equals(cleanup)) ? returnOnlyRecord : cur);
                    CompletableFuture<Boolean> tpFuture = player.teleportAsync(targetDest);
                    if (tpFuture == null) {
                        tpFuture = CompletableFuture.completedFuture(Boolean.FALSE);
                    }
                    tpFuture.whenComplete((ok, err) -> {
                        if (err == null && Boolean.TRUE.equals(ok)) {
                            if (pendingCleanupPlayers.remove(id, returnOnlyRecord)) {
                                deletePlayerCleanupAsync(id);
                            }
                        } else {
                            if (recoveryRepository != null && returnOnlyRecord.equals(pendingCleanupPlayers.get(id))) {
                                recoveryRepository.upsertPendingPlayerCleanupAsync(returnOnlyRecord.toPendingCleanup(id));
                            }
                            logger.log(Level.WARNING,
                                    "Failed to teleport returning player " + player.getName() + " to return location",
                                    err);
                            if (player.isOnline()) {
                                scheduler.runAtEntity(player,
                                        () -> messageService.send(player, "finder.leave-teleport-failed"));
                            }
                        }
                    });
                } else {
                    if (pendingCleanupPlayers.remove(id, cleanup)) {
                        deletePlayerCleanupAsync(id);
                    }
                }
                updateSnapshot(player);
            });
            return;
        }

        RaidParticipant participant;
        synchronized (stateLock) {
            participant = participants.get(id);
            if (participant != null && (state == RaidState.ACTIVE || state == RaidState.WIPING)) {
                participant.setOnline(true);
                participant.setName(player.getName());
            } else {
                participant = null;
            }
        }
        if (participant != null) {
            RaidParticipant activeParticipant = participant;
            scheduler.runAtEntity(player, () -> {
                switch (activeParticipant.state()) {
                    case ALIVE -> restorePlayerFlags(activeParticipant, player);
                    case DOWNED -> {
                        player.setInvulnerable(true);
                        player.setInvisible(true);
                        player.setCollidable(false);
                        Location loc = activeParticipant.downedLocation();
                        if (loc != null) {
                            player.teleportAsync(loc);
                        }
                    }
                    case DEAD_SPECTATOR -> {
                        player.setGameMode(GameMode.SPECTATOR);
                        player.setInvisible(activeParticipant.previousInvisible());
                        player.setCollidable(activeParticipant.previousCollidable());
                        player.setInvulnerable(activeParticipant.previousInvulnerable());
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
    }

    @Override
    public void onPlayerQuit(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        soulMarkerService.invalidateViewer(id);
        if (hudService != null) {
            hudService.removeViewer(player);
        }
        snapshots.remove(id);
        lastRightClickMillis.remove(id);
        lastFeedbackMillis.remove(id);

        DutyStandbySession standbyToDestroy = null;
        RaidParticipant participant;
        boolean activeAtQuit;
        synchronized (stateLock) {
            participant = participants.get(id);
            DutyStandbySession currentStandby = this.standbySession;
            boolean inDutyScope = participant != null
                    || endingWipeMemberIds.contains(id)
                    || (currentStandby != null && currentStandby.memberIds().contains(id));
            if (inDutyScope) {
                disconnectedDutyMembers.add(id);
            }
            if (participant != null) {
                participant.setOnline(false);
            }
            if (currentStandby != null && currentStandby.memberIds().contains(id)) {
                boolean anyOnlineStandbyRemaining = currentStandby.memberIds().stream().anyMatch(otherId -> {
                    if (otherId.equals(id) || disconnectedDutyMembers.contains(otherId)) {
                        return false;
                    }
                    Player other = playerLookup.apply(otherId);
                    return other != null && other.isOnline();
                });
                if (!anyOnlineStandbyRemaining) {
                    this.standbySession = null;
                    this.activeReturnLocations.clear();
                    this.disconnectedDutyMembers.clear();
                    standbyToDestroy = currentStandby;
                }
            }
            activeAtQuit = (state == RaidState.ACTIVE);
        }
        if (standbyToDestroy != null && instanceManager != null) {
            World mainEnd = endWorldSupplier.get();
            Location mainFallback = mainEnd != null ? regionService.safeZoneSpawnLocation(mainEnd) : null;
            RaidInstanceManager.RaidInstance targetInst = standbyToDestroy.instance() != null
                    ? standbyToDestroy.instance() : instanceManager.activeInstance();
            if (targetInst != null) {
                destroyInstanceWhenReady(targetInst, standbyToDestroy.rollbackFuture(), mainFallback);
            }
        }

        if (participant == null || !activeAtQuit) {
            return;
        }
        World combatWorld = instanceManager != null
                ? instanceManager.activeCombatWorld(endWorldSupplier.get())
                : endWorldSupplier.get();
        if (configManager.pluginConfig().revive().downOnDisconnect()
                && participant.state() == ParticipantState.ALIVE) {
            Location markerLoc = regionService.isInArena(player.getLocation())
                    ? player.getLocation()
                    : regionService.clampToArenaAnchor(player.getLocation(), 0.0);
            transitionToDowned(participant, player, markerLoc);
        } else {
            evaluateWipeCondition(combatWorld, configManager.pluginConfig().revive());
        }
    }

    @Override
    public void onPlayerChangedWorld(Player player) {
        Objects.requireNonNull(player, "player");
        updateSnapshot(player);
        UUID id = player.getUniqueId();
        if (!regionService.isRaidWorld(player.getWorld())) {
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
    public void onBossDamage(EnderDragon dragon, EntityDamageEvent event) {
        Objects.requireNonNull(dragon, "dragon");
        Objects.requireNonNull(event, "event");
        if (state != RaidState.ACTIVE || !regionService.isRaidWorld(dragon.getWorld())) {
            return;
        }
        if (bossController != null) {
            EnderDragon activeBoss = bossController.activeDragon();
            if (activeBoss != null && !activeBoss.getUniqueId().equals(dragon.getUniqueId())) {
                return;
            }
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player attacker = resolvePlayerAttacker(byEntity.getDamager());
        if (attacker != null && participants.containsKey(attacker.getUniqueId())) {
            RaidEnemyDefinition primaryDef = enemyRegistry.primaryBoss().orElse(null);
            String enemyId = primaryDef != null ? primaryDef.id() : "ancient_dragon";
            EnemyRole role = primaryDef != null ? primaryDef.role() : EnemyRole.PRIMARY_BOSS;
            combatMetricsTracker.recordDamage(attacker.getUniqueId(), attacker.getName(), enemyId, role, event.getFinalDamage());
        }
    }

    private static @Nullable Player resolvePlayerAttacker(@Nullable Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    public void setVanillaEndProgressionPolicy(@Nullable VanillaEndProgressionPolicy vanillaEndProgressionPolicy) {
        this.vanillaEndProgressionPolicy = vanillaEndProgressionPolicy;
    }

    private void emitEncounterCompleted(
            EncounterOutcome outcome,
            long durationMs,
            int partySizeSnapshot,
            Map<UUID, net.sabafly.dragonraid.api.event.ParticipantCombatMetrics> metricsSnapshot,
            long completionSeq) {
        EncounterBlueprint blueprint = this.activeBlueprint;
        BossConfig.Difficulty diff = this.activeDifficulty;
        if (blueprint == null && diff == null) {
            return;
        }
        String encounterId = blueprint != null ? blueprint.id()
                : (diff == BossConfig.Difficulty.HARD ? "dragon_hard" : "dragon_normal");
        EncounterDifficulty encDiff = blueprint != null ? blueprint.difficulty()
                : (diff == BossConfig.Difficulty.HARD ? EncounterDifficulty.HARD : EncounterDifficulty.NORMAL);
        emitEncounterCompleted(outcome, encounterId, encDiff, durationMs, partySizeSnapshot, metricsSnapshot, Set.of(), completionSeq);
    }

    private void emitEncounterCompleted(
            EncounterOutcome outcome,
            String encounterId,
            EncounterDifficulty encDiff,
            long durationMs,
            int partySizeSnapshot,
            Map<UUID, net.sabafly.dragonraid.api.event.ParticipantCombatMetrics> metricsSnapshot,
            Set<UUID> firstClearPlayerIds,
            long completionSeq) {
        Set<UUID> safeFirstClears = firstClearPlayerIds == null ? Set.of() : Set.copyOf(firstClearPlayerIds);
        EncounterResultSnapshot result = new EncounterResultSnapshot(
                encounterId,
                encDiff,
                outcome,
                durationMs,
                !safeFirstClears.isEmpty(),
                partySizeSnapshot,
                metricsSnapshot,
                safeFirstClears
        );
        synchronized (stateLock) {
            if (completionSeq > this.lastPublishedCompletionSeq) {
                this.lastPublishedCompletionSeq = completionSeq;
                this.lastCompletedResult = result;
            }
        }
        Consumer<EncounterResultSnapshot> sink = this.encounterCompletedSink;
        if (sink != null) {
            sink.accept(result);
        }
        if (Bukkit.getServer() != null) {
            Bukkit.getPluginManager().callEvent(new RaidEncounterCompletedEvent(result));
        }
    }

    @Override
    public void onEntityExplode(Entity entity, Cancellable event) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(event, "event");
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
            DutyStandbySession standbyAtShutdown = standbySession;
            standbySession = null;
            endingWipeMemberIds.clear();
            disconnectedDutyMembers.clear();
            encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
            if (standbyAtShutdown != null && standbyAtShutdown.returnLocations() != null) {
                for (Map.Entry<UUID, Location> entry : standbyAtShutdown.returnLocations().entrySet()) {
                    Location retLoc = entry.getValue();
                    if (retLoc != null && retLoc.getWorld() != null) {
                        pendingCleanupPlayers.putIfAbsent(entry.getKey(), SavedCleanupRecord.returnOnly(retLoc));
                    }
                }
            }
            for (RaidParticipant participant : participants.values()) {
                UUID id = participant.playerId();
                Location returnLoc = activeReturnLocations.getOrDefault(id, participant.preEntryReturnLocation());
                if (participant.isStateCaptured()) {
                    pendingCleanupPlayers.put(id, SavedCleanupRecord.withFlags(
                            participant.previousGameMode(),
                            participant.previousInvisible(),
                            participant.previousCollidable(),
                            participant.previousInvulnerable(),
                            true,
                            returnLoc
                    ));
                } else if (returnLoc != null && returnLoc.getWorld() != null) {
                    pendingCleanupPlayers.putIfAbsent(id, SavedCleanupRecord.returnOnly(returnLoc));
                }
            }
        }
        World mainEndWorld = endWorldSupplier.get();
        World combatWorld = instanceManager != null ? instanceManager.activeCombatWorld(mainEndWorld) : mainEndWorld;
        List<Player> viewers = combatWorld != null ? onlinePlayersInWorld(combatWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        CompletableFuture<Void> encounterCleanupFuture = cleanupEncounterSystems(viewers, true);
        if (instanceManager != null) {
            Location returnLoc = mainEndWorld != null ? regionService.safeZoneSpawnLocation(mainEndWorld) : null;
            instanceManager.destroyActiveInstance(returnLoc);
        }
        if (!encounterCleanupFuture.isDone()) {
            try {
                encounterCleanupFuture.get(500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ex) {
                logger.log(Level.FINE, "Timed out or failed waiting for encounter entity cleanup on shutdown", ex);
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
        activeReturnLocations.clear();
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

    private void restoreParticipantsInsideInstanceForRetry(@Nullable Location instanceSafeSpawn) {
        for (RaidParticipant participant : List.copyOf(participants.values())) {
            UUID id = participant.playerId();
            Player player = playerLookup.apply(id);
            Location returnLoc = activeReturnLocations.getOrDefault(id, participant.preEntryReturnLocation());
            SavedCleanupRecord returnOnlyRecord = (returnLoc != null && returnLoc.getWorld() != null)
                    ? SavedCleanupRecord.returnOnly(returnLoc)
                    : null;
            SavedCleanupRecord cleanupRecord = participant.isStateCaptured()
                    ? SavedCleanupRecord.withFlags(
                            participant.previousGameMode(),
                            participant.previousInvisible(),
                            participant.previousCollidable(),
                            participant.previousInvulnerable(),
                            instanceSafeSpawn != null,
                            returnLoc
                    )
                    : returnOnlyRecord;
            if (player != null && player.isOnline()) {
                if (cleanupRecord != null) {
                    pendingCleanupPlayers.put(id, cleanupRecord);
                }
                TaskHandle handle = scheduler.runAtEntity(player, () -> {
                    restorePlayerFlags(participant, player);
                    player.setHealth(maxHealth(player));
                    player.setFireTicks(0);
                    player.setFallDistance(0.0f);
                    if (instanceSafeSpawn != null) {
                        player.teleportAsync(instanceSafeSpawn);
                    }
                    if (returnOnlyRecord != null) {
                        pendingCleanupPlayers.compute(id,
                                (k, cur) -> (cur == null || cur.equals(cleanupRecord)) ? returnOnlyRecord : cur);
                        if (recoveryRepository != null && returnOnlyRecord.equals(pendingCleanupPlayers.get(id))) {
                            recoveryRepository.upsertPendingPlayerCleanupAsync(returnOnlyRecord.toPendingCleanup(id));
                        }
                    } else {
                        if (cleanupRecord != null) {
                            pendingCleanupPlayers.remove(id, cleanupRecord);
                        } else {
                            pendingCleanupPlayers.remove(id);
                        }
                        deletePlayerCleanupAsync(id);
                    }
                    updateSnapshot(player);
                }, () -> {
                    if (cleanupRecord != null && recoveryRepository != null) {
                        recoveryRepository.upsertPendingPlayerCleanupAsync(cleanupRecord.toPendingCleanup(id));
                    }
                });
                if (handle == null && cleanupRecord != null && recoveryRepository != null) {
                    recoveryRepository.upsertPendingPlayerCleanupAsync(cleanupRecord.toPendingCleanup(id));
                }
            } else if (cleanupRecord != null) {
                pendingCleanupPlayers.put(id, cleanupRecord);
                if (recoveryRepository != null) {
                    recoveryRepository.upsertPendingPlayerCleanupAsync(cleanupRecord.toPendingCleanup(id));
                }
            }
        }
        participants.clear();
    }

    private CompletableFuture<Void> teleportPlayerForWithdrawal(
            Player player,
            @Nullable RaidParticipant participant,
            @Nullable SavedCleanupRecord cleanupRecord,
            @Nullable Location destination) {
        UUID playerId = player.getUniqueId();
        CompletableFuture<Void> done = new CompletableFuture<>();
        TaskHandle handle = scheduler.runAtEntity(player, () -> {
            if (participant != null) {
                restorePlayerFlags(participant, player);
                if (player.getHealth() <= 0.0) {
                    player.setHealth(Math.min(maxHealth(player), 1.0));
                } else {
                    player.setHealth(maxHealth(player));
                }
            } else if (cleanupRecord != null && cleanupRecord.restoreFlags()) {
                cleanupRecord.applyTo(player);
            }
            player.setFireTicks(0);
            player.setFallDistance(0.0f);
            if (destination != null && destination.getWorld() != null) {
                SavedCleanupRecord returnOnlyRecord = SavedCleanupRecord.returnOnly(destination);
                pendingCleanupPlayers.compute(playerId,
                        (k, cur) -> (cur == null || cur.equals(cleanupRecord)) ? returnOnlyRecord : cur);
                CompletableFuture<Boolean> tpFuture = player.teleportAsync(destination);
                if (tpFuture == null) {
                    tpFuture = CompletableFuture.completedFuture(Boolean.FALSE);
                }
                tpFuture.whenComplete((ok, err) -> {
                    if (err == null && Boolean.TRUE.equals(ok)) {
                        if (pendingCleanupPlayers.remove(playerId, returnOnlyRecord)) {
                            deletePlayerCleanupAsync(playerId);
                        }
                    } else {
                        if (recoveryRepository != null && returnOnlyRecord.equals(pendingCleanupPlayers.get(playerId))) {
                            recoveryRepository.upsertPendingPlayerCleanupAsync(
                                    returnOnlyRecord.toPendingCleanup(playerId));
                        }
                        logger.log(Level.WARNING,
                                "Failed to teleport withdrawing player " + player.getName() + " to return location",
                                err);
                        if (player.isOnline()) {
                            scheduler.runAtEntity(player,
                                    () -> messageService.send(player, "finder.leave-teleport-failed"));
                        }
                    }
                    done.complete(null);
                });
            } else {
                boolean removed = cleanupRecord != null
                        ? pendingCleanupPlayers.remove(playerId, cleanupRecord)
                        : pendingCleanupPlayers.remove(playerId) != null;
                if (removed || cleanupRecord == null) {
                    deletePlayerCleanupAsync(playerId);
                }
                done.complete(null);
            }
        }, () -> {
            if (cleanupRecord != null && recoveryRepository != null
                    && cleanupRecord.equals(pendingCleanupPlayers.get(playerId))) {
                recoveryRepository.upsertPendingPlayerCleanupAsync(cleanupRecord.toPendingCleanup(playerId));
            }
            done.complete(null);
        });
        if (handle == null) {
            if (cleanupRecord != null && recoveryRepository != null
                    && cleanupRecord.equals(pendingCleanupPlayers.get(playerId))) {
                recoveryRepository.upsertPendingPlayerCleanupAsync(cleanupRecord.toPendingCleanup(playerId));
            }
            done.complete(null);
        }
        return done;
    }

    private void registerInstanceWithdrawal(
            @Nullable RaidInstanceManager.RaidInstance inst,
            @Nullable CompletableFuture<Void> withdrawalFuture) {
        if (inst == null || inst.world() == null || withdrawalFuture == null) {
            return;
        }
        UUID worldId = inst.world().getUID();
        inFlightInstanceWithdrawals.compute(worldId, (ignored, existing) -> {
            Set<CompletableFuture<Void>> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
            set.add(withdrawalFuture);
            return set;
        });
        withdrawalFuture.whenComplete((ignored, err) ->
                inFlightInstanceWithdrawals.computeIfPresent(worldId, (k, existing) -> {
                    existing.remove(withdrawalFuture);
                    return existing.isEmpty() ? null : existing;
                }));
    }

    private CompletableFuture<Void> awaitInstanceWithdrawals(@Nullable RaidInstanceManager.RaidInstance inst) {
        if (inst == null || inst.world() == null) {
            return CompletableFuture.completedFuture(null);
        }
        AtomicReference<CompletableFuture<?>[]> snapshotRef = new AtomicReference<>();
        inFlightInstanceWithdrawals.computeIfPresent(inst.world().getUID(), (k, existing) -> {
            if (!existing.isEmpty()) {
                snapshotRef.set(existing.toArray(CompletableFuture[]::new));
            }
            return existing;
        });
        CompletableFuture<?>[] arr = snapshotRef.get();
        return (arr == null || arr.length == 0)
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(arr).handle((ignored, err) -> (Void) null);
    }

    private CompletableFuture<Void> destroyInstanceWhenReady(
            @Nullable RaidInstanceManager.RaidInstance inst,
            @Nullable CompletableFuture<Void> priorWork,
            @Nullable Location fallback) {
        if (instanceManager == null || inst == null) {
            return priorWork != null
                    ? priorWork.handle((ignored, err) -> (Void) null)
                    : CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> base = priorWork == null
                ? CompletableFuture.completedFuture(null)
                : priorWork.handle((ignored, err) -> (Void) null);
        return base.thenCompose(ignored -> awaitInstanceWithdrawals(inst))
                .handle((ignored, err) -> (Void) null)
                .thenCompose(ignored -> {
                    CompletableFuture<Void> df = instanceManager.destroyInstance(inst, fallback);
                    if (inst.world() != null) {
                        inFlightInstanceWithdrawals.computeIfPresent(inst.world().getUID(),
                                (k, existing) -> existing.isEmpty() ? null : existing);
                    }
                    return df != null ? df.handle((v, dErr) -> (Void) null) : CompletableFuture.completedFuture(null);
                });
    }

    private void teardownAfterAllParticipantsLeft(
            @Nullable World combatWorld,
            @Nullable World mainEndWorld,
            @Nullable Location defaultFallback,
            long expectedGeneration,
            CompletableFuture<Void> priorTeleport) {
        long endingGeneration;
        synchronized (stateLock) {
            if (encounterGeneration.get() != expectedGeneration) {
                return;
            }
            cancelHandle(monitorTask);
            cancelHandle(wipeTask);
            monitorTask = null;
            wipeTask = null;
            endingWipeMemberIds.clear();
            disconnectedDutyMembers.clear();
            awaitingInstanceEntry = false;
            standbySession = null;
            endingGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
        }
        List<Player> viewers = combatWorld != null ? onlinePlayersInWorld(combatWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        cleanupEncounterSystems(viewers, true);
        int partySizeSnapshot = activeScaling != null ? activeScaling.partySize() : Math.max(1, participants.size());
        Map<UUID, net.sabafly.dragonraid.api.event.ParticipantCombatMetrics> metricsSnapshot =
                combatMetricsTracker.snapshot();
        long completionSeq = completionSequence.incrementAndGet();
        restoreAndResetParticipants(defaultFallback);
        activeReturnLocations.clear();
        emitEncounterCompleted(
                EncounterOutcome.ABORTED,
                Math.max(0L, clockMillis.getAsLong() - combatStartedMillis),
                partySizeSnapshot,
                metricsSnapshot,
                completionSeq
        );
        CompletableFuture<Void> rollbackFuture = rollbackBlocksIfConfigured(combatWorld);
        CompletableFuture<Void> endingFuture = CompletableFuture.allOf(priorTeleport, rollbackFuture);
        if (instanceManager != null) {
            RaidInstanceManager.RaidInstance targetInstance = instanceManager.activeInstance();
            if (targetInstance != null) {
                endingFuture = evacuatePendingReturnsAndDestroy(targetInstance, endingFuture, defaultFallback);
            }
        }
        completeEndingWhenRolledBack(endingGeneration, endingFuture);
    }

    private CompletableFuture<Void> evacuatePendingReturnsAndDestroy(
            RaidInstanceManager.RaidInstance targetInstance,
            CompletableFuture<Void> priorWork,
            @Nullable Location fallbackSpawn) {
        return priorWork.handle((ignored, err) -> (Void) null)
                .thenCompose(ignored -> awaitInstanceWithdrawals(targetInstance))
                .handle((ignored, err) -> (Void) null)
                .thenCompose(ignored -> {
                    List<CompletableFuture<Void>> pendingTps = new ArrayList<>();
                    for (Player player : onlinePlayersInWorld(targetInstance.world())) {
                        UUID id = player.getUniqueId();
                        SavedCleanupRecord pending = pendingCleanupPlayers.get(id);
                        if (pending != null && (pending.hasReturnLocation() || pending.teleportToSafeZone())) {
                            Location dest = resolveReturnLocation(id, null, fallbackSpawn);
                            pendingTps.add(teleportPlayerForWithdrawal(player, null, pending, dest));
                        }
                    }
                    CompletableFuture<Void> allPending = pendingTps.isEmpty()
                            ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.allOf(pendingTps.toArray(CompletableFuture[]::new));
                    return allPending.handle((v, e) -> (Void) null)
                            .thenCompose(v -> {
                                CompletableFuture<Void> df = instanceManager.destroyInstance(targetInstance, fallbackSpawn);
                                if (targetInstance.world() != null) {
                                    inFlightInstanceWithdrawals.computeIfPresent(targetInstance.world().getUID(),
                                            (k, existing) -> existing.isEmpty() ? null : existing);
                                }
                                return df != null ? df.handle((dv, dErr) -> (Void) null)
                                        : CompletableFuture.completedFuture(null);
                            });
                });
    }

    private CompletableFuture<Void> evacuateToIndividualReturnsThenDestroy(
            RaidInstanceManager.RaidInstance defeatedInstance,
            Map<UUID, Location> returnMap,
            @Nullable Location fallbackSpawn) {
        List<CompletableFuture<Void>> tpFutures = new ArrayList<>();
        for (Player player : onlinePlayersInWorld(defeatedInstance.world())) {
            UUID id = player.getUniqueId();
            Location dest = resolveReturnLocation(id, returnMap.get(id), fallbackSpawn);
            if (dest != null && dest.getWorld() != null) {
                SavedCleanupRecord pending = pendingCleanupPlayers.get(id);
                tpFutures.add(teleportPlayerForWithdrawal(player, null, pending, dest));
            }
        }
        CompletableFuture<Void> allTps = tpFutures.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(tpFutures.toArray(CompletableFuture[]::new));
        return CompletableFuture.allOf(allTps, awaitInstanceWithdrawals(defeatedInstance))
                .handle((ignored, err) -> (Void) null)
                .thenCompose(ignored -> {
                    CompletableFuture<Void> df = instanceManager.destroyInstance(defeatedInstance, fallbackSpawn);
                    if (defeatedInstance.world() != null) {
                        inFlightInstanceWithdrawals.computeIfPresent(defeatedInstance.world().getUID(),
                                (k, existing) -> existing.isEmpty() ? null : existing);
                    }
                    return df != null ? df.handle((v, dErr) -> null) : CompletableFuture.completedFuture(null);
                });
    }

    private @Nullable Location resolveReturnLocation(
            @Nullable UUID playerId,
            @Nullable Location specificReturn,
            @Nullable Location defaultFallback) {
        if (isValidReturnLocation(specificReturn)) {
            return specificReturn.clone();
        }
        if (playerId != null) {
            Location stored = activeReturnLocations.get(playerId);
            if (isValidReturnLocation(stored)) {
                return stored.clone();
            }
            Location saved = resolveSavedReturnLocation(pendingCleanupPlayers.get(playerId));
            if (saved != null) {
                return saved;
            }
        }
        if (isValidReturnLocation(defaultFallback)) {
            return defaultFallback.clone();
        }
        World mainEnd = endWorldSupplier.get();
        if (mainEnd != null) {
            return regionService.safeZoneSpawnLocation(mainEnd);
        }
        if (Bukkit.getServer() != null && !Bukkit.getWorlds().isEmpty()) {
            return Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        return null;
    }

    private @Nullable Location resolveSavedReturnLocation(@Nullable SavedCleanupRecord record) {
        if (record == null || !record.hasReturnLocation()) {
            return null;
        }
        if (record.cachedReturnLocation() != null && isValidReturnLocation(record.cachedReturnLocation())) {
            return record.cachedReturnLocation().clone();
        }
        UUID worldId = record.returnWorldId();
        Double x = record.returnX();
        Double y = record.returnY();
        Double z = record.returnZ();
        if (worldId == null || x == null || y == null || z == null) {
            return null;
        }
        World world = worldLookup.apply(worldId);
        if (world == null) {
            World mainEnd = endWorldSupplier.get();
            if (mainEnd != null && worldId.equals(mainEnd.getUID())) {
                world = mainEnd;
            }
        }
        if (world == null) {
            return null;
        }
        float yaw = record.returnYaw() != null ? record.returnYaw() : 0.0f;
        float pitch = record.returnPitch() != null ? record.returnPitch() : 0.0f;
        Location loc = new Location(world, x, y, z, yaw, pitch);
        return isValidReturnLocation(loc) ? loc : null;
    }

    private boolean isValidReturnLocation(@Nullable Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        if (instanceManager != null && instanceManager.isInstanceWorld(loc.getWorld())) {
            return false;
        }
        if (Bukkit.getServer() != null) {
            return Bukkit.getWorld(loc.getWorld().getUID()) != null;
        }
        return true;
    }

    private void restoreAndResetParticipants(@Nullable Location teleportTarget) {
        for (RaidParticipant participant : List.copyOf(participants.values())) {
            UUID id = participant.playerId();
            Player player = playerLookup.apply(id);
            Location savedPreEntry = activeReturnLocations.getOrDefault(id, participant.preEntryReturnLocation());
            Location resolvedTarget = teleportTarget != null
                    ? resolveReturnLocation(id, savedPreEntry, teleportTarget)
                    : null;
            Location preservedReturn = resolvedTarget != null ? resolvedTarget : savedPreEntry;
            SavedCleanupRecord cleanupRecord = participant.isStateCaptured()
                    ? SavedCleanupRecord.withFlags(
                            participant.previousGameMode(),
                            participant.previousInvisible(),
                            participant.previousCollidable(),
                            participant.previousInvulnerable(),
                            resolvedTarget != null,
                            preservedReturn
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
                    } else if (resolvedTarget != null) {
                        player.setHealth(maxHealth(player));
                    }
                    player.setFireTicks(0);
                    player.setFallDistance(0.0f);
                    if (resolvedTarget != null && regionService.isRaidWorld(player.getWorld())) {
                        player.teleportAsync(resolvedTarget);
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
            pendingCleanupPlayers.put(cleanup.playerId(), SavedCleanupRecord.fromPendingCleanup(cleanup));
        }
    }

    private void persistPlayerCleanupAsync(RaidParticipant participant, boolean teleportToSafeZone) {
        if (recoveryRepository == null || !participant.isStateCaptured()) {
            return;
        }
        Location returnLoc = activeReturnLocations.getOrDefault(
                participant.playerId(), participant.preEntryReturnLocation());
        SavedCleanupRecord record = SavedCleanupRecord.withFlags(
                participant.previousGameMode(),
                participant.previousInvisible(),
                participant.previousCollidable(),
                participant.previousInvulnerable(),
                teleportToSafeZone,
                returnLoc
        );
        recoveryRepository.upsertPendingPlayerCleanupAsync(record.toPendingCleanup(participant.playerId()));
    }

    private void deletePlayerCleanupAsync(UUID playerId) {
        if (recoveryRepository != null) {
            recoveryRepository.deletePendingPlayerCleanupAsync(playerId);
        }
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

    private void startEncounterSystems(
            World fallbackEndWorld,
            List<Player> party,
            BossConfig bossConfig,
            ScaledEncounterParameters scaled,
            long expectedGeneration) {
        if (bossController == null) {
            return;
        }
        if (instanceManager != null) {
            instanceManager.getOrCreateInstance().whenComplete((instance, err) -> {
                if (err != null || instance == null) {
                    logger.log(Level.SEVERE, "Failed to prepare temporary raid instance; aborting raid encounter", err);
                    abortOnBossSpawnFailure(fallbackEndWorld, expectedGeneration);
                    return;
                }
                synchronized (stateLock) {
                    if (state != RaidState.ACTIVE || encounterGeneration.get() != expectedGeneration) {
                        instanceManager.destroyInstance(instance, null);
                        return;
                    }
                }
                World instWorld = instance.world();
                Location entrySpawn = regionService.safeZoneSpawnLocation(instWorld);
                List<CompletableFuture<Void>> teleportFutures = new ArrayList<>();
                for (Player member : party) {
                    if (member != null && member.isOnline() && !instWorld.equals(member.getWorld())) {
                        CompletableFuture<Void> memberTpDone = new CompletableFuture<>();
                        teleportFutures.add(memberTpDone);
                        TaskHandle tpHandle = scheduler.runAtEntity(member, () -> {
                            CompletableFuture<Boolean> tpFuture = null;
                            synchronized (stateLock) {
                                if (state == RaidState.ACTIVE
                                        && encounterGeneration.get() == expectedGeneration
                                        && participants.containsKey(member.getUniqueId())) {
                                    tpFuture = member.teleportAsync(entrySpawn);
                                    updateSnapshot(member, entrySpawn);
                                }
                            }
                            if (tpFuture != null) {
                                tpFuture.whenComplete((ok, tpErr) -> memberTpDone.complete(null));
                            } else {
                                memberTpDone.complete(null);
                            }
                        }, () -> memberTpDone.complete(null));
                        if (tpHandle == null) {
                            memberTpDone.complete(null);
                        }
                    }
                }
                CompletableFuture<Void> allEntered = teleportFutures.isEmpty()
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.allOf(teleportFutures.toArray(CompletableFuture[]::new));
                allEntered.whenComplete((ignored, tpErr) -> {
                    synchronized (stateLock) {
                        if (encounterGeneration.get() == expectedGeneration) {
                            this.awaitingInstanceEntry = false;
                        }
                    }
                });
                activateInCombatWorld(instWorld, instance.arenaCenter(), bossConfig, scaled, expectedGeneration);
            });
            return;
        }
        activateInCombatWorld(fallbackEndWorld, null, bossConfig, scaled, expectedGeneration);
    }

    private void activateInCombatWorld(
            World endWorld,
            @Nullable Location customArenaCenter,
            BossConfig bossConfig,
            ScaledEncounterParameters scaled,
            long expectedGeneration) {
        if (bossController == null) {
            return;
        }
        final RaidTimelineEngine timeline;
        final CompletableFuture<Void> bossReadyFuture;
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE || encounterGeneration.get() != expectedGeneration) {
                return;
            }

            timeline = new RaidTimelineEngine(bossConfig, new TimelineListener() {
                @Override
                public void onPhaseChanged(BossConfig.PhaseConfig newPhase) {
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
                    dispatchRegisteredGimmickAction(step.action(), ability, endWorld, customArenaCenter, bossConfig, expectedGeneration);
                }

                @Override
                public void onCastInterrupted(
                        BossConfig.TimelineStep step,
                        @Nullable BossConfig.AbilitySettings ability) {
                    List<Player> viewers = onlinePlayersInWorld(endWorld);
                    if (hudService != null) {
                        hudService.updateCastBossBar(null, 0.0, 0.0, viewers);
                    }
                }

                @Override
                public void onInstantAction(
                        BossConfig.TimelineStep step,
                        @Nullable BossConfig.AbilitySettings ability) {
                    dispatchRegisteredGimmickAction(step.action(), ability, endWorld, customArenaCenter, bossConfig, expectedGeneration);
                }
            });
            this.activeTimeline = timeline;
            timeline.start();
            bossReadyFuture = customArenaCenter != null
                    ? bossController.bindOrSpawn(endWorld, customArenaCenter, scaled.scaledBossHp())
                    : bossController.bindOrSpawn(endWorld, scaled.scaledBossHp());
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

    private void dispatchRegisteredGimmickAction(
            String actionId,
            @Nullable BossConfig.AbilitySettings ability,
            World endWorld,
            @Nullable Location customArenaCenter,
            BossConfig bossConfig,
            long expectedGeneration) {
        if (gimmickActionRegistry == null || actionId == null || actionId.isBlank()) {
            return;
        }
        gimmickActionRegistry.get(actionId).ifPresent(action -> {
            Location center = customArenaCenter != null
                    ? customArenaCenter.clone()
                    : regionService.clampToArenaAnchor(new Location(endWorld, 0.0, 64.0, 0.0), 0.0);
            List<Player> aliveSnapshot = List.copyOf(aliveOnlineParticipants());
            RaidScriptContext scriptContext = new EncounterScriptContext(
                    endWorld,
                    aliveSnapshot,
                    center,
                    bossConfig.baseAttackDamage(),
                    expectedGeneration
            );
            action.execute(scriptContext, ability != null ? ability.parameters() : Map.of());
        });
    }

    private final class EncounterScriptContext implements RaidScriptContext {

        private final World world;
        private final List<Player> aliveSnapshot;
        private final Location arenaCenter;
        private final double fallbackAttackDamage;
        private final long expectedGeneration;

        private EncounterScriptContext(
                World world,
                List<Player> aliveSnapshot,
                Location arenaCenter,
                double fallbackAttackDamage,
                long expectedGeneration) {
            this.world = world;
            this.aliveSnapshot = aliveSnapshot;
            this.arenaCenter = arenaCenter;
            this.fallbackAttackDamage = fallbackAttackDamage;
            this.expectedGeneration = expectedGeneration;
        }

        @Override
        public World world() {
            return world;
        }

        @Override
        public List<Player> aliveParticipants() {
            return aliveSnapshot;
        }

        @Override
        public @Nullable Location bossLocation() {
            return bossController != null ? bossController.bossLocation() : null;
        }

        @Override
        public Location arenaCenter() {
            return arenaCenter.clone();
        }

        @Override
        public boolean isShieldBlocking(UUID playerId) {
            return false;
        }

        @Override
        public Optional<RaidEnemyDefinition> enemyDefinition(String enemyId) {
            return enemyRegistry.find(enemyId);
        }

        @Override
        public @Nullable ActorModelController primaryBossModel() {
            return bossController != null ? bossController.activeModelController() : null;
        }

        @Override
        public RaidScheduler scheduler() {
            return scheduler;
        }

        @Override
        public void damagePlayer(Player player, double amount, String mechanicId) {
            if (player == null || !player.isOnline()) {
                return;
            }
            double damage = amount > 0.0 ? amount : fallbackAttackDamage;
            scheduler.runAtEntity(player, () -> {
                synchronized (stateLock) {
                    if (state != RaidState.ACTIVE || encounterGeneration.get() != expectedGeneration) {
                        return;
                    }
                    RaidParticipant participant = participants.get(player.getUniqueId());
                    if (participant == null || participant.state() != ParticipantState.ALIVE) {
                        return;
                    }
                }
                combatMetricsTracker.recordMechanicHit(player.getUniqueId(), player.getName());
                player.damage(damage);
            });
        }

        @Override
        public void knockbackPlayer(Player player, Vector velocity) {
            if (player == null || !player.isOnline() || velocity == null) {
                return;
            }
            Vector copy = velocity.clone();
            scheduler.runAtEntity(player, () -> {
                synchronized (stateLock) {
                    if (state != RaidState.ACTIVE || encounterGeneration.get() != expectedGeneration) {
                        return;
                    }
                    RaidParticipant participant = participants.get(player.getUniqueId());
                    if (participant == null || participant.state() != ParticipantState.ALIVE) {
                        return;
                    }
                }
                player.setVelocity(player.getVelocity().add(copy));
            });
        }
    }

    private void abortOnBossSpawnFailure(World endWorld, long expectedGeneration) {
        long endingGeneration;
        RaidInstanceManager.RaidInstance failedInstance = null;
        Map<UUID, Location> abortReturns = new LinkedHashMap<>();
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE || encounterGeneration.get() != expectedGeneration) {
                return;
            }
            cancelHandle(monitorTask);
            monitorTask = null;
            endingWipeMemberIds.clear();
            disconnectedDutyMembers.clear();
            awaitingInstanceEntry = false;
            endingGeneration = encounterGeneration.incrementAndGet();
            state = RaidState.ENDING;
            abortReturns.putAll(activeReturnLocations);
            for (RaidParticipant p : participants.values()) {
                if (!abortReturns.containsKey(p.playerId()) && p.preEntryReturnLocation() != null) {
                    abortReturns.put(p.playerId(), p.preEntryReturnLocation());
                }
            }
            if (instanceManager != null) {
                RaidInstanceManager.RaidInstance current = instanceManager.activeInstance();
                DutyStandbySession currentStandby = this.standbySession;
                boolean ownedByStandby = currentStandby != null && current != null && current.equals(currentStandby.instance());
                if (current != null && !ownedByStandby
                        && (endWorld.equals(current.world()) || instanceManager.isInstanceWorld(endWorld))) {
                    failedInstance = current;
                }
            }
        }
        World mainEnd = endWorldSupplier.get();
        Location fallbackSpawn = mainEnd != null
                ? regionService.safeZoneSpawnLocation(mainEnd)
                : regionService.safeZoneSpawnLocation(endWorld);
        List<Player> viewers = onlinePlayersInWorld(endWorld);
        soulMarkerService.removeAllMarkers(viewers);
        cleanupEncounterSystems(viewers, true);
        int partySizeSnapshot = participants.size();
        Map<UUID, net.sabafly.dragonraid.api.event.ParticipantCombatMetrics> metricsSnapshot =
                combatMetricsTracker.snapshot();
        long completionSeq = completionSequence.incrementAndGet();
        if (failedInstance != null) {
            restoreAndResetParticipants(null);
        } else {
            restoreAndResetParticipants(fallbackSpawn);
        }
        activeReturnLocations.clear();
        emitEncounterCompleted(EncounterOutcome.ABORTED, 0L, partySizeSnapshot, metricsSnapshot, completionSeq);
        CompletableFuture<Void> rollbackFuture = rollbackBlocksIfConfigured(endWorld);
        RaidInstanceManager.RaidInstance destroyTarget = failedInstance;
        CompletableFuture<Void> endingFuture = destroyTarget != null
                ? rollbackFuture.handle((ignored, err) -> (Void) null)
                        .thenCompose(ignored -> evacuateToIndividualReturnsThenDestroy(
                                destroyTarget, abortReturns, fallbackSpawn))
                : rollbackFuture;
        completeEndingWhenRolledBack(endingGeneration, endingFuture);
    }

    private void tickEncounterSystems(World endWorld, List<Player> endPlayers, Set<UUID> channelingRevivers) {
        if (bossController == null) {
            return;
        }
        bossController.tick(MONITOR_STEP_SECONDS);
        RaidTimelineEngine timeline = this.activeTimeline;
        if (timeline != null) {
            timeline.tick(MONITOR_STEP_SECONDS, bossController.hpRatio());
        }
        BossConfig bossConfig = this.activeBossConfig;
        BossConfig.PhaseConfig currentPhase = timeline != null ? timeline.currentPhase() : null;
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
                    playerId -> null,
                    channelingRevivers::contains
            );
        }
    }

    private CompletableFuture<Set<UUID>> prepareVictoryCleanup(
            BossConfig.Difficulty difficulty,
            long durationMs,
            String encounterId) {
        List<CompletableFuture<Optional<UUID>>> clearFutures = new ArrayList<>();
        if (playerDataRepository != null) {
            Difficulty storageDifficulty = difficulty == BossConfig.Difficulty.HARD ? Difficulty.HARD : Difficulty.NORMAL;
            long clearedAt = clockMillis.getAsLong();
            for (RaidParticipant participant : participants.values()) {
                CompletableFuture<?> clearStatsFuture = playerDataRepository.recordClear(
                        participant.playerId(),
                        participant.name(),
                        storageDifficulty,
                        durationMs
                );
                if (clearStatsFuture != null) {
                    clearStatsFuture.exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to record raid clear for " + participant.name(), ex);
                        return null;
                    });
                }
                CompletableFuture<Boolean> encClearFuture = playerDataRepository.recordEncounterClear(
                        participant.playerId(),
                        participant.name(),
                        encounterId,
                        durationMs,
                        clearedAt
                );
                if (encClearFuture == null) {
                    clearFutures.add(CompletableFuture.completedFuture(Optional.empty()));
                } else {
                    clearFutures.add(encClearFuture.handle((isFirst, ex) -> {
                        if (ex != null) {
                            logger.log(Level.WARNING, "Failed to record encounter clear for " + participant.name(), ex);
                            return Optional.<UUID>empty();
                        }
                        return Boolean.TRUE.equals(isFirst)
                                ? Optional.of(participant.playerId())
                                : Optional.<UUID>empty();
                    }));
                }
            }
        }
        RaidTimelineEngine timeline = this.activeTimeline;
        this.activeTimeline = null;
        if (timeline != null) {
            timeline.stop();
        }
        if (hudService != null) {
            World endWorld = endWorldSupplier.get();
            List<Player> viewers = endWorld != null ? onlinePlayersInWorld(endWorld) : onlineParticipantPlayers();
            hudService.clearAll(viewers);
        }
        if (bossController != null) {
            bossController.prepareForVanillaDefeat();
        }
        if (clearFutures.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        return CompletableFuture.allOf(clearFutures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Set<UUID> firstClearIds = new HashSet<>();
                    for (CompletableFuture<Optional<UUID>> f : clearFutures) {
                        f.join().ifPresent(firstClearIds::add);
                    }
                    return Set.copyOf(firstClearIds);
                });
    }

    private void runLegacyVanillaProgressionFallback(World endWorld) {
        World mainEnd = endWorldSupplier.get();
        World progressionWorld = mainEnd != null ? mainEnd : endWorld;
        DragonBattle battle = progressionWorld != null ? progressionWorld.getEnderDragonBattle() : null;
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

    private CompletableFuture<Void> cleanupEncounterSystems(List<Player> viewers, boolean removeDragonIfSummoned) {
        RaidTimelineEngine timeline = this.activeTimeline;
        this.activeTimeline = null;
        if (timeline != null) {
            timeline.stop();
        }
        if (hudService != null) {
            hudService.clearAll(viewers);
        }
        CompletableFuture<Void> bossFuture = bossController != null
                ? bossController.cleanup(removeDragonIfSummoned)
                : CompletableFuture.completedFuture(null);
        return bossFuture != null ? bossFuture : CompletableFuture.completedFuture(null);
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

    private record DutyStandbySession(
            EncounterBlueprint blueprint,
            BossConfig bossConfig,
            World fallbackEndWorld,
            Set<UUID> memberIds,
            Map<UUID, Location> returnLocations,
            @Nullable RaidInstanceManager.RaidInstance instance,
            CompletableFuture<Void> rollbackFuture
    ) {
        private DutyStandbySession {
            rollbackFuture = rollbackFuture == null ? CompletableFuture.completedFuture(null) : rollbackFuture;
        }
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
            boolean teleportToSafeZone,
            boolean restoreFlags,
            @Nullable Location cachedReturnLocation,
            @Nullable UUID returnWorldId,
            @Nullable Double returnX,
            @Nullable Double returnY,
            @Nullable Double returnZ,
            @Nullable Float returnYaw,
            @Nullable Float returnPitch
    ) {
        static SavedCleanupRecord withFlags(
                @Nullable GameMode gameMode,
                boolean invisible,
                boolean collidable,
                boolean invulnerable,
                boolean teleportToSafeZone,
                @Nullable Location returnLocation) {
            Location copy = (returnLocation != null && returnLocation.getWorld() != null)
                    ? returnLocation.clone()
                    : null;
            return new SavedCleanupRecord(
                    gameMode,
                    invisible,
                    collidable,
                    invulnerable,
                    teleportToSafeZone,
                    true,
                    copy,
                    copy != null ? copy.getWorld().getUID() : null,
                    copy != null ? copy.getX() : null,
                    copy != null ? copy.getY() : null,
                    copy != null ? copy.getZ() : null,
                    copy != null ? copy.getYaw() : null,
                    copy != null ? copy.getPitch() : null
            );
        }

        static SavedCleanupRecord returnOnly(Location returnLocation) {
            Objects.requireNonNull(returnLocation, "returnLocation");
            World world = Objects.requireNonNull(returnLocation.getWorld(), "returnLocation.world");
            Location copy = returnLocation.clone();
            return new SavedCleanupRecord(
                    null,
                    false,
                    true,
                    false,
                    true,
                    false,
                    copy,
                    world.getUID(),
                    copy.getX(),
                    copy.getY(),
                    copy.getZ(),
                    copy.getYaw(),
                    copy.getPitch()
            );
        }

        static SavedCleanupRecord fromPendingCleanup(PendingPlayerCleanup cleanup) {
            GameMode mode = null;
            if (cleanup.gameModeName() != null) {
                try {
                    mode = GameMode.valueOf(cleanup.gameModeName());
                } catch (IllegalArgumentException ignored) {
                    // Ignore unrecognized enum names safely.
                }
            }
            return new SavedCleanupRecord(
                    mode,
                    cleanup.invisible(),
                    cleanup.collidable(),
                    cleanup.invulnerable(),
                    cleanup.teleportToSafeZone(),
                    cleanup.restoreFlags(),
                    null,
                    cleanup.returnWorldId(),
                    cleanup.returnX(),
                    cleanup.returnY(),
                    cleanup.returnZ(),
                    cleanup.returnYaw(),
                    cleanup.returnPitch()
            );
        }

        boolean hasReturnLocation() {
            return (cachedReturnLocation != null && cachedReturnLocation.getWorld() != null)
                    || (returnWorldId != null && returnX != null && returnY != null && returnZ != null);
        }

        SavedCleanupRecord asReturnOnly() {
            Location copy = (cachedReturnLocation != null && cachedReturnLocation.getWorld() != null)
                    ? cachedReturnLocation.clone()
                    : null;
            return new SavedCleanupRecord(
                    null,
                    false,
                    true,
                    false,
                    teleportToSafeZone,
                    false,
                    copy,
                    returnWorldId,
                    returnX,
                    returnY,
                    returnZ,
                    returnYaw,
                    returnPitch
            );
        }

        void applyTo(Player player) {
            if (!restoreFlags) {
                return;
            }
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
                    teleportToSafeZone,
                    restoreFlags,
                    returnWorldId,
                    returnX,
                    returnY,
                    returnZ,
                    returnYaw,
                    returnPitch
            );
        }
    }
}
