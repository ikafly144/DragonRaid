package net.sabafly.dragonraid.api.model;

import net.sabafly.dragonraid.api.enemy.ModelRendererType;
import net.sabafly.dragonraid.boss.DragonPosture;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/**
 * Unified runtime controller for an enemy or prop model across both {@link ModelRendererType#BETTER_MODEL}
 * and {@link ModelRendererType#VANILLA} renderers.
 */
public interface ActorModelController extends AutoCloseable {

    ModelRendererType rendererType();

    /**
     * Plays a named animation clip (resolved via {@link net.sabafly.dragonraid.api.enemy.ActorModelSpec}).
     *
     * @return {@code true} if the animation clip was started or posture-mapped
     */
    boolean playAnimation(String animationKey, double speed, boolean loop);

    /**
     * Stops a running animation clip by logical key or resolved clip name.
     *
     * @return {@code true} if a matching animation was stopped
     */
    boolean stopAnimation(String animationKey);

    /**
     * Resolves the current world-space {@link Location} of a named bone (e.g. {@code "mouth"}, {@code "head"}),
     * returning {@code null} if no underlying entity/location is bound.
     */
    @Nullable Location boneLocation(String boneName);

    /**
     * Synchronizes visual posture state (e.g. dive, hover, stagger) onto the actor renderer.
     */
    void setPosture(DragonPosture posture);

    /**
     * Updates the actor's visual scale multiplier.
     */
    void setScale(double scale);

    /**
     * Detaches/cleans up any BetterModel trackers or virtual display handles owned by this controller.
     */
    @Override
    void close();
}
