package net.sabafly.dragonraid.api.reward;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Pool inside a {@link RewardTable} rolling one or all matching entries per roll.
 */
public record RewardPool(
        String id,
        RewardPoolMode mode,
        RollRange rolls,
        double bonusRollsPerLuck,
        @Nullable String claimKey,
        List<RewardConditionSpec> conditions,
        List<RewardEntrySpec> entries,
        List<RewardFunctionSpec> functions
) {

    public RewardPool {
        id = id == null ? "" : id.trim();
        mode = mode == null ? RewardPoolMode.WEIGHTED : mode;
        rolls = rolls == null ? RollRange.exact(1) : rolls;
        if (!Double.isFinite(bonusRollsPerLuck)) {
            throw new IllegalArgumentException("bonusRollsPerLuck must be finite: " + bonusRollsPerLuck);
        }
        claimKey = (claimKey == null || claimKey.isBlank()) ? null : claimKey.trim();
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("RewardPool must contain at least one entry" + (id.isEmpty() ? "" : ": " + id));
        }
        functions = functions == null ? List.of() : List.copyOf(functions);
    }

    public static RewardPool allOf(String id, @Nullable String claimKey, List<RewardConditionSpec> conditions, List<RewardEntrySpec> entries) {
        return new RewardPool(id, RewardPoolMode.ALL, RollRange.exact(1), 0.0, claimKey, conditions, entries, List.of());
    }

    public static RewardPool weighted(String id, RollRange rolls, List<RewardConditionSpec> conditions, List<RewardEntrySpec> entries) {
        return new RewardPool(id, RewardPoolMode.WEIGHTED, rolls, 0.0, null, conditions, entries, List.of());
    }
}
