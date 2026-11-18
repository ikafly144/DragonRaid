package net.sabafly.dragonraid.gimmick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.instance.CircularArenaBuilder;
import net.sabafly.dragonraid.instance.TheNextLvlRaidInstanceManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
import net.sabafly.dragonraid.visual.TelegraphStyle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.jetbrains.annotations.Nullable;

/**
 * Manages raid End Crystals placed on the low obsidian pillars around the circular arena.
 * <ul>
 *   <li>Scans and binds existing vanilla {@link EnderCrystal}s on the obsidian pillars first,
 *       then spawns any remaining crystals on unoccupied obsidian pillar tops.</li>
 *   <li>One-hit destruction: any player hit shatters the crystal immediately (without terrain/player
 *       explosion grief).</li>
 *   <li>Active crystal defenses: periodic lightning AoE rings around active pillars and
 *       player-tracking beam tethers that must be kited to the outer perimeter.</li>
 *   <li>When all crystals in a wave are destroyed, triggers a True Ending-style shatter sound
 *       sequence and staggers the dragon.</li>
 * </ul>
 */
public final class RaidCrystalManager {

    private static final double PILLAR_LIGHTNING_INTERVAL_SECONDS = 2.6;
    private static final double PILLAR_LIGHTNING_WARN_SECONDS = 1.2;
    private static final double PILLAR_LIGHTNING_RADIUS = 3.2;
    private static final double PILLAR_LIGHTNING_DAMAGE = 10.0;

    private static final double TRACKING_BEAM_INTERVAL_SECONDS = 6.0;
    private static final double TRACKING_BEAM_WARN_SECONDS = 2.2;
    private static final double TRACKING_BEAM_WIDTH = 2.0;
    private static final double TRACKING_BEAM_DAMAGE = 14.0;

    private final ConfigManager configManager;
    private final RaidScheduler scheduler;
    private final SpatialTelegraphService telegraphService;
    private final Logger logger;
    private final CrystalSpawner crystalSpawner;
    private final BiConsumer<Player, Double> damageApplier;

    private final Object spawnLock = new Object();
    private final Map<UUID, TrackedCrystal> trackedCrystals = new ConcurrentHashMap<>();
    private final Set<UUID> pendingCrystalIds = new HashSet<>();
    private final List<PendingLightning> pendingLightnings = new ArrayList<>();
    private final List<PendingCrystalBeam> pendingBeams = new ArrayList<>();
    private final AtomicInteger waveCounter = new AtomicInteger(0);
    private final AtomicInteger generation = new AtomicInteger(0);
    private final AtomicLong telegraphIdSeq = new AtomicLong(0L);
    private int pendingSpawns;
    private volatile Collection<? extends Player> lastViewers = List.of();
    private volatile double bossRegenPerSecond;
    private volatile double lightningTimerSeconds;
    private volatile double beamTimerSeconds;
    private volatile @Nullable Runnable onAllCrystalsDestroyedCallback;

    @FunctionalInterface
    interface CrystalSpawner {
        EnderCrystal spawn(World world, Location location);
    }

    public RaidCrystalManager(
            ConfigManager configManager,
            RaidScheduler scheduler,
            SpatialTelegraphService telegraphService,
            Logger logger) {
        this(
                configManager,
                scheduler,
                telegraphService,
                logger,
                (world, loc) -> world.spawn(loc, EnderCrystal.class),
                (player, amount) -> player.damage(amount)
        );
    }

    RaidCrystalManager(
            ConfigManager configManager,
            RaidScheduler scheduler,
            SpatialTelegraphService telegraphService,
            Logger logger,
            CrystalSpawner crystalSpawner) {
        this(configManager, scheduler, telegraphService, logger, crystalSpawner, (player, amount) -> player.damage(amount));
    }

