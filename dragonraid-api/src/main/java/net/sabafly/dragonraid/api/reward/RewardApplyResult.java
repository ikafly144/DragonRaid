package net.sabafly.dragonraid.api.reward;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Result of applying a {@link RewardBundle} to persistent storage and player state.
 */
public record RewardApplyResult(
        RewardBundle evaluated,
        List<RewardGrant> appliedGrants,
        Set<String> newlyClaimedKeys,
        Set<String> skippedClaimKeys,
        List<RewardGrant> failedGrants
) {

    public RewardApplyResult {
        Objects.requireNonNull(evaluated, "evaluated");
        appliedGrants = appliedGrants == null ? List.of() : List.copyOf(appliedGrants);
        newlyClaimedKeys = newlyClaimedKeys == null ? Set.of() : Set.copyOf(newlyClaimedKeys);
        skippedClaimKeys = skippedClaimKeys == null ? Set.of() : Set.copyOf(skippedClaimKeys);
        failedGrants = failedGrants == null ? List.of() : List.copyOf(failedGrants);
    }

    public RewardApplyResult(
            RewardBundle evaluated,
            List<RewardGrant> appliedGrants,
            Set<String> newlyClaimedKeys,
            Set<String> skippedClaimKeys) {
        this(evaluated, appliedGrants, newlyClaimedKeys, skippedClaimKeys, List.of());
    }

    public static RewardApplyResult empty(RewardBundle bundle) {
        return new RewardApplyResult(bundle, List.of(), Set.of(), Set.of(), List.of());
    }
}
