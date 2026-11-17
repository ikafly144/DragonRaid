package net.sabafly.dragonraid.instance;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.thenextlvl.worlds.Dimension;
import net.thenextlvl.worlds.WorldsAccess;
import net.thenextlvl.worlds.generator.GeneratorType;
import net.thenextlvl.worlds.preset.Preset;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * {@link RaidInstanceManager} backed by TheNextLvl Worlds API (`WorldsAccess`).
 * Dynamically creates a temporary Void {@code THE_END} instance world, builds a flat circular
 * arena with 8 low obsidian pillars, reuses it across wipes for instant retry, and deletes it
 * when the raid concludes.
 */
public final class TheNextLvlRaidInstanceManager implements RaidInstanceManager {

    public static final String INSTANCE_KEY_NAMESPACE = "dragonraid";
    public static final String INSTANCE_KEY_PREFIX = "instance_";
    public static final String INSTANCE_WORLD_NAME_PREFIX = INSTANCE_KEY_NAMESPACE + "_" + INSTANCE_KEY_PREFIX;

    /**
     * Returns {@code true} if {@code worldName} matches the plugin-owned instance prefix
     * ({@code dragonraid_instance_*}).
     */
    public static boolean isManagedInstanceWorldName(@Nullable String worldName) {
        return worldName != null && worldName.toLowerCase(Locale.ROOT).startsWith(INSTANCE_WORLD_NAME_PREFIX);
    }

    /**
     * Abstraction over TheNextLvl Worlds world creation/deletion for unit testing.
     */
    public interface WorldsBridge {
        CompletableFuture<World> createVoidEndWorld(Key worldKey);

        CompletableFuture<Boolean> deleteWorld(World world);

        List<World> loadedInstanceWorlds();
    }

    private final Object lock = new Object();
    private final RaidScheduler scheduler;
    private final CircularArenaBuilder arenaBuilder;
    private final WorldsBridge worldsBridge;
    private final Logger logger;
    private final AtomicInteger instanceCounter = new AtomicInteger(0);

    private long generation = 0L;
    private volatile @Nullable RaidInstance activeInstance;
    private volatile @Nullable CompletableFuture<RaidInstance> pendingCreation;

    public TheNextLvlRaidInstanceManager(RaidScheduler scheduler, Logger logger) {
        this(
                scheduler,
                new CircularArenaBuilder(scheduler),
                new DefaultWorldsBridge(),
                logger
        );
    }

    public TheNextLvlRaidInstanceManager(
            RaidScheduler scheduler,
            CircularArenaBuilder arenaBuilder,
            WorldsBridge worldsBridge,
            Logger logger) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.arenaBuilder = Objects.requireNonNull(arenaBuilder, "arenaBuilder");
        this.worldsBridge = Objects.requireNonNull(worldsBridge, "worldsBridge");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public @Nullable RaidInstance activeInstance() {
        return activeInstance;
    }

    @Override
    public @Nullable World activeCombatWorld(@Nullable World fallbackEndWorld) {
        RaidInstance current = this.activeInstance;
        return current != null ? current.world() : fallbackEndWorld;
    }

    @Override
    public boolean isInstanceWorld(@Nullable World world) {
        if (world == null) {
            return false;
        }
        RaidInstance current = this.activeInstance;
        if (current != null && world.equals(current.world())) {
            return true;
        }
        return isManagedInstanceWorldName(world.getName());
    }

    @Override
    public CompletableFuture<RaidInstance> getOrCreateInstance() {
        synchronized (lock) {
            if (activeInstance != null) {
                return CompletableFuture.completedFuture(activeInstance);
            }
            if (pendingCreation != null) {
                return pendingCreation;
            }
            final long creationGen = this.generation;
            int id = instanceCounter.incrementAndGet();
            Key worldKey = Key.key(INSTANCE_KEY_NAMESPACE, INSTANCE_KEY_PREFIX + id);
            CompletableFuture<RaidInstance> creation = worldsBridge.createVoidEndWorld(worldKey)
                    .thenCompose(world -> {
                        configureInstanceWorldRules(world);
                        return arenaBuilder.buildArena(world)
                                .handle((pillars, buildErr) -> {
                                    if (buildErr != null) {
                                        worldsBridge.deleteWorld(world);
                                        throw new CompletionException(buildErr);
                                    }
                                    return pillars;
                                })
                                .thenApply(pillars -> new RaidInstance(
                                        world,
                                        new Location(world, 0.0, CircularArenaBuilder.DEFAULT_FLOOR_Y, 0.0),
                                        CircularArenaBuilder.DEFAULT_ARENA_RADIUS,
                                        pillars
                                ));
                    })
                    .whenComplete((instance, ex) -> {
                        boolean stale;
                        synchronized (lock) {
                            stale = this.generation != creationGen;
                            if (!stale) {
                                this.pendingCreation = null;
                                if (ex == null && instance != null) {
                                    this.activeInstance = instance;
                                }
                            }
                        }
                        if (stale && ex == null && instance != null) {
                            worldsBridge.deleteWorld(instance.world());
                        }
                    });
            this.pendingCreation = creation;
            return creation;
        }
    }

