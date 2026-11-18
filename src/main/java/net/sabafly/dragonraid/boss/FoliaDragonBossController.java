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
import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import net.sabafly.dragonraid.api.enemy.ModelRendererType;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.api.model.ActorModelService;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.model.DefaultActorModelService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.script.BuiltInEncounters;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe {@link DragonBossController} keeping dragon AI active (`setAI(true)`) so wing-flapping
 * and body articulation look natural, while steering flight, ascents, high-speed dives, and laser
 * hover orientation via velocity impulses and controlled phase transitions.
 */
public final class FoliaDragonBossController implements DragonBossController {

    private static final double ORBIT_RADIUS = 22.0;
    private static final double LOW_ORBIT_Y_OFFSET = 10.0;
    private static final double CENTER_LANDED_Y_OFFSET = 2.0;
    private static final double HIGH_HOVER_Y_OFFSET = 18.0;
    private static final double ASCEND_CHARGE_Y_OFFSET = 28.0;
    private static final double STAGGERED_Y_OFFSET = 1.5;
    private static final double ORBIT_ANGULAR_SPEED_RAD_PER_TICK = 0.045;
    private static final double MAX_MOVE_STEP_PER_TICK = 2.6;
    private static final double DIVE_STEP_PER_TICK = 4.2;
    private static final double DEFAULT_FALLBACK_MAX_HP = 1200.0;

    private final Object lock = new Object();
    private final ConfigManager configManager;
    private final RaidScheduler scheduler;
    private final Logger logger;
    private final Function<World, @Nullable EnderDragon> dragonFinder;
    private final DragonSpawner dragonSpawner;
    private final ActorModelService modelService;

