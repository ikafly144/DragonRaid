package net.sabafly.dragonraid.api.enemy;

/**
 * First-class rendering and animation backend used by an enemy or prop actor.
 *
 * <p>Both backends are supported on equal footing: {@link #BETTER_MODEL} drives Blockbench
 * {@code .bbmodel} rigs via BetterModel, while {@link #VANILLA} drives vanilla entities or
 * PacketEvents virtual models with configured bone offsets.
 */
public enum ModelRendererType {
    BETTER_MODEL,
    VANILLA
}
