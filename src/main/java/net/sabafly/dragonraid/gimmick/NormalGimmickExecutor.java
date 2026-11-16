package net.sabafly.dragonraid.gimmick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.boss.DragonPosture;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.instance.CircularArenaBuilder;
import net.sabafly.dragonraid.scaling.ScaledEncounterParameters;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.timeline.RaidTimelineEngine;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
import net.sabafly.dragonraid.visual.TelegraphStyle;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * Executes all 12 True Ending-inspired & MMO raid mechanics across Normal and Hard/Savage:
 * <ol>
 *   <li>{@code DRAGON_DIVE_STACK} (or {@code STACK}): Player-targeted dragon dive with shared impact</li>
 *   <li>{@code GROUND_SLAM_DIVE}: Point-targeted dive with distance falloff & expanding 360° shockwave</li>
 *   <li>{@code LIGHTNING_STORM}: Multi-wave random + predictive lightning strikes</li>
 *   <li>{@code SWEEPING_LASER}: True Ending-style rotating raycast laser beam across the arena</li>
 *   <li>{@code SHIELD_WALL_BREATH}: Field-wide breath where a shielding ally carves a safe rear cone</li>
 *   <li>{@code FIREBALL_BARRAGE}: Accelerating bullet-hell energy orb barrage</li>
 *   <li>{@code ENDERMAN_ERUPTION}: Virtual scaled Enderman bamboo-shoot (Takenoko) AoEs (chasing, delayed, ring)</li>
 *   <li>{@code EXAFLARE_WAVES}: Marching directional AoE lanes with 3D arrow displays</li>
 *   <li>{@code KNOCKBACK_COMBO}: Radial knockback roar with 3D arrows + follow-up ring eruption</li>
 *   <li>{@code ENDERMAN_ROTATING_BEAM} (Hard): 1-per-player tethered Enderman rotating beam kited outward</li>
 *   <li>{@code DUAL_DIVE_DICE} (Hard): Odd/Even 2-group simultaneous dual dragon dive</li>
 *   <li>{@code TETHER_DIVE_RELAY} (Hard): Nearest-player auto-transfer tether dive relay with vulnerability debuff</li>
 * </ol>
 * Chat/title hand-holding text prompts are omitted so visual dragon animations, sounds, and
 * PacketEvents Display/Virtual entities communicate danger organically.
 */
public final class NormalGimmickExecutor {

    private static final double MIN_SOFT_ENRAGE_RADIUS = 14.0;
    private static final double TOWER_RING_RADIUS = 18.0;
    private static final double LOS_SAMPLE_STEP = 0.5;
    private static final double MONITOR_TICK_SECONDS = 0.1;

    /**
     * Async line-of-sight occlusion probe returning {@code true} if solid terrain or temporary blocks
     * block the sightline between {@code bossOrigin} and {@code playerEye}.
     */
    @FunctionalInterface
    public interface OcclusionChecker {
        CompletableFuture<Boolean> isOccludedAsync(Location bossOrigin, Location playerEye);
    }

    private final ConfigManager configManager;
    private final MessageService messageService;
    private final RaidScheduler scheduler;
    private final SpatialTelegraphService telegraphService;
    private final RaidCrystalManager crystalManager;
    private final LongSupplier clockMillis;
    private final BiConsumer<Player, Double> damageApplier;
    private final OcclusionChecker occlusionChecker;

    private final Map<UUID, Long> shieldRaiseMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lastBlockingState = new ConcurrentHashMap<>();
    private final List<ActiveFollowUpEffect> followUpEffects = new ArrayList<>();
    private final AtomicInteger towerRotationIndex = new AtomicInteger(0);
    private final AtomicInteger mechanicGeneration = new AtomicInteger(0);
    private final AtomicInteger sessionGeneration = new AtomicInteger(0);

    private volatile @Nullable ActiveMechanic activeMechanic;
    private volatile double safeArenaRadius;
    private volatile double softEnrageAccumulatorSeconds;
    private volatile boolean softEnrageStarted;

    public NormalGimmickExecutor(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            SpatialTelegraphService telegraphService,
            RaidCrystalManager crystalManager) {
        this(
                configManager,
                messageService,
                scheduler,
                telegraphService,
                crystalManager,
                System::currentTimeMillis,
                (player, amount) -> player.damage(amount),
                null
        );
    }

