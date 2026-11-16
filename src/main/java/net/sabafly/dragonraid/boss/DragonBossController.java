package net.sabafly.dragonraid.boss;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.jetbrains.annotations.Nullable;

/**
 * Controls the EnderDragon boss entity in a Folia-safe manner, suppressing chaotic vanilla
 * dragon AI and driving deterministic raid postures, HP scaling, and regeneration.
 */
public interface DragonBossController {

    /**
     * Binds an existing living EnderDragon in {@code endWorld} or spawns a new one at the arena center,
     * applying {@code scaledMaxHp} and starting custom flight control.
     *
     * @return future completing once the dragon entity is bound/spawned, or exceptionally on spawn failure
     */
    CompletableFuture<Void> bindOrSpawn(World endWorld, double scaledMaxHp);

    /**
     * Captures an unkilled first-clear EnderDragon into a controlled idle orbit before the raid pull begins.
     */
    void holdFirstClearIdle(World endWorld);

    /**
     * Updates the target posture for boss movement.
     */
    void setPosture(DragonPosture posture);

    /**
     * Returns the effective current posture.
     */
    DragonPosture posture();

    /**
     * Temporarily staggers the boss on the ground for {@code seconds}, restoring its prior posture afterward.
     */
    void stagger(double seconds);

    /**
     * Returns {@code true} if the boss is currently staggered.
     */
    boolean isStaggered();

    /**
     * Advances posture/stagger timers from the encounter coordinator tick.
     */
    void tick(double deltaSeconds);

    /**
     * Heals the boss up to its scaled max HP (e.g. from living End Crystals).
     */
    void healBoss(double amount);

    /**
     * Applies plugin-driven damage to the boss.
     */
    void damageBoss(double amount);

    /**
     * Returns the latest cached boss HP.
     */
    double currentHp();

    /**
     * Returns the boss's configured max HP for the active encounter.
     */
    double maxHp();

    /**
     * Returns {@code currentHp / maxHp} clamped to {@code [0.0, 1.0]}.
     */
    default double hpRatio() {
        double max = maxHp();
        if (max <= 0.0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, currentHp() / max));
    }

    /**
     * Returns the bound dragon entity, or {@code null} if unbound or dead.
     */
    @Nullable EnderDragon activeDragon();

    /**
     * Returns the latest thread-safe cached boss world location.
     */
    @Nullable Location bossLocation();

    /**
     * Returns {@code true} if the currently bound dragon was spawned by the plugin (rather than pre-existing).
     */
    boolean wasSummonedByPlugin();

    /**
     * Releases custom movement control when the dragon is defeated so vanilla death animation
     * and DragonBattle progression complete naturally.
     */
    void prepareForVanillaDefeat();

    /**
     * Stops controller tasks and either restores vanilla flags or removes a plugin-summoned dragon.
     *
     * @return future completing once entity cleanup tasks finish or retire
     */
    CompletableFuture<Void> cleanup(boolean removeDragonIfSummoned);
}
