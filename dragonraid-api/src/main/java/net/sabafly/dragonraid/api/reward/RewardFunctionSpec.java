package net.sabafly.dragonraid.api.reward;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Declarative quantity transformation analogous to Minecraft Loot Table functions.
 */
public record RewardFunctionSpec(
        RewardFunctionType type,
        @Nullable RollRange amountRange,
        double factor,
        RewardRoundingMode roundingMode,
        @Nullable RewardMetricType metric,
        String attributeKey,
        double step,
        double perStep,
        long minBound,
        long maxBound,
        List<RewardConditionSpec> conditions
) {

    public RewardFunctionSpec {
        Objects.requireNonNull(type, "type");
        roundingMode = roundingMode == null ? RewardRoundingMode.FLOOR : roundingMode;
        attributeKey = attributeKey == null ? "" : attributeKey.trim();
        conditions = conditions == null ? List.of() : List.copyOf(conditions);

        switch (type) {
            case SET_AMOUNT, ADD_AMOUNT -> {
                if (amountRange == null) {
                    throw new IllegalArgumentException("amountRange must not be null for " + type);
                }
            }
            case MULTIPLY_AMOUNT -> {
                if (!Double.isFinite(factor) || factor < 0.0) {
                    throw new IllegalArgumentException("factor must be finite and >= 0: " + factor);
                }
            }
            case ADD_METRIC_SCALED -> {
                if (metric == null) {
                    throw new IllegalArgumentException("metric must not be null for ADD_METRIC_SCALED");
                }
                if (!Double.isFinite(step) || step <= 0.0) {
                    throw new IllegalArgumentException("step must be finite and > 0: " + step);
                }
                if (!Double.isFinite(perStep) || perStep < 0.0) {
                    throw new IllegalArgumentException("perStep must be finite and >= 0: " + perStep);
                }
                if (maxBound < 0L) {
                    throw new IllegalArgumentException("maxBound must be >= 0 (0 = unbounded): " + maxBound);
                }
                if (metric == RewardMetricType.ATTRIBUTE && attributeKey.isEmpty()) {
                    throw new IllegalArgumentException("attributeKey must not be blank when metric is ATTRIBUTE");
                }
            }
            case CLAMP_AMOUNT -> {
                if (minBound < 0L || maxBound < minBound) {
                    throw new IllegalArgumentException("Invalid clamp bounds: " + minBound + ".." + maxBound);
                }
            }
        }
    }

    public static RewardFunctionSpec setAmount(RollRange amount, @Nullable List<RewardConditionSpec> conditions) {
        return new RewardFunctionSpec(RewardFunctionType.SET_AMOUNT, amount, 1.0, RewardRoundingMode.FLOOR, null, "", 1.0, 0.0, 0L, 0L, conditions);
    }

    public static RewardFunctionSpec addAmount(RollRange delta, @Nullable List<RewardConditionSpec> conditions) {
        return new RewardFunctionSpec(RewardFunctionType.ADD_AMOUNT, delta, 1.0, RewardRoundingMode.FLOOR, null, "", 1.0, 0.0, 0L, 0L, conditions);
    }

    public static RewardFunctionSpec multiplyAmount(double factor, @Nullable RewardRoundingMode roundingMode, @Nullable List<RewardConditionSpec> conditions) {
        return new RewardFunctionSpec(RewardFunctionType.MULTIPLY_AMOUNT, null, factor, roundingMode, null, "", 1.0, 0.0, 0L, 0L, conditions);
    }

    public static RewardFunctionSpec addMetricScaled(
            RewardMetricType metric,
            @Nullable String attributeKey,
            double step,
            double perStep,
            long maxBonus,
            @Nullable List<RewardConditionSpec> conditions) {
        return new RewardFunctionSpec(
                RewardFunctionType.ADD_METRIC_SCALED,
                null,
                1.0,
                RewardRoundingMode.FLOOR,
                metric,
                attributeKey == null ? "" : attributeKey,
                step,
                perStep,
                0L,
                maxBonus,
                conditions
        );
    }

    public static RewardFunctionSpec clampAmount(long minBound, long maxBound, @Nullable List<RewardConditionSpec> conditions) {
        return new RewardFunctionSpec(RewardFunctionType.CLAMP_AMOUNT, null, 1.0, RewardRoundingMode.FLOOR, null, "", 1.0, 0.0, minBound, maxBound, conditions);
    }
}
