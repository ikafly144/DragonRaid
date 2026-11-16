package net.sabafly.dragonraid.gimmick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.boss.DragonPosture;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.i18n.MessageService;
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
import org.jetbrains.annotations.Nullable;

/**
 * Executes Normal difficulty raid mechanics (Phase 1-3: Tank Buster with Shield/Just Guard,
 * Stack with Solo Fallback, Spread, Meteor Towers, Line-of-Sight Breath, Crystals, and Soft Enrage).
 */
public final class NormalGimmickExecutor {

    private static final double MIN_SOFT_ENRAGE_RADIUS = 14.0;
    private static final double TOWER_RING_RADIUS = 18.0;
    private static final double LOS_SAMPLE_STEP = 0.5;

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
    private final AtomicInteger towerRotationIndex = new AtomicInteger(0);

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
        this.activeMechanic = null;
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
     * Executes an instant timeline action (e.g. {@code SPAWN_CRYSTALS}).
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

        if ("SPAWN_CRYSTALS".equalsIgnoreCase(step.action())) {
            double regen = ability != null ? ability.getDouble("boss-regen-per-second", 4.0) : 4.0;
            crystalManager.spawnWave(endWorld, scaled.crystalCount(), scaled.scaledCrystalHp(), regen);
            broadcastWarning(viewers, "ability.crystals.warning");
        }
    }

    /**
     * Initializes an ability cast, selects mechanic targets/zones, sends warnings, and updates boss posture.
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

        String action = step.action().toUpperCase(java.util.Locale.ROOT);
        switch (action) {
            case "TANK_BUSTER" -> {
                Player target = selectNearestToBoss(aliveParticipants, bossController.bossLocation());
                if (target == null) {
                    this.activeMechanic = null;
                    return;
                }
                bossController.setPosture(DragonPosture.CENTER_LANDED);
                this.activeMechanic = ActiveMechanic.tankBuster(ability, target.getUniqueId());
                broadcastWarning(
                        viewers,
                        "ability.tank_buster.warning",
                        Placeholder.unparsed("player", target.getName())
                );
                playWarningSound(viewers, "minecraft:block.note_block.pling", 0.7f);
            }
            case "STACK" -> {
                Player target = selectFirstInArena(aliveParticipants);
                if (target == null) {
                    this.activeMechanic = null;
                    return;
                }
                boolean solo = scaled.soloFallbackActive() || aliveParticipants.size() <= 1;
                this.activeMechanic = ActiveMechanic.stack(ability, target.getUniqueId(), solo);
                if (solo) {
                    broadcastWarning(viewers, "ability.stack.solo-warning");
                } else {
                    broadcastWarning(viewers, "ability.stack.warning");
                }
                playWarningSound(viewers, "minecraft:block.note_block.bell", 1.1f);
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
                this.activeMechanic = ActiveMechanic.spread(ability, targets);
                broadcastWarning(viewers, "ability.spread.warning");
                playWarningSound(viewers, "minecraft:block.note_block.bass", 1.2f);
            }
            case "METEOR_TOWERS" -> {
                bossController.setPosture(DragonPosture.HIGH_HOVER);
                List<Location> towers = generateTowerLocations(endWorld, scaled.towerCount());
                this.activeMechanic = ActiveMechanic.towers(ability, towers);
                broadcastWarning(viewers, "ability.meteor_towers.warning");
                playWarningSound(viewers, "minecraft:block.beacon.activate", 1.1f);
            }
            case "LOS_BREATH" -> {
                bossController.setPosture(DragonPosture.HIGH_HOVER);
                this.activeMechanic = ActiveMechanic.losBreath(ability);
                broadcastWarning(viewers, "ability.los_breath.warning");
                playWarningSound(viewers, "minecraft:entity.ender_dragon.ambient", 0.8f);
            }
            default -> this.activeMechanic = null;
        }
    }

    /**
     * Renders spatial telegraphs for any active cast mechanic on each monitor tick.
     */
    public void tickActiveTelegraphs(
            List<Player> aliveParticipants,
            Collection<? extends Player> viewers,
            DragonBossController bossController) {
        ActiveMechanic mech = this.activeMechanic;
        if (mech == null) {
            return;
        }
        Map<UUID, Player> byId = indexPlayers(aliveParticipants);
        switch (mech.type) {
            case TANK_BUSTER -> {
                Player target = byId.get(mech.primaryTargetId);
                if (target != null && target.isOnline()) {
                    double radius = mech.ability != null ? mech.ability.getDouble("telegraph-radius", 3.5) : 3.5;
                    telegraphService.renderCircleAoE(target.getLocation(), radius, TelegraphStyle.DANGER, viewers);
                }
            }
            case STACK -> {
                Player target = byId.get(mech.primaryTargetId);
                if (target != null && target.isOnline()) {
                    double radius = mech.ability != null ? mech.ability.radius(4.5) : 4.5;
                    telegraphService.renderStackMarker(target.getLocation(), radius, mech.soloMode, viewers);
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
                for (Location towerLoc : mech.towerLocations) {
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
                Location bossLoc = bossController.bossLocation();
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
     * Resolves damage and side effects when an ability cast completes.
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
        Map<UUID, Player> byId = indexPlayers(aliveParticipants);
        switch (mech.type) {
            case TANK_BUSTER -> resolveTankBuster(
                    mech.ability != null ? mech.ability : ability,
                    byId.get(mech.primaryTargetId),
                    bossController,
                    timelineEngine
            );
            case STACK -> resolveStack(
                    mech.ability != null ? mech.ability : ability,
                    byId.get(mech.primaryTargetId),
                    aliveParticipants,
                    scaled,
                    mech.soloMode
            );
            case SPREAD -> resolveSpread(
                    mech.ability != null ? mech.ability : ability,
                    mech.targetIds,
                    aliveParticipants,
                    byId
            );
            case METEOR_TOWERS -> resolveMeteorTowers(
                    mech.ability != null ? mech.ability : ability,
                    mech.towerLocations,
                    aliveParticipants
            );
            case LOS_BREATH -> resolveLosBreath(
                    mech.ability != null ? mech.ability : ability,
                    bossController.bossLocation(),
                    aliveParticipants
            );
        }

        // Return dragon to default phase posture unless staggered by Just Guard.
        if (!bossController.isStaggered()) {
            BossConfig.PhaseConfig currentPhase = timelineEngine.currentPhase();
            bossController.setPosture(defaultPostureForPhase(currentPhase));
        }
    }

    /**
     * Cancels any in-progress mechanic (e.g. on phase change, stagger, or wipe).
     */
    public void cancelActiveMechanic() {
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
        RegionConfig.Point3D center = configManager.pluginConfig().regions().arena().center();
        this.safeArenaRadius = Math.max(
                MIN_SOFT_ENRAGE_RADIUS,
                this.safeArenaRadius - soft.voidCorruptionShrinkPerSecond() * deltaSeconds
        );
        Location centerLoc = new Location(endWorld, center.x(), center.y(), center.z());
        telegraphService.renderArenaBoundary(centerLoc, this.safeArenaRadius, viewers);

        this.softEnrageAccumulatorSeconds += deltaSeconds;
        if (this.softEnrageAccumulatorSeconds + 1e-6 >= 1.0) {
            this.softEnrageAccumulatorSeconds = Math.max(0.0, this.softEnrageAccumulatorSeconds - 1.0);
            double damagePerSec = soft.voidDamagePerSecond();
            double safeRadiusSq = this.safeArenaRadius * this.safeArenaRadius;
            for (Player player : aliveParticipants) {
                if (player == null || !player.isOnline()) {
                    continue;
                }
                Location loc = player.getLocation();
                if (loc.getWorld() == null || !loc.getWorld().equals(endWorld)) {
                    continue;
                }
                double dx = loc.getX() - center.x();
                double dz = loc.getZ() - center.z();
                if (dx * dx + dz * dz > safeRadiusSq && damagePerSec > 0.0) {
                    applyDamageOnEntityThread(player, damagePerSec);
                }
            }
        }
    }

    /**
     * Returns localized ActionBar extra status component for {@code playerId} describing active
     * mechanic assignments, or {@code null} if none.
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
            case STACK -> id.equals(mech.primaryTargetId)
                    ? messageService.render(player, "ability.stack.name")
                    : null;
            case SPREAD -> mech.targetIds.contains(id)
                    ? messageService.render(player, "ability.spread.name")
                    : null;
            case METEOR_TOWERS -> messageService.render(player, "ability.meteor_towers.name");
            case LOS_BREATH -> messageService.render(player, "ability.los_breath.name");
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

    private void resolveTankBuster(
            @Nullable BossConfig.AbilitySettings ability,
            @Nullable Player target,
            DragonBossController bossController,
            RaidTimelineEngine timelineEngine) {
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
            applyDamageOnEntityThread(target, finalDamage);
            bossController.stagger(staggerSeconds);
            timelineEngine.stagger(staggerSeconds);
            scheduler.runAtEntity(target, () -> {
                target.sendActionBar(messageService.render(target, "actionbar.just-guard-success"));
                target.playSound(Sound.sound(
                        Key.key("minecraft:block.anvil.land"),
                        Sound.Source.PLAYER,
                        0.8f,
                        1.4f
                ));
            });
        } else if (blocking) {
            double finalDamage = baseDamage * shieldMult;
            applyDamageOnEntityThread(target, finalDamage);
            scheduler.runAtEntity(target, () -> target.playSound(Sound.sound(
                    Key.key("minecraft:item.shield.block"),
                    Sound.Source.PLAYER,
                    0.9f,
                    1.0f
            )));
        } else {
            applyDamageOnEntityThread(target, baseDamage);
        }
    }

    private void resolveStack(
            @Nullable BossConfig.AbilitySettings ability,
            @Nullable Player target,
            List<Player> aliveParticipants,
            ScaledEncounterParameters scaled,
            boolean soloMode) {
        if (target == null || !target.isOnline()) {
            return;
        }
        double totalDamage = ability != null ? ability.getDouble("total-damage", 32.0) : 32.0;
        double radius = ability != null ? ability.radius(4.5) : 4.5;

        if (soloMode) {
            double soloDamage = totalDamage * scaled.soloStackDamageMultiplier();
            if (target.isBlocking()) {
                soloDamage *= 0.5;
            }
            applyDamageOnEntityThread(target, soloDamage);
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
            applyDamageOnEntityThread(soaker, splitDamage);
        }
    }

    private void resolveSpread(
            @Nullable BossConfig.AbilitySettings ability,
            List<UUID> targetIds,
            List<Player> aliveParticipants,
            Map<UUID, Player> byId) {
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
            applyDamageOnEntityThread(target, finalDamage);
        }
    }

    private void resolveMeteorTowers(
            @Nullable BossConfig.AbilitySettings ability,
            List<Location> towers,
            List<Player> aliveParticipants) {
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
                    applyDamageOnEntityThread(p, soakDamage);
                }
            }
        }

        if (unsoakedCount > 0 && unsoakedDamage > 0.0) {
            double raidPenalty = unsoakedCount * unsoakedDamage;
            for (Player p : aliveParticipants) {
                if (p != null && p.isOnline()) {
                    applyDamageOnEntityThread(p, raidPenalty);
                }
            }
        }
    }

    private void resolveLosBreath(
            @Nullable BossConfig.AbilitySettings ability,
            @Nullable Location bossLocation,
            List<Player> aliveParticipants) {
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
            if (!bossLocation.getWorld().equals(playerLoc.getWorld())) {
                continue;
            }
            if (playerLoc.distanceSquared(bossLocation) > rangeSq) {
                continue;
            }
            Location playerEye = playerLoc.clone().add(0.0, 1.62, 0.0);
            occlusionChecker.isOccludedAsync(bossLocation, playerEye).whenComplete((occluded, ex) -> {
                if (ex == null && !Boolean.TRUE.equals(occluded) && player.isOnline()) {
                    applyDamageOnEntityThread(player, damage);
                }
            });
        }
    }

    /**
     * Checks segment occlusion across Folia regions by grouping ray sample points by chunk
     * and dispatching chunk reads onto each chunk's region scheduler.
     */
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

        // Skip immediate head/eye offset and the last 1.5m inside the dragon hitbox.
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

    private List<Location> generateTowerLocations(World world, int towerCount) {
        RegionConfig.ArenaRegion arena = configManager.pluginConfig().regions().arena();
        RegionConfig.Point3D center = arena.center();
        double ringRadius = Math.min(TOWER_RING_RADIUS, Math.max(8.0, arena.radius() * 0.3));
        double phaseAngle = (towerRotationIndex.getAndIncrement() * Math.PI) / Math.max(1, towerCount);

        List<Location> towers = new ArrayList<>(towerCount);
        for (int i = 0; i < towerCount; i++) {
            double angle = phaseAngle + (2.0 * Math.PI * i) / towerCount;
            double tx = center.x() + Math.cos(angle) * ringRadius;
            double tz = center.z() + Math.sin(angle) * ringRadius;
            Location loc = new Location(world, tx, center.y(), tz);
            towers.add(loc);

            // Refine Y to surface ground level on the owning region thread when possible.
            scheduler.runAtRegion(loc, () -> {
                int bx = (int) Math.floor(tx);
                int bz = (int) Math.floor(tz);
                int maxScanY = Math.min(world.getMaxHeight() - 2, (int) Math.ceil(center.y()) + 24);
                int minScanY = Math.max(world.getMinHeight(), (int) Math.floor(center.y()) - 16);
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

    private void applyDamageOnEntityThread(Player player, double amount) {
        if (amount <= 0.0) {
            return;
        }
        scheduler.runAtEntity(player, () -> {
            if (player.isOnline()) {
                damageApplier.accept(player, amount);
            }
        });
    }

    private void broadcastWarning(
            Collection<? extends Player> viewers,
            String key,
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... placeholders) {
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) {
                scheduler.runAtEntity(viewer, () -> messageService.send(viewer, key, placeholders));
            }
        }
    }

    private void playWarningSound(Collection<? extends Player> viewers, String soundKey, float pitch) {
        Sound sound = Sound.sound(Key.key(soundKey), Sound.Source.HOSTILE, 0.85f, pitch);
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

    private static boolean hasAnyPlayerInside(Location center, double radiusSq, List<Player> players) {
        if (center.getWorld() == null) {
            return false;
        }
        for (Player p : players) {
            if (p != null && p.isOnline()
                    && center.getWorld().equals(p.getLocation().getWorld())
                    && horizontalDistSq(p.getLocation(), center) <= radiusSq) {
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
        STACK,
        SPREAD,
        METEOR_TOWERS,
        LOS_BREATH
    }

    private static final class ActiveMechanic {
        final MechanicType type;
        final @Nullable BossConfig.AbilitySettings ability;
        final @Nullable UUID primaryTargetId;
        final List<UUID> targetIds;
        final List<Location> towerLocations;
        final boolean soloMode;

        private ActiveMechanic(
                MechanicType type,
                @Nullable BossConfig.AbilitySettings ability,
                @Nullable UUID primaryTargetId,
                List<UUID> targetIds,
                List<Location> towerLocations,
                boolean soloMode) {
            this.type = type;
            this.ability = ability;
            this.primaryTargetId = primaryTargetId;
            this.targetIds = targetIds;
            this.towerLocations = towerLocations;
            this.soloMode = soloMode;
        }

        static ActiveMechanic tankBuster(@Nullable BossConfig.AbilitySettings ability, UUID targetId) {
            return new ActiveMechanic(MechanicType.TANK_BUSTER, ability, targetId, List.of(), List.of(), false);
        }

        static ActiveMechanic stack(@Nullable BossConfig.AbilitySettings ability, UUID targetId, boolean soloMode) {
            return new ActiveMechanic(MechanicType.STACK, ability, targetId, List.of(), List.of(), soloMode);
        }

        static ActiveMechanic spread(@Nullable BossConfig.AbilitySettings ability, List<UUID> targets) {
            return new ActiveMechanic(MechanicType.SPREAD, ability, null, List.copyOf(targets), List.of(), false);
        }

        static ActiveMechanic towers(@Nullable BossConfig.AbilitySettings ability, List<Location> locations) {
            return new ActiveMechanic(MechanicType.METEOR_TOWERS, ability, null, List.of(), List.copyOf(locations), false);
        }

        static ActiveMechanic losBreath(@Nullable BossConfig.AbilitySettings ability) {
            return new ActiveMechanic(MechanicType.LOS_BREATH, ability, null, List.of(), List.of(), false);
        }
    }
}