    NormalGimmickExecutor(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            SpatialTelegraphService telegraphService,
            RaidCrystalManager crystalManager,
            LongSupplier clockMillis,
            BiConsumer<Player, Double> damageApplier,
            @Nullable OcclusionChecker occlusionChecker) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telegraphService = Objects.requireNonNull(telegraphService, "telegraphService");
        this.crystalManager = Objects.requireNonNull(crystalManager, "crystalManager");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.damageApplier = Objects.requireNonNull(damageApplier, "damageApplier");
        this.occlusionChecker = occlusionChecker != null ? occlusionChecker : this::checkRayOccludedFoliaSafe;
        resetState();
    }

    /**
     * Resets mechanic state and soft enrage radius for a fresh encounter attempt.
     */
    public void resetState() {
        this.sessionGeneration.incrementAndGet();
        this.mechanicGeneration.incrementAndGet();
        this.activeMechanic = null;
        synchronized (followUpEffects) {
            this.followUpEffects.clear();
        }
        this.shieldRaiseMillis.clear();
        this.lastBlockingState.clear();
        this.towerRotationIndex.set(0);
        this.softEnrageAccumulatorSeconds = 0.0;
        this.softEnrageStarted = false;
        this.safeArenaRadius = configManager.pluginConfig().regions().arena().radius();
    }

    /**
     * Records a shield-raise edge when a player begins guarding with a shield.
     */
    public void recordShieldPulse(Player player) {
        Objects.requireNonNull(player, "player");
        if (!hasShieldEquipped(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean alreadyBlocking = Boolean.TRUE.equals(lastBlockingState.get(id)) || player.isBlocking();
        if (!alreadyBlocking) {
            shieldRaiseMillis.put(id, clockMillis.getAsLong());
            lastBlockingState.put(id, true);
        }
    }

    /**
     * Updates shield-blocking edge timestamps for Just Guard detection.
     */
    public void sampleShieldStates(Collection<? extends Player> aliveParticipants) {
        long now = clockMillis.getAsLong();
        for (Player player : aliveParticipants) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            UUID id = player.getUniqueId();
            boolean blocking = player.isBlocking();
            boolean prev = Boolean.TRUE.equals(lastBlockingState.put(id, blocking));
            if (blocking && !prev) {
                shieldRaiseMillis.put(id, now);
            }
        }
    }

    /**
     * Executes an instant timeline action (e.g. {@code SPAWN_CRYSTALS} / {@code SPAWN_TETHERED_CRYSTALS}).
     */
    public void executeInstantAction(
            BossConfig.TimelineStep step,
            @Nullable BossConfig.AbilitySettings ability,
            World endWorld,
            ScaledEncounterParameters scaled,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(endWorld, "endWorld");
        Objects.requireNonNull(scaled, "scaled");

        String act = step.action().toUpperCase(Locale.ROOT);
        if ("SPAWN_CRYSTALS".equals(act) || "SPAWN_TETHERED_CRYSTALS".equals(act)) {
            double regen = ability != null ? ability.getDouble("boss-regen-per-second", 4.0) : 4.0;
            crystalManager.spawnWave(endWorld, scaled.crystalCount(), scaled.scaledCrystalHp(), regen);
            playWarningSound(viewers, "minecraft:block.beacon.activate", 1.2f);
        }
    }

    /**
     * Initializes an ability cast, selects mechanic targets/zones, triggers physical dragon motion,
     * and spawns PacketEvents Display / Virtual Enderman telegraphs without chat text spam.
     */
    public void startCast(
            BossConfig.TimelineStep step,
            @Nullable BossConfig.AbilitySettings ability,
            World endWorld,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            ScaledEncounterParameters scaled,
            DragonBossController bossController) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(endWorld, "endWorld");
        Objects.requireNonNull(aliveParticipants, "aliveParticipants");
        Objects.requireNonNull(viewers, "viewers");
        Objects.requireNonNull(scaled, "scaled");
        Objects.requireNonNull(bossController, "bossController");

        cleanupActiveVirtualEntities(viewers);
        this.mechanicGeneration.incrementAndGet();
        String action = step.action().toUpperCase(Locale.ROOT);
        double castSec = ability != null ? ability.castSeconds() : 3.5;
        Location center = arenaCenter(endWorld);

        switch (action) {
            case "TANK_BUSTER" -> {
                Player target = selectNearestToBoss(aliveParticipants, bossController.bossLocation());
                if (target == null) {
                    this.activeMechanic = null;
                    return;
                }
                bossController.beginDive(target.getLocation(), Math.max(0.6, castSec * 0.45));
                this.activeMechanic = ActiveMechanic.singleTarget(MechanicType.TANK_BUSTER, ability, target.getUniqueId(), false, castSec);
                playWarningSound(viewers, "minecraft:entity.ender_dragon.growl", 0.75f);
            }
            case "STACK", "DRAGON_DIVE_STACK" -> {
                Player target = selectFirstInArena(aliveParticipants);
                if (target == null) {
                    this.activeMechanic = null;
                    return;
                }
                boolean solo = scaled.soloFallbackActive() || aliveParticipants.size() <= 1;
                bossController.beginDive(target.getLocation(), Math.max(0.8, castSec * 0.5));
                this.activeMechanic = ActiveMechanic.singleTarget(MechanicType.DRAGON_DIVE_STACK, ability, target.getUniqueId(), solo, castSec);
                playWarningSound(viewers, "minecraft:entity.ender_dragon.growl", 1.1f);
                playWarningSound(viewers, "minecraft:entity.ender_dragon.flap", 0.9f);
            }
            case "GROUND_SLAM_DIVE", "DONUT_OUTER_AOE" -> {
                Player anchorPlayer = selectFirstInArena(aliveParticipants);
                Location slamTarget = anchorPlayer != null && anchorPlayer.getLocation() != null
                        ? anchorPlayer.getLocation().clone()
                        : center.clone();
                slamTarget.setY(center.getY());
                bossController.beginDive(slamTarget, Math.max(1.0, castSec * 0.55));
                this.activeMechanic = ActiveMechanic.locations(
                        MechanicType.GROUND_SLAM_DIVE,
                        ability,
                        List.of(slamTarget),
                        castSec
                );
                playWarningSound(viewers, "minecraft:entity.ender_dragon.growl", 0.6f);
                playWarningSound(viewers, "minecraft:block.bell.resonate", 0.9f);
            }
            case "LIGHTNING_STORM" -> {
                bossController.setPosture(DragonPosture.HIGH_HOVER);
                List<Location> strikes = generateLightningLocations(endWorld, aliveParticipants, ability);
                this.activeMechanic = ActiveMechanic.locations(MechanicType.LIGHTNING_STORM, ability, strikes, castSec);
                playWarningSound(viewers, "minecraft:item.trident.thunder", 1.4f);
            }
            case "SWEEPING_LASER" -> {
                bossController.setPosture(DragonPosture.HIGH_HOVER);
                Player first = selectFirstInArena(aliveParticipants);
                float startYaw = 0.0f;
                if (first != null && first.getLocation() != null) {
                    Location pLoc = first.getLocation();
                    startYaw = (float) Math.toDegrees(Math.atan2(-(pLoc.getX() - center.getX()), pLoc.getZ() - center.getZ())) - 60.0f;
                }
                bossController.setHoverAim(startYaw, 35.0f);
                ActiveMechanic mech = ActiveMechanic.laserSweep(ability, startYaw, 140.0f, castSec);
                this.activeMechanic = mech;
                playWarningSound(viewers, "minecraft:block.bell.resonate", 1.2f);
                playWarningSound(viewers, "minecraft:entity.illusioner.prepare_blindness", 1.3f);
            }
            case "SHIELD_WALL_BREATH" -> {
                bossController.setPosture(DragonPosture.CENTER_LANDED);
                this.activeMechanic = ActiveMechanic.simple(MechanicType.SHIELD_WALL_BREATH, ability, castSec);
                playWarningSound(viewers, "minecraft:entity.ender_dragon.growl", 0.65f);
                playWarningSound(viewers, "minecraft:block.beacon.power_select", 0.8f);
            }
            case "FIREBALL_BARRAGE" -> {
                bossController.setPosture(DragonPosture.HIGH_HOVER);
                this.activeMechanic = ActiveMechanic.simple(MechanicType.FIREBALL_BARRAGE, ability, castSec);
                playWarningSound(viewers, "minecraft:entity.ender_dragon.shoot", 0.9f);
            }
            case "ENDERMAN_ERUPTION" -> {
                String pattern = ability != null ? ability.getString("pattern", "CHASING") : "CHASING";
                ActiveMechanic mech = ActiveMechanic.eruption(ability, pattern.toUpperCase(Locale.ROOT), aliveParticipants, center, telegraphService, viewers, castSec);
                this.activeMechanic = mech;
                playWarningSound(viewers, "minecraft:entity.enderman.scream", 0.85f);
            }
            case "EXAFLARE_WAVES" -> {
                List<ExaflareLane> lanes = generateExaflareLanes(center, telegraphService, viewers);
                this.activeMechanic = ActiveMechanic.exaflare(ability, lanes, castSec);
                playWarningSound(viewers, "minecraft:entity.evoker.prepare_attack", 0.9f);
            }
            case "KNOCKBACK_COMBO", "KNOCKBACK_ROAR" -> {
                bossController.setPosture(DragonPosture.CENTER_LANDED);
                List<Integer> arrowIds = spawnKnockbackArrows(center, telegraphService, viewers);
                this.activeMechanic = ActiveMechanic.knockback(ability, center, arrowIds, castSec);
                playWarningSound(viewers, "minecraft:entity.warden.roar", 1.1f);
            }
            case "ENDERMAN_ROTATING_BEAM" -> {
                List<RotatingEndermanBeam> beams = spawnPlayerTetheredEndermen(aliveParticipants, center, telegraphService, viewers);
                this.activeMechanic = ActiveMechanic.rotatingEndermen(ability, beams, castSec);
                playWarningSound(viewers, "minecraft:entity.enderman.stare", 1.1f);
            }
            case "DUAL_DIVE_DICE" -> {
                ActiveMechanic mech = ActiveMechanic.dualDiveDice(ability, aliveParticipants, telegraphService, viewers, castSec);
                if (!mech.targetIds.isEmpty()) {
                    Player firstOdd = findPlayerById(aliveParticipants, mech.targetIds.getFirst());
                    if (firstOdd != null && firstOdd.getLocation() != null) {
                        bossController.beginDive(firstOdd.getLocation(), Math.max(0.8, castSec * 0.5));
                    }
                }
                this.activeMechanic = mech;
                playWarningSound(viewers, "minecraft:block.note_block.chime", 1.3f);
                playWarningSound(viewers, "minecraft:entity.ender_dragon.growl", 1.0f);
            }
            case "TETHER_DIVE_RELAY" -> {
                bossController.setPosture(DragonPosture.HIGH_HOVER);
                int totalHits = ability != null ? ability.getInt("relay-hits", 3) : 3;
                this.activeMechanic = ActiveMechanic.tetherRelay(ability, totalHits, castSec);
                playWarningSound(viewers, "minecraft:block.respawn_anchor.charge", 1.2f);
            }
            case "SPREAD" -> {
                int count = Math.min(aliveParticipants.size(), Math.max(1, scaled.spreadTargets()));
                List<UUID> targets = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    targets.add(aliveParticipants.get(i).getUniqueId());
                }
                if (targets.isEmpty()) {
                    this.activeMechanic = null;
                    return;
                }
                this.activeMechanic = ActiveMechanic.spread(ability, targets, castSec);
                playWarningSound(viewers, "minecraft:block.note_block.bass", 1.2f);
            }
            case "METEOR_TOWERS", "TOWERS_AND_SPREAD" -> {
                bossController.setPosture(DragonPosture.HIGH_HOVER);
                List<Location> towers = generateTowerLocations(endWorld, scaled.towerCount());
                this.activeMechanic = ActiveMechanic.locations(MechanicType.METEOR_TOWERS, ability, towers, castSec);
                playWarningSound(viewers, "minecraft:block.beacon.activate", 1.1f);
            }
            case "LOS_BREATH" -> {
                bossController.setPosture(DragonPosture.HIGH_HOVER);
                this.activeMechanic = ActiveMechanic.simple(MechanicType.LOS_BREATH, ability, castSec);
                playWarningSound(viewers, "minecraft:entity.ender_dragon.ambient", 0.8f);
            }
            default -> this.activeMechanic = null;
        }
    }

    /**
     * Renders spatial telegraphs, updates boss aura trail, and advances multi-wave follow-up
     * effects (shockwaves, Exaflare steps, chasing Enderman eruptions, fireball projectiles)
     * on each 2-tick monitor cycle.
     */
    public void tickActiveTelegraphs(
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            DragonBossController bossController) {
        Location bossLoc = bossController.bossLocation();
        if (bossLoc != null) {
            telegraphService.renderDragonAuraTrail(bossLoc, viewers);
        }

        int currentSessionGen = sessionGeneration.get();
        tickFollowUpEffects(aliveParticipants, viewers, currentSessionGen);

        ActiveMechanic mech = this.activeMechanic;
        if (mech == null) {
            return;
        }
        mech.elapsedSeconds += MONITOR_TICK_SECONDS;
        double progress = Math.max(0.0, Math.min(1.0, mech.elapsedSeconds / Math.max(0.1, mech.totalCastSeconds)));
        Map<UUID, Player> byId = indexPlayers(aliveParticipants);

        switch (mech.type) {
            case TANK_BUSTER -> {
                Player target = byId.get(mech.primaryTargetId);
                if (target != null && target.isOnline()) {
                    double radius = mech.ability != null ? mech.ability.getDouble("telegraph-radius", 3.5) : 3.5;
                    telegraphService.renderCircleAoE(target.getLocation(), radius, TelegraphStyle.DANGER, viewers);
                    if (bossLoc != null) {
                        telegraphService.renderLineAoE(bossLoc, target.getLocation(), 0.9, TelegraphStyle.DANGER, viewers);
                    }
                }
            }
            case DRAGON_DIVE_STACK -> {
                Player target = byId.get(mech.primaryTargetId);
                if (target != null && target.isOnline()) {
                    double radius = mech.ability != null ? mech.ability.radius(4.5) : 4.5;
                    telegraphService.renderStackMarker(target.getLocation(), radius, mech.soloMode, viewers);
                    if (bossLoc != null) {
                        telegraphService.renderLineAoE(bossLoc, target.getLocation(), 1.2, TelegraphStyle.STACK, viewers);
                    }
                }
            }
            case GROUND_SLAM_DIVE -> {
                if (!mech.locations.isEmpty()) {
                    Location impact = mech.locations.getFirst();
                    telegraphService.renderCircleAoE(impact, 6.0, TelegraphStyle.DANGER, viewers);
                    telegraphService.renderCircleAoE(impact, 12.0 + progress * 8.0, TelegraphStyle.WARNING, viewers);
                }
            }
            case LIGHTNING_STORM -> {
                double radius = mech.ability != null ? mech.ability.radius(3.5) : 3.5;
                for (Location loc : mech.locations) {
                    telegraphService.renderCircleAoE(loc, radius, TelegraphStyle.WARNING, viewers);
                }
            }
            case SWEEPING_LASER -> {
                if (bossLoc != null && bossLoc.getWorld() != null) {
                    float currentYaw = mech.laserStartYaw + (float) (mech.laserTotalSweepDegrees * progress);
                    bossController.setHoverAim(currentYaw, 35.0f);
                    Location beamEnd = computeLaserGroundImpact(bossLoc, currentYaw, 36.0);
                    if (progress < 0.30) {
                        telegraphService.renderLineAoE(bossLoc, beamEnd, 1.8, TelegraphStyle.WARNING, viewers);
                    } else {
                        telegraphService.renderLaserBeam(bossLoc, beamEnd, viewers);
                        double beamWidth = mech.ability != null ? mech.ability.getDouble("beam-width", 2.6) : 2.6;
                        double tickDamage = (mech.ability != null ? mech.ability.damage(24.0) : 24.0) * 0.20;
                        int expectedMechGen = mechanicGeneration.get();
                        for (Player p : aliveParticipants) {
                            if (p != null && p.isOnline() && p.getLocation() != null
                                    && bossLoc.getWorld().equals(p.getLocation().getWorld())
                                    && distanceToSegment2D(p.getLocation(), bossLoc, beamEnd) <= beamWidth) {
                                applyMechanicDamageOnEntityThread(p, tickDamage, expectedMechGen, currentSessionGen);
                            }
                        }
                    }
                }
            }
            case SHIELD_WALL_BREATH -> {
                Player shieldBlocker = findFrontShieldingPlayer(aliveParticipants, bossLoc);
                if (shieldBlocker != null && bossLoc != null) {
                    double dx = shieldBlocker.getLocation().getX() - bossLoc.getX();
                    double dz = shieldBlocker.getLocation().getZ() - bossLoc.getZ();
                    double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                    telegraphService.renderShieldSafeCone(shieldBlocker.getLocation(), yaw, 24.0, 32.0, viewers);
                }
                if (bossLoc != null) {
                    telegraphService.renderCircleAoE(bossLoc, 8.0 + progress * 26.0, TelegraphStyle.DANGER, viewers);
                }
            }
            case FIREBALL_BARRAGE -> {
                if (bossLoc != null && mech.elapsedSeconds - mech.lastWaveSpawnSeconds >= 0.45) {
                    mech.lastWaveSpawnSeconds = mech.elapsedSeconds;
                    spawnAcceleratingFireballWave(bossLoc, aliveParticipants, mech.ability, currentSessionGen);
                }
            }
            case ENDERMAN_ERUPTION -> {
                if ("CHASING".equals(mech.eruptionPattern) && mech.elapsedSeconds - mech.lastWaveSpawnSeconds >= 0.65) {
                    mech.lastWaveSpawnSeconds = mech.elapsedSeconds;
                    for (Player p : aliveParticipants) {
                        if (p != null && p.isOnline() && p.getLocation() != null) {
                            scheduleBambooEruption(p.getLocation().clone(), mech.ability, viewers, currentSessionGen, 0.9);
                        }
                    }
                }
                for (EruptionSite site : mech.eruptionSites) {
                    telegraphService.renderCircleAoE(site.location, site.radius, TelegraphStyle.DANGER, viewers);
                    double riseY = site.location.getY() - 2.4 + progress * 2.4;
                    Location risen = site.location.clone();
                    risen.setY(riseY);
                    telegraphService.moveAndRotateVirtualEnderman(site.virtualEntityId, risen, 0.0f, viewers);
                }
            }
            case EXAFLARE_WAVES -> {
                for (ExaflareLane lane : mech.exaflareLanes) {
                    telegraphService.renderCircleAoE(lane.origin, 3.8, TelegraphStyle.DANGER, viewers);
                }
            }
            case KNOCKBACK_COMBO -> {
                if (!mech.locations.isEmpty()) {
                    Location kbCenter = mech.locations.getFirst();
                    telegraphService.renderCircleAoE(kbCenter, 4.5 + progress * 10.0, TelegraphStyle.WARNING, viewers);
                }
            }
            case ENDERMAN_ROTATING_BEAM -> {
                for (RotatingEndermanBeam beam : mech.rotatingBeams) {
                    Player owner = byId.get(beam.ownerPlayerId);
                    if (progress < 0.75 && owner != null && owner.isOnline() && owner.getLocation() != null) {
                        double dx = owner.getLocation().getX() - beam.origin.getX();
                        double dz = owner.getLocation().getZ() - beam.origin.getZ();
                        beam.lockedYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        telegraphService.renderTetherLine(beam.origin, owner.getLocation(), TelegraphStyle.TETHER, viewers);
                    }
                    double riseY = beam.origin.getY() - 1.8 + Math.min(1.0, progress * 1.5) * 1.8;
                    Location eLoc = beam.origin.clone();
                    eLoc.setY(riseY);
                    telegraphService.moveAndRotateVirtualEnderman(beam.virtualEntityId, eLoc, beam.lockedYaw, viewers);
                    Location previewEnd = extrapolateYaw(beam.origin, beam.lockedYaw, 34.0);
                    telegraphService.renderLineAoE(beam.origin, previewEnd, 2.0, TelegraphStyle.WARNING, viewers);
                }
            }
            case DUAL_DIVE_DICE -> {
                double radius = mech.ability != null ? mech.ability.radius(4.5) : 4.5;
                for (int i = 0; i < Math.min(2, mech.targetIds.size()); i++) {
                    Player t = byId.get(mech.targetIds.get(i));
                    if (t != null && t.isOnline() && t.getLocation() != null) {
                        telegraphService.renderStackMarker(t.getLocation(), radius, false, viewers);
                    }
                }
                for (DiceAssignment d : mech.diceAssignments) {
                    Player p = byId.get(d.playerId);
                    if (p != null && p.isOnline() && p.getLocation() != null) {
                        telegraphService.moveAndRotateVirtualEnderman(
                                d.displayEntityId,
                                p.getLocation().clone().add(0.0, 2.6, 0.0),
                                0.0f,
                                viewers
                        );
                    }
                }
            }
            case TETHER_DIVE_RELAY -> {
                if (bossLoc != null && !aliveParticipants.isEmpty()) {
                    Player nearest = selectNearestToBoss(aliveParticipants, bossLoc);
                    if (nearest != null && nearest.getLocation() != null) {
                        boolean vulnerable = mech.vulnerablePlayerIds.contains(nearest.getUniqueId());
                        TelegraphStyle style = vulnerable ? TelegraphStyle.DANGER : TelegraphStyle.SAFE;
                        telegraphService.renderTetherLine(bossLoc, nearest.getLocation(), style, viewers);
                        telegraphService.renderCircleAoE(nearest.getLocation(), 3.2, style, viewers);
                    }
                    double stepInterval = mech.totalCastSeconds / Math.max(1, mech.relayTotalHits);
                    if (mech.relayCompletedHits < mech.relayTotalHits - 1
                            && mech.elapsedSeconds >= (mech.relayCompletedHits + 1) * stepInterval) {
                        mech.relayCompletedHits++;
                        resolveSingleRelayDiveHit(mech, aliveParticipants, bossController, mechanicGeneration.get(), currentSessionGen);
                    }
                }
            }
            case SPREAD -> {
                double radius = mech.ability != null ? mech.ability.radius(4.5) : 4.5;
                for (UUID targetId : mech.targetIds) {
                    Player target = byId.get(targetId);
                    if (target != null && target.isOnline()) {
                        telegraphService.renderSpreadMarker(target.getLocation(), radius, viewers);
                    }
                }
            }
            case METEOR_TOWERS -> {
                double towerRadius = mech.ability != null ? mech.ability.getDouble("tower-radius", 3.0) : 3.0;
                double radiusSq = towerRadius * towerRadius;
                for (Location towerLoc : mech.locations) {
                    boolean soaked = hasAnyPlayerInside(towerLoc, radiusSq, aliveParticipants);
                    telegraphService.renderCircleAoE(
                            towerLoc,
                            towerRadius,
                            soaked ? TelegraphStyle.SAFE : TelegraphStyle.DANGER,
                            viewers
                    );
                }
            }
            case LOS_BREATH -> {
                if (bossLoc != null) {
                    for (Player p : aliveParticipants) {
                        if (p.isOnline() && bossLoc.getWorld() != null && bossLoc.getWorld().equals(p.getWorld())) {
                            telegraphService.renderLineAoE(
                                    bossLoc,
                                    p.getLocation().clone().add(0.0, 1.0, 0.0),
                                    0.8,
                                    TelegraphStyle.WARNING,
                                    viewers
                            );
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves damage, knockbacks, and follow-up waves when an ability cast completes.
     */
    public void completeCast(
            BossConfig.TimelineStep step,
            @Nullable BossConfig.AbilitySettings ability,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            ScaledEncounterParameters scaled,
            DragonBossController bossController,
            RaidTimelineEngine timelineEngine) {
        ActiveMechanic mech = this.activeMechanic;
        this.activeMechanic = null;
        if (mech == null) {
            return;
        }
        int expectedMechGen = mechanicGeneration.get();
        int expectedSessionGen = sessionGeneration.get();
        Map<UUID, Player> byId = indexPlayers(aliveParticipants);
        BossConfig.AbilitySettings effAbility = mech.ability != null ? mech.ability : ability;

        switch (mech.type) {
            case TANK_BUSTER -> resolveTankBuster(
                    effAbility,
                    byId.get(mech.primaryTargetId),
                    bossController,
                    timelineEngine,
                    expectedMechGen,
                    expectedSessionGen
            );
            case DRAGON_DIVE_STACK -> resolveStack(
                    effAbility,
                    byId.get(mech.primaryTargetId),
                    aliveParticipants,
                    viewers,
                    scaled,
                    mech.soloMode,
                    expectedMechGen,
                    expectedSessionGen
            );
            case GROUND_SLAM_DIVE -> resolveGroundSlamDive(
                    effAbility,
                    mech.locations.isEmpty() ? bossController.bossLocation() : mech.locations.getFirst(),
                    aliveParticipants,
                    viewers,
                    expectedMechGen,
                    expectedSessionGen
            );
            case LIGHTNING_STORM -> resolveLightningStorm(
                    effAbility,
                    mech.locations,
                    aliveParticipants,
                    viewers,
                    expectedMechGen,
                    expectedSessionGen
            );
            case SWEEPING_LASER -> playWarningSound(viewers, "minecraft:entity.generic.explode", 1.1f);
            case SHIELD_WALL_BREATH -> resolveShieldWallBreath(
                    effAbility,
                    bossController.bossLocation(),
                    aliveParticipants,
                    viewers,
                    expectedMechGen,
                    expectedSessionGen
            );
            case FIREBALL_BARRAGE -> {
                Location bossLoc = bossController.bossLocation();
                if (bossLoc != null) {
                    spawnAcceleratingFireballWave(bossLoc, aliveParticipants, effAbility, expectedSessionGen);
                }
            }
            case ENDERMAN_ERUPTION -> resolveEndermanEruption(
                    effAbility,
                    mech,
                    aliveParticipants,
                    viewers,
                    expectedMechGen,
                    expectedSessionGen
            );
            case EXAFLARE_WAVES -> startExaflareMarchingWaves(
                    effAbility,
                    mech.exaflareLanes,
                    viewers,
                    expectedSessionGen
            );
            case KNOCKBACK_COMBO -> resolveKnockbackCombo(
                    effAbility,
                    mech,
                    aliveParticipants,
                    viewers,
                    expectedMechGen,
                    expectedSessionGen
            );
            case ENDERMAN_ROTATING_BEAM -> startRotatingEndermanSweep(
                    effAbility,
                    mech.rotatingBeams,
                    viewers,
                    expectedSessionGen
            );
            case DUAL_DIVE_DICE -> resolveDualDiveDice(
                    effAbility,
                    mech,
                    aliveParticipants,
                    byId,
                    viewers,
                    scaled,
                    expectedMechGen,
                    expectedSessionGen
            );
            case TETHER_DIVE_RELAY -> resolveSingleRelayDiveHit(
                    mech,
                    aliveParticipants,
                    bossController,
                    expectedMechGen,
                    expectedSessionGen
            );
            case SPREAD -> resolveSpread(
                    effAbility,
                    mech.targetIds,
                    aliveParticipants,
                    byId,
                    expectedMechGen,
                    expectedSessionGen
            );
            case METEOR_TOWERS -> resolveMeteorTowers(
                    effAbility,
                    mech.locations,
                    aliveParticipants,
                    expectedMechGen,
                    expectedSessionGen
            );
            case LOS_BREATH -> resolveLosBreath(
                    effAbility,
                    bossController.bossLocation(),
                    aliveParticipants,
                    expectedMechGen,
                    expectedSessionGen
            );
        }

        // Destroy any temporary cast virtual entities that are not handed off to follow-up effects
        if (mech.type != MechanicType.ENDERMAN_ROTATING_BEAM && mech.type != MechanicType.EXAFLARE_WAVES) {
            destroyMechanicVirtualEntities(mech, viewers);
        }

        if (!bossController.isStaggered()) {
            BossConfig.PhaseConfig currentPhase = timelineEngine.currentPhase();
            bossController.setPosture(defaultPostureForPhase(currentPhase));
        }
    }

    /**
     * Cancels any in-progress mechanic (e.g. on phase change, stagger, or wipe).
     */
    public void cancelActiveMechanic() {
        this.mechanicGeneration.incrementAndGet();
        this.activeMechanic = null;
    }

    /**
     * Advances Soft Enrage arena shrinkage, draws the boundary ring, and applies void corruption
     * damage to participants outside the safe radius.
     */
    public void tickSoftEnrage(
            double deltaSeconds,
            BossConfig bossConfig,
            @Nullable BossConfig.PhaseConfig currentPhase,
            World endWorld,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers) {
        BossConfig.SoftEnrageSettings soft = bossConfig.enrage().softEnrage();
        if (!soft.enabled() || currentPhase == null || !currentPhase.softEnrage()) {
            return;
        }
        this.softEnrageStarted = true;
        Location centerLoc = arenaCenter(endWorld);
        this.safeArenaRadius = Math.max(
                MIN_SOFT_ENRAGE_RADIUS,
                this.safeArenaRadius - soft.voidCorruptionShrinkPerSecond() * deltaSeconds
        );
        telegraphService.renderArenaBoundary(centerLoc, this.safeArenaRadius, viewers);

        this.softEnrageAccumulatorSeconds += deltaSeconds;
        if (this.softEnrageAccumulatorSeconds + 1e-6 >= 1.0) {
            this.softEnrageAccumulatorSeconds = Math.max(0.0, this.softEnrageAccumulatorSeconds - 1.0);
            double damagePerSec = soft.voidDamagePerSecond();
            double safeRadiusSq = this.safeArenaRadius * this.safeArenaRadius;
            int expectedSessionGen = sessionGeneration.get();
            for (Player player : aliveParticipants) {
                if (player == null || !player.isOnline()) {
                    continue;
                }
                Location loc = player.getLocation();
                if (loc.getWorld() == null || !loc.getWorld().equals(endWorld)) {
                    continue;
                }
                double dx = loc.getX() - centerLoc.getX();
                double dz = loc.getZ() - centerLoc.getZ();
                if (dx * dx + dz * dz > safeRadiusSq && damagePerSec > 0.0) {
                    applySessionDamageOnEntityThread(player, damagePerSec, expectedSessionGen);
                }
            }
        }
    }

    /**
     * Returns localized ActionBar extra status component for {@code player} if targeted by a personal marker.
     */
    public @Nullable Component extraStatusFor(Player player) {
        ActiveMechanic mech = this.activeMechanic;
        if (mech == null) {
            return null;
        }
        UUID id = player.getUniqueId();
        return switch (mech.type) {
            case TANK_BUSTER -> id.equals(mech.primaryTargetId)
                    ? messageService.render(player, "ability.tank_buster.name")
                    : null;
            case DRAGON_DIVE_STACK -> id.equals(mech.primaryTargetId)
                    ? messageService.render(player, "ability.stack.name")
                    : null;
            case SPREAD -> mech.targetIds.contains(id)
                    ? messageService.render(player, "ability.spread.name")
                    : null;
            case METEOR_TOWERS -> messageService.render(player, "ability.meteor_towers.name");
            case LOS_BREATH -> messageService.render(player, "ability.los_breath.name");
            default -> null;
        };
    }

    public double safeArenaRadius() {
        return safeArenaRadius;
    }

    public boolean isSoftEnrageStarted() {
        return softEnrageStarted;
    }

    /**
     * Maps a phase configuration to its default DragonPosture.
     */
    public static DragonPosture defaultPostureForPhase(@Nullable BossConfig.PhaseConfig phase) {
        if (phase == null) {
            return DragonPosture.LOW_ORBIT;
        }
        return switch (phase.id()) {
            case "phase-2" -> DragonPosture.HIGH_HOVER;
            default -> DragonPosture.LOW_ORBIT;
        };
    }

    // =========================================================================
    // Resolution & Follow-up Effect Implementations for the 12 Gimmicks
    // =========================================================================

    private void resolveTankBuster(
            @Nullable BossConfig.AbilitySettings ability,
            @Nullable Player target,
            DragonBossController bossController,
            RaidTimelineEngine timelineEngine,
            int expectedMechGen,
            int expectedSessionGen) {
        if (target == null || !target.isOnline()) {
            return;
        }
        double baseDamage = ability != null ? ability.damage(24.0) : 24.0;
        double shieldMult = ability != null ? ability.getDouble("shield-damage-multiplier", 0.25) : 0.25;
        int justGuardTicks = ability != null ? ability.getInt("just-guard-window-ticks", 10) : 10;
        double justGuardMult = ability != null ? ability.getDouble("just-guard-damage-multiplier", 0.10) : 0.10;
        double staggerSeconds = ability != null ? ability.getDouble("stagger-seconds", 3.0) : 3.0;

        boolean blocking = target.isBlocking();
        Long raisedAt = shieldRaiseMillis.get(target.getUniqueId());
        long windowMs = Math.max(50L, justGuardTicks * 50L);
        long now = clockMillis.getAsLong();
        boolean justGuard = blocking && raisedAt != null && (now - raisedAt) <= windowMs;

        if (justGuard) {
            double finalDamage = baseDamage * justGuardMult;
            applyMechanicDamageOnEntityThread(target, finalDamage, expectedMechGen, expectedSessionGen);
            bossController.stagger(staggerSeconds);
            timelineEngine.stagger(staggerSeconds);
            scheduler.runAtEntity(target, () -> {
                if (mechanicGeneration.get() == expectedMechGen && sessionGeneration.get() == expectedSessionGen) {
                    target.sendActionBar(messageService.render(target, "actionbar.just-guard-success"));
                    target.playSound(Sound.sound(
                            Key.key("minecraft:block.anvil.land"),
                            Sound.Source.PLAYER,
                            0.8f,
                            1.4f
                    ));
                }
            });
        } else if (blocking) {
            double finalDamage = baseDamage * shieldMult;
            applyMechanicDamageOnEntityThread(target, finalDamage, expectedMechGen, expectedSessionGen);
            scheduler.runAtEntity(target, () -> {
                if (mechanicGeneration.get() == expectedMechGen && sessionGeneration.get() == expectedSessionGen) {
                    target.playSound(Sound.sound(
                            Key.key("minecraft:item.shield.block"),
                            Sound.Source.PLAYER,
                            0.9f,
                            1.0f
                    ));
                }
            });
        } else {
            applyMechanicDamageOnEntityThread(target, baseDamage, expectedMechGen, expectedSessionGen);
        }
    }

    private void resolveStack(
            @Nullable BossConfig.AbilitySettings ability,
            @Nullable Player target,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            ScaledEncounterParameters scaled,
            boolean soloMode,
            int expectedMechGen,
            int expectedSessionGen) {
        if (target == null || !target.isOnline()) {
            return;
        }
        double totalDamage = ability != null ? ability.getDouble("total-damage", 32.0) : 32.0;
        double radius = ability != null ? ability.radius(4.5) : 4.5;
        playWarningSound(viewers, "minecraft:entity.warden.sonic_boom", 1.25f);
        playWarningSound(viewers, "minecraft:entity.generic.explode", 0.9f);

        if (soloMode) {
            double soloDamage = totalDamage * scaled.soloStackDamageMultiplier();
            if (target.isBlocking()) {
                soloDamage *= 0.5;
            }
            applyMechanicDamageOnEntityThread(target, soloDamage, expectedMechGen, expectedSessionGen);
            return;
        }

        Location anchor = target.getLocation();
        double radiusSq = radius * radius;
        List<Player> soakers = new ArrayList<>();
        for (Player p : aliveParticipants) {
            if (p != null && p.isOnline()
                    && anchor.getWorld() != null
                    && anchor.getWorld().equals(p.getLocation().getWorld())
                    && p.getLocation().distanceSquared(anchor) <= radiusSq) {
                soakers.add(p);
            }
        }
        if (soakers.isEmpty()) {
            soakers.add(target);
        }
        double splitDamage = totalDamage / soakers.size();
        for (Player soaker : soakers) {
            applyMechanicDamageOnEntityThread(soaker, splitDamage, expectedMechGen, expectedSessionGen);
        }
    }

    private void resolveGroundSlamDive(
            @Nullable BossConfig.AbilitySettings ability,
            @Nullable Location impactPoint,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            int expectedMechGen,
            int expectedSessionGen) {
        if (impactPoint == null || impactPoint.getWorld() == null) {
            return;
        }
        double maxDamage = ability != null ? ability.damage(36.0) : 36.0;
        double minDamage = ability != null ? ability.getDouble("min-damage", 4.0) : 4.0;
        double falloffRadius = ability != null ? ability.getDouble("falloff-radius", 28.0) : 28.0;

        playWarningSound(viewers, "minecraft:entity.warden.sonic_boom", 1.3f);
        playWarningSound(viewers, "minecraft:entity.generic.explode", 0.8f);

        for (Player p : aliveParticipants) {
            if (p == null || !p.isOnline() || p.getLocation() == null
                    || !impactPoint.getWorld().equals(p.getLocation().getWorld())) {
                continue;
            }
            double dist = Math.sqrt(horizontalDistSq(p.getLocation(), impactPoint));
            double ratio = Math.max(0.0, 1.0 - (dist / Math.max(1.0, falloffRadius)));
            double dmg = Math.max(minDamage, maxDamage * ratio);
            if (p.isBlocking()) {
                dmg *= 0.65;
            }
            applyMechanicDamageOnEntityThread(p, dmg, expectedMechGen, expectedSessionGen);
        }

        // Spawn True Ending-style expanding 360-degree shockwave ring
        synchronized (followUpEffects) {
            followUpEffects.add(ActiveFollowUpEffect.shockwave(impactPoint.clone(), 38.0, 16.0));
        }
    }

    private void resolveLightningStorm(
            @Nullable BossConfig.AbilitySettings ability,
            List<Location> strikes,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            int expectedMechGen,
            int expectedSessionGen) {
        double radius = ability != null ? ability.radius(3.5) : 3.5;
        double damage = ability != null ? ability.damage(18.0) : 18.0;
        double rSq = radius * radius;
        playWarningSound(viewers, "minecraft:item.trident.thunder", 1.0f);

        for (Location strike : strikes) {
            telegraphService.renderLightningStrike(strike, viewers);
            for (Player p : aliveParticipants) {
                if (p != null && p.isOnline() && p.getLocation() != null
                        && strike.getWorld() != null
                        && strike.getWorld().equals(p.getLocation().getWorld())
                        && horizontalDistSq(p.getLocation(), strike) <= rSq) {
                    applyMechanicDamageOnEntityThread(p, damage, expectedMechGen, expectedSessionGen);
                }
            }
        }
    }

    private void resolveShieldWallBreath(
            @Nullable BossConfig.AbilitySettings ability,
            @Nullable Location bossLocation,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            int expectedMechGen,
            int expectedSessionGen) {
        if (bossLocation == null || bossLocation.getWorld() == null) {
            return;
        }
        double fullDamage = ability != null ? ability.damage(34.0) : 34.0;
        double guardChipMultiplier = ability != null ? ability.getDouble("shield-chip-multiplier", 0.20) : 0.20;
        double coneHalfAngleDeg = ability != null ? ability.getDouble("safe-cone-half-angle", 32.0) : 32.0;

        Player blocker = findFrontShieldingPlayer(aliveParticipants, bossLocation);
        playWarningSound(viewers, "minecraft:entity.ender_dragon.growl", 0.8f);

        for (Player p : aliveParticipants) {
            if (p == null || !p.isOnline() || p.getLocation() == null
                    || !bossLocation.getWorld().equals(p.getLocation().getWorld())) {
                continue;
            }
            if (blocker != null && p.getUniqueId().equals(blocker.getUniqueId())) {
                applyMechanicDamageOnEntityThread(p, fullDamage * guardChipMultiplier, expectedMechGen, expectedSessionGen);
                scheduler.runAtEntity(p, () -> p.playSound(Sound.sound(
                        Key.key("minecraft:item.shield.block"),
                        Sound.Source.PLAYER,
                        1.0f,
                        0.9f
                )));
            } else if (blocker != null && isInsideRearSafeCone(bossLocation, blocker.getLocation(), p.getLocation(), coneHalfAngleDeg)) {
                // Protected inside the shielding ally's rear cone!
                scheduler.runAtEntity(p, () -> p.playSound(Sound.sound(
                        Key.key("minecraft:block.beacon.ambient"),
                        Sound.Source.PLAYER,
                        0.8f,
                        1.4f
                )));
            } else if (p.isBlocking()) {
                applyMechanicDamageOnEntityThread(p, fullDamage * 0.45, expectedMechGen, expectedSessionGen);
            } else {
                applyMechanicDamageOnEntityThread(p, fullDamage, expectedMechGen, expectedSessionGen);
            }
        }
    }

    private void resolveEndermanEruption(
            @Nullable BossConfig.AbilitySettings ability,
            ActiveMechanic mech,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            int expectedMechGen,
            int expectedSessionGen) {
        double damage = ability != null ? ability.damage(20.0) : 20.0;
        playWarningSound(viewers, "minecraft:entity.enderman.teleport", 0.7f);
        for (EruptionSite site : mech.eruptionSites) {
            double rSq = site.radius * site.radius;
            for (Player p : aliveParticipants) {
                if (p != null && p.isOnline() && p.getLocation() != null
                        && site.location.getWorld() != null
                        && site.location.getWorld().equals(p.getLocation().getWorld())
                        && horizontalDistSq(p.getLocation(), site.location) <= rSq) {
                    applyMechanicDamageOnEntityThread(p, damage, expectedMechGen, expectedSessionGen);
                }
            }
        }
        if ("RING_CASCADE".equals(mech.eruptionPattern) && !mech.eruptionSites.isEmpty()) {
            Location center = mech.eruptionSites.getFirst().location;
            synchronized (followUpEffects) {
                followUpEffects.add(ActiveFollowUpEffect.ringEruptionCascade(center.clone(), 12.0, 24.0, damage));
            }
        }
    }

    private void startExaflareMarchingWaves(
            @Nullable BossConfig.AbilitySettings ability,
            List<ExaflareLane> lanes,
            Collection<? extends Player> viewers,
            int expectedSessionGen) {
        double damage = ability != null ? ability.damage(22.0) : 22.0;
        List<Integer> arrowIds = new ArrayList<>(lanes.size());
        for (ExaflareLane lane : lanes) {
            if (lane.arrowEntityId >= 0) {
                arrowIds.add(lane.arrowEntityId);
            }
        }
        if (!arrowIds.isEmpty()) {
            telegraphService.destroyVirtualEntities(viewers, arrowIds.stream().mapToInt(Integer::intValue).toArray());
        }
        synchronized (followUpEffects) {
            followUpEffects.add(ActiveFollowUpEffect.exaflareWaves(lanes, damage));
        }
    }

    private void resolveKnockbackCombo(
            @Nullable BossConfig.AbilitySettings ability,
            ActiveMechanic mech,
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            int expectedMechGen,
            int expectedSessionGen) {
        if (mech.locations.isEmpty()) {
            return;
        }
        Location origin = mech.locations.getFirst();
        if (origin.getWorld() == null) {
            return;
        }
        double horizImpulse = ability != null ? ability.getDouble("horizontal-impulse", 2.1) : 2.1;
        double vertImpulse = ability != null ? ability.getDouble("vertical-impulse", 0.55) : 0.55;
        double damage = ability != null ? ability.damage(10.0) : 10.0;

        playWarningSound(viewers, "minecraft:entity.warden.sonic_boom", 0.9f);
        for (Player p : aliveParticipants) {
            if (p == null || !p.isOnline() || p.getLocation() == null
                    || !origin.getWorld().equals(p.getLocation().getWorld())) {
                continue;
            }
            double dx = p.getLocation().getX() - origin.getX();
            double dz = p.getLocation().getZ() - origin.getZ();
            double len = Math.hypot(dx, dz);
            double nx = len > 1e-4 ? dx / len : 1.0;
            double nz = len > 1e-4 ? dz / len : 0.0;
            double mult = p.isBlocking() ? 0.45 : 1.0;
            Vector kb = new Vector(nx * horizImpulse * mult, vertImpulse * mult, nz * horizImpulse * mult);
            scheduler.runAtEntity(p, () -> {
                if (p.isOnline() && mechanicGeneration.get() == expectedMechGen && sessionGeneration.get() == expectedSessionGen) {
                    p.setVelocity(kb);
                    damageApplier.accept(p, damage * mult);
                }
            });
        }
        // Follow-up center bamboo eruption 1.4s after knockback
        scheduleBambooEruption(origin.clone(), ability, viewers, expectedSessionGen, 1.4);
    }

    private void startRotatingEndermanSweep(
            @Nullable BossConfig.AbilitySettings ability,
            List<RotatingEndermanBeam> beams,
            Collection<? extends Player> viewers,
            int expectedSessionGen) {
        double damage = ability != null ? ability.damage(24.0) : 24.0;
        double sweepDegrees = ability != null ? ability.getDouble("sweep-degrees", 110.0) : 110.0;
        playWarningSound(viewers, "minecraft:entity.evoker.prepare_summon", 0.7f);
        synchronized (followUpEffects) {
            followUpEffects.add(ActiveFollowUpEffect.rotatingEndermen(beams, (float) sweepDegrees, damage));
        }
    }

    private void resolveDualDiveDice(
            @Nullable BossConfig.AbilitySettings ability,
            ActiveMechanic mech,
            List<Player> aliveParticipants,
            Map<UUID, Player> byId,
            Collection<? extends Player> viewers,
            ScaledEncounterParameters scaled,
            int expectedMechGen,
            int expectedSessionGen) {
        double totalDamage = ability != null ? ability.getDouble("total-damage", 40.0) : 40.0;
        double radius = ability != null ? ability.radius(4.5) : 4.5;
        playWarningSound(viewers, "minecraft:entity.warden.sonic_boom", 1.3f);

        int diveCount = Math.min(2, mech.targetIds.size());
        for (int i = 0; i < diveCount; i++) {
            Player anchorPlayer = byId.get(mech.targetIds.get(i));
            if (anchorPlayer != null && anchorPlayer.isOnline()) {
                resolveStack(
                        ability,
                        anchorPlayer,
                        aliveParticipants,
                        viewers,
                        scaled,
                        aliveParticipants.size() <= 1,
                        expectedMechGen,
                        expectedSessionGen
                );
            }
        }
    }

    private void resolveSingleRelayDiveHit(
            ActiveMechanic mech,
            List<Player> aliveParticipants,
            DragonBossController bossController,
            int expectedMechGen,
            int expectedSessionGen) {
        Location bossLoc = bossController.bossLocation();
        Player nearest = selectNearestToBoss(aliveParticipants, bossLoc);
        if (nearest == null || !nearest.isOnline()) {
            return;
        }
        double baseDamage = mech.ability != null ? mech.ability.damage(18.0) : 18.0;
        double vulnMult = mech.ability != null ? mech.ability.getDouble("vulnerability-multiplier", 3.0) : 3.0;
        boolean alreadyVulnerable = !mech.vulnerablePlayerIds.add(nearest.getUniqueId());
        double finalDamage = alreadyVulnerable ? baseDamage * vulnMult : baseDamage;
        if (nearest.isBlocking()) {
            finalDamage *= 0.5;
        }
        if (nearest.getLocation() != null) {
            bossController.beginDive(nearest.getLocation(), 0.25);
        }
        applyMechanicDamageOnEntityThread(nearest, finalDamage, expectedMechGen, expectedSessionGen);
    }

    private void tickFollowUpEffects(
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            int currentSessionGen) {
        List<ActiveFollowUpEffect> snapshot;
        synchronized (followUpEffects) {
            if (followUpEffects.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(followUpEffects);
        }
        List<ActiveFollowUpEffect> finished = new ArrayList<>();
        for (ActiveFollowUpEffect effect : snapshot) {
            effect.elapsedSeconds += MONITOR_TICK_SECONDS;
            switch (effect.kind) {
                case SHOCKWAVE -> {
                    effect.currentRadius += effect.expandSpeedPerSecond * MONITOR_TICK_SECONDS;
                    telegraphService.renderExpandingShockwave(effect.origin, effect.currentRadius, viewers);
                    for (Player p : aliveParticipants) {
                        if (p == null || !p.isOnline() || p.getLocation() == null
                                || effect.origin.getWorld() == null
                                || !effect.origin.getWorld().equals(p.getLocation().getWorld())
                                || effect.hitPlayerIds.contains(p.getUniqueId())) {
                            continue;
                        }
                        double dist = Math.sqrt(horizontalDistSq(p.getLocation(), effect.origin));
                        double yDiff = p.getLocation().getY() - effect.origin.getY();
                        // Jumping > 1.1m over the ground ring dodges the shockwave!
                        if (Math.abs(dist - effect.currentRadius) <= 1.35 && yDiff <= 1.1) {
                            effect.hitPlayerIds.add(p.getUniqueId());
                            applySessionDamageOnEntityThread(p, 16.0, currentSessionGen);
                        }
                    }
                    if (effect.currentRadius >= effect.maxRadius) {
                        finished.add(effect);
                    }
                }
                case BAMBOO_ERUPTION -> {
                    double progress = Math.min(1.0, effect.elapsedSeconds / Math.max(0.1, effect.durationSeconds));
                    telegraphService.renderCircleAoE(effect.origin, 3.2, TelegraphStyle.DANGER, viewers);
                    Location eLoc = effect.origin.clone();
                    eLoc.setY(effect.origin.getY() - 2.4 + progress * 2.4);
                    telegraphService.moveAndRotateVirtualEnderman(effect.virtualEntityId, eLoc, 0.0f, viewers);
                    if (effect.elapsedSeconds >= effect.durationSeconds) {
                        double rSq = 3.2 * 3.2;
                        for (Player p : aliveParticipants) {
                            if (p != null && p.isOnline() && p.getLocation() != null
                                    && effect.origin.getWorld() != null
                                    && effect.origin.getWorld().equals(p.getLocation().getWorld())
                                    && horizontalDistSq(p.getLocation(), effect.origin) <= rSq) {
                                applySessionDamageOnEntityThread(p, effect.damage, currentSessionGen);
                            }
                        }
                        telegraphService.destroyVirtualEntities(viewers, effect.virtualEntityId);
                        finished.add(effect);
                    }
                }
                case RING_CASCADE -> {
                    double ringR = effect.elapsedSeconds < 0.9 ? effect.innerRadius : effect.maxRadius;
                    telegraphService.renderDonutAoE(effect.origin, Math.max(0.0, ringR - 4.0), ringR + 2.0, TelegraphStyle.DANGER, viewers);
                    if (effect.elapsedSeconds >= 1.6) {
                        finished.add(effect);
                    }
                }
                case EXAFLARE -> {
                    if (effect.elapsedSeconds - effect.lastStepSeconds >= 0.70) {
                        effect.lastStepSeconds = effect.elapsedSeconds;
                        effect.stepIndex++;
                        for (ExaflareLane lane : effect.lanes) {
                            double rad = Math.toRadians(lane.yawDegrees);
                            double dist = effect.stepIndex * 5.5;
                            Location burst = new Location(
                                    lane.origin.getWorld(),
                                    lane.origin.getX() - Math.sin(rad) * dist,
                                    lane.origin.getY(),
                                    lane.origin.getZ() + Math.cos(rad) * dist
                            );
                            telegraphService.renderCircleAoE(burst, 3.6, TelegraphStyle.DANGER, viewers);
                            int eid = telegraphService.spawnVirtualScaledEnderman(burst, 2.2, lane.yawDegrees, true, viewers);
                            scheduler.runGlobalLater(() -> telegraphService.destroyVirtualEntities(viewers, eid), 10L);
                            double rSq = 3.6 * 3.6;
                            for (Player p : aliveParticipants) {
                                if (p != null && p.isOnline() && p.getLocation() != null
                                        && burst.getWorld() != null
                                        && burst.getWorld().equals(p.getLocation().getWorld())
                                        && horizontalDistSq(p.getLocation(), burst) <= rSq) {
                                    applySessionDamageOnEntityThread(p, effect.damage, currentSessionGen);
                                }
                            }
                        }
                    }
                    if (effect.stepIndex >= 8) {
                        finished.add(effect);
                    }
                }
                case ROTATING_ENDERMEN -> {
                    double ratio = Math.min(1.0, effect.elapsedSeconds / Math.max(0.2, effect.durationSeconds));
                    for (RotatingEndermanBeam beam : effect.rotatingBeams) {
                        float yaw = beam.lockedYaw + (float) (effect.sweepDegrees * ratio * (beam.clockwise ? 1.0 : -1.0));
                        telegraphService.moveAndRotateVirtualEnderman(beam.virtualEntityId, beam.origin, yaw, viewers);
                        Location end = extrapolateYaw(beam.origin, yaw, 34.0);
                        telegraphService.renderLaserBeam(beam.origin, end, viewers);
                        for (Player p : aliveParticipants) {
                            if (p != null && p.isOnline() && p.getLocation() != null
                                    && beam.origin.getWorld() != null
                                    && beam.origin.getWorld().equals(p.getLocation().getWorld())
                                    && distanceToSegment2D(p.getLocation(), beam.origin, end) <= 1.8) {
                                applySessionDamageOnEntityThread(p, effect.damage * 0.25, currentSessionGen);
                            }
                        }
                    }
                    if (effect.elapsedSeconds >= effect.durationSeconds) {
                        int[] ids = effect.rotatingBeams.stream().mapToInt(b -> b.virtualEntityId).toArray();
                        telegraphService.destroyVirtualEntities(viewers, ids);
                        finished.add(effect);
                    }
                }
                case FIREBALL_ORB -> {
                    // True Ending accelerating projectile: 0.25 -> 0.5 -> 0.9 -> 1.3 blocks/tick
                    double speedPerSec = effect.elapsedSeconds < 0.5 ? 5.0 : (effect.elapsedSeconds < 1.0 ? 12.0 : 22.0);
                    effect.origin.add(
                            effect.direction.getX() * speedPerSec * MONITOR_TICK_SECONDS,
                            effect.direction.getY() * speedPerSec * MONITOR_TICK_SECONDS,
                            effect.direction.getZ() * speedPerSec * MONITOR_TICK_SECONDS
                    );
                    telegraphService.renderDragonAuraTrail(effect.origin, viewers);
                    boolean hit = effect.origin.getY() <= CircularArenaBuilder.DEFAULT_FLOOR_Y + 0.6;
                    for (Player p : aliveParticipants) {
                        if (p != null && p.isOnline() && p.getLocation() != null
                                && effect.origin.getWorld() != null
                                && effect.origin.getWorld().equals(p.getLocation().getWorld())
                                && p.getLocation().distanceSquared(effect.origin) <= 2.6 * 2.6) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit || effect.elapsedSeconds >= 4.0) {
                        telegraphService.renderCircleAoE(effect.origin, 3.5, TelegraphStyle.DANGER, viewers);
                        for (Player p : aliveParticipants) {
                            if (p != null && p.isOnline() && p.getLocation() != null
                                    && effect.origin.getWorld() != null
                                    && effect.origin.getWorld().equals(p.getLocation().getWorld())
                                    && horizontalDistSq(p.getLocation(), effect.origin) <= 3.5 * 3.5) {
                                applySessionDamageOnEntityThread(p, effect.damage, currentSessionGen);
                            }
                        }
                        finished.add(effect);
                    }
                }
            }
        }
        if (!finished.isEmpty()) {
            synchronized (followUpEffects) {
                followUpEffects.removeAll(finished);
            }
        }
    }

    private void spawnAcceleratingFireballWave(
            Location bossLoc,
            List<Player> aliveParticipants,
            @Nullable BossConfig.AbilitySettings ability,
            int currentSessionGen) {
        if (bossLoc.getWorld() == null || aliveParticipants.isEmpty()) {
            return;
        }
        double damage = ability != null ? ability.damage(16.0) : 16.0;
        Player target = aliveParticipants.get(ThreadLocalRandom.current().nextInt(aliveParticipants.size()));
        Location tLoc = target.getLocation();
        if (tLoc == null || !bossLoc.getWorld().equals(tLoc.getWorld())) {
            return;
        }
        Vector baseDir = tLoc.toVector().subtract(bossLoc.toVector());
        if (baseDir.lengthSquared() < 1e-4) {
            baseDir = new Vector(0.0, -1.0, 0.0);
        } else {
            baseDir.normalize();
        }
        synchronized (followUpEffects) {
            followUpEffects.add(ActiveFollowUpEffect.fireballOrb(bossLoc.clone(), baseDir.clone(), damage));
            // Offset fan projectiles (True Ending summon_ultra_fireball_offset)
            followUpEffects.add(ActiveFollowUpEffect.fireballOrb(bossLoc.clone(), baseDir.clone().rotateAroundY(Math.toRadians(14.0)), damage));
            followUpEffects.add(ActiveFollowUpEffect.fireballOrb(bossLoc.clone(), baseDir.clone().rotateAroundY(Math.toRadians(-14.0)), damage));
        }
    }

    private void scheduleBambooEruption(
            Location targetLoc,
            @Nullable BossConfig.AbilitySettings ability,
            Collection<? extends Player> viewers,
            int currentSessionGen,
            double delaySeconds) {
        double damage = ability != null ? ability.damage(18.0) : 18.0;
        Location under = targetLoc.clone().subtract(0.0, 2.4, 0.0);
        int entityId = telegraphService.spawnVirtualScaledEnderman(under, 2.6, 0.0f, true, viewers);
        synchronized (followUpEffects) {
            followUpEffects.add(ActiveFollowUpEffect.bambooEruption(targetLoc.clone(), entityId, delaySeconds, damage));
        }
    }

    private void resolveSpread(
            @Nullable BossConfig.AbilitySettings ability,
            List<UUID> targetIds,
            List<Player> aliveParticipants,
            Map<UUID, Player> byId,
            int expectedMechGen,
            int expectedSessionGen) {
        double baseDamage = ability != null ? ability.damage(14.0) : 14.0;
        double radius = ability != null ? ability.radius(4.5) : 4.5;
        double overlapMult = ability != null ? ability.getDouble("overlap-penalty-multiplier", 2.0) : 2.0;
        double radiusSq = radius * radius;

        for (UUID targetId : targetIds) {
            Player target = byId.get(targetId);
            if (target == null || !target.isOnline()) {
                continue;
            }
            Location loc = target.getLocation();
            boolean overlapped = false;
            for (Player other : aliveParticipants) {
                if (other == null || !other.isOnline() || other.getUniqueId().equals(targetId)) {
                    continue;
                }
                Location otherLoc = other.getLocation();
                if (loc.getWorld() != null
                        && loc.getWorld().equals(otherLoc.getWorld())
                        && loc.distanceSquared(otherLoc) <= radiusSq) {
                    overlapped = true;
                    break;
                }
            }
            double finalDamage = overlapped ? baseDamage * overlapMult : baseDamage;
            applyMechanicDamageOnEntityThread(target, finalDamage, expectedMechGen, expectedSessionGen);
        }
    }

    private void resolveMeteorTowers(
            @Nullable BossConfig.AbilitySettings ability,
            List<Location> towers,
            List<Player> aliveParticipants,
            int expectedMechGen,
            int expectedSessionGen) {
        double towerRadius = ability != null ? ability.getDouble("tower-radius", 3.0) : 3.0;
        double soakDamage = ability != null ? ability.getDouble("soak-damage", 8.0) : 8.0;
        double unsoakedDamage = ability != null ? ability.getDouble("unsoaked-raid-damage", 22.0) : 22.0;
        double radiusSq = towerRadius * towerRadius;

        int unsoakedCount = 0;
        for (Location tower : towers) {
            List<Player> inside = new ArrayList<>();
            for (Player p : aliveParticipants) {
                if (p != null && p.isOnline()
                        && tower.getWorld() != null
                        && tower.getWorld().equals(p.getLocation().getWorld())
                        && horizontalDistSq(p.getLocation(), tower) <= radiusSq) {
                    inside.add(p);
                }
            }
            if (inside.isEmpty()) {
                unsoakedCount++;
            } else {
                for (Player p : inside) {
                    applyMechanicDamageOnEntityThread(p, soakDamage, expectedMechGen, expectedSessionGen);
                }
            }
        }

        if (unsoakedCount > 0 && unsoakedDamage > 0.0) {
            double raidPenalty = unsoakedCount * unsoakedDamage;
            for (Player p : aliveParticipants) {
                if (p != null && p.isOnline()) {
                    applyMechanicDamageOnEntityThread(p, raidPenalty, expectedMechGen, expectedSessionGen);
                }
            }
        }
    }

    private void resolveLosBreath(
            @Nullable BossConfig.AbilitySettings ability,
            @Nullable Location bossLocation,
            List<Player> aliveParticipants,
            int expectedMechGen,
            int expectedSessionGen) {
        if (bossLocation == null || bossLocation.getWorld() == null) {
            return;
        }
        double damage = ability != null ? ability.damage(26.0) : 26.0;
        double range = ability != null ? ability.getDouble("range", 96.0) : 96.0;
        double rangeSq = range * range;

        for (Player player : aliveParticipants) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            Location playerLoc = player.getLocation();
            if (playerLoc == null || !bossLocation.getWorld().equals(playerLoc.getWorld())) {
                continue;
            }
            if (playerLoc.distanceSquared(bossLocation) > rangeSq) {
                continue;
            }
            Location playerEye = playerLoc.clone().add(0.0, 1.62, 0.0);
            occlusionChecker.isOccludedAsync(bossLocation, playerEye).whenComplete((occluded, ex) -> {
                if (ex == null
                        && mechanicGeneration.get() == expectedMechGen
                        && sessionGeneration.get() == expectedSessionGen
                        && !Boolean.TRUE.equals(occluded)
                        && player.isOnline()) {
                    applyMechanicDamageOnEntityThread(player, damage, expectedMechGen, expectedSessionGen);
                }
            });
        }
    }

    private CompletableFuture<Boolean> checkRayOccludedFoliaSafe(Location bossOrigin, Location playerEye) {
        World world = playerEye.getWorld();
        if (world == null || !world.equals(bossOrigin.getWorld())) {
            return CompletableFuture.completedFuture(false);
        }
        double dx = bossOrigin.getX() - playerEye.getX();
        double dy = bossOrigin.getY() - playerEye.getY();
        double dz = bossOrigin.getZ() - playerEye.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= 2.0) {
            return CompletableFuture.completedFuture(false);
        }

        double startDist = 0.6;
        double endDist = dist - 1.5;
        if (endDist <= startDist) {
            return CompletableFuture.completedFuture(false);
        }

        Map<ChunkKey, List<int[]>> samplesByChunk = new HashMap<>();
        Set<Long> seenBlocks = new LinkedHashSet<>();
        for (double d = startDist; d <= endDist; d += LOS_SAMPLE_STEP) {
            double t = d / dist;
            int bx = (int) Math.floor(playerEye.getX() + dx * t);
            int by = (int) Math.floor(playerEye.getY() + dy * t);
            int bz = (int) Math.floor(playerEye.getZ() + dz * t);
            long packed = packBlock(bx, by, bz);
            if (!seenBlocks.add(packed)) {
                continue;
            }
            ChunkKey chunk = new ChunkKey(bx >> 4, bz >> 4);
            samplesByChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(new int[]{bx, by, bz});
        }

        if (samplesByChunk.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        List<CompletableFuture<Boolean>> chunkChecks = new ArrayList<>(samplesByChunk.size());
        for (Map.Entry<ChunkKey, List<int[]>> entry : samplesByChunk.entrySet()) {
            ChunkKey chunk = entry.getKey();
            List<int[]> coords = entry.getValue();
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            scheduler.runAtRegion(world, chunk.cx(), chunk.cz(), () -> {
                try {
                    boolean blocked = false;
                    for (int[] c : coords) {
                        Block block = world.getBlockAt(c[0], c[1], c[2]);
                        if (block != null && block.isSolid()) {
                            blocked = true;
                            break;
                        }
                    }
                    future.complete(blocked);
                } catch (RuntimeException ex) {
                    future.completeExceptionally(ex);
                }
            });
            chunkChecks.add(future);
        }

        return CompletableFuture.allOf(chunkChecks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    for (CompletableFuture<Boolean> cf : chunkChecks) {
                        if (Boolean.TRUE.equals(cf.join())) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    // =========================================================================
    // Geometry & Target Helpers
    // =========================================================================

    private Location arenaCenter(World world) {
        if (world.getName() != null && world.getName().toLowerCase(Locale.ROOT).contains("instance")) {
            return new Location(world, 0.0, CircularArenaBuilder.DEFAULT_FLOOR_Y, 0.0);
        }
        RegionConfig.Point3D c = configManager.pluginConfig().regions().arena().center();
        return new Location(world, c.x(), c.y(), c.z());
    }

    private List<Location> generateLightningLocations(
            World world,
            List<Player> aliveParticipants,
            @Nullable BossConfig.AbilitySettings ability) {
        Location center = arenaCenter(world);
        int randomCount = ability != null ? ability.getInt("random-strikes", 6) : 6;
        List<Location> strikes = new ArrayList<>(aliveParticipants.size() + randomCount);
        for (Player p : aliveParticipants) {
            if (p != null && p.isOnline() && p.getLocation() != null) {
                strikes.add(p.getLocation().clone());
            }
        }
        for (int i = 0; i < randomCount; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
            double r = ThreadLocalRandom.current().nextDouble(3.0, 30.0);
            strikes.add(new Location(
                    world,
                    center.getX() + Math.cos(angle) * r,
                    center.getY(),
                    center.getZ() + Math.sin(angle) * r
            ));
        }
        return strikes;
    }

    private static List<ExaflareLane> generateExaflareLanes(
            Location center,
            SpatialTelegraphService telegraphService,
            Collection<? extends Player> viewers) {
        List<ExaflareLane> lanes = new ArrayList<>(4);
        double baseAngle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
        float marchYaw = (float) Math.toDegrees(baseAngle + Math.PI * 0.5);
        for (int i = -1; i <= 1; i++) {
            double offset = i * 9.0;
            double ox = center.getX() + Math.cos(baseAngle) * 28.0 + Math.cos(baseAngle + Math.PI * 0.5) * offset;
            double oz = center.getZ() + Math.sin(baseAngle) * 28.0 + Math.sin(baseAngle + Math.PI * 0.5) * offset;
            Location origin = new Location(center.getWorld(), ox, center.getY(), oz);
            int arrowId = telegraphService.spawnDirectionalArrowDisplay(origin, marchYaw, TelegraphStyle.DANGER, viewers);
            lanes.add(new ExaflareLane(origin, marchYaw, arrowId));
        }
        return lanes;
    }

    private static List<Integer> spawnKnockbackArrows(
            Location center,
            SpatialTelegraphService telegraphService,
            Collection<? extends Player> viewers) {
        List<Integer> ids = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            float yaw = i * 45.0f;
            double rad = Math.toRadians(yaw);
            Location arrowLoc = new Location(
                    center.getWorld(),
                    center.getX() - Math.sin(rad) * 4.5,
                    center.getY(),
                    center.getZ() + Math.cos(rad) * 4.5
            );
            ids.add(telegraphService.spawnDirectionalArrowDisplay(arrowLoc, yaw, TelegraphStyle.WARNING, viewers));
        }
        return ids;
    }

    private static List<RotatingEndermanBeam> spawnPlayerTetheredEndermen(
            List<Player> aliveParticipants,
            Location center,
            SpatialTelegraphService telegraphService,
            Collection<? extends Player> viewers) {
        int count = Math.max(1, aliveParticipants.size());
        List<RotatingEndermanBeam> beams = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Player owner = aliveParticipants.get(i);
            double angle = (2.0 * Math.PI * i) / count;
            Location origin = new Location(
                    center.getWorld(),
                    center.getX() + Math.cos(angle) * 16.0,
                    center.getY(),
                    center.getZ() + Math.sin(angle) * 16.0
            );
            Location under = origin.clone().subtract(0.0, 1.8, 0.0);
            int eid = telegraphService.spawnVirtualScaledEnderman(under, 2.8, 0.0f, true, viewers);
            beams.add(new RotatingEndermanBeam(owner.getUniqueId(), origin, eid, (i % 2 == 0)));
        }
        return beams;
    }

    private static @Nullable Player findFrontShieldingPlayer(
            List<Player> aliveParticipants,
            @Nullable Location bossLocation) {
        if (bossLocation == null || bossLocation.getWorld() == null) {
            return null;
        }
        Player best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Player p : aliveParticipants) {
            if (p == null || !p.isOnline() || !p.isBlocking() || p.getLocation() == null
                    || !bossLocation.getWorld().equals(p.getLocation().getWorld())) {
                continue;
            }
            double dSq = horizontalDistSq(p.getLocation(), bossLocation);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = p;
            }
        }
        return best;
    }

    private static boolean isInsideRearSafeCone(
            Location bossOrigin,
            Location blockerLoc,
            Location candidateLoc,
            double halfAngleDegrees) {
        double bx = blockerLoc.getX() - bossOrigin.getX();
        double bz = blockerLoc.getZ() - bossOrigin.getZ();
        double bLen = Math.hypot(bx, bz);
        if (bLen < 1e-4) {
            return false;
        }
        double cx = candidateLoc.getX() - blockerLoc.getX();
        double cz = candidateLoc.getZ() - blockerLoc.getZ();
        double cLen = Math.hypot(cx, cz);
        if (cLen < 0.5) {
            return true;
        }
        double cosAngle = ((bx / bLen) * (cx / cLen)) + ((bz / bLen) * (cz / cLen));
        double minCos = Math.cos(Math.toRadians(halfAngleDegrees));
        return cosAngle >= minCos;
    }

    private static Location computeLaserGroundImpact(Location bossLoc, float yawDegrees, double horizontalReach) {
        double rad = Math.toRadians(yawDegrees);
        double ex = bossLoc.getX() - Math.sin(rad) * horizontalReach;
        double ez = bossLoc.getZ() + Math.cos(rad) * horizontalReach;
        return new Location(bossLoc.getWorld(), ex, CircularArenaBuilder.DEFAULT_FLOOR_Y + 0.5, ez);
    }

    private static Location extrapolateYaw(Location origin, float yawDegrees, double length) {
        double rad = Math.toRadians(yawDegrees);
        double ex = origin.getX() - Math.sin(rad) * length;
        double ez = origin.getZ() + Math.cos(rad) * length;
        return new Location(origin.getWorld(), ex, origin.getY() + 0.8, ez);
    }

    private static double distanceToSegment2D(Location point, Location a, Location b) {
        double px = point.getX();
        double pz = point.getZ();
        double ax = a.getX();
        double az = a.getZ();
        double bx = b.getX();
        double bz = b.getZ();
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1e-6) {
            return Math.hypot(px - ax, pz - az);
        }
        double t = Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (pz - az) * dz) / lenSq));
        double projX = ax + t * dx;
        double projZ = az + t * dz;
        return Math.hypot(px - projX, pz - projZ);
    }

    private List<Location> generateTowerLocations(World world, int towerCount) {
        Location center = arenaCenter(world);
        double ringRadius = Math.min(TOWER_RING_RADIUS, Math.max(8.0, configManager.pluginConfig().regions().arena().radius() * 0.3));
        double phaseAngle = (towerRotationIndex.getAndIncrement() * Math.PI) / Math.max(1, towerCount);

        List<Location> towers = new ArrayList<>(towerCount);
        for (int i = 0; i < towerCount; i++) {
            double angle = phaseAngle + (2.0 * Math.PI * i) / towerCount;
            double tx = center.getX() + Math.cos(angle) * ringRadius;
            double tz = center.getZ() + Math.sin(angle) * ringRadius;
            Location loc = new Location(world, tx, center.getY(), tz);
            towers.add(loc);

            scheduler.runAtRegion(loc, () -> {
                int bx = (int) Math.floor(tx);
                int bz = (int) Math.floor(tz);
                int maxScanY = Math.min(world.getMaxHeight() - 2, (int) Math.ceil(center.getY()) + 24);
                int minScanY = Math.max(world.getMinHeight(), (int) Math.floor(center.getY()) - 16);
                for (int y = maxScanY; y >= minScanY; y--) {
                    Block block = world.getBlockAt(bx, y, bz);
                    if (block != null && block.isSolid()) {
                        loc.setY(y + 1.0);
                        break;
                    }
                }
            });
        }
        return towers;
    }

    private void cleanupActiveVirtualEntities(Collection<? extends Player> viewers) {
        ActiveMechanic current = this.activeMechanic;
        if (current != null) {
            destroyMechanicVirtualEntities(current, viewers);
        }
    }

    private void destroyMechanicVirtualEntities(ActiveMechanic mech, Collection<? extends Player> viewers) {
        List<Integer> ids = new ArrayList<>();
        ids.addAll(mech.virtualEntityIds);
        for (EruptionSite s : mech.eruptionSites) {
            if (s.virtualEntityId >= 0) {
                ids.add(s.virtualEntityId);
            }
        }
        for (DiceAssignment d : mech.diceAssignments) {
            if (d.displayEntityId >= 0) {
                ids.add(d.displayEntityId);
            }
        }
        if (!ids.isEmpty()) {
            telegraphService.destroyVirtualEntities(viewers, ids.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    private void applyMechanicDamageOnEntityThread(
            Player player,
            double amount,
            int expectedMechGen,
            int expectedSessionGen) {
        if (amount <= 0.0) {
            return;
        }
        scheduler.runAtEntity(player, () -> {
            if (player.isOnline()
                    && mechanicGeneration.get() == expectedMechGen
                    && sessionGeneration.get() == expectedSessionGen) {
                damageApplier.accept(player, amount);
            }
        });
    }

    private void applySessionDamageOnEntityThread(
            Player player,
            double amount,
            int expectedSessionGen) {
        if (amount <= 0.0) {
            return;
        }
        scheduler.runAtEntity(player, () -> {
            if (player.isOnline() && sessionGeneration.get() == expectedSessionGen) {
                damageApplier.accept(player, amount);
            }
        });
    }

    private void playWarningSound(Collection<? extends Player> viewers, String soundKey, float pitch) {
        Sound sound = Sound.sound(Key.key(soundKey), Sound.Source.HOSTILE, 0.9f, pitch);
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) {
                scheduler.runAtEntity(viewer, () -> viewer.playSound(sound));
            }
        }
    }

    private static @Nullable Player selectNearestToBoss(
            List<Player> aliveParticipants,
            @Nullable Location bossLocation) {
        if (aliveParticipants.isEmpty()) {
            return null;
        }
        if (bossLocation == null || bossLocation.getWorld() == null) {
            return aliveParticipants.getFirst();
        }
        Player best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Player p : aliveParticipants) {
            if (p == null || !p.isOnline() || !bossLocation.getWorld().equals(p.getLocation().getWorld())) {
                continue;
            }
            double dSq = horizontalDistSq(p.getLocation(), bossLocation);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = p;
            }
        }
        return best != null ? best : aliveParticipants.getFirst();
    }

    private static @Nullable Player selectFirstInArena(List<Player> aliveParticipants) {
        for (Player p : aliveParticipants) {
            if (p != null && p.isOnline()) {
                return p;
            }
        }
        return null;
    }

    private static @Nullable Player findPlayerById(List<Player> players, UUID id) {
        for (Player p : players) {
            if (p != null && p.getUniqueId().equals(id)) {
                return p;
            }
        }
        return null;
    }

    private static boolean hasAnyPlayerInside(Location center, double radiusSq, List<Player> players) {
        if (center.getWorld() == null) {
            return false;
        }
        for (Player p : players) {
            if (p == null || !p.isOnline()) {
                continue;
            }
            Location pLoc = p.getLocation();
            if (pLoc != null
                    && center.getWorld().equals(pLoc.getWorld())
                    && horizontalDistSq(pLoc, center) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    private static double horizontalDistSq(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static Map<UUID, Player> indexPlayers(List<Player> players) {
        Map<UUID, Player> map = new HashMap<>(players.size());
        for (Player p : players) {
            if (p != null) {
                map.put(p.getUniqueId(), p);
            }
        }
        return map;
    }

    private static boolean hasShieldEquipped(Player player) {
        EntityEquipment eq = player.getEquipment();
        if (eq == null) {
            return false;
        }
        ItemStack main = eq.getItemInMainHand();
        ItemStack off = eq.getItemInOffHand();
        return main.getType() == Material.SHIELD || off.getType() == Material.SHIELD;
    }

    private static long packBlock(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    private record ChunkKey(int cx, int cz) {
    }

    private enum MechanicType {
        TANK_BUSTER,
        DRAGON_DIVE_STACK,
        GROUND_SLAM_DIVE,
        LIGHTNING_STORM,
        SWEEPING_LASER,
        SHIELD_WALL_BREATH,
        FIREBALL_BARRAGE,
        ENDERMAN_ERUPTION,
        EXAFLARE_WAVES,
        KNOCKBACK_COMBO,
        ENDERMAN_ROTATING_BEAM,
        DUAL_DIVE_DICE,
        TETHER_DIVE_RELAY,
        SPREAD,
        METEOR_TOWERS,
        LOS_BREATH
    }

    private record EruptionSite(Location location, double radius, int virtualEntityId) {
    }

    private record ExaflareLane(Location origin, float yawDegrees, int arrowEntityId) {
    }

    private static final class RotatingEndermanBeam {
        final UUID ownerPlayerId;
        final Location origin;
        final int virtualEntityId;
        final boolean clockwise;
        float lockedYaw;

        RotatingEndermanBeam(UUID ownerPlayerId, Location origin, int virtualEntityId, boolean clockwise) {
            this.ownerPlayerId = ownerPlayerId;
            this.origin = origin;
            this.virtualEntityId = virtualEntityId;
            this.clockwise = clockwise;
        }
    }

    private record DiceAssignment(UUID playerId, int diceNumber, boolean oddGroup, int displayEntityId) {
    }

    private static final class ActiveMechanic {
        final MechanicType type;
        final @Nullable BossConfig.AbilitySettings ability;
        final @Nullable UUID primaryTargetId;
        final List<UUID> targetIds;
        final List<Location> locations;
        final boolean soloMode;
        final double totalCastSeconds;
        final String eruptionPattern;
        final List<EruptionSite> eruptionSites;
        final List<ExaflareLane> exaflareLanes;
        final List<RotatingEndermanBeam> rotatingBeams;
        final List<DiceAssignment> diceAssignments;
        final List<Integer> virtualEntityIds;
        final Set<UUID> vulnerablePlayerIds = ConcurrentHashMap.newKeySet();
        final float laserStartYaw;
        final float laserTotalSweepDegrees;
        final int relayTotalHits;

        double elapsedSeconds;
        double lastWaveSpawnSeconds;
        int relayCompletedHits;

        private ActiveMechanic(
                MechanicType type,
                @Nullable BossConfig.AbilitySettings ability,
                @Nullable UUID primaryTargetId,
                List<UUID> targetIds,
                List<Location> locations,
                boolean soloMode,
                double totalCastSeconds,
                String eruptionPattern,
                List<EruptionSite> eruptionSites,
                List<ExaflareLane> exaflareLanes,
                List<RotatingEndermanBeam> rotatingBeams,
                List<DiceAssignment> diceAssignments,
                List<Integer> virtualEntityIds,
                float laserStartYaw,
                float laserTotalSweepDegrees,
                int relayTotalHits) {
            this.type = type;
            this.ability = ability;
            this.primaryTargetId = primaryTargetId;
            this.targetIds = targetIds;
            this.locations = locations;
            this.soloMode = soloMode;
            this.totalCastSeconds = totalCastSeconds;
            this.eruptionPattern = eruptionPattern;
            this.eruptionSites = eruptionSites;
            this.exaflareLanes = exaflareLanes;
            this.rotatingBeams = rotatingBeams;
            this.diceAssignments = diceAssignments;
            this.virtualEntityIds = virtualEntityIds;
            this.laserStartYaw = laserStartYaw;
            this.laserTotalSweepDegrees = laserTotalSweepDegrees;
            this.relayTotalHits = relayTotalHits;
        }

        static ActiveMechanic simple(
                MechanicType type,
                @Nullable BossConfig.AbilitySettings ability,
                double castSec) {
            return new ActiveMechanic(
                    type, ability, null, List.of(), List.of(), false, castSec,
                    "", List.of(), List.of(), List.of(), List.of(), List.of(), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic singleTarget(
                MechanicType type,
                @Nullable BossConfig.AbilitySettings ability,
                UUID targetId,
                boolean soloMode,
                double castSec) {
            return new ActiveMechanic(
                    type, ability, targetId, List.of(), List.of(), soloMode, castSec,
                    "", List.of(), List.of(), List.of(), List.of(), List.of(), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic spread(
                @Nullable BossConfig.AbilitySettings ability,
                List<UUID> targets,
                double castSec) {
            return new ActiveMechanic(
                    MechanicType.SPREAD, ability, null, List.copyOf(targets), List.of(), false, castSec,
                    "", List.of(), List.of(), List.of(), List.of(), List.of(), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic locations(
                MechanicType type,
                @Nullable BossConfig.AbilitySettings ability,
                List<Location> locations,
                double castSec) {
            return new ActiveMechanic(
                    type, ability, null, List.of(), List.copyOf(locations), false, castSec,
                    "", List.of(), List.of(), List.of(), List.of(), List.of(), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic laserSweep(
                @Nullable BossConfig.AbilitySettings ability,
                float startYaw,
                float sweepDegrees,
                double castSec) {
            return new ActiveMechanic(
                    MechanicType.SWEEPING_LASER, ability, null, List.of(), List.of(), false, castSec,
                    "", List.of(), List.of(), List.of(), List.of(), List.of(), startYaw, sweepDegrees, 0
            );
        }

        static ActiveMechanic eruption(
                @Nullable BossConfig.AbilitySettings ability,
                String pattern,
                List<Player> aliveParticipants,
                Location center,
                SpatialTelegraphService telegraphService,
                Collection<? extends Player> viewers,
                double castSec) {
            List<EruptionSite> sites = new ArrayList<>();
            double radius = ability != null ? ability.radius(3.4) : 3.4;
            if ("SINGLE_DELAYED".equals(pattern)) {
                for (Player p : aliveParticipants) {
                    if (p != null && p.isOnline() && p.getLocation() != null) {
                        Location snap = p.getLocation().clone();
                        int eid = telegraphService.spawnVirtualScaledEnderman(snap.clone().subtract(0.0, 2.4, 0.0), 2.8, 0.0f, true, viewers);
                        sites.add(new EruptionSite(snap, radius, eid));
                    }
                }
            } else if ("RING_CASCADE".equals(pattern)) {
                int eid = telegraphService.spawnVirtualScaledEnderman(center.clone().subtract(0.0, 2.4, 0.0), 3.4, 0.0f, true, viewers);
                sites.add(new EruptionSite(center.clone(), 6.5, eid));
            }
            return new ActiveMechanic(
                    MechanicType.ENDERMAN_ERUPTION, ability, null, List.of(), List.of(), false, castSec,
                    pattern, sites, List.of(), List.of(), List.of(), List.of(), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic exaflare(
                @Nullable BossConfig.AbilitySettings ability,
                List<ExaflareLane> lanes,
                double castSec) {
            return new ActiveMechanic(
                    MechanicType.EXAFLARE_WAVES, ability, null, List.of(), List.of(), false, castSec,
                    "", List.of(), List.copyOf(lanes), List.of(), List.of(), List.of(), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic knockback(
                @Nullable BossConfig.AbilitySettings ability,
                Location center,
                List<Integer> arrowIds,
                double castSec) {
            return new ActiveMechanic(
                    MechanicType.KNOCKBACK_COMBO, ability, null, List.of(), List.of(center), false, castSec,
                    "", List.of(), List.of(), List.of(), List.of(), List.copyOf(arrowIds), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic rotatingEndermen(
                @Nullable BossConfig.AbilitySettings ability,
                List<RotatingEndermanBeam> beams,
                double castSec) {
            return new ActiveMechanic(
                    MechanicType.ENDERMAN_ROTATING_BEAM, ability, null, List.of(), List.of(), false, castSec,
                    "", List.of(), List.of(), List.copyOf(beams), List.of(), List.of(), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic dualDiveDice(
                @Nullable BossConfig.AbilitySettings ability,
                List<Player> aliveParticipants,
                SpatialTelegraphService telegraphService,
                Collection<? extends Player> viewers,
                double castSec) {
            List<DiceAssignment> assignments = new ArrayList<>(aliveParticipants.size());
            List<UUID> primaryTargets = new ArrayList<>(2);
            for (int i = 0; i < aliveParticipants.size(); i++) {
                Player p = aliveParticipants.get(i);
                int diceNum = i + 1;
                boolean odd = (diceNum % 2 == 1);
                int did = telegraphService.spawnOverheadDiceDisplay(p.getLocation(), diceNum, odd, viewers);
                assignments.add(new DiceAssignment(p.getUniqueId(), diceNum, odd, did));
                if (diceNum <= 2) {
                    primaryTargets.add(p.getUniqueId());
                }
            }
            return new ActiveMechanic(
                    MechanicType.DUAL_DIVE_DICE, ability, null, primaryTargets, List.of(), false, castSec,
                    "", List.of(), List.of(), List.of(), assignments, List.of(), 0.0f, 0.0f, 0
            );
        }

        static ActiveMechanic tetherRelay(
                @Nullable BossConfig.AbilitySettings ability,
                int totalHits,
                double castSec) {
            return new ActiveMechanic(
                    MechanicType.TETHER_DIVE_RELAY, ability, null, List.of(), List.of(), false, castSec,
                    "", List.of(), List.of(), List.of(), List.of(), List.of(), 0.0f, 0.0f, Math.max(1, totalHits)
            );
        }
    }

    private enum FollowUpKind {
        SHOCKWAVE,
        BAMBOO_ERUPTION,
        RING_CASCADE,
        EXAFLARE,
        ROTATING_ENDERMEN,
        FIREBALL_ORB
    }

    private static final class ActiveFollowUpEffect {
        final FollowUpKind kind;
        final Location origin;
        final Vector direction;
        final int virtualEntityId;
        final double durationSeconds;
        final double innerRadius;
        final double maxRadius;
        final double expandSpeedPerSecond;
        final double damage;
        final float sweepDegrees;
        final List<ExaflareLane> lanes;
        final List<RotatingEndermanBeam> rotatingBeams;
        final Set<UUID> hitPlayerIds = ConcurrentHashMap.newKeySet();

        double elapsedSeconds;
        double currentRadius;
        double lastStepSeconds;
        int stepIndex;

        private ActiveFollowUpEffect(
                FollowUpKind kind,
                Location origin,
                Vector direction,
                int virtualEntityId,
                double durationSeconds,
                double innerRadius,
                double maxRadius,
                double expandSpeedPerSecond,
                double damage,
                float sweepDegrees,
                List<ExaflareLane> lanes,
                List<RotatingEndermanBeam> rotatingBeams) {
            this.kind = kind;
            this.origin = origin;
            this.direction = direction;
            this.virtualEntityId = virtualEntityId;
            this.durationSeconds = durationSeconds;
            this.innerRadius = innerRadius;
            this.maxRadius = maxRadius;
            this.expandSpeedPerSecond = expandSpeedPerSecond;
            this.damage = damage;
            this.sweepDegrees = sweepDegrees;
            this.lanes = lanes;
            this.rotatingBeams = rotatingBeams;
        }

        static ActiveFollowUpEffect shockwave(Location origin, double maxRadius, double speedPerSecond) {
            return new ActiveFollowUpEffect(
                    FollowUpKind.SHOCKWAVE, origin, new Vector(), -1, 4.0,
                    0.0, maxRadius, speedPerSecond, 16.0, 0.0f, List.of(), List.of()
            );
        }

        static ActiveFollowUpEffect bambooEruption(Location origin, int entityId, double delaySeconds, double damage) {
            return new ActiveFollowUpEffect(
                    FollowUpKind.BAMBOO_ERUPTION, origin, new Vector(), entityId, delaySeconds,
                    0.0, 3.2, 0.0, damage, 0.0f, List.of(), List.of()
            );
        }

        static ActiveFollowUpEffect ringEruptionCascade(Location origin, double innerR, double outerR, double damage) {
            return new ActiveFollowUpEffect(
                    FollowUpKind.RING_CASCADE, origin, new Vector(), -1, 1.6,
                    innerR, outerR, 0.0, damage, 0.0f, List.of(), List.of()
            );
        }

        static ActiveFollowUpEffect exaflareWaves(List<ExaflareLane> lanes, double damage) {
            Location firstOrigin = lanes.isEmpty() ? new Location(null, 0, 64, 0) : lanes.getFirst().origin;
            return new ActiveFollowUpEffect(
                    FollowUpKind.EXAFLARE, firstOrigin, new Vector(), -1, 6.0,
                    0.0, 0.0, 0.0, damage, 0.0f, lanes, List.of()
            );
        }

        static ActiveFollowUpEffect rotatingEndermen(
                List<RotatingEndermanBeam> beams,
                float sweepDegrees,
                double damage) {
            Location firstOrigin = beams.isEmpty() ? new Location(null, 0, 64, 0) : beams.getFirst().origin;
            return new ActiveFollowUpEffect(
                    FollowUpKind.ROTATING_ENDERMEN, firstOrigin, new Vector(), -1, 2.8,
                    0.0, 0.0, 0.0, damage, sweepDegrees, List.of(), beams
            );
        }

        static ActiveFollowUpEffect fireballOrb(Location origin, Vector direction, double damage) {
            return new ActiveFollowUpEffect(
                    FollowUpKind.FIREBALL_ORB, origin, direction, -1, 4.0,
                    0.0, 0.0, 0.0, damage, 0.0f, List.of(), List.of()
            );
        }
    }
}
