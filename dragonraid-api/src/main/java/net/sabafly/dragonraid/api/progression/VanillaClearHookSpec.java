package net.sabafly.dragonraid.api.progression;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.util.ImmutableSnapshots;
import org.jetbrains.annotations.Nullable;

/**
 * Vanilla world progression actions executed when a matching raid encounter is cleared.
 */
public record VanillaClearHookSpec(
        String id,
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
        id = normalize(id);
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
        params = ImmutableSnapshots.copyStringMap(params);
    }

    public VanillaClearHookSpec(
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
            @Nullable Map<String, Object> params) {
        this(
                "",
                encounterId,
                difficulty,
                firstClearOnly,
                serverFirstOnly,
                endWorldName,
                syncDragonBattleDefeated,
                generateExitPortal,
                removeLiveVanillaDragon,
                spawnGatewayCount,
                maxGateways,
                scriptHookId,
                params
        );
    }

    /**
     * Returns an order-independent stable identifier for checkpointing this clear hook:
     * the explicit configured {@link #id()}, or a deterministic structural digest when absent.
     */
    public String stableKey() {
        if (!id.isEmpty()) {
            return "explicit:" + id;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateDigest(md, encounterId);
            updateDigest(md, difficulty != null ? difficulty.name() : "");
            updateDigest(md, Boolean.toString(firstClearOnly));
            updateDigest(md, Boolean.toString(serverFirstOnly));
            updateDigest(md, endWorldName);
            updateDigest(md, Boolean.toString(syncDragonBattleDefeated));
            updateDigest(md, Boolean.toString(generateExitPortal));
            updateDigest(md, Boolean.toString(removeLiveVanillaDragon));
            updateDigest(md, Integer.toString(spawnGatewayCount));
            updateDigest(md, Integer.toString(maxGateways));
            updateDigest(md, scriptHookId);
            params.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        updateDigest(md, entry.getKey());
                        updateDigest(md, Objects.toString(entry.getValue(), ""));
                    });
            byte[] hash = md.digest();
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((hash[i] >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return "hash:" + hex;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void updateDigest(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        md.update((byte) (bytes.length >>> 8));
        md.update((byte) bytes.length);
        md.update(bytes);
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
