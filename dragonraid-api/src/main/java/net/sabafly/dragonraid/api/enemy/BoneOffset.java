package net.sabafly.dragonraid.api.enemy;

/**
 * Local entity-space bone offset relative to entity position and horizontal facing yaw,
 * used by {@link ModelRendererType#VANILLA} actors and fallback bone calculations.
 *
 * @param right offset along the actor's right vector (blocks)
 * @param up offset along the world Y axis (blocks)
 * @param forward offset along the actor's horizontal facing direction (blocks)
 */
public record BoneOffset(double right, double up, double forward) {

    public static final BoneOffset ZERO = new BoneOffset(0.0, 0.0, 0.0);
}
