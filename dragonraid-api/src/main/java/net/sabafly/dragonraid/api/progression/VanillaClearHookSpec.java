package net.sabafly.dragonraid.api.progression;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            encodeCanonicalValue(md, params);
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

    private static void encodeCanonicalValue(MessageDigest md, @Nullable Object value)
            throws NoSuchAlgorithmException {
        if (value == null) {
            updateDigest(md, "NULL");
            return;
        }
        if (value instanceof Boolean b) {
            updateDigest(md, "BOOL");
            updateDigest(md, b ? "1" : "0");
            return;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            updateDigest(md, "INT");
            updateDigest(md, Long.toString(((Number) value).longValue()));
            return;
        }
        if (value instanceof Float || value instanceof Double) {
            updateDigest(md, "DEC");
            double d = ((Number) value).doubleValue();
            updateDigest(md, Double.toString(d == 0.0 ? 0.0 : d));
            return;
        }
        if (value instanceof Number n) {
            updateDigest(md, "NUM:" + n.getClass().getName());
            updateDigest(md, n.toString());
            return;
        }
        if (value instanceof Enum<?> e) {
            updateDigest(md, "ENUM:" + e.getDeclaringClass().getName());
            updateDigest(md, e.name());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            updateDigest(md, "MAP");
            updateInt(md, map.size());
            record EncodedEntry(byte[] keyBytes, @Nullable Object val) {}
            List<EncodedEntry> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add(new EncodedEntry(canonicalBytes(entry.getKey()), entry.getValue()));
            }
            entries.sort((a, b) -> Arrays.compareUnsigned(a.keyBytes(), b.keyBytes()));
            for (EncodedEntry entry : entries) {
                updateInt(md, entry.keyBytes().length);
                md.update(entry.keyBytes());
                encodeCanonicalValue(md, entry.val());
            }
            return;
        }
        if (value instanceof Set<?> set) {
            updateDigest(md, "SET");
            updateInt(md, set.size());
            List<byte[]> encoded = new ArrayList<>(set.size());
            for (Object elem : set) {
                encoded.add(canonicalBytes(elem));
            }
            encoded.sort(Arrays::compareUnsigned);
            for (byte[] bytes : encoded) {
                updateInt(md, bytes.length);
                md.update(bytes);
            }
            return;
        }
        if (value instanceof List<?> list) {
            updateDigest(md, "LIST");
            updateInt(md, list.size());
            for (Object elem : list) {
                encodeCanonicalValue(md, elem);
            }
            return;
        }
        if (value instanceof Character c) {
            updateDigest(md, "CHAR");
            updateDigest(md, c.toString());
            return;
        }
        updateDigest(md, "STR:" + value.getClass().getName());
        updateDigest(md, value.toString());
    }

    private static byte[] canonicalBytes(@Nullable Object value) throws NoSuchAlgorithmException {
        MessageDigest sub = MessageDigest.getInstance("SHA-256");
        encodeCanonicalValue(sub, value);
        return sub.digest();
    }

    private static void updateInt(MessageDigest md, int value) {
        md.update((byte) (value >>> 24));
        md.update((byte) (value >>> 16));
        md.update((byte) (value >>> 8));
        md.update((byte) value);
    }

    private static void updateDigest(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(md, bytes.length);
        md.update(bytes);
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
