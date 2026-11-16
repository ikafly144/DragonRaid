package net.sabafly.dragonraid.encounter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.EncounterSettings;
import net.sabafly.dragonraid.config.PluginConfig.ProtectionSettings;
import net.sabafly.dragonraid.config.PluginConfig.ReviveSettings;
import net.sabafly.dragonraid.config.PluginConfig.ScalingSettings;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.scaling.RaidScalingCalculator;
import net.sabafly.dragonraid.scaling.ScaledEncounterParameters;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingBlockRollback;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingPlayerCleanup;
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
import org.bukkit.entity.Player;
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
    private final Logger logger;
    private final Supplier<@Nullable World> endWorldSupplier;
    private final Supplier<Collection<? extends Player>> onlinePlayersSupplier;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final LongSupplier clockMillis;
    private final Predicate<World> livingDragonPredicate;

    private volatile RaidState state = RaidState.IDLE;
    private volatile @Nullable BossConfig.Difficulty activeDifficulty;
    private volatile @Nullable ScaledEncounterParameters activeScaling;
    private final AtomicInteger remainingRevives = new AtomicInteger(0);
    private volatile long combatStartedMillis;

    private final Set<UUID> readyPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RaidParticipant> participants = new ConcurrentHashMap<>();
    private final Map<UUID, SavedCleanupRecord> pendingCleanupPlayers = new ConcurrentHashMap<>();
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
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.regionService = Objects.requireNonNull(regionService, "regionService");
        this.blockTracker = Objects.requireNonNull(blockTracker, "blockTracker");
        this.soulMarkerService = Objects.requireNonNull(soulMarkerService, "soulMarkerService");
        this.recoveryRepository = recoveryRepository;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.endWorldSupplier = Objects.requireNonNull(endWorldSupplier, "endWorldSupplier");
        this.onlinePlayersSupplier = Objects.requireNonNull(onlinePlayersSupplier, "onlinePlayersSupplier");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.livingDragonPredicate = Objects.requireNonNull(livingDragonPredicate, "livingDragonPredicate");
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
        blockTracker.rollbackPendingEntries(scheduler, worldLookup, pending)
                .thenCompose(recoveryRepository::deletePendingBlockRollbacksAsync)
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Failed to recover pending temporary block rollbacks", ex);
                    return null;
                });
    }

    @Override
    public RaidState state() {
        return state;
    }

    @Override
    public boolean isProtectionActive() {
        RaidState current = state;
        return current == RaidState.COUNTDOWN || current == RaidState.ACTIVE || current == RaidState.WIPING;
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
    public void adminStart(CommandSender sender, BossConfig.Difficulty difficulty) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(difficulty, "difficulty");
        startPull(sender, difficulty, true, false);
    }

    private void startPull(
            CommandSender sender,
            BossConfig.Difficulty difficulty,
            boolean forced,
            boolean triggeredByArenaEntry) {
        World endWorld = endWorldSupplier.get();
        if (endWorld == null) {
            if (sender != null) {
                messageService.send(sender, "command.start-no-players");
            }
            return;
        }

        List<Player> candidates = collectPullCandidates(endWorld);
        if (candidates.isEmpty()) {
            if (!triggeredByArenaEntry && sender != null) {
                messageService.send(sender, "command.start-no-players");
            }
            return;
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
            return;
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
            return;
        }

        int countdownSeconds;
        synchronized (stateLock) {
            if (state != RaidState.IDLE) {
                if (!triggeredByArenaEntry && sender != null) {
                    messageService.send(sender, "command.already-running");
                }
                return;
            }
            state = RaidState.COUNTDOWN;
            activeDifficulty = difficulty;
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
            lockInAndActivate(difficulty, endWorld);
            return;
        }

        AtomicInteger remaining = new AtomicInteger(countdownSeconds);
        broadcastCountdownTick(endWorld, difficulty, candidates.size(), remaining.get());
        countdownTask = scheduler.runGlobalTimer(() -> {
            if (state != RaidState.COUNTDOWN) {
                cancelHandle(countdownTask);
                return;
            }
            World world = endWorldSupplier.get();
            if (world == null) {
                cancelPullInternal(null);
                return;
            }
            List<Player> currentCandidates = collectPullCandidates(world);
            if (currentCandidates.isEmpty() || (triggeredByArenaEntry && !forced && countPlayersInCombatArena(currentCandidates) == 0)) {
                cancelPullInternal(world);
                return;
            }
            int next = remaining.decrementAndGet();
            if (next <= 0) {
                cancelHandle(countdownTask);
                countdownTask = null;
                lockInAndActivate(difficulty, world);
                return;
            }
            broadcastCountdownTick(world, difficulty, currentCandidates.size(), next);
        }, 20L, 20L);
    }

    private void cancelPullInternal(@Nullable World world) {
        cancelPullInternal(world, "raid.pull-cancelled", null);
    }

    private void cancelPullInternal(@Nullable World world, String messageKey, @Nullable Integer minPlayers) {
        synchronized (stateLock) {
            if (state != RaidState.COUNTDOWN) {
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

    private void lockInAndActivate(BossConfig.Difficulty difficulty, World endWorld) {
        List<Player> candidates = collectPullCandidates(endWorld);
        ScalingSettings scaling = configManager.pluginConfig().scaling();
        if (candidates.isEmpty() || candidates.size() < scaling.minPlayers()) {
            cancelPullInternal(
                    endWorld,
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

        synchronized (stateLock) {
            if (state != RaidState.COUNTDOWN) {
                return;
            }
            participants.clear();
            for (Player member : party) {
                UUID memberId = member.getUniqueId();
                SavedCleanupRecord staleCleanup = pendingCleanupPlayers.remove(memberId);
                RaidParticipant participant = new RaidParticipant(memberId, member.getName());
                participant.setOnline(true);
                participants.put(memberId, participant);
                scheduler.runAtEntity(member, () -> {
                    if (staleCleanup != null) {
                        staleCleanup.applyTo(member);
                        deletePlayerCleanupAsync(memberId);
                    }
                    participant.captureOriginalState(
                            member.getGameMode(),
                            member.isInvisible(),
                            member.isCollidable(),
                            member.isInvulnerable()
                    );
                    persistPlayerCleanupAsync(participant, true);
                    updateSnapshot(member);
                });
            }
            readyPlayers.clear();
            this.activeDifficulty = difficulty;
            this.activeScaling = scaled;
            this.remainingRevives.set(scaled.maxRevives());
            this.combatStartedMillis = clockMillis.getAsLong();
            this.state = RaidState.ACTIVE;
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

        monitorTask = scheduler.runGlobalTimer(this::tickActiveEncounter, 2L, 2L);
    }

    /**
     * Global 2-tick coordinator reading {@link PlayerSnapshot}s and scheduling entity-thread updates.
     */
    void tickActiveEncounter() {
        if (state != RaidState.ACTIVE) {
            return;
        }
        World endWorld = endWorldSupplier.get();
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
                    if (!channellingAlly.getUniqueId().equals(downed.currentReviverId())) {
                        downed.setCurrentReviverId(channellingAlly.getUniqueId());
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

        // 3. Evaluate wipe condition.
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
        regionService.findNearestSafeArenaEdgeAsync(voidLoc, yOffset).whenComplete((safeLoc, error) -> {
            Location target = (error == null && safeLoc != null)
                    ? safeLoc
                    : regionService.clampToArenaAnchor(voidLoc, yOffset);
            scheduler.runAtEntity(player, () -> {
                participant.setRescueInProgress(false);
                if (state != RaidState.ACTIVE || !player.isOnline()) {
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
            scheduler.runAtEntity(player, () -> {
                participant.captureOriginalState(
                        player.getGameMode(),
                        player.isInvisible(),
                        player.isCollidable(),
                        player.isInvulnerable()
                );
                persistPlayerCleanupAsync(participant, true);
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
            scheduler.runAtEntity(target, () -> {
                restorePlayerFlags(participant, target);
                double maxHp = maxHealth(target);
                target.setHealth(Math.max(1.0, Math.min(maxHp, maxHp * reviveSettings.healPercent())));
                int invulTicks = (int) Math.round(reviveSettings.invulnerabilitySeconds() * 20.0);
                if (invulTicks > 0) {
                    target.setInvulnerable(true);
                    scheduler.runAtEntityLater(target, () -> {
                        if (participant.state() == ParticipantState.ALIVE) {
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
            scheduler.runAtEntity(target, () -> {
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
        for (RaidParticipant p : participants.values()) {
            if (!p.isOnline() || p.state() != ParticipantState.ALIVE) {
                continue;
            }
            onlineAlive++;
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
        boolean allLeftArena = reviveSettings.wipeIfAllLeaveArena() && onlineAlive > 0 && onlineAliveInArena == 0;
        if (noAlivePlayers || allLeftArena) {
            beginWipe(endWorld);
        }
    }

    private void beginWipe(@Nullable World endWorld) {
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE) {
                return;
            }
            state = RaidState.WIPING;
            cancelHandle(monitorTask);
            monitorTask = null;
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
            finishWipe(endWorld);
        } else {
            wipeTask = scheduler.runGlobalLater(() -> finishWipe(endWorld), delaySeconds * 20L);
        }
    }

    private void finishWipe(@Nullable World endWorld) {
        World targetWorld = endWorld != null ? endWorld : endWorldSupplier.get();
        List<Player> viewers = targetWorld != null ? onlinePlayersInWorld(targetWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);

        Location safeSpawn = targetWorld != null ? regionService.safeZoneSpawnLocation(targetWorld) : null;
        restoreAndResetParticipants(safeSpawn);
        rollbackBlocksIfConfigured(targetWorld);

        synchronized (stateLock) {
            cancelHandle(wipeTask);
            wipeTask = null;
            activeDifficulty = null;
            activeScaling = null;
            remainingRevives.set(0);
            state = RaidState.IDLE;
        }
    }

    @Override
    public void adminReset(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        World endWorld = endWorldSupplier.get();
        synchronized (stateLock) {
            cancelHandle(countdownTask);
            cancelHandle(monitorTask);
            cancelHandle(wipeTask);
            countdownTask = null;
            monitorTask = null;
            wipeTask = null;
        }

        List<Player> viewers = endWorld != null ? onlinePlayersInWorld(endWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        Location safeSpawn = endWorld != null ? regionService.safeZoneSpawnLocation(endWorld) : null;
        restoreAndResetParticipants(safeSpawn);
        rollbackBlocksIfConfigured(endWorld);
        readyPlayers.clear();

        synchronized (stateLock) {
            activeDifficulty = null;
            activeScaling = null;
            remainingRevives.set(0);
            state = RaidState.IDLE;
        }
        messageService.send(sender, "command.admin-reset");
    }

    @Override
    public void onDragonDefeated(EnderDragon dragon) {
        Objects.requireNonNull(dragon, "dragon");
        if (!regionService.isRaidWorld(dragon.getWorld())) {
            return;
        }
        BossConfig.Difficulty difficulty;
        synchronized (stateLock) {
            if (state != RaidState.ACTIVE) {
                return;
            }
            cancelHandle(monitorTask);
            monitorTask = null;
            difficulty = activeDifficulty != null ? activeDifficulty : BossConfig.Difficulty.NORMAL;
        }

        long durationMs = Math.max(0L, clockMillis.getAsLong() - combatStartedMillis);
        String formattedTime = formatDuration(durationMs);

        World endWorld = dragon.getWorld();
        List<Player> viewers = onlinePlayersInWorld(endWorld);
        soulMarkerService.removeAllMarkers(viewers);

        // On victory, restore participant flags where they stand so they can collect drops/egg.
        restoreAndResetParticipants(null);
        rollbackBlocksIfConfigured(endWorld);

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

        synchronized (stateLock) {
            activeDifficulty = null;
            activeScaling = null;
            remainingRevives.set(0);
            state = RaidState.IDLE;
        }
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

        // Lock DOWNED participant X/Y/Z while allowing yaw/pitch rotation.
        if (currentState == RaidState.ACTIVE && participant != null && participant.state() == ParticipantState.DOWNED) {
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

        // Late-joiner lock when entering the arena while raid is active/wiping.
        EncounterSettings enc = configManager.pluginConfig().encounter();
        if ((currentState == RaidState.ACTIVE || currentState == RaidState.WIPING)
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
        updateSnapshot(player);

        // If a previous raid ended while this player was offline, restore flags and optionally move to safe zone.
        SavedCleanupRecord cleanup = pendingCleanupPlayers.remove(id);
        if (cleanup != null) {
            deletePlayerCleanupAsync(id);
            scheduler.runAtEntity(player, () -> {
                cleanup.applyTo(player);
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
        if ((state == RaidState.ACTIVE || state == RaidState.WIPING)
                && configManager.pluginConfig().encounter().lockLateJoinersToSafeZone()
                && regionService.isRaidWorld(player.getWorld())
                && !regionService.isInSafeZone(player.getLocation())) {
            World endWorld = player.getWorld();
            scheduler.runAtEntity(player, () -> {
                player.teleportAsync(regionService.safeZoneSpawnLocation(endWorld));
                messageService.send(player, "raid.late-join-locked");
            });
        }
    }

    @Override
    public void onPlayerQuit(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        readyPlayers.remove(id);
        snapshots.remove(id);
        lastRightClickMillis.remove(id);
        lastFeedbackMillis.remove(id);

        RaidParticipant participant = participants.get(id);
        if (participant == null || state != RaidState.ACTIVE) {
            return;
        }
        participant.setOnline(false);
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
            return;
        }
        if ((state == RaidState.ACTIVE || state == RaidState.WIPING)
                && !isParticipant(id)
                && configManager.pluginConfig().encounter().lockLateJoinersToSafeZone()
                && !regionService.isInSafeZone(player.getLocation())) {
            World endWorld = player.getWorld();
            scheduler.runAtEntity(player, () -> {
                player.teleportAsync(regionService.safeZoneSpawnLocation(endWorld));
                messageService.send(player, "raid.late-join-locked");
            });
        }
    }

    @Override
    public void onPlayerDamage(Player player, EntityDamageEvent event) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(event, "event");
        if (state != RaidState.ACTIVE) {
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
    public void shutdown() {
        synchronized (stateLock) {
            cancelHandle(countdownTask);
            cancelHandle(monitorTask);
            cancelHandle(wipeTask);
            countdownTask = null;
            monitorTask = null;
            wipeTask = null;
        }
        World endWorld = endWorldSupplier.get();
        List<Player> viewers = endWorld != null ? onlinePlayersInWorld(endWorld) : List.of();
        soulMarkerService.removeAllMarkers(viewers);
        for (RaidParticipant participant : participants.values()) {
            UUID id = participant.playerId();
            Player player = playerLookup.apply(id);
            if (player != null && player.isOnline()) {
                restorePlayerFlags(participant, player);
                pendingCleanupPlayers.remove(id);
            } else if (participant.isStateCaptured()) {
                pendingCleanupPlayers.put(id, new SavedCleanupRecord(
                        participant.previousGameMode(),
                        participant.previousInvisible(),
                        participant.previousCollidable(),
                        participant.previousInvulnerable(),
                        true
                ));
            }
        }
        if (recoveryRepository != null) {
            List<PendingPlayerCleanup> cleanups = new ArrayList<>(pendingCleanupPlayers.size());
            for (Map.Entry<UUID, SavedCleanupRecord> entry : pendingCleanupPlayers.entrySet()) {
                cleanups.add(entry.getValue().toPendingCleanup(entry.getKey()));
            }
            recoveryRepository.replacePendingPlayerCleanupsSync(cleanups);

            List<PendingBlockRollback> pendingBlocks = configManager.pluginConfig().protection().rollbackTemporaryBlocksOnEnd()
                    ? blockTracker.exportPendingRollbacks()
                    : List.of();
            recoveryRepository.replacePendingBlockRollbacksSync(pendingBlocks);
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
            if (player != null && player.isOnline()) {
                pendingCleanupPlayers.remove(id);
                deletePlayerCleanupAsync(id);
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
                    updateSnapshot(player);
                });
            } else if (participant.isStateCaptured()) {
                SavedCleanupRecord record = new SavedCleanupRecord(
                        participant.previousGameMode(),
                        participant.previousInvisible(),
                        participant.previousCollidable(),
                        participant.previousInvulnerable(),
                        teleportTarget != null
                );
                pendingCleanupPlayers.put(id, record);
                if (recoveryRepository != null) {
                    recoveryRepository.upsertPendingPlayerCleanupAsync(record.toPendingCleanup(id));
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

    private void rollbackBlocksIfConfigured(@Nullable World endWorld) {
        if (configManager.pluginConfig().protection().rollbackTemporaryBlocksOnEnd()) {
            blockTracker.rollbackAll(scheduler, endWorld).exceptionally(ex -> {
                logger.log(Level.WARNING, "Failed to roll back temporary raid blocks", ex);
                return 0;
            });
        } else {
            blockTracker.clear();
        }
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
        for (Player player : onlinePlayersInWorld(endWorld)) {
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
