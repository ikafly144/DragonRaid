package net.sabafly.dragonraid.boss;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe {@link DragonBossController} driving smooth custom flight/landing/hover motion
 * on the dragon entity's scheduler while suppressing vanilla random dragon AI.
 */
public final class FoliaDragonBossController implements DragonBossController {

    private static final double ORBIT_RADIUS = 22.0;
    private static final double LOW_ORBIT_Y_OFFSET = 10.0;
    private static final double CENTER_LANDED_Y_OFFSET = 2.0;
    private static final double HIGH_HOVER_Y_OFFSET = 18.0;
    private static final double STAGGERED_Y_OFFSET = 1.5;
    private static final double ORBIT_ANGULAR_SPEED_RAD_PER_TICK = 0.045;
    private static final double MAX_MOVE_STEP_PER_TICK = 2.2;
    private static final double DEFAULT_FALLBACK_MAX_HP = 1200.0;

    private final Object lock = new Object();
    private final ConfigManager configManager;
    private final RaidScheduler scheduler;
    private final Logger logger;
    private final Function<World, @Nullable EnderDragon> dragonFinder;
    private final DragonSpawner dragonSpawner;

    private volatile @Nullable EnderDragon activeDragon;
    private volatile @Nullable UUID capturedFlagsDragonId;
    private volatile boolean summonedByPlugin;
    private volatile boolean previousAi = true;
    private volatile boolean previousGravity = true;
    private volatile DragonPosture targetPosture = DragonPosture.LOW_ORBIT;
    private volatile @Nullable DragonPosture preStaggerPosture;
    private volatile double staggerRemainingSeconds;
    private volatile double maxHp = DEFAULT_FALLBACK_MAX_HP;
    private volatile double currentHp = DEFAULT_FALLBACK_MAX_HP;
    private volatile @Nullable Location cachedLocation;
    private volatile double orbitAngleRad;
    private volatile @Nullable TaskHandle movementTask;
    private volatile boolean activeCombat;
    private final AtomicLong generation = new AtomicLong(0L);
    private final Set<UUID> pendingDeletionDragons = ConcurrentHashMap.newKeySet();

    @FunctionalInterface
    interface DragonSpawner {
        EnderDragon spawn(World world, Location spawnLocation);
    }

    public FoliaDragonBossController(ConfigManager configManager, RaidScheduler scheduler, Logger logger) {
        this(
                configManager,
                scheduler,
                logger,
                FoliaDragonBossController::findLivingDragonDefault,
                (world, loc) -> world.spawn(loc, EnderDragon.class)
        );
    }

