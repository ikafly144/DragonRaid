package net.sabafly.dragonraid.api.script;

import java.util.Objects;

/**
 * Revive limit formula and downed expiration rules for an {@link EncounterBlueprint}.
 */
public record ReviveRuleSpec(
        Formula formula,
        double multiplier,
        int downedTimeoutSeconds
) {

    public enum Formula {
        PER_PLAYER,
        HALF_CEIL
    }

    public ReviveRuleSpec {
        Objects.requireNonNull(formula, "formula");
        if (!Double.isFinite(multiplier) || multiplier < 0.0) {
            throw new IllegalArgumentException("multiplier must be >= 0: " + multiplier);
        }
        if (downedTimeoutSeconds < 0) {
            throw new IllegalArgumentException("downedTimeoutSeconds must be >= 0: " + downedTimeoutSeconds);
        }
    }

    public static ReviveRuleSpec normalDefault() {
        return new ReviveRuleSpec(Formula.PER_PLAYER, 1.0, 0);
    }

    public static ReviveRuleSpec hardDefault() {
        return new ReviveRuleSpec(Formula.HALF_CEIL, 0.5, 45);
    }
}
