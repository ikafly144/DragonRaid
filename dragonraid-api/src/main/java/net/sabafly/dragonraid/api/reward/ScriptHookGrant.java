package net.sabafly.dragonraid.api.reward;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable script action hook output from {@link RewardTable} evaluation.
 */
public record ScriptHookGrant(
        String hookId,
        Map<String, Object> params,
        @Nullable String claimKey
) implements RewardGrant {

    public ScriptHookGrant {
        if (hookId == null || hookId.isBlank()) {
            throw new IllegalArgumentException("hookId must not be blank");
        }
        hookId = hookId.trim();
        params = immutableCopy(params);
        claimKey = (claimKey == null || claimKey.isBlank()) ? null : claimKey.trim();
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