    FoliaDragonBossController(
            ConfigManager configManager,
            RaidScheduler scheduler,
            Logger logger,
            Function<World, @Nullable EnderDragon> dragonFinder,
            DragonSpawner dragonSpawner) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dragonFinder = Objects.requireNonNull(dragonFinder, "dragonFinder");
        this.dragonSpawner = Objects.requireNonNull(dragonSpawner, "dragonSpawner");
    }

    @Override
    public CompletableFuture<Void> bindOrSpawn(World endWorld, double scaledMaxHp) {
        Objects.requireNonNull(endWorld, "endWorld");
        double validMaxHp = Math.max(1.0, scaledMaxHp);
        Location anchor = arenaAnchor(endWorld, LOW_ORBIT_Y_OFFSET);

        final long bindGeneration;
        synchronized (lock) {
            cancelMovementTask();
            bindGeneration = generation.incrementAndGet();
            this.maxHp = validMaxHp;
            this.currentHp = validMaxHp;
            this.targetPosture = DragonPosture.LOW_ORBIT;
            this.preStaggerPosture = null;
            this.staggerRemainingSeconds = 0.0;
            this.orbitAngleRad = 0.0;
            this.activeCombat = true;
            this.cachedLocation = anchor.clone();
        }

        EnderDragon existing = findEligibleDragon(endWorld);
        if (existing != null) {
            return bindEntity(existing, false, validMaxHp, true, anchor, bindGeneration);
        }

        CompletableFuture<Void> readyFuture = new CompletableFuture<>();
        TaskHandle regionHandle = scheduler.runAtRegion(anchor, () -> {
            if (generation.get() != bindGeneration || !activeCombat) {
                readyFuture.complete(null);
                return;
            }
            EnderDragon rechecked = findEligibleDragon(endWorld);
            if (rechecked != null) {
                bindEntity(rechecked, false, validMaxHp, true, anchor, bindGeneration)
                        .whenComplete((ignored, err) -> {
                            if (err != null) {
                                readyFuture.completeExceptionally(err);
                            } else {
                                readyFuture.complete(null);
                            }
                        });
                return;
            }
            try {
                EnderDragon spawned = dragonSpawner.spawn(endWorld, anchor);
                if (spawned == null) {
                    throw new IllegalStateException("Dragon spawner returned null");
                }
                if (generation.get() != bindGeneration || !activeCombat) {
                    spawned.remove();
                    readyFuture.complete(null);
                    return;
                }
                bindEntity(spawned, true, validMaxHp, true, anchor, bindGeneration)
                        .whenComplete((ignored, err) -> {
                            if (err != null) {
                                readyFuture.completeExceptionally(err);
                            } else {
                                readyFuture.complete(null);
                            }
                        });
            } catch (RuntimeException ex) {
                logger.log(Level.WARNING, "Failed to spawn raid EnderDragon", ex);
                readyFuture.completeExceptionally(ex);
            }
        });
        if (regionHandle == null) {
            readyFuture.completeExceptionally(new IllegalStateException("Failed to schedule region dragon spawn"));
        }
        return readyFuture;
    }

    @Override
    public void holdFirstClearIdle(World endWorld) {
        Objects.requireNonNull(endWorld, "endWorld");
        EnderDragon existing = findEligibleDragon(endWorld);
        if (existing == null) {
            return;
        }
        final long holdGeneration;
        synchronized (lock) {
            if (activeCombat || (activeDragon != null && !activeDragon.isDead() && movementTask != null)) {
                return;
            }
            holdGeneration = generation.incrementAndGet();
            this.targetPosture = DragonPosture.LOW_ORBIT;
            this.preStaggerPosture = null;
            this.staggerRemainingSeconds = 0.0;
            this.activeCombat = false;
        }
        Location anchor = arenaAnchor(endWorld, LOW_ORBIT_Y_OFFSET);
        bindEntity(existing, false, 0.0, false, anchor, holdGeneration);
    }

    private CompletableFuture<Void> bindEntity(
            EnderDragon dragon,
            boolean wasSummoned,
            double targetMaxHp,
            boolean applyHealth,
            Location initialAnchor,
            long expectedGeneration) {
        synchronized (lock) {
            if (generation.get() != expectedGeneration) {
                return CompletableFuture.completedFuture(null);
            }
            cancelMovementTask();
            this.activeDragon = dragon;
            this.summonedByPlugin = wasSummoned;
            this.cachedLocation = initialAnchor.clone();
        }

        CompletableFuture<Void> entityReady = new CompletableFuture<>();
        Runnable entityTask = () -> {
            if (dragon.isDead() || generation.get() != expectedGeneration) {
                if (dragon.isDead()) {
                    entityReady.completeExceptionally(new IllegalStateException("Bound dragon died during initialization"));
                } else {
                    entityReady.complete(null);
                }
                return;
            }
            synchronized (lock) {
                if (this.activeDragon != dragon || generation.get() != expectedGeneration) {
                    entityReady.complete(null);
                    return;
                }
                UUID dragonId = dragon.getUniqueId();
                if (!Objects.equals(this.capturedFlagsDragonId, dragonId)) {
                    this.capturedFlagsDragonId = dragonId;
                    this.previousAi = dragon.hasAI();
                    this.previousGravity = dragon.hasGravity();
                }
            }
            suppressVanillaAi(dragon, initialAnchor.getWorld());
            if (applyHealth && targetMaxHp > 0.0) {
                applyScaledHealth(dragon, targetMaxHp);
            } else {
                refreshCachedHp(dragon);
            }
            Location loc = dragon.getLocation();
            if (loc != null) {
                this.cachedLocation = loc.clone();
            }
            startMovementLoop(dragon);
            entityReady.complete(null);
        };
        TaskHandle handle = scheduler.runAtEntity(
                dragon,
                entityTask,
                () -> entityReady.completeExceptionally(new IllegalStateException("Dragon entity retired before binding"))
        );
        if (handle == null) {
            entityReady.completeExceptionally(new IllegalStateException("Failed to schedule entity dragon binding"));
        }
        return entityReady;
    }

    private @Nullable EnderDragon findEligibleDragon(World endWorld) {
        EnderDragon dragon = dragonFinder.apply(endWorld);
        if (dragon == null || dragon.isDead()) {
            return null;
        }
        UUID id = dragon.getUniqueId();
        if (id != null && pendingDeletionDragons.contains(id)) {
            return null;
        }
        return dragon;
    }

    private void suppressVanillaAi(EnderDragon dragon, @Nullable World world) {
        dragon.setAI(false);
        dragon.setGravity(false);
        dragon.setPhase(EnderDragon.Phase.HOVER);
        if (world != null) {
            RegionConfig.Point3D center = configManager.pluginConfig().regions().arena().center();
            dragon.setPodium(new Location(world, center.x(), center.y(), center.z()));
        }
    }

    private void applyScaledHealth(EnderDragon dragon, double targetMaxHp) {
        if (Bukkit.getServer() != null) {
            AttributeInstance maxHealthAttr = dragon.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealthAttr != null) {
                maxHealthAttr.setBaseValue(targetMaxHp);
            }
        }
        dragon.setHealth(Math.max(1.0, Math.min(targetMaxHp, dragonMaxHealthValue(dragon, targetMaxHp))));
        this.maxHp = targetMaxHp;
        this.currentHp = targetMaxHp;
    }

    private void refreshCachedHp(EnderDragon dragon) {
        double max = dragonMaxHealthValue(dragon, this.maxHp);
        double cur = Math.max(0.0, Math.min(max, dragon.getHealth()));
        this.maxHp = max;
        this.currentHp = cur;
    }

    private void startMovementLoop(EnderDragon dragon) {
        AtomicReference<TaskHandle> ownHandle = new AtomicReference<>();
        TaskHandle scheduled = scheduler.runAtEntityTimer(dragon, () -> {
            if (dragon.isDead() || dragon.getPhase() == EnderDragon.Phase.DYING || this.activeDragon != dragon) {
                cancelHandle(ownHandle.get());
                return;
            }
            tickEntityMovement(dragon);
        }, () -> {
            synchronized (lock) {
                if (this.activeDragon == dragon) {
                    this.movementTask = null;
                }
            }
        }, 2L, 2L);
        ownHandle.set(scheduled);
        synchronized (lock) {
            if (this.activeDragon == dragon) {
                this.movementTask = scheduled;
            } else {
                cancelHandle(scheduled);
            }
        }
    }

    void tickEntityMovement(EnderDragon dragon) {
        if (dragon.isDead() || dragon.getPhase() == EnderDragon.Phase.DYING) {
            return;
        }
        refreshCachedHp(dragon);
        World world = dragon.getWorld();
        if (world == null) {
            return;
        }

        // Re-assert AI suppression if vanilla flipped phase unexpectedly.
        if (dragon.hasAI()) {
            dragon.setAI(false);
        }
        if (dragon.getPhase() != EnderDragon.Phase.HOVER) {
            dragon.setPhase(EnderDragon.Phase.HOVER);
        }

        Location current = dragon.getLocation();
        if (current == null || current.getWorld() == null) {
            return;
        }

        Location target = computeTargetLocation(world);
        Location next = stepTowards(current, target, MAX_MOVE_STEP_PER_TICK * 2.0);
        this.cachedLocation = next.clone();

        dragon.setRotation(next.getYaw(), next.getPitch());
        dragon.teleportAsync(next);
    }

    private Location computeTargetLocation(World world) {
        RegionConfig.Point3D center = configManager.pluginConfig().regions().arena().center();
        DragonPosture effective = posture();
        return switch (effective) {
            case LOW_ORBIT -> {
                orbitAngleRad = (orbitAngleRad + ORBIT_ANGULAR_SPEED_RAD_PER_TICK * 2.0) % (Math.PI * 2.0);
                double x = center.x() + Math.cos(orbitAngleRad) * ORBIT_RADIUS;
                double z = center.z() + Math.sin(orbitAngleRad) * ORBIT_RADIUS;
                double y = center.y() + LOW_ORBIT_Y_OFFSET;
                double tangentX = -Math.sin(orbitAngleRad);
                double tangentZ = Math.cos(orbitAngleRad);
                float yaw = computeYawDegrees(tangentX, tangentZ);
                yield new Location(world, x, y, z, yaw, 0.0f);
            }
            case CENTER_LANDED -> new Location(
                    world,
                    center.x(),
                    center.y() + CENTER_LANDED_Y_OFFSET,
                    center.z(),
                    0.0f,
                    10.0f
            );
            case HIGH_HOVER -> new Location(
                    world,
                    center.x(),
                    center.y() + HIGH_HOVER_Y_OFFSET,
                    center.z(),
                    0.0f,
                    25.0f
            );
            case STAGGERED -> new Location(
                    world,
                    center.x(),
                    center.y() + STAGGERED_Y_OFFSET,
                    center.z(),
                    0.0f,
                    30.0f
            );
        };
    }

    static Location stepTowards(Location current, Location target, double maxStep) {
        double dx = target.getX() - current.getX();
        double dy = target.getY() - current.getY();
        double dz = target.getZ() - current.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq <= maxStep * maxStep || distSq < 1e-6) {
            return target.clone();
        }
        double dist = Math.sqrt(distSq);
        double scale = maxStep / dist;
        return new Location(
                target.getWorld(),
                current.getX() + dx * scale,
                current.getY() + dy * scale,
                current.getZ() + dz * scale,
                target.getYaw(),
                target.getPitch()
        );
    }

    private static float computeYawDegrees(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    @Override
    public void setPosture(DragonPosture posture) {
        Objects.requireNonNull(posture, "posture");
        synchronized (lock) {
            if (staggerRemainingSeconds > 0.0 && posture != DragonPosture.STAGGERED) {
                this.preStaggerPosture = posture;
                return;
            }
            this.targetPosture = posture;
            if (posture != DragonPosture.STAGGERED) {
                this.preStaggerPosture = null;
            }
        }
    }

    @Override
    public DragonPosture posture() {
        synchronized (lock) {
            return staggerRemainingSeconds > 0.0 ? DragonPosture.STAGGERED : targetPosture;
        }
    }

    @Override
    public void stagger(double seconds) {
        if (seconds <= 0.0) {
            return;
        }
        synchronized (lock) {
            if (staggerRemainingSeconds <= 0.0 && targetPosture != DragonPosture.STAGGERED) {
                this.preStaggerPosture = targetPosture;
            }
            this.staggerRemainingSeconds = Math.max(this.staggerRemainingSeconds, seconds);
            this.targetPosture = DragonPosture.STAGGERED;
        }
    }

    @Override
    public boolean isStaggered() {
        return staggerRemainingSeconds > 0.0;
    }

    @Override
    public void tick(double deltaSeconds) {
        if (deltaSeconds <= 0.0) {
            return;
        }
        synchronized (lock) {
            if (staggerRemainingSeconds > 1e-6) {
                staggerRemainingSeconds = Math.max(0.0, staggerRemainingSeconds - deltaSeconds);
                if (staggerRemainingSeconds <= 1e-6) {
                    staggerRemainingSeconds = 0.0;
                    targetPosture = preStaggerPosture != null ? preStaggerPosture : DragonPosture.LOW_ORBIT;
                    preStaggerPosture = null;
                }
            }
        }
    }

    @Override
    public void healBoss(double amount) {
        if (amount <= 0.0) {
            return;
        }
        EnderDragon dragon = this.activeDragon;
        if (dragon == null || dragon.isDead()) {
            return;
        }
        scheduler.runAtEntity(dragon, () -> {
            if (dragon.isDead()) {
                return;
            }
            double max = dragonMaxHealthValue(dragon, this.maxHp);
            double next = Math.min(max, dragon.getHealth() + amount);
            dragon.setHealth(next);
            this.maxHp = max;
            this.currentHp = next;
        });
    }

    @Override
    public void damageBoss(double amount) {
        if (amount <= 0.0) {
            return;
        }
        EnderDragon dragon = this.activeDragon;
        if (dragon == null || dragon.isDead()) {
            return;
        }
        scheduler.runAtEntity(dragon, () -> {
            if (dragon.isDead()) {
                return;
            }
            dragon.damage(amount);
            refreshCachedHp(dragon);
        });
    }

    @Override
    public double currentHp() {
        return currentHp;
    }

    @Override
    public double maxHp() {
        return maxHp;
    }

    @Override
    public @Nullable EnderDragon activeDragon() {
        EnderDragon dragon = this.activeDragon;
        return (dragon != null && !dragon.isDead()) ? dragon : null;
    }

    @Override
    public @Nullable Location bossLocation() {
        Location loc = this.cachedLocation;
        return loc != null ? loc.clone() : null;
    }

    @Override
    public boolean wasSummonedByPlugin() {
        return summonedByPlugin;
    }

    @Override
    public void prepareForVanillaDefeat() {
        EnderDragon dragon;
        boolean prevAi;
        boolean prevGravity;
        long defeatGen;
        synchronized (lock) {
            defeatGen = generation.incrementAndGet();
            cancelMovementTask();
            dragon = this.activeDragon;
            prevAi = this.previousAi;
            prevGravity = this.previousGravity;
            this.capturedFlagsDragonId = null;
            this.activeCombat = false;
            this.staggerRemainingSeconds = 0.0;
            this.preStaggerPosture = null;
        }
        if (dragon != null && !dragon.isDead()) {
            Runnable restoreTask = () -> {
                if (generation.get() == defeatGen) {
                    dragon.setAI(prevAi);
                    dragon.setGravity(prevGravity);
                }
            };
            if (Bukkit.getServer() != null && Bukkit.isOwnedByCurrentRegion(dragon)) {
                restoreTask.run();
            } else {
                scheduler.runAtEntity(dragon, restoreTask);
            }
        }
    }

    @Override
    public CompletableFuture<Void> cleanup(boolean removeDragonIfSummoned) {
        EnderDragon dragon;
        boolean wasSummoned;
        boolean prevAi;
        boolean prevGravity;
        long cleanupGen;
        synchronized (lock) {
            cleanupGen = generation.incrementAndGet();
            cancelMovementTask();
            dragon = this.activeDragon;
            wasSummoned = this.summonedByPlugin;
            prevAi = this.previousAi;
            prevGravity = this.previousGravity;
            this.activeDragon = null;
            this.capturedFlagsDragonId = null;
            this.summonedByPlugin = false;
            this.activeCombat = false;
            this.staggerRemainingSeconds = 0.0;
            this.preStaggerPosture = null;
            this.targetPosture = DragonPosture.LOW_ORBIT;
            this.cachedLocation = null;
        }
        if (dragon == null || dragon.isDead()) {
            return CompletableFuture.completedFuture(null);
        }
        UUID dragonId = dragon.getUniqueId();
        boolean shouldDelete = removeDragonIfSummoned && wasSummoned;
        if (shouldDelete && dragonId != null) {
            pendingDeletionDragons.add(dragonId);
        }
        CompletableFuture<Void> cleanupDone = new CompletableFuture<>();
        Runnable cleanupTask = () -> {
            try {
                if (dragon.isDead()) {
                    return;
                }
                if (shouldDelete) {
                    if (dragonId == null || pendingDeletionDragons.contains(dragonId)) {
                        dragon.remove();
                    }
                } else if (generation.get() == cleanupGen) {
                    dragon.setAI(prevAi);
                    dragon.setGravity(prevGravity);
                    dragon.setPhase(EnderDragon.Phase.CIRCLING);
                }
            } finally {
                if (shouldDelete && dragonId != null) {
                    pendingDeletionDragons.remove(dragonId);
                }
                cleanupDone.complete(null);
            }
        };
        if (Bukkit.getServer() != null && Bukkit.isOwnedByCurrentRegion(dragon)) {
            cleanupTask.run();
            return cleanupDone;
        }
        TaskHandle handle = scheduler.runAtEntity(dragon, cleanupTask, () -> {
            if (shouldDelete && dragonId != null) {
                pendingDeletionDragons.remove(dragonId);
            }
            cleanupDone.complete(null);
        });
        if (handle == null) {
            if (shouldDelete && dragonId != null) {
                pendingDeletionDragons.remove(dragonId);
            }
            cleanupDone.complete(null);
        }
        return cleanupDone;
    }

    private Location arenaAnchor(World world, double yOffset) {
        RegionConfig.Point3D center = configManager.pluginConfig().regions().arena().center();
        return new Location(world, center.x(), center.y() + yOffset, center.z(), 0.0f, 0.0f);
    }

    private void cancelMovementTask() {
        cancelHandle(this.movementTask);
        this.movementTask = null;
    }

    private static void cancelHandle(@Nullable TaskHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    private static double dragonMaxHealthValue(EnderDragon dragon, double fallback) {
        if (Bukkit.getServer() != null) {
            AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                return attr.getValue();
            }
        }
        return fallback > 0.0 ? fallback : DEFAULT_FALLBACK_MAX_HP;
    }

    private static @Nullable EnderDragon findLivingDragonDefault(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle != null) {
            EnderDragon dragon = battle.getEnderDragon();
            if (dragon != null && !dragon.isDead()) {
                return dragon;
            }
        }
        Collection<EnderDragon> dragons = world.getEntitiesByClass(EnderDragon.class);
        for (EnderDragon d : dragons) {
            if (d != null && !d.isDead()) {
                return d;
            }
        }
        return null;
    }
}