    RaidCrystalManager(
            ConfigManager configManager,
            RaidScheduler scheduler,
            SpatialTelegraphService telegraphService,
            Logger logger,
            CrystalSpawner crystalSpawner,
            BiConsumer<Player, Double> damageApplier) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telegraphService = Objects.requireNonNull(telegraphService, "telegraphService");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.crystalSpawner = Objects.requireNonNull(crystalSpawner, "crystalSpawner");
        this.damageApplier = Objects.requireNonNull(damageApplier, "damageApplier");
    }

    /**
     * Registers a callback invoked when the last living crystal of a wave is shattered.
     */
    public void setOnAllCrystalsDestroyedCallback(@Nullable Runnable callback) {
        this.onAllCrystalsDestroyedCallback = callback;
    }

    /**
     * Binds pre-existing End Crystals in {@code world} and spawns additional crystals on the
     * obsidian pillar tops up to {@code targetCount}.
     */
    public void spawnWave(
            World world,
            int targetCount,
            double crystalMaxHp,
            double regenPerSecond) {
        Objects.requireNonNull(world, "world");
        pruneDead();

        final int toSpawn;
        final int expectedGen;
        synchronized (spawnLock) {
            this.bossRegenPerSecond = Math.max(0.0, regenPerSecond);
            expectedGen = generation.get();
            // 1. Schedule binding of any existing untracked vanilla EnderCrystals on their owning entity threads.
            bindExistingCrystalsInWorldLocked(world, targetCount, expectedGen);
            int existing = trackedCrystals.size() + pendingSpawns;
            toSpawn = Math.max(0, targetCount - existing);
            if (toSpawn == 0) {
                return;
            }
            pendingSpawns += toSpawn;
        }

        List<Location> pillarCandidates = computeObsidianPillarTops(world, Math.max(8, targetCount));
        int offset = waveCounter.getAndIncrement();

        for (int i = 0; i < toSpawn; i++) {
            Location candidate = pillarCandidates.get((offset + i) % pillarCandidates.size());
            TaskHandle scheduled = scheduler.runAtRegion(candidate, () -> {
                try {
                    if (generation.get() != expectedGen) {
                        return;
                    }
                    Location spawnLoc = resolveObsidianPillarTop(world, candidate);
                    EnderCrystal crystal = crystalSpawner.spawn(world, spawnLoc);
                    crystal.setShowingBottom(true);
                    crystal.setBeamTarget(new Location(world, spawnLoc.getX(), spawnLoc.getY() + 10.0, spawnLoc.getZ()));
                    crystal.setCustomNameVisible(true);
                    updateCrystalLabel(crystal);
                    synchronized (spawnLock) {
                        if (generation.get() != expectedGen) {
                            crystal.remove();
                            return;
                        }
                        trackedCrystals.put(
                                crystal.getUniqueId(),
                                new TrackedCrystal(crystal, spawnLoc.clone())
                        );
                    }
                } catch (RuntimeException ex) {
                    logger.log(Level.WARNING, "Failed to spawn raid EndCrystal at " + candidate, ex);
                } finally {
                    synchronized (spawnLock) {
                        if (generation.get() == expectedGen && pendingSpawns > 0) {
                            pendingSpawns--;
                        }
                    }
                }
            });
            if (scheduled == null) {
                synchronized (spawnLock) {
                    if (generation.get() == expectedGen && pendingSpawns > 0) {
                        pendingSpawns--;
                    }
                }
            }
        }
    }

    private void bindExistingCrystalsInWorldLocked(World world, int maxTotal, int expectedGen) {
        Collection<EnderCrystal> existingInWorld;
        try {
            existingInWorld = world.getEntitiesByClass(EnderCrystal.class);
        } catch (RuntimeException ignored) {
            return;
        }
        if (existingInWorld == null || existingInWorld.isEmpty()) {
            return;
        }
        for (EnderCrystal crystal : existingInWorld) {
            if (crystal == null) {
                continue;
            }
            UUID crystalId = crystal.getUniqueId();
            if (crystalId == null
                    || trackedCrystals.containsKey(crystalId)
                    || pendingCrystalIds.contains(crystalId)) {
                continue;
            }
            if (trackedCrystals.size() + pendingSpawns >= maxTotal) {
                break;
            }
            pendingCrystalIds.add(crystalId);
            pendingSpawns++;
            Runnable bindTask = () -> {
                try {
                    if (generation.get() != expectedGen || crystal.isDead()) {
                        return;
                    }
                    Location loc = crystal.getLocation();
                    if (loc == null) {
                        return;
                    }
                    crystal.setCustomNameVisible(true);
                    updateCrystalLabel(crystal);
                    synchronized (spawnLock) {
                        if (generation.get() != expectedGen) {
                            return;
                        }
                        trackedCrystals.putIfAbsent(crystalId, new TrackedCrystal(crystal, loc.clone()));
                    }
                } finally {
                    releasePendingCrystal(crystalId, expectedGen);
                }
            };
            TaskHandle handle = scheduler.runAtEntity(
                    crystal,
                    bindTask,
                    () -> releasePendingCrystal(crystalId, expectedGen)
            );
            if (handle == null) {
                releasePendingCrystalLocked(crystalId, expectedGen);
            }
        }
    }

    private void releasePendingCrystal(UUID crystalId, int expectedGen) {
        synchronized (spawnLock) {
            releasePendingCrystalLocked(crystalId, expectedGen);
        }
    }

    private void releasePendingCrystalLocked(UUID crystalId, int expectedGen) {
        if (generation.get() == expectedGen) {
            if (pendingCrystalIds.remove(crystalId) && pendingSpawns > 0) {
                pendingSpawns--;
            }
        }
    }

    private List<Location> computeObsidianPillarTops(World world, int count) {
        if (TheNextLvlRaidInstanceManager.isManagedInstanceWorldName(world.getName())) {
            List<Location> instancePillars = new ArrayList<>(CircularArenaBuilder.DEFAULT_PILLAR_COUNT);
            for (int i = 0; i < CircularArenaBuilder.DEFAULT_PILLAR_COUNT; i++) {
                double angle = (2.0 * Math.PI * i) / CircularArenaBuilder.DEFAULT_PILLAR_COUNT;
                int px = (int) Math.round(Math.cos(angle) * CircularArenaBuilder.DEFAULT_PILLAR_RING_RADIUS);
                int pz = (int) Math.round(Math.sin(angle) * CircularArenaBuilder.DEFAULT_PILLAR_RING_RADIUS);
                int topY = CircularArenaBuilder.DEFAULT_FLOOR_Y + CircularArenaBuilder.DEFAULT_PILLAR_HEIGHT;
                instancePillars.add(new Location(world, px + 0.5, topY + 1.0, pz + 0.5));
            }
            return instancePillars;
        }

        // Standard End or custom arena: 8-10 evenly spaced pillar probes around arena center
        RegionConfig.ArenaRegion arena = configManager.pluginConfig().regions().arena();
        RegionConfig.Point3D center = arena.center();
        double ringRadius = Math.min(31.0, Math.max(12.0, arena.radius() * 0.45));
        List<Location> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / count;
            double x = center.x() + Math.cos(angle) * ringRadius;
            double z = center.z() + Math.sin(angle) * ringRadius;
            list.add(new Location(world, x, center.y() + CircularArenaBuilder.DEFAULT_PILLAR_HEIGHT + 1.0, z));
        }
        return list;
    }

    /**
     * Returns {@code true} if {@code entity} is an active raid-managed crystal.
     */
    public boolean isTrackedCrystal(@Nullable Entity entity) {
        return entity != null && trackedCrystals.containsKey(entity.getUniqueId());
    }

    public enum CrystalDamageResult {
        IGNORED,
        CANCELLED_NO_BREAK,
        SHATTERED
    }

    /**
     * One-hit shatters a tracked crystal when struck by a player or player projectile, suppressing
     * terrain/self explosion grief and firing the all-crystals-destroyed stagger effect when the last
     * crystal falls.
     *
     * @return {@code true} if the entity was a tracked raid crystal and the vanilla hit should be cancelled
     */
    public boolean onCrystalDamage(
            EnderCrystal crystal,
            double damage,
            @Nullable Entity damager) {
        return handleCrystalDamage(crystal, damage, damager) != CrystalDamageResult.IGNORED;
    }

    /**
     * Evaluates incoming damage against a tracked crystal, distinguishing unhandled crystals
     * ({@link CrystalDamageResult#IGNORED}), cancelled zero/already-removed hits
     * ({@link CrystalDamageResult#CANCELLED_NO_BREAK}), and actual one-hit destruction
     * ({@link CrystalDamageResult#SHATTERED}).
     */
    public CrystalDamageResult handleCrystalDamage(
            EnderCrystal crystal,
            double damage,
            @Nullable Entity damager) {
        Objects.requireNonNull(crystal, "crystal");
        TrackedCrystal tracked = trackedCrystals.get(crystal.getUniqueId());
        if (tracked == null) {
            return CrystalDamageResult.IGNORED;
        }
        if (damage <= 0.0) {
            return CrystalDamageResult.CANCELLED_NO_BREAK;
        }
        synchronized (tracked) {
            if (tracked.removed) {
                return CrystalDamageResult.CANCELLED_NO_BREAK;
            }
            tracked.removed = true;
        }

        trackedCrystals.remove(crystal.getUniqueId(), tracked);
        crystal.remove();
        boolean allDestroyed = trackedCrystals.isEmpty();

        World world = crystal.getWorld();
        Collection<? extends Player> cleanupViewers = resolveCleanupViewers(world);
        if (allDestroyed) {
            telegraphService.clearTelegraphsByPrefix("crystal:", cleanupViewers);
        } else {
            telegraphService.clearTelegraph("crystal:tether:" + crystal.getUniqueId(), cleanupViewers);
        }
        if (world != null) {
            Location loc = tracked.location;
            for (Player p : world.getPlayers()) {
                if (p != null && p.isOnline()) {
                    scheduler.runAtEntity(p, () -> {
                        p.playSound(Sound.sound(
                                Key.key("minecraft:block.glass.break"),
                                Sound.Source.HOSTILE,
                                1.2f,
                                1.4f
                        ));
                        if (allDestroyed) {
                            // True Ending phase_end_crystals_destroyed sound sequence
                            p.playSound(Sound.sound(
                                    Key.key("minecraft:item.trident.return"),
                                    Sound.Source.HOSTILE,
                                    1.4f,
                                    0.75f
                            ));
                            p.playSound(Sound.sound(
                                    Key.key("minecraft:block.beacon.deactivate"),
                                    Sound.Source.HOSTILE,
                                    1.2f,
                                    0.66f
                            ));
                        }
                    });
                }
            }
        }
        Player attacker = resolvePlayerAttacker(damager);
        if (attacker != null && attacker.isOnline()) {
            scheduler.runAtEntity(attacker, () -> attacker.playSound(Sound.sound(
                    Key.key("minecraft:entity.experience_orb.pickup"),
                    Sound.Source.PLAYER,
                    0.9f,
                    1.3f
            )));
        }
        if (allDestroyed) {
            Runnable callback = this.onAllCrystalsDestroyedCallback;
            if (callback != null) {
                callback.run();
            }
        }
        return CrystalDamageResult.SHATTERED;
    }

    /**
     * Ticks active crystals, renders tether lines to the boss, executes active crystal defenses
     * (pillar lightning AoEs & outer-perimeter tracking beams), and returns total HP regenerated
     * during {@code deltaSeconds}.
     */
    public double tick(
            double deltaSeconds,
            @Nullable Location bossLocation,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(viewers, "viewers");
        this.lastViewers = List.copyOf(viewers);
        pruneDead();
        if (trackedCrystals.isEmpty()) {
            synchronized (spawnLock) {
                pendingLightnings.clear();
                pendingBeams.clear();
            }
            telegraphService.clearTelegraphsByPrefix("crystal:", viewers);
            return 0.0;
        }
        int activeCount = trackedCrystals.size();
        if (bossLocation != null && bossLocation.getWorld() != null) {
            for (Map.Entry<UUID, TrackedCrystal> entry : trackedCrystals.entrySet()) {
                TrackedCrystal tracked = entry.getValue();
                if (tracked.removed) {
                    continue;
                }
                telegraphService.renderTetherLine(
                        "crystal:tether:" + entry.getKey(),
                        tracked.location,
                        bossLocation,
                        TelegraphStyle.TETHER,
                        viewers
                );
            }
        } else {
            telegraphService.clearTelegraphsByPrefix("crystal:tether:", viewers);
        }
        if (deltaSeconds > 0.0) {
            tickCrystalDefenses(deltaSeconds, viewers);
        }
        if (deltaSeconds <= 0.0 || bossRegenPerSecond <= 0.0) {
            return 0.0;
        }
        return activeCount * bossRegenPerSecond * deltaSeconds;
    }

    private void tickCrystalDefenses(double deltaSeconds, Collection<? extends Player> viewers) {
        List<Player> alivePlayers = new ArrayList<>();
        for (Player p : viewers) {
            if (p != null && p.isOnline() && !p.isDead()) {
                alivePlayers.add(p);
            }
        }
        if (alivePlayers.isEmpty()) {
            return;
        }

        // 1. Pillar-area continuous lightning AoE defense
        lightningTimerSeconds += deltaSeconds;
        if (lightningTimerSeconds + 1e-6 >= PILLAR_LIGHTNING_INTERVAL_SECONDS) {
            lightningTimerSeconds = 0.0;
            for (TrackedCrystal crystal : trackedCrystals.values()) {
                if (crystal.removed || crystal.location.getWorld() == null) {
                    continue;
                }
                double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
                double dist = ThreadLocalRandom.current().nextDouble(2.5, 9.0);
                double sx = crystal.location.getX() + Math.cos(angle) * dist;
                double sz = crystal.location.getZ() + Math.sin(angle) * dist;
                double groundY = resolveFloorSurfaceY(
                        crystal.location.getWorld(),
                        Math.max(CircularArenaBuilder.DEFAULT_SURFACE_Y, crystal.location.getY() - CircularArenaBuilder.DEFAULT_PILLAR_HEIGHT - 1.0)
                );
                Location strikeLoc = new Location(crystal.location.getWorld(), sx, groundY, sz);
                snapLocationToGroundAsync(strikeLoc);
                synchronized (spawnLock) {
                    pendingLightnings.add(new PendingLightning(telegraphIdSeq.incrementAndGet(), strikeLoc, PILLAR_LIGHTNING_WARN_SECONDS));
                }
            }
        }

        // 2. Crystal-to-player tracking beam defense (kite to outer perimeter)
        beamTimerSeconds += deltaSeconds;
        if (beamTimerSeconds + 1e-6 >= TRACKING_BEAM_INTERVAL_SECONDS) {
            beamTimerSeconds = 0.0;
            List<TrackedCrystal> crystals = new ArrayList<>(trackedCrystals.values());
            if (!crystals.isEmpty()) {
                TrackedCrystal shooter = crystals.get(ThreadLocalRandom.current().nextInt(crystals.size()));
                Player target = alivePlayers.get(ThreadLocalRandom.current().nextInt(alivePlayers.size()));
                Location targetSnap = target.getLocation();
                if (targetSnap != null && shooter.location.getWorld() != null
                        && shooter.location.getWorld().equals(targetSnap.getWorld())) {
                    synchronized (spawnLock) {
                        pendingBeams.add(new PendingCrystalBeam(
                                telegraphIdSeq.incrementAndGet(),
                                shooter.location.clone(),
                                target.getUniqueId(),
                                targetSnap.clone(),
                                TRACKING_BEAM_WARN_SECONDS
                        ));
                    }
                }
            }
        }

        // Advance pending lightning strikes
        List<PendingLightning> readyLightnings = new ArrayList<>();
        synchronized (spawnLock) {
            var it = pendingLightnings.iterator();
            while (it.hasNext()) {
                PendingLightning pl = it.next();
                pl.remainingSeconds -= deltaSeconds;
                if (pl.remainingSeconds <= 1e-6) {
                    readyLightnings.add(pl);
                    it.remove();
                } else {
                    double progress = Math.min(1.0, Math.max(0.0, 1.0 - (pl.remainingSeconds / pl.totalSeconds)));
                    telegraphService.renderCircleAoE(
                            "crystal:lightning:" + pl.id,
                            pl.location,
                            PILLAR_LIGHTNING_RADIUS,
                            progress,
                            TelegraphStyle.WARNING,
                            viewers
                    );
                }
            }
        }
        final int expectedGen = generation.get();
        for (PendingLightning strike : readyLightnings) {
            telegraphService.clearTelegraph("crystal:lightning:" + strike.id, viewers);
            telegraphService.renderLightningStrike(strike.location, viewers);
            double rSq = PILLAR_LIGHTNING_RADIUS * PILLAR_LIGHTNING_RADIUS;
            for (Player p : alivePlayers) {
                Location pLoc = p.getLocation();
                if (pLoc != null && strike.location.getWorld() != null
                        && strike.location.getWorld().equals(pLoc.getWorld())
                        && horizontalDistSq(pLoc, strike.location) <= rSq) {
                    scheduler.runAtEntity(p, () -> {
                        if (generation.get() == expectedGen && p.isOnline()) {
                            damageApplier.accept(p, PILLAR_LIGHTNING_DAMAGE);
                        }
                    });
                }
            }
        }

        // Advance pending crystal tracking beams
        List<PendingCrystalBeam> readyBeams = new ArrayList<>();
        synchronized (spawnLock) {
            var it = pendingBeams.iterator();
            while (it.hasNext()) {
                PendingCrystalBeam beam = it.next();
                beam.remainingSeconds -= deltaSeconds;
                // Track target player position until final 0.5s lock-in so player can bait it outward
                if (beam.remainingSeconds > 0.5) {
                    for (Player p : alivePlayers) {
                        if (p.getUniqueId().equals(beam.targetPlayerId) && p.getLocation() != null) {
                            beam.lockedEndpoint = extrapolateBeamEndpoint(beam.crystalOrigin, p.getLocation(), 40.0);
                            break;
                        }
                    }
                }
                if (beam.remainingSeconds <= 1e-6) {
                    readyBeams.add(beam);
                    it.remove();
                } else {
                    double progress = Math.min(1.0, Math.max(0.0, 1.0 - (beam.remainingSeconds / beam.totalSeconds)));
                    telegraphService.renderLineAoE(
                            "crystal:beam:" + beam.id,
                            beam.crystalOrigin,
                            beam.lockedEndpoint,
                            TRACKING_BEAM_WIDTH,
                            progress,
                            TelegraphStyle.WARNING,
                            viewers
                    );
                }
            }
        }
        for (PendingCrystalBeam beam : readyBeams) {
            telegraphService.clearTelegraph("crystal:beam:" + beam.id, viewers);
            telegraphService.renderLaserBeam(beam.crystalOrigin, beam.lockedEndpoint, viewers);
            for (Player p : alivePlayers) {
                Location pLoc = p.getLocation();
                if (pLoc != null && beam.crystalOrigin.getWorld() != null
                        && beam.crystalOrigin.getWorld().equals(pLoc.getWorld())
                        && distanceToSegment2D(pLoc, beam.crystalOrigin, beam.lockedEndpoint) <= TRACKING_BEAM_WIDTH * 0.65) {
                    scheduler.runAtEntity(p, () -> {
                        if (generation.get() == expectedGen && p.isOnline()) {
                            damageApplier.accept(p, TRACKING_BEAM_DAMAGE);
                        }
                    });
                }
            }
        }
    }

    private double resolveFloorSurfaceY(@Nullable World world, double fallbackY) {
        if (world != null && TheNextLvlRaidInstanceManager.isManagedInstanceWorldName(world.getName())) {
            return CircularArenaBuilder.DEFAULT_SURFACE_Y;
        }
        return fallbackY;
    }

    private void snapLocationToGroundAsync(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        if (TheNextLvlRaidInstanceManager.isManagedInstanceWorldName(world.getName())) {
            loc.setY(CircularArenaBuilder.DEFAULT_SURFACE_Y);
            return;
        }
        scheduler.runAtRegion(loc, () -> {
            int bx = (int) Math.floor(loc.getX());
            int bz = (int) Math.floor(loc.getZ());
            int maxScanY = Math.min(world.getMaxHeight() - 2, (int) Math.ceil(loc.getY()) + 16);
            int minScanY = Math.max(world.getMinHeight(), (int) Math.floor(loc.getY()) - 16);
            for (int y = maxScanY; y >= minScanY; y--) {
                Block block = world.getBlockAt(bx, y, bz);
                if (block != null && block.isSolid()) {
                    loc.setY(y + 1.0);
                    break;
                }
            }
        });
    }

    private static Location extrapolateBeamEndpoint(Location origin, Location target, double length) {
        double dx = target.getX() - origin.getX();
        double dz = target.getZ() - origin.getZ();
        double horiz = Math.hypot(dx, dz);
        double minSurfaceY = (origin.getWorld() != null && TheNextLvlRaidInstanceManager.isManagedInstanceWorldName(origin.getWorld().getName()))
                ? CircularArenaBuilder.DEFAULT_SURFACE_Y
                : Math.max(CircularArenaBuilder.DEFAULT_FLOOR_Y, origin.getY() - CircularArenaBuilder.DEFAULT_PILLAR_HEIGHT - 1.0);
        double endpointY = Math.max(minSurfaceY, target.getY());
        if (horiz < 1e-4) {
            Location copy = target.clone();
            copy.setY(endpointY);
            return copy;
        }
        double ex = origin.getX() + (dx / horiz) * length;
        double ez = origin.getZ() + (dz / horiz) * length;
        return new Location(origin.getWorld(), ex, endpointY, ez);
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

    private static double horizontalDistSq(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Returns the number of currently living raid crystals.
     */
    public int activeCount() {
        pruneDead();
        return trackedCrystals.size();
    }

    /**
     * Removes all tracked raid crystals safely on their owning entity threads.
     */
    public CompletableFuture<Void> clearAll() {
        List<TrackedCrystal> snapshot;
        synchronized (spawnLock) {
            generation.incrementAndGet();
            pendingSpawns = 0;
            pendingCrystalIds.clear();
            snapshot = new ArrayList<>(trackedCrystals.values());
            trackedCrystals.clear();
            pendingLightnings.clear();
            pendingBeams.clear();
            bossRegenPerSecond = 0.0;
            lightningTimerSeconds = 0.0;
            beamTimerSeconds = 0.0;
        }
        telegraphService.clearTelegraphsByPrefix("crystal:", this.lastViewers);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (TrackedCrystal tracked : snapshot) {
            synchronized (tracked) {
                tracked.removed = true;
            }
            EnderCrystal entity = tracked.entity;
            if (entity != null && !entity.isDead()) {
                if (Bukkit.getServer() != null && Bukkit.isOwnedByCurrentRegion(entity)) {
                    entity.remove();
                    continue;
                }
                CompletableFuture<Void> done = new CompletableFuture<>();
                Runnable removeTask = () -> {
                    try {
                        if (!entity.isDead()) {
                            entity.remove();
                        }
                    } finally {
                        done.complete(null);
                    }
                };
                TaskHandle handle = scheduler.runAtEntity(entity, removeTask, () -> done.complete(null));
                if (handle == null) {
                    done.complete(null);
                }
                futures.add(done);
            }
        }
        return futures.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private void pruneDead() {
        trackedCrystals.entrySet().removeIf(entry -> {
            TrackedCrystal tracked = entry.getValue();
            return tracked.removed || tracked.entity == null || tracked.entity.isDead();
        });
    }

    private static Location resolveObsidianPillarTop(World world, Location probe) {
        int bx = (int) Math.floor(probe.getX());
        int bz = (int) Math.floor(probe.getZ());
        int topY = Math.min(world.getMaxHeight() - 3, (int) Math.ceil(probe.getY()) + 32);
        int bottomY = Math.max(world.getMinHeight(), (int) Math.floor(probe.getY()) - 16);
        for (int y = topY; y >= bottomY; y--) {
            Block block = world.getBlockAt(bx, y, bz);
            if (block != null && (block.getType() == Material.BEDROCK
                    || block.getType() == Material.OBSIDIAN
                    || block.isSolid())) {
                return new Location(world, bx + 0.5, y + 1.0, bz + 0.5);
            }
        }
        return probe.clone();
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

    private static void updateCrystalLabel(EnderCrystal crystal) {
        Component label = Component.text("✦ END CRYSTAL ✦", NamedTextColor.LIGHT_PURPLE);
        crystal.customName(label);
    }

    private static final class TrackedCrystal {
        final EnderCrystal entity;
        final Location location;
        boolean removed;

        TrackedCrystal(EnderCrystal entity, Location location) {
            this.entity = entity;
            this.location = location;
        }
    }

    private Collection<? extends Player> resolveCleanupViewers(@Nullable World world) {
        Collection<? extends Player> cached = this.lastViewers;
        if (!cached.isEmpty()) {
            return cached;
        }
        return world != null ? world.getPlayers() : List.of();
    }

    private static final class PendingLightning {
        final long id;
        final Location location;
        final double totalSeconds;
        double remainingSeconds;

        PendingLightning(long id, Location location, double remainingSeconds) {
            this.id = id;
            this.location = location;
            this.totalSeconds = Math.max(0.05, remainingSeconds);
            this.remainingSeconds = remainingSeconds;
        }
    }

    private static final class PendingCrystalBeam {
        final long id;
        final Location crystalOrigin;
        final UUID targetPlayerId;
        final double totalSeconds;
        Location lockedEndpoint;
        double remainingSeconds;

        PendingCrystalBeam(long id, Location crystalOrigin, UUID targetPlayerId, Location initialTarget, double remainingSeconds) {
            this.id = id;
            this.crystalOrigin = crystalOrigin;
            this.targetPlayerId = targetPlayerId;
            this.totalSeconds = Math.max(0.05, remainingSeconds);
            this.lockedEndpoint = extrapolateBeamEndpoint(crystalOrigin, initialTarget, 40.0);
            this.remainingSeconds = remainingSeconds;
        }
    }
}