    @Override
    public CompletableFuture<Void> destroyInstance(
            @Nullable RaidInstance targetInstance,
            @Nullable Location returnLocation) {
        if (targetInstance == null) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (lock) {
            if (this.activeInstance == targetInstance) {
                this.generation++;
                this.activeInstance = null;
                this.pendingCreation = null;
            }
        }
        return evacuateAndDelete(targetInstance.world(), returnLocation);
    }

    @Override
    public CompletableFuture<Void> destroyActiveInstance(@Nullable Location returnLocation) {
        final RaidInstance instanceToDestroy;
        synchronized (lock) {
            this.generation++;
            instanceToDestroy = this.activeInstance;
            this.activeInstance = null;
            this.pendingCreation = null;
        }
        if (instanceToDestroy == null) {
            return CompletableFuture.completedFuture(null);
        }
        return evacuateAndDelete(instanceToDestroy.world(), returnLocation);
    }

    private CompletableFuture<Void> evacuateAndDelete(World world, @Nullable Location returnLocation) {
        Collection<Player> players = world.getPlayers();
        List<CompletableFuture<Boolean>> evacuations = players.stream()
                .filter(p -> p != null && p.isOnline() && returnLocation != null && returnLocation.getWorld() != null)
                .map(p -> {
                    CompletableFuture<Boolean> tpFuture = new CompletableFuture<>();
                    scheduler.runAtEntity(p, () -> p.teleportAsync(returnLocation).whenComplete((ok, err) -> {
                        tpFuture.complete(Boolean.TRUE.equals(ok));
                    }), () -> tpFuture.complete(false));
                    return tpFuture;
                })
                .toList();

        CompletableFuture<Void> afterEvac = evacuations.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(evacuations.toArray(CompletableFuture[]::new));

        return afterEvac.thenCompose(ignored -> worldsBridge.deleteWorld(world))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Failed to delete temporary raid instance world " + world.getName(), ex);
                    return false;
                })
                .thenAccept(ignored -> {
                });
    }

    @Override
    public void cleanupOrphanedInstances() {
        try {
            for (World world : worldsBridge.loadedInstanceWorlds()) {
                worldsBridge.deleteWorld(world).exceptionally(ex -> {
                    logger.log(Level.FINE, "Failed to delete orphaned instance world " + world.getName(), ex);
                    return false;
                });
            }
        } catch (RuntimeException ex) {
            logger.log(Level.FINE, "Skipping orphaned instance cleanup", ex);
        }
    }

    @SuppressWarnings("removal")
    private void configureInstanceWorldRules(World world) {
        Location center = new Location(world, 0.0, CircularArenaBuilder.DEFAULT_FLOOR_Y + 1.0, 0.0);
        scheduler.runAtRegion(center, () -> {
            try {
                world.setSpawnLocation(0, CircularArenaBuilder.DEFAULT_FLOOR_Y + 1, 0);
                if (Bukkit.getServer() != null) {
                    world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                    world.setGameRule(GameRule.MOB_GRIEFING, false);
                    world.setGameRule(GameRule.DO_FIRE_TICK, false);
                    world.setGameRule(GameRule.KEEP_INVENTORY, true);
                    world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
                }
            } catch (Throwable ignored) {
                // Mocked worlds in unit tests may not initialize static GameRule registries.
            }
        });
    }

    private static final class DefaultWorldsBridge implements WorldsBridge {

        @Override
        public CompletableFuture<World> createVoidEndWorld(Key worldKey) {
            WorldsAccess access = WorldsAccess.access();
            net.thenextlvl.worlds.Level level = net.thenextlvl.worlds.Level.builder(worldKey)
                    .dimension(Dimension.THE_END)
                    .structures(false)
                    .bonusChest(false)
                    .generatorType(GeneratorType.FLAT.with(Preset.THE_VOID))
                    .ignoreLevelData(true)
                    .build();
            return access.create(level);
        }

        @Override
        public CompletableFuture<Boolean> deleteWorld(World world) {
            WorldsAccess access = WorldsAccess.access();
            return access.delete(world);
        }

        @Override
        public List<World> loadedInstanceWorlds() {
            if (Bukkit.getServer() == null) {
                return List.of();
            }
            return Bukkit.getWorlds().stream()
                    .filter(w -> w != null && isManagedInstanceWorldName(w.getName()))
                    .toList();
        }
    }
}
