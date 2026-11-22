package net.sabafly.dragonraid.api.finder;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Structured detail describing a single unmet Content Finder eligibility condition.
 */
public record EligibilityIssue(
        EligibilityFailureReason reason,
        @Nullable UUID playerId,
        int actualValue,
        int requiredValue,
        @Nullable String detail
) {

    public EligibilityIssue {
        Objects.requireNonNull(reason, "reason");
    }

    public EligibilityIssue(EligibilityFailureReason reason, @Nullable UUID playerId) {
        this(reason, playerId, 0, 0, null);
    }

    public EligibilityIssue(EligibilityFailureReason reason, int actualValue, int requiredValue) {
        this(reason, null, actualValue, requiredValue, null);
    }
}
