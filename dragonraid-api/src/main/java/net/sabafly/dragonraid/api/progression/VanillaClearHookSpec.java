package net.sabafly.dragonraid.api.progression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.jetbrains.annotations.Nullable;

/**
 * Vanilla world progression actions executed when a matching raid encounter is cleared.
 */
public record VanillaClearHookSpec(
        String encounterId,
        @Nullable EncounterDifficulty difficulty,
        boolean firstClearOnly,
        boolean serverFirstOnly,
        String endWorldName,
        boolean syncDragonBattleDefeated,
        boolean generateExitPortal,
        boolean removeLiveVanillaDragon,
        int spawnGatewayCount,
        int maxGateways,
        String scriptHookId,
        Map<String, Object> params
) {

    public static final int MAX_VANILLA_GATEWAYS = 20;

    public VanillaClearHookSpec {
        encounterId = normalize(encounterId);
        if (encounterId.isEmpty()) {
            throw new IllegalArgumentException("encounterId must not be blank");
        }
        endWorldName = normalize(endWorldName);
        if (spawnGatewayCount < 0) {
            throw new IllegalArgumentException("spawnGatewayCount must be >= 0");
        }
        if (maxGateways < 0 || maxGateways > MAX_VANILLA_GATEWAYS) {
            throw new IllegalArgumentException("maxGateways must be between 0 and " + MAX_VANILLA_GATEWAYS);
        }
        scriptHookId = normalize(scriptHookId);
        params = immutableCopy(params);
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
