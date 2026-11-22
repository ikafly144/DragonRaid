package net.sabafly.dragonraid.api.reward;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.sabafly.dragonraid.api.util.ImmutableSnapshots;
import org.jetbrains.annotations.Nullable;

/**
 * Single weighted or unconditional entry inside a {@link RewardPool}.
 */
public record RewardEntrySpec(
        RewardEntryType type,
        String target,
        int weight,
        double quality,
        RollRange amount,
        boolean notifyPlayer,
        String titleKey,
        String subtitleKey,
        String toastTitleKey,
        String toastDescriptionKey,
        String iconMaterial,
        String soundKey,
        Map<String, Object> params,
        List<RewardConditionSpec> conditions,
        List<RewardFunctionSpec> functions
) {

    public RewardEntrySpec {
        Objects.requireNonNull(type, "type");
        target = normalize(target);
        if (type != RewardEntryType.EMPTY && target.isEmpty()) {
            throw new IllegalArgumentException("target must not be blank for reward entry type " + type);
        }
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be >= 1: " + weight);
        }
        if (!Double.isFinite(quality)) {
            throw new IllegalArgumentException("quality must be finite: " + quality);
        }
        if (amount == null) {
            amount = (type == RewardEntryType.CURRENCY
                    || type == RewardEntryType.VANILLA_ITEM
                    || type == RewardEntryType.CUSTOM_ITEM)
                    ? RollRange.exact(1)
                    : RollRange.exact(0);
        }
        if ((type == RewardEntryType.CURRENCY
                || type == RewardEntryType.VANILLA_ITEM
                || type == RewardEntryType.CUSTOM_ITEM) && amount.max() <= 0) {
            throw new IllegalArgumentException("amount.max must be > 0 for entry type " + type);
        }
        titleKey = normalize(titleKey);
        subtitleKey = normalize(subtitleKey);
        toastTitleKey = normalize(toastTitleKey);
        toastDescriptionKey = normalize(toastDescriptionKey);
        iconMaterial = normalize(iconMaterial);
        soundKey = normalize(soundKey);
        params = immutableCopy(params);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        functions = functions == null ? List.of() : List.copyOf(functions);
    }

    public static RewardEntrySpec empty(int weight) {
        return new RewardEntrySpec(
                RewardEntryType.EMPTY, "", weight, 0.0, RollRange.exact(0),
                false, "", "", "", "", "", "", Map.of(), List.of(), List.of()
        );
    }

    public static RewardEntrySpec currency(
            String currencyId,
            RollRange amount,
            int weight,
            @Nullable List<RewardConditionSpec> conditions,
            @Nullable List<RewardFunctionSpec> functions) {
        return new RewardEntrySpec(
                RewardEntryType.CURRENCY, currencyId, weight, 0.0, amount,
                false, "", "", "", "", "", "", Map.of(), conditions, functions
        );
    }

    public static RewardEntrySpec vanillaItem(
            String materialName,
            RollRange amount,
            int weight,
            @Nullable List<RewardConditionSpec> conditions,
            @Nullable List<RewardFunctionSpec> functions) {
        return new RewardEntrySpec(
                RewardEntryType.VANILLA_ITEM, materialName, weight, 0.0, amount,
                false, "", "", "", "", "", "", Map.of(), conditions, functions
        );
    }

    public static RewardEntrySpec customItem(
            String customItemId,
            RollRange amount,
            int weight,
            @Nullable List<RewardConditionSpec> conditions,
            @Nullable List<RewardFunctionSpec> functions) {
        return new RewardEntrySpec(
                RewardEntryType.CUSTOM_ITEM, customItemId, weight, 0.0, amount,
                false, "", "", "", "", "", "", Map.of(), conditions, functions
        );
    }

    public static RewardEntrySpec unlockContent(
            String contentId,
            boolean notifyPlayer,
            @Nullable String iconMaterial,
            @Nullable String soundKey) {
        return new RewardEntrySpec(
                RewardEntryType.UNLOCK_CONTENT, contentId, 1, 0.0, RollRange.exact(0),
                notifyPlayer, "", "", "", "", iconMaterial, soundKey, Map.of(), List.of(), List.of()
        );
    }

    public static RewardEntrySpec scriptHook(String hookId, @Nullable Map<String, Object> params) {
        return new RewardEntrySpec(
                RewardEntryType.SCRIPT_HOOK, hookId, 1, 0.0, RollRange.exact(0),
                false, "", "", "", "", "", "", params, List.of(), List.of()
        );
    }

    public static RewardEntrySpec tableRef(
            String tableId,
            int weight,
            double quality,
            @Nullable List<RewardConditionSpec> conditions,
            @Nullable List<RewardFunctionSpec> functions) {
        return new RewardEntrySpec(
                RewardEntryType.TABLE_REF, tableId, weight, quality, RollRange.exact(0),
                false, "", "", "", "", "", "", Map.of(), conditions, functions
        );
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, Object> immutableCopy(@Nullable Map<String, Object> source) {
        return ImmutableSnapshots.copyStringMap(source);
    }
}
