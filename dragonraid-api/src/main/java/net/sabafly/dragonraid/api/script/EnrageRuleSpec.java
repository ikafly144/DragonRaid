package net.sabafly.dragonraid.api.script;

import java.util.Objects;

/**
 * Soft- and hard-enrage configuration for an {@link EncounterBlueprint}.
 */
public record EnrageRuleSpec(
        boolean softEnabled,
        String softStartPhase,
        double voidShrinkPerSecond,
        double voidDamagePerSecond,
        boolean hardEnabled,
        int hardTimeLimitSeconds,
        String hardAbilityNameKey,
        double hardCastSeconds
) {

    public EnrageRuleSpec {
        Objects.requireNonNull(softStartPhase, "softStartPhase");
        Objects.requireNonNull(hardAbilityNameKey, "hardAbilityNameKey");
    }

    public static EnrageRuleSpec normalDefault() {
        return new EnrageRuleSpec(true, "final", 0.25, 4.0, false, 600, "ability.hard_enrage.name", 5.0);
    }

    public static EnrageRuleSpec hardDefault() {
        return new EnrageRuleSpec(true, "final", 0.35, 6.0, true, 720, "ability.hard_enrage.name", 5.0);
    }
}
