package net.sabafly.dragonraid.api.stat;

/**
 * Standard stat keys supported by {@link RaidStatService} and custom equipment/buff pipelines.
 */
public enum StatKey {
    MAX_HEALTH_BONUS("max_health_bonus"),
    ATTACK_DAMAGE_MULTIPLIER("attack_damage_multiplier"),
    DAMAGE_REDUCTION_MULTIPLIER("damage_reduction_multiplier"),
    HEALING_MULTIPLIER("healing_multiplier"),
    MOVE_SPEED_BONUS("move_speed_bonus");

    private final String id;

    StatKey(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
