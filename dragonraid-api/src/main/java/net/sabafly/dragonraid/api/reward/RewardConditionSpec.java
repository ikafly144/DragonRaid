package net.sabafly.dragonraid.api.reward;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.jetbrains.annotations.Nullable;

/**
 * Declarative predicate applied to reward pools, entries, or functions.
 */
public record RewardConditionSpec(
        RewardConditionType type,
        boolean negate,
        String target,
        @Nullable EncounterDifficulty difficulty,
        int minInt,
        int maxInt,
        long longValue,
        double doubleValue,
        double luckMultiplier,
        String expectedValue,
        Map<String, Object> params
) {

    public RewardConditionSpec {
        Objects.requireNonNull(type, "type");
        target = normalize(target);
        expectedValue = normalize(expectedValue);
        params = immutableCopy(params);

        switch (type) {
            case DIFFICULTY -> {
                if (difficulty == null) {
                    throw new IllegalArgumentException("difficulty must not be null for DIFFICULTY condition");
                }
            }
            case HAS_ACHIEVEMENT, HAS_UNLOCK, NOT_CLAIMED, CUSTOM_SCRIPT, ATTRIBUTE_EQUALS -> {
                if (target.isEmpty()) {
                    throw new IllegalArgumentException("target must not be blank for condition type " + type);
                }
            }
            case RANDOM_CHANCE -> {
                if (!Double.isFinite(doubleValue) || doubleValue < 0.0 || doubleValue > 1.0) {
                    throw new IllegalArgumentException("chance must be within [0.0, 1.0]: " + doubleValue);
                }
                if (!Double.isFinite(luckMultiplier)) {
                    throw new IllegalArgumentException("luckMultiplier must be finite: " + luckMultiplier);
                }
            }
            case PARTY_SIZE -> {
                if (minInt < 0) {
                    throw new IllegalArgumentException("minInt must be >= 0 for PARTY_SIZE");
                }
                if (maxInt != 0 && maxInt < minInt) {
                    throw new IllegalArgumentException("maxInt must be 0 or >= minInt for PARTY_SIZE");
                }
            }
            case MAX_DURATION_MS -> {
                if (longValue <= 0L) {
                    throw new IllegalArgumentException("maxDurationMs must be > 0");
                }
            }
            case MIN_BOSS_DAMAGE -> {
                if (!Double.isFinite(doubleValue) || doubleValue < 0.0) {
                    throw new IllegalArgumentException("minBossDamage must be >= 0");
                }
            }
            case MIN_CRYSTALS_DESTROYED, MIN_REVIVES_COMPLETED -> {
                if (minInt < 0) {
                    throw new IllegalArgumentException("minInt must be >= 0 for condition type " + type);
                }
            }
            case FIRST_CLEAR, SERVER_FIRST, NO_DEATH -> {
                // no extra required parameters
            }
        }
    }

    public RewardConditionSpec negated() {
        return new RewardConditionSpec(
                type,
                !negate,
                target,
                difficulty,
                minInt,
                maxInt,
                longValue,
                doubleValue,
                luckMultiplier,
                expectedValue,
                params
        );
    }

    public static RewardConditionSpec firstClear() {
        return new RewardConditionSpec(RewardConditionType.FIRST_CLEAR, false, "", null, 0, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec serverFirst() {
        return new RewardConditionSpec(RewardConditionType.SERVER_FIRST, false, "", null, 0, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec noDeath() {
        return new RewardConditionSpec(RewardConditionType.NO_DEATH, false, "", null, 0, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec difficulty(EncounterDifficulty difficulty) {
        return new RewardConditionSpec(RewardConditionType.DIFFICULTY, false, "", difficulty, 0, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec partySize(int min, int max) {
        return new RewardConditionSpec(RewardConditionType.PARTY_SIZE, false, "", null, min, max, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec maxDurationMs(long maxDurationMs) {
        return new RewardConditionSpec(RewardConditionType.MAX_DURATION_MS, false, "", null, 0, 0, maxDurationMs, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec minBossDamage(double minBossDamage) {
        return new RewardConditionSpec(RewardConditionType.MIN_BOSS_DAMAGE, false, "", null, 0, 0, 0L, minBossDamage, 0.0, "", Map.of());
    }

    public static RewardConditionSpec minCrystalsDestroyed(int minCrystals) {
        return new RewardConditionSpec(RewardConditionType.MIN_CRYSTALS_DESTROYED, false, "", null, minCrystals, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec minRevivesCompleted(int minRevives) {
        return new RewardConditionSpec(RewardConditionType.MIN_REVIVES_COMPLETED, false, "", null, minRevives, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec randomChance(double chance, double luckMultiplier) {
        return new RewardConditionSpec(RewardConditionType.RANDOM_CHANCE, false, "", null, 0, 0, 0L, chance, luckMultiplier, "", Map.of());
    }

    public static RewardConditionSpec hasAchievement(String achievementId) {
        return new RewardConditionSpec(RewardConditionType.HAS_ACHIEVEMENT, false, achievementId, null, 0, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec hasUnlock(String contentId) {
        return new RewardConditionSpec(RewardConditionType.HAS_UNLOCK, false, contentId, null, 0, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec notClaimed(String claimKey) {
        return new RewardConditionSpec(RewardConditionType.NOT_CLAIMED, false, claimKey, null, 0, 0, 0L, 0.0, 0.0, "", Map.of());
    }

    public static RewardConditionSpec attributeEquals(String key, String expected) {
        return new RewardConditionSpec(RewardConditionType.ATTRIBUTE_EQUALS, false, key, null, 0, 0, 0L, 0.0, 0.0, expected, Map.of());
    }

    public static RewardConditionSpec customScript(String hookId, @Nullable Map<String, Object> params) {
        return new RewardConditionSpec(RewardConditionType.CUSTOM_SCRIPT, false, hookId, null, 0, 0, 0L, 0.0, 0.0, "", params);
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, Object> immutableCopy(@Nullable Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