    private volatile ActorModelSpec modelSpec =
            BuiltInEncounters.normalEncounter().primaryBossDefinition().modelSpec();
    private volatile @Nullable ActorModelController modelController;
    private volatile @Nullable EnderDragon activeDragon;
    private volatile @Nullable UUID capturedFlagsDragonId;
    private volatile boolean summonedByPlugin;
    private volatile boolean previousAi = true;
    private volatile boolean previousGravity = true;
    private volatile DragonPosture targetPosture = DragonPosture.LOW_ORBIT;
    private volatile @Nullable DragonPosture preStaggerPosture;
    private volatile double staggerRemainingSeconds;
    private volatile double diveWindupRemainingSeconds;
    private volatile @Nullable Location diveTargetLocation;
    private volatile @Nullable Float customHoverYaw;
    private volatile @Nullable Float customHoverPitch;
    private volatile @Nullable RegionConfig.Point3D customArenaCenter;
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
                (world, loc) -> world.spawn(loc, EnderDragon.class),
                null
        );
    }

    public FoliaDragonBossController(
            ConfigManager configManager,
            RaidScheduler scheduler,
            Logger logger,
            @Nullable ActorModelService modelService) {
        this(
                configManager,
                scheduler,
                logger,
                FoliaDragonBossController::findLivingDragonDefault,
                (world, loc) -> world.spawn(loc, EnderDragon.class),
                modelService
        );
    }

    FoliaDragonBossController(
            ConfigManager configManager,
            RaidScheduler scheduler,
            Logger logger,
            Function<World, @Nullable EnderDragon> dragonFinder,
            DragonSpawner dragonSpawner) {
        this(configManager, scheduler, logger, dragonFinder, dragonSpawner, null);
    }

    FoliaDragonBossController(
            ConfigManager configManager,
            RaidScheduler scheduler,
            Logger logger,
            Function<World, @Nullable EnderDragon> dragonFinder,
            DragonSpawner dragonSpawner,
            @Nullable ActorModelService modelService) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dragonFinder = Objects.requireNonNull(dragonFinder, "dragonFinder");
        this.dragonSpawner = Objects.requireNonNull(dragonSpawner, "dragonSpawner");
        this.modelService = modelService != null ? modelService : new DefaultActorModelService(logger);
    }

    @Override
    public void setModelSpec(ActorModelSpec modelSpec) {
        this.modelSpec = Objects.requireNonNull(modelSpec, "modelSpec");
    }

    @Override
    public @Nullable ActorModelController activeModelController() {
        return modelController;
    }

    @Override
    public CompletableFuture<Void> bindOrSpawn(World endWorld, double scaledMaxHp) {
        return bindOrSpawn(endWorld, null, scaledMaxHp);
    }

    @Override
    public CompletableFuture<Void> bindOrSpawn(
            World endWorld,
            @Nullable Location customCenter,
            double scaledMaxHp) {
        Objects.requireNonNull(endWorld, "endWorld");
        double validMaxHp = Math.max(1.0, scaledMaxHp);
        RegionConfig.Point3D explicitCenter = customCenter != null
                ? new RegionConfig.Point3D(customCenter.getX(), customCenter.getY(), customCenter.getZ())
                : null;

        final long bindGeneration;
        final Location anchor;
        synchronized (lock) {
            cancelMovementTask();
            bindGeneration = generation.incrementAndGet();
            this.customArenaCenter = explicitCenter;
            anchor = arenaAnchor(endWorld, LOW_ORBIT_Y_OFFSET);
            this.maxHp = validMaxHp;
            this.currentHp = validMaxHp;
            this.targetPosture = DragonPosture.LOW_ORBIT;
            this.preStaggerPosture = null;
            this.staggerRemainingSeconds = 0.0;
            this.diveWindupRemainingSeconds = 0.0;
            this.diveTargetLocation = null;
            this.customHoverYaw = null;
            this.customHoverPitch = null;
            this.orbitAngleRad = 0.0;
            this.activeCombat = true;
            this.cachedLocation = anchor.clone();
        }

        EnderDragon existing = findEligibleDragon(endWorld);
        if (existing != null) {
            return bindEntity(existing, false, validMaxHp, true, anchor, bindGeneration, true);
        }

        CompletableFuture<Void> readyFuture = new CompletableFuture<>();
        TaskHandle regionHandle = scheduler.runAtRegion(anchor, () -> {
            if (generation.get() != bindGeneration || !activeCombat) {
                readyFuture.complete(null);
                return;
            }
            EnderDragon rechecked = findEligibleDragon(endWorld);
            if (rechecked != null) {
                bindEntity(rechecked, false, validMaxHp, true, anchor, bindGeneration, true)
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
                bindEntity(spawned, true, validMaxHp, true, anchor, bindGeneration, true)
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
        bindEntity(existing, false, 0.0, false, anchor, holdGeneration, false);
    }

    private CompletableFuture<Void> bindEntity(
            EnderDragon dragon,
            boolean wasSummoned,
            double targetMaxHp,
            boolean applyHealth,
            Location initialAnchor,
            long expectedGeneration,
            boolean expectCombat) {
        synchronized (lock) {
            if (generation.get() != expectedGeneration) {
                if (wasSummoned) {
                    removeSummonedOnEntityThread(dragon);
                }
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
                    if (wasSummoned) {
                        dragon.remove();
                    }
                    entityReady.complete(null);
                }
                return;
            }
            synchronized (lock) {
                if (this.activeDragon != dragon || generation.get() != expectedGeneration) {
                    if (wasSummoned) {
                        dragon.remove();
                    }
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
            configureAnimatedDragon(dragon, initialAnchor.getWorld());
            ActorModelController candidateModel = modelService.bindEntity(dragon, this.modelSpec);
            ActorModelController prevModel = null;
            boolean validBind;
            synchronized (lock) {
                validBind = this.activeDragon == dragon
                        && generation.get() == expectedGeneration
                        && this.activeCombat == expectCombat;
                if (validBind) {
                    prevModel = this.modelController;
                    this.modelController = candidateModel;
                    syncModelPosture(this.modelController, effectivePostureLocked());
                }
            }
            if (!validBind) {
                candidateModel.close();
                if (wasSummoned) {
                    dragon.remove();
                }
                entityReady.complete(null);
                return;
            }
            if (prevModel != null) {
                prevModel.close();
            }
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

    /**
     * Enables dragon AI (`setAI(true)`) so the client animates wings, neck, and tail naturally,
     * while anchoring the podium to the arena center.
     */
    private void configureAnimatedDragon(EnderDragon dragon, @Nullable World world) {
        dragon.setAI(true);
        dragon.setGravity(false);
        dragon.setPhase(EnderDragon.Phase.CIRCLING);
        if (world != null) {
            RegionConfig.Point3D center = arenaCenterFor(world);
            dragon.setPodium(new Location(world, center.x(), center.y(), center.z()));
        }
        if (Bukkit.getServer() != null) {
            AttributeInstance kbAttr = dragon.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (kbAttr != null) {
                kbAttr.setBaseValue(1.0);
            }
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
        }, 1L, 1L);
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

        // Keep AI enabled so client wing/neck/tail animations never freeze!
        if (!dragon.hasAI()) {
            dragon.setAI(true);
        }

        DragonPosture effective = posture();
        EnderDragon.Phase desiredPhase = desiredVanillaPhase(effective);
        if (dragon.getPhase() != desiredPhase) {
            dragon.setPhase(desiredPhase);
        }

        Location current = dragon.getLocation();
        if (current == null || current.getWorld() == null) {
            return;
        }

        Location target = computeTargetLocation(world, current, effective);
        double stepSpeed = (effective == DragonPosture.DIVING || effective == DragonPosture.ASCENDING_CHARGE)
                ? DIVE_STEP_PER_TICK
                : MAX_MOVE_STEP_PER_TICK;
        Location next = stepTowards(current, target, stepSpeed);
        if (this.customHoverYaw == null) {
            double travelDx = target.getX() - current.getX();
            double travelDz = target.getZ() - current.getZ();
            if (travelDx * travelDx + travelDz * travelDz > 0.0625) {
                next.setYaw(computeYawDegrees(travelDx, travelDz));
            }
        }
        this.cachedLocation = next.clone();

        Vector velocity = new Vector(
                next.getX() - current.getX(),
                next.getY() - current.getY(),
                next.getZ() - current.getZ()
        );
        float entityYaw = toEntityYaw(next.getYaw());
        Location entityNext = next.clone();
        entityNext.setYaw(entityYaw);

        dragon.setVelocity(velocity);
        dragon.setRotation(entityYaw, entityNext.getPitch());
        dragon.teleportAsync(entityNext);
    }

    private float toEntityYaw(float logicalYaw) {
        ActorModelSpec spec = this.modelSpec;
        ActorModelController activeModel = this.modelController;
        ModelRendererType effectiveRenderer = activeModel != null ? activeModel.rendererType() : spec.rendererType();
        float rendererOffset = effectiveRenderer == ModelRendererType.VANILLA ? 180.0f : 0.0f;
        return normalizeYaw(logicalYaw + rendererOffset + (float) spec.yawOffsetDegrees());
    }

    static float normalizeYaw(float yaw) {
        float wrapped = yaw % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        } else if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    private static EnderDragon.Phase desiredVanillaPhase(DragonPosture posture) {
        return switch (posture) {
            case LOW_ORBIT -> EnderDragon.Phase.CIRCLING;
            case ASCENDING_CHARGE, DIVING -> EnderDragon.Phase.CHARGE_PLAYER;
            case HIGH_HOVER -> EnderDragon.Phase.SEARCH_FOR_BREATH_ATTACK_TARGET;
            case CENTER_LANDED, STAGGERED -> EnderDragon.Phase.HOVER;
        };
    }

    private Location computeTargetLocation(World world, Location current, DragonPosture effective) {
        RegionConfig.Point3D center = arenaCenterFor(world);
        Location fallbackLoc = this.cachedLocation;
        float fallbackLogicalYaw = fallbackLoc != null ? fallbackLoc.getYaw() : 0.0f;
        return switch (effective) {
            case LOW_ORBIT -> {
                orbitAngleRad = (orbitAngleRad + ORBIT_ANGULAR_SPEED_RAD_PER_TICK) % (Math.PI * 2.0);
                double x = center.x() + Math.cos(orbitAngleRad) * ORBIT_RADIUS;
                double z = center.z() + Math.sin(orbitAngleRad) * ORBIT_RADIUS;
                double y = center.y() + LOW_ORBIT_Y_OFFSET + Math.sin(orbitAngleRad * 2.0) * 2.5;
                double tangentX = -Math.sin(orbitAngleRad);
                double tangentZ = Math.cos(orbitAngleRad);
                float yaw = computeYawDegrees(tangentX, tangentZ);
                yield new Location(world, x, y, z, yaw, 0.0f);
            }
            case ASCENDING_CHARGE -> {
                Location diveTarget = this.diveTargetLocation;
                float yaw = diveTarget != null
                        ? computeYawDegrees(diveTarget.getX() - current.getX(), diveTarget.getZ() - current.getZ())
                        : fallbackLogicalYaw;
                yield new Location(
                        world,
                        current.getX() * 0.85 + center.x() * 0.15,
                        center.y() + ASCEND_CHARGE_Y_OFFSET,
                        current.getZ() * 0.85 + center.z() * 0.15,
                        yaw,
                        -35.0f
                );
            }
            case DIVING -> {
                Location diveTarget = this.diveTargetLocation;
                if (diveTarget != null && diveTarget.getWorld() != null && diveTarget.getWorld().equals(world)) {
                    double dx = diveTarget.getX() - current.getX();
                    double dz = diveTarget.getZ() - current.getZ();
                    float yaw = (dx * dx + dz * dz) > 0.0625 ? computeYawDegrees(dx, dz) : fallbackLogicalYaw;
                    yield new Location(
                            world,
                            diveTarget.getX(),
                            diveTarget.getY() + 1.5,
                            diveTarget.getZ(),
                            yaw,
                            55.0f
                    );
                }
                yield new Location(world, center.x(), center.y() + CENTER_LANDED_Y_OFFSET, center.z(), fallbackLogicalYaw, 45.0f);
            }
            case CENTER_LANDED -> new Location(
                    world,
                    center.x(),
                    center.y() + CENTER_LANDED_Y_OFFSET,
                    center.z(),
                    customHoverYaw != null ? customHoverYaw : fallbackLogicalYaw,
                    customHoverPitch != null ? customHoverPitch : 10.0f
            );
            case HIGH_HOVER -> new Location(
                    world,
                    center.x(),
                    center.y() + HIGH_HOVER_Y_OFFSET,
                    center.z(),
                    customHoverYaw != null ? customHoverYaw : fallbackLogicalYaw,
                    customHoverPitch != null ? customHoverPitch : 25.0f
            );
            case STAGGERED -> new Location(
                    world,
                    center.x(),
                    center.y() + STAGGERED_Y_OFFSET,
                    center.z(),
                    fallbackLogicalYaw,
                    30.0f
            );
        };
    }

    private RegionConfig.Point3D arenaCenterFor(World world) {
        RegionConfig.Point3D explicit = this.customArenaCenter;
        if (explicit != null) {
            return explicit;
        }
        return configManager.pluginConfig().regions().arena().center();
    }

    private void removeSummonedOnEntityThread(EnderDragon dragon) {
        if (dragon == null || dragon.isDead()) {
            return;
        }
        if (Bukkit.getServer() != null && Bukkit.isOwnedByCurrentRegion(dragon)) {
            dragon.remove();
            return;
        }
        scheduler.runAtEntity(dragon, () -> {
            if (!dragon.isDead()) {
                dragon.remove();
            }
        });
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
            if (posture != DragonPosture.DIVING && posture != DragonPosture.ASCENDING_CHARGE) {
                this.diveWindupRemainingSeconds = 0.0;
                this.diveTargetLocation = null;
            }
            if (posture != DragonPosture.HIGH_HOVER && posture != DragonPosture.CENTER_LANDED) {
                this.customHoverYaw = null;
                this.customHoverPitch = null;
            }
            if (staggerRemainingSeconds > 0.0 && posture != DragonPosture.STAGGERED) {
                this.preStaggerPosture = posture;
                return;
            }
            this.targetPosture = posture;
            if (posture != DragonPosture.STAGGERED) {
                this.preStaggerPosture = null;
            }
            syncModelPosture(this.modelController, effectivePostureLocked());
        }
    }

    @Override
    public void beginDive(Location diveTargetLocation, double windupSeconds) {
        Objects.requireNonNull(diveTargetLocation, "diveTargetLocation");
        synchronized (lock) {
            this.diveTargetLocation = diveTargetLocation.clone();
            this.diveWindupRemainingSeconds = Math.max(0.0, windupSeconds);
            DragonPosture next = this.diveWindupRemainingSeconds > 0.0
                    ? DragonPosture.ASCENDING_CHARGE
                    : DragonPosture.DIVING;
            if (staggerRemainingSeconds > 0.0) {
                this.preStaggerPosture = next;
            } else {
                this.targetPosture = next;
                syncModelPosture(this.modelController, effectivePostureLocked());
            }
        }
    }

    @Override
    public void setHoverAim(float aimYawDegrees, float aimPitchDegrees) {
        synchronized (lock) {
            this.customHoverYaw = aimYawDegrees;
            this.customHoverPitch = aimPitchDegrees;
        }
    }

    @Override
    public DragonPosture posture() {
        synchronized (lock) {
            return effectivePostureLocked();
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
            this.diveWindupRemainingSeconds = 0.0;
            syncModelPosture(this.modelController, effectivePostureLocked());
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
            DragonPosture before = effectivePostureLocked();
            if (staggerRemainingSeconds > 1e-6) {
                staggerRemainingSeconds = Math.max(0.0, staggerRemainingSeconds - deltaSeconds);
                if (staggerRemainingSeconds <= 1e-6) {
                    staggerRemainingSeconds = 0.0;
                    targetPosture = preStaggerPosture != null ? preStaggerPosture : DragonPosture.LOW_ORBIT;
                    preStaggerPosture = null;
                }
            }
            if (diveWindupRemainingSeconds > 1e-6) {
                diveWindupRemainingSeconds = Math.max(0.0, diveWindupRemainingSeconds - deltaSeconds);
                if (diveWindupRemainingSeconds <= 1e-6 && targetPosture == DragonPosture.ASCENDING_CHARGE) {
                    diveWindupRemainingSeconds = 0.0;
                    targetPosture = DragonPosture.DIVING;
                }
            }
            DragonPosture after = effectivePostureLocked();
            if (after != before) {
                syncModelPosture(this.modelController, after);
            }
        }
    }

    private DragonPosture effectivePostureLocked() {
        if (staggerRemainingSeconds > 0.0) {
            return DragonPosture.STAGGERED;
        }
        if (targetPosture == DragonPosture.ASCENDING_CHARGE && diveWindupRemainingSeconds <= 1e-6) {
            return DragonPosture.DIVING;
        }
        return targetPosture;
    }

    private static void syncModelPosture(
            @Nullable ActorModelController model,
            @Nullable DragonPosture effectivePosture) {
        if (model != null && effectivePosture != null) {
            model.setPosture(effectivePosture);
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
        ActorModelController detachedModel;
        synchronized (lock) {
            defeatGen = generation.incrementAndGet();
            cancelMovementTask();
            dragon = this.activeDragon;
            prevAi = this.previousAi;
            prevGravity = this.previousGravity;
            this.capturedFlagsDragonId = null;
            this.activeCombat = false;
            this.staggerRemainingSeconds = 0.0;
            this.diveWindupRemainingSeconds = 0.0;
            this.diveTargetLocation = null;
            this.preStaggerPosture = null;
            detachedModel = this.modelController;
            this.modelController = null;
        }
        if (detachedModel != null) {
            detachedModel.close();
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
        ActorModelController detachedModel;
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
            this.diveWindupRemainingSeconds = 0.0;
            this.diveTargetLocation = null;
            this.customHoverYaw = null;
            this.customHoverPitch = null;
            this.customArenaCenter = null;
            this.preStaggerPosture = null;
            this.targetPosture = DragonPosture.LOW_ORBIT;
            this.cachedLocation = null;
            detachedModel = this.modelController;
            this.modelController = null;
        }
        if (detachedModel != null) {
            detachedModel.close();
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
        RegionConfig.Point3D center = arenaCenterFor(world);
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
