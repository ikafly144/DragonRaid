package net.sabafly.dragonraid.gimmick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
import net.sabafly.dragonraid.visual.TelegraphStyle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Manages raid-specific End Crystals with scaled virtual HP, safe explosion suppression,
 * boss regeneration tethers, and Folia-safe spawning/cleanup.
 */
public final class RaidCrystalManager {

    private static final double CRYSTAL_RING_RADIUS = 38.0;
    private static final double FALLBACK_Y_OFFSET = 8.0;

    private final ConfigManager configManager;
    private final RaidScheduler scheduler;
    private final SpatialTelegraphService telegraphService;
    private final Logger logger;
    private final CrystalSpawner crystalSpawner;

    private final Map<UUID, TrackedCrystal> trackedCrystals = new ConcurrentHashMap<>();
    private final AtomicInteger waveCounter = new AtomicInteger(0);
    private volatile double bossRegenPerSecond;

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
                (world, loc) -> world.spawn(loc, EnderCrystal.class)
        );
    }

    RaidCrystalManager(
            ConfigManager configManager,
            RaidScheduler scheduler,
            SpatialTelegraphService telegraphService,
            Logger logger,
            CrystalSpawner crystalSpawner) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telegraphService = Objects.requireNonNull(telegraphService, "telegraphService");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.crystalSpawner = Objects.requireNonNull(crystalSpawner, "crystalSpawner");
    }

    /**
     * Spawns a wave of raid End Crystals up to {@code targetCount} total living crystals.
     */
    public void spawnWave(
            World world,
            int targetCount,
            double crystalMaxHp,
            double regenPerSecond) {
        Objects.requireNonNull(world, "world");
        pruneDead();
        this.bossRegenPerSecond = Math.max(0.0, regenPerSecond);
        int existing = trackedCrystals.size();
        int toSpawn = Math.max(0, targetCount - existing);
        if (toSpawn == 0) {
            return;
        }

        RegionConfig.ArenaRegion arena = configManager.pluginConfig().regions().arena();
        RegionConfig.Point3D center = arena.center();
        double ringRadius = Math.min(CRYSTAL_RING_RADIUS, Math.max(12.0, arena.radius() * 0.45));
        double phaseAngle = (waveCounter.getAndIncrement() * Math.PI) / Math.max(1, targetCount);
        double validMaxHp = Math.max(1.0, crystalMaxHp);

        for (int i = 0; i < toSpawn; i++) {
            double angle = phaseAngle + (2.0 * Math.PI * i) / toSpawn;
            double x = center.x() + Math.cos(angle) * ringRadius;
            double z = center.z() + Math.sin(angle) * ringRadius;
            Location probeLoc = new Location(world, x, center.y() + FALLBACK_Y_OFFSET, z);

            scheduler.runAtRegion(probeLoc, () -> {
                Location spawnLoc = resolveCrystalSpawnLocation(world, x, z, center.y());
                try {
                    EnderCrystal crystal = crystalSpawner.spawn(world, spawnLoc);
                    crystal.setShowingBottom(false);
                    crystal.setBeamTarget(new Location(world, center.x(), center.y() + 10.0, center.z()));
                    crystal.setCustomNameVisible(true);
                    updateCrystalLabel(crystal, validMaxHp, validMaxHp);
                    trackedCrystals.put(
                            crystal.getUniqueId(),
                            new TrackedCrystal(crystal, spawnLoc.clone(), validMaxHp, validMaxHp)
                    );
                } catch (RuntimeException ex) {
                    logger.log(Level.WARNING, "Failed to spawn raid EndCrystal at " + spawnLoc, ex);
                }
            });
        }
    }

    /**
     * Returns {@code true} if {@code entity} is an active raid-managed crystal.
     */
    public boolean isTrackedCrystal(@Nullable Entity entity) {
        return entity != null && trackedCrystals.containsKey(entity.getUniqueId());
    }

    /**
     * Applies virtual HP damage to a tracked crystal, preventing vanilla one-hit explosion.
     *
     * @return {@code true} if the entity was a tracked raid crystal and the vanilla hit should be cancelled
     */
    public boolean onCrystalDamage(
            EnderCrystal crystal,
            double damage,
            @Nullable Entity damager) {
        Objects.requireNonNull(crystal, "crystal");
        TrackedCrystal tracked = trackedCrystals.get(crystal.getUniqueId());
        if (tracked == null) {
            return false;
        }
        double effectiveDamage = Math.max(1.0, damage);
        boolean destroyed = false;
        double remainingHp;
        synchronized (tracked) {
            if (tracked.removed) {
                return true;
            }
            tracked.currentHp = Math.max(0.0, tracked.currentHp - effectiveDamage);
            remainingHp = tracked.currentHp;
            if (remainingHp <= 0.0) {
                tracked.removed = true;
                destroyed = true;
            }
        }

        if (destroyed) {
            trackedCrystals.remove(crystal.getUniqueId(), tracked);
            crystal.remove();
            World world = crystal.getWorld();
            if (world != null) {
                Location loc = tracked.location;
                for (Player p : world.getPlayers()) {
                    if (p != null && p.isOnline() && p.getLocation().distanceSquared(loc) <= 64.0 * 64.0) {
                        scheduler.runAtEntity(p, () -> p.playSound(Sound.sound(
                                Key.key("minecraft:block.glass.break"),
                                Sound.Source.HOSTILE,
                                1.0f,
                                0.8f
                        )));
                    }
                }
            }
        } else {
            updateCrystalLabel(crystal, remainingHp, tracked.maxHp);
            if (damager instanceof Player player) {
                player.playSound(Sound.sound(
                        Key.key("minecraft:block.amethyst_block.hit"),
                        Sound.Source.HOSTILE,
                        0.8f,
                        1.2f
                ));
            }
        }
        return true;
    }

    /**
     * Ticks active crystals, renders tether lines to the boss, and returns total HP regenerated
     * during {@code deltaSeconds}.
     */
    public double tick(
            double deltaSeconds,
            @Nullable Location bossLocation,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(viewers, "viewers");
        pruneDead();
        if (trackedCrystals.isEmpty()) {
            return 0.0;
        }
        int activeCount = trackedCrystals.size();
        if (bossLocation != null && bossLocation.getWorld() != null) {
            for (TrackedCrystal tracked : trackedCrystals.values()) {
                if (tracked.removed) {
                    continue;
                }
                telegraphService.renderTetherLine(
                        tracked.location,
                        bossLocation,
                        TelegraphStyle.TETHER,
                        viewers
                );
            }
        }
        if (deltaSeconds <= 0.0 || bossRegenPerSecond <= 0.0) {
            return 0.0;
        }
        return activeCount * bossRegenPerSecond * deltaSeconds;
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
    public void clearAll() {
        List<TrackedCrystal> snapshot = new ArrayList<>(trackedCrystals.values());
        trackedCrystals.clear();
        bossRegenPerSecond = 0.0;
        for (TrackedCrystal tracked : snapshot) {
            synchronized (tracked) {
                tracked.removed = true;
            }
            EnderCrystal entity = tracked.entity;
            if (entity != null && !entity.isDead()) {
                scheduler.runAtEntity(entity, () -> {
                    if (!entity.isDead()) {
                        entity.remove();
                    }
                });
            }
        }
    }

    private void pruneDead() {
        trackedCrystals.entrySet().removeIf(entry -> {
            TrackedCrystal tracked = entry.getValue();
            return tracked.removed || tracked.entity == null || tracked.entity.isDead();
        });
    }

    private static Location resolveCrystalSpawnLocation(World world, double x, double z, double centerY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int topY = Math.min(world.getMaxHeight() - 3, (int) Math.ceil(centerY) + 42);
        int bottomY = Math.max(world.getMinHeight(), (int) Math.floor(centerY) + 2);
        for (int y = topY; y >= bottomY; y--) {
            Block block = world.getBlockAt(bx, y, bz);
            if (block != null && block.isSolid()) {
                return new Location(world, bx + 0.5, y + 1.5, bz + 0.5);
            }
        }
        return new Location(world, x, centerY + FALLBACK_Y_OFFSET, z);
    }

    private static void updateCrystalLabel(EnderCrystal crystal, double currentHp, double maxHp) {
        int cur = (int) Math.ceil(Math.max(0.0, currentHp));
        int max = (int) Math.ceil(Math.max(1.0, maxHp));
        Component label = Component.text("✦ " + cur + " / " + max + " HP", NamedTextColor.LIGHT_PURPLE);
        crystal.customName(label);
    }

    private static final class TrackedCrystal {
        final EnderCrystal entity;
        final Location location;
        final double maxHp;
        double currentHp;
        boolean removed;

        TrackedCrystal(EnderCrystal entity, Location location, double maxHp, double currentHp) {
            this.entity = entity;
            this.location = location;
            this.maxHp = maxHp;
            this.currentHp = currentHp;
        }
    }
}
