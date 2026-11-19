package net.sabafly.dragonraid.api.achievement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.jetbrains.annotations.Nullable;

/**
 * Declarative trigger specification evaluated by {@link RaidAchievementService}.
 */
public record AchievementTriggerSpec(
        AchievementTriggerType type,
        String encounterId,
        @Nullable EncounterDifficulty difficulty,
        String worldName,
        String mechanicId,
        String sourceEnemyId,
        String customEventKey,
        String conditionHook,
        long maxDurationMillis,
        int minPartySize,
        int maxPartySize,
        double minBossDamage,
        int minCrystalsDestroyed,
        int minRevivesCompleted,
        Map<String, Object> params
) {

    public AchievementTriggerSpec {
        Objects.requireNonNull(type, "type");
        encounterId = normalize(encounterId);
        worldName = normalize(worldName);
        mechanicId = normalize(mechanicId);
        sourceEnemyId = normalize(sourceEnemyId);
        customEventKey = normalize(customEventKey);
        conditionHook = normalize(conditionHook);
        if (maxDurationMillis < 0L) {
            throw new IllegalArgumentException("maxDurationMillis must be >= 0");
        }
        if (minPartySize < 0 || maxPartySize < 0) {
            throw new IllegalArgumentException("minPartySize and maxPartySize must be >= 0");
        }
        if (maxPartySize > 0 && minPartySize > maxPartySize) {
            throw new IllegalArgumentException("maxPartySize must be >= minPartySize");
        }
        if (!Double.isFinite(minBossDamage) || minBossDamage < 0.0) {
            throw new IllegalArgumentException("minBossDamage must be finite and >= 0");
        }
        if (minCrystalsDestroyed < 0 || minRevivesCompleted < 0) {
            throw new IllegalArgumentException("minCrystalsDestroyed and minRevivesCompleted must be >= 0");
        }
        params = immutableCopy(params);
    }

    public static AchievementTriggerSpec endPortalTouch() {
        return endPortalTouch("");
    }

    public static AchievementTriggerSpec endPortalTouch(@Nullable String worldName) {
        return new AchievementTriggerSpec(
                AchievementTriggerType.END_PORTAL_TOUCH,
                "", null, worldName, "", "", "", "",
                0L, 0, 0, 0.0, 0, 0, Map.of()
        );
    }

    public static AchievementTriggerSpec encounterClear(
            @Nullable String encounterId,
            @Nullable EncounterDifficulty difficulty) {
        return new AchievementTriggerSpec(
                AchievementTriggerType.ENCOUNTER_CLEAR,
                encounterId, difficulty, "", "", "", "", "",
                0L, 0, 0, 0.0, 0, 0, Map.of()
        );
    }

    public static AchievementTriggerSpec serverFirst(
            @Nullable String encounterId,
            @Nullable EncounterDifficulty difficulty) {
        return new AchievementTriggerSpec(
                AchievementTriggerType.SERVER_FIRST,
                encounterId, difficulty, "", "", "", "", "",
                0L, 0, 0, 0.0, 0, 0, Map.of()
        );
    }

    public static AchievementTriggerSpec noDeath(
            @Nullable String encounterId,
            @Nullable EncounterDifficulty difficulty) {
        return new AchievementTriggerSpec(
                AchievementTriggerType.NO_DEATH,
                encounterId, difficulty, "", "", "", "", "",
                0L, 0, 0, 0.0, 0, 0, Map.of()
        );
    }

    public static AchievementTriggerSpec mechanicPerfect(
            @Nullable String mechanicId,
            @Nullable String sourceEnemyId) {
        return new AchievementTriggerSpec(
                AchievementTriggerType.MECHANIC_PERFECT,
                "", null, "", mechanicId, sourceEnemyId, "", "",
                0L, 0, 0, 0.0, 0, 0, Map.of()
        );
    }

    public static AchievementTriggerSpec customScript(
            @Nullable String customEventKey,
            String conditionHook,
            @Nullable Map<String, Object> params) {
        return new AchievementTriggerSpec(
                AchievementTriggerType.CUSTOM_SCRIPT,
                "", null, "", "", "", customEventKey, conditionHook,
                0L, 0, 0, 0.0, 0, 0, params
        );
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
