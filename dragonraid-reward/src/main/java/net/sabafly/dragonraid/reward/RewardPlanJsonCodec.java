package net.sabafly.dragonraid.reward;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.sabafly.dragonraid.api.reward.ContentUnlockGrant;
import net.sabafly.dragonraid.api.reward.CurrencyGrant;
import net.sabafly.dragonraid.api.reward.CustomItemGrant;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardGrant;
import net.sabafly.dragonraid.api.reward.ScriptHookGrant;
import net.sabafly.dragonraid.api.reward.VanillaItemGrant;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.util.ImmutableSnapshots;
import org.jetbrains.annotations.Nullable;

/**
 * Pure-Java versioned JSON codec ({@code "v": 1}) for durable {@code player_reward_plans} entries.
 */
final class RewardPlanJsonCodec {

    static final int SCHEMA_VERSION = 1;

    private RewardPlanJsonCodec() {
    }

    sealed interface DecodeResult {
        record Parsed(RewardPlan plan) implements DecodeResult {}
        record UnsupportedVersion(int version) implements DecodeResult {}
        record Malformed(String reason) implements DecodeResult {}
    }

    static final class RewardPlan {
        final int version;
        final String planKey;
        final String rootTableId;
        final UUID playerId;
        final @Nullable String playerName;
        final String sourceType;
        final String sourceId;
        final @Nullable EncounterDifficulty difficulty;
        final boolean firstClear;
        final boolean serverFirst;
        final boolean noDeath;
        final int partySize;
        final long durationMillis;
        final double luck;
        final Map<String, Object> attributes;
        final List<PlanStep> steps;

        RewardPlan(
                int version,
                String planKey,
                String rootTableId,
                UUID playerId,
                @Nullable String playerName,
                String sourceType,
                String sourceId,
                @Nullable EncounterDifficulty difficulty,
                boolean firstClear,
                boolean serverFirst,
                boolean noDeath,
                int partySize,
                long durationMillis,
                double luck,
                Map<String, Object> attributes,
                List<PlanStep> steps) {
            this.version = version;
            this.planKey = Objects.requireNonNull(planKey, "planKey");
            this.rootTableId = rootTableId == null ? "" : rootTableId;
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.playerName = (playerName == null || playerName.isBlank()) ? null : playerName;
            this.sourceType = sourceType == null ? "" : sourceType;
            this.sourceId = sourceId == null ? "" : sourceId;
            this.difficulty = difficulty;
            this.firstClear = firstClear;
            this.serverFirst = serverFirst;
            this.noDeath = noDeath;
            this.partySize = Math.max(0, partySize);
            this.durationMillis = Math.max(0L, durationMillis);
            this.luck = Double.isFinite(luck) ? luck : 0.0;
            this.attributes = ImmutableSnapshots.copyStringMap(attributes);
            this.steps = new ArrayList<>(steps);
        }

        static RewardPlan fromGrants(String planKey, String rootTableId, RewardContext ctx, List<RewardGrant> grants) {
            List<PlanStep> steps = new ArrayList<>(grants.size());
            for (int i = 0; i < grants.size(); i++) {
                steps.add(PlanStep.fromGrant(planKey + "#s" + i, grants.get(i)));
            }
            return new RewardPlan(
                    SCHEMA_VERSION,
                    planKey,
                    rootTableId,
                    ctx.playerId(),
                    ctx.playerName(),
                    ctx.sourceType(),
                    ctx.sourceId(),
                    ctx.difficulty(),
                    ctx.firstClear(),
                    ctx.serverFirst(),
                    ctx.noDeath(),
                    ctx.partySize(),
                    ctx.durationMillis(),
                    ctx.luck(),
                    ctx.attributes(),
                    steps
            );
        }

        boolean allCompleted() {
            for (PlanStep step : steps) {
                if (!step.completed) {
                    return false;
                }
            }
            return true;
        }

        RewardBundle toBundle() {
            List<RewardGrant> grants = new ArrayList<>(steps.size());
            for (PlanStep step : steps) {
                grants.add(step.toOriginalGrant(planKey));
            }
            return new RewardBundle(rootTableId, playerId, grants, Set.of(planKey));
        }

        RewardContext toContext(@Nullable String overridePlayerName) {
            String effectiveName = (overridePlayerName != null && !overridePlayerName.isBlank())
                    ? overridePlayerName
                    : playerName;
            RewardContext.Builder builder = RewardContext.builder(playerId)
                    .playerName(effectiveName)
                    .sourceType(sourceType)
                    .sourceId(sourceId)
                    .firstClear(firstClear)
                    .serverFirst(serverFirst)
                    .noDeath(noDeath)
                    .partySize(partySize)
                    .durationMillis(durationMillis)
                    .luck(luck)
                    .attributes(attributes);
            if (difficulty != null) {
                builder.difficulty(difficulty);
            }
            return builder.build();
        }
    }

    static final class PlanStep {
        final String id;
        final String type;
        boolean completed;
        // Currency
        final @Nullable String currencyId;
        final long amount;
        // Vanilla / Custom Item
        final @Nullable String targetName;
        final int requestedAmount;
        int remainingAmount;
        // Unlock
        final boolean notifyPlayer;
        final @Nullable String titleKey;
        final @Nullable String subtitleKey;
        final @Nullable String toastTitleKey;
        final @Nullable String toastDescriptionKey;
        final @Nullable String iconMaterial;
        final @Nullable String soundKey;
        // ScriptHook
        final @Nullable String hookId;
        final Map<String, Object> params;

        private PlanStep(
                String id,
                String type,
                boolean completed,
                @Nullable String currencyId,
                long amount,
                @Nullable String targetName,
                int requestedAmount,
                int remainingAmount,
                boolean notifyPlayer,
                @Nullable String titleKey,
                @Nullable String subtitleKey,
                @Nullable String toastTitleKey,
                @Nullable String toastDescriptionKey,
                @Nullable String iconMaterial,
                @Nullable String soundKey,
                @Nullable String hookId,
                Map<String, Object> params) {
            this.id = Objects.requireNonNull(id, "id");
            this.type = Objects.requireNonNull(type, "type");
            this.completed = completed;
            this.currencyId = currencyId;
            this.amount = amount;
            this.targetName = targetName;
            this.requestedAmount = requestedAmount;
            this.remainingAmount = remainingAmount;
            this.notifyPlayer = notifyPlayer;
            this.titleKey = titleKey;
            this.subtitleKey = subtitleKey;
            this.toastTitleKey = toastTitleKey;
            this.toastDescriptionKey = toastDescriptionKey;
            this.iconMaterial = iconMaterial;
            this.soundKey = soundKey;
            this.hookId = hookId;
            this.params = ImmutableSnapshots.copyStringMap(params);
        }

        static PlanStep fromGrant(String id, RewardGrant grant) {
            return switch (grant) {
                case CurrencyGrant cg -> new PlanStep(
                        id, "CURRENCY", false, cg.currencyId(), cg.amount(),
                        null, 0, 0, false, null, null, null, null, null, null, null, Map.of());
                case VanillaItemGrant vg -> new PlanStep(
                        id, "VANILLA_ITEM", false, null, 0L,
                        vg.materialName(), vg.amount(), vg.amount(), false, null, null, null, null, null, null, null, Map.of());
                case CustomItemGrant ig -> new PlanStep(
                        id, "CUSTOM_ITEM", false, null, 0L,
                        ig.customItemId(), ig.amount(), ig.amount(), false, null, null, null, null, null, null, null, Map.of());
                case ContentUnlockGrant ug -> new PlanStep(
                        id, "UNLOCK_CONTENT", false, null, 0L,
                        ug.contentId(), 0, 0, ug.notifyPlayer(),
                        ug.titleKey(), ug.subtitleKey(), ug.toastTitleKey(), ug.toastDescriptionKey(),
                        ug.iconMaterial(), ug.soundKey(), null, Map.of());
                case ScriptHookGrant sg -> new PlanStep(
                        id, "SCRIPT_HOOK", false, null, 0L,
                        null, 0, 0, false, null, null, null, null, null, null, sg.hookId(), sg.params());
            };
        }

        RewardGrant toOriginalGrant(String planKey) {
            return toGrantWithAmount(planKey, requestedAmount);
        }

        RewardGrant toPendingGrant(String planKey) {
            return toGrantWithAmount(planKey, Math.max(1, remainingAmount));
        }

        private RewardGrant toGrantWithAmount(String planKey, int itemAmount) {
            return switch (type) {
                case "CURRENCY" -> new CurrencyGrant(Objects.requireNonNull(currencyId), amount, planKey);
                case "VANILLA_ITEM" -> new VanillaItemGrant(Objects.requireNonNull(targetName), itemAmount, planKey);
                case "CUSTOM_ITEM" -> new CustomItemGrant(Objects.requireNonNull(targetName), itemAmount, planKey);
                case "UNLOCK_CONTENT" -> new ContentUnlockGrant(
                        Objects.requireNonNull(targetName),
                        notifyPlayer,
                        nullToEmpty(titleKey),
                        nullToEmpty(subtitleKey),
                        nullToEmpty(toastTitleKey),
                        nullToEmpty(toastDescriptionKey),
                        nullToEmpty(iconMaterial),
                        nullToEmpty(soundKey),
                        planKey
                );
                case "SCRIPT_HOOK" -> new ScriptHookGrant(Objects.requireNonNull(hookId), params, planKey);
                default -> throw new IllegalStateException("Unsupported reward step type: " + type);
            };
        }

        private static String nullToEmpty(@Nullable String value) {
            return value == null ? "" : value;
        }
    }

    static String encode(RewardPlan plan) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", plan.version);
        root.put("planKey", plan.planKey);
        root.put("rootTableId", plan.rootTableId);
        root.put("playerId", plan.playerId.toString());
        root.put("playerName", plan.playerName);
        root.put("sourceType", plan.sourceType);
        root.put("sourceId", plan.sourceId);
        root.put("difficulty", plan.difficulty != null ? plan.difficulty.name() : null);
        root.put("firstClear", plan.firstClear);
        root.put("serverFirst", plan.serverFirst);
        root.put("noDeath", plan.noDeath);
        root.put("partySize", plan.partySize);
        root.put("durationMillis", plan.durationMillis);
        root.put("luck", plan.luck);
        root.put("attributes", plan.attributes);

        List<Map<String, Object>> stepList = new ArrayList<>(plan.steps.size());
        for (PlanStep step : plan.steps) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", step.id);
            s.put("type", step.type);
            s.put("completed", step.completed);
            switch (step.type) {
                case "CURRENCY" -> {
                    s.put("currencyId", step.currencyId);
                    s.put("amount", step.amount);
                }
                case "VANILLA_ITEM" -> {
                    s.put("materialName", step.targetName);
                    s.put("requestedAmount", step.requestedAmount);
                    s.put("remainingAmount", step.remainingAmount);
                }
                case "CUSTOM_ITEM" -> {
                    s.put("customItemId", step.targetName);
                    s.put("requestedAmount", step.requestedAmount);
                    s.put("remainingAmount", step.remainingAmount);
                }
                case "UNLOCK_CONTENT" -> {
                    s.put("contentId", step.targetName);
                    s.put("notifyPlayer", step.notifyPlayer);
                    s.put("titleKey", step.titleKey);
                    s.put("subtitleKey", step.subtitleKey);
                    s.put("toastTitleKey", step.toastTitleKey);
                    s.put("toastDescriptionKey", step.toastDescriptionKey);
                    s.put("iconMaterial", step.iconMaterial);
                    s.put("soundKey", step.soundKey);
                }
                case "SCRIPT_HOOK" -> {
                    s.put("hookId", step.hookId);
                    s.put("params", step.params);
                }
                default -> throw new IllegalStateException("Unsupported step type: " + step.type);
            }
            stepList.add(s);
        }
        root.put("steps", stepList);

        StringBuilder sb = new StringBuilder(256);
        writeJsonValue(sb, root);
        return sb.toString();
    }

    static DecodeResult decode(String json) {
        if (json == null || json.isBlank()) {
            return new DecodeResult.Malformed("empty json");
        }
        try {
            Object parsed = new JsonParser(json).parse();
            if (!(parsed instanceof Map<?, ?> rawMap)) {
                return new DecodeResult.Malformed("root must be a JSON object");
            }
            Object rawVersion = rawMap.get("v");
            if (!(rawVersion instanceof Number numVersion)) {
                return new DecodeResult.Malformed("missing schema version 'v'");
            }
            int version = numVersion.intValue();
            if (version != SCHEMA_VERSION || numVersion.doubleValue() != version) {
                return new DecodeResult.UnsupportedVersion(version);
            }

            String planKey = reqString(rawMap, "planKey");
            String rootTableId = optString(rawMap, "rootTableId", "");
            UUID playerId = UUID.fromString(reqString(rawMap, "playerId"));
            @Nullable String playerName = optNullableString(rawMap, "playerName");
            String sourceType = optString(rawMap, "sourceType", "");
            String sourceId = optString(rawMap, "sourceId", "");
            @Nullable String diffStr = optNullableString(rawMap, "difficulty");
            @Nullable EncounterDifficulty difficulty = diffStr == null ? null : EncounterDifficulty.valueOf(diffStr);
            boolean firstClear = optBoolean(rawMap, "firstClear", false);
            boolean serverFirst = optBoolean(rawMap, "serverFirst", false);
            boolean noDeath = optBoolean(rawMap, "noDeath", false);
            int partySize = (int) optLong(rawMap, "partySize", 0L);
            long durationMillis = optLong(rawMap, "durationMillis", 0L);
            double luck = optDouble(rawMap, "luck", 0.0);
            Map<String, Object> attributes = asStringObjectMap(rawMap.get("attributes"));

            Object rawSteps = rawMap.get("steps");
            if (!(rawSteps instanceof List<?> stepList)) {
                return new DecodeResult.Malformed("missing 'steps' array");
            }
            List<PlanStep> steps = new ArrayList<>(stepList.size());
            for (Object entry : stepList) {
                if (!(entry instanceof Map<?, ?> sm)) {
                    return new DecodeResult.Malformed("step entry must be an object");
                }
                String id = reqString(sm, "id");
                String type = reqString(sm, "type");
                boolean completed = optBoolean(sm, "completed", false);
                PlanStep step = switch (type) {
                    case "CURRENCY" -> new PlanStep(
                            id, type, completed,
                            reqString(sm, "currencyId"),
                            reqLong(sm, "amount"),
                            null, 0, 0, false, null, null, null, null, null, null, null, Map.of());
                    case "VANILLA_ITEM" -> new PlanStep(
                            id, type, completed,
                            null, 0L,
                            reqString(sm, "materialName"),
                            (int) reqLong(sm, "requestedAmount"),
                            (int) reqLong(sm, "remainingAmount"),
                            false, null, null, null, null, null, null, null, Map.of());
                    case "CUSTOM_ITEM" -> new PlanStep(
                            id, type, completed,
                            null, 0L,
                            reqString(sm, "customItemId"),
                            (int) reqLong(sm, "requestedAmount"),
                            (int) reqLong(sm, "remainingAmount"),
                            false, null, null, null, null, null, null, null, Map.of());
                    case "UNLOCK_CONTENT" -> new PlanStep(
                            id, type, completed,
                            null, 0L,
                            reqString(sm, "contentId"),
                            0, 0,
                            optBoolean(sm, "notifyPlayer", false),
                            optString(sm, "titleKey", ""),
                            optString(sm, "subtitleKey", ""),
                            optString(sm, "toastTitleKey", ""),
                            optString(sm, "toastDescriptionKey", ""),
                            optString(sm, "iconMaterial", ""),
                            optString(sm, "soundKey", ""),
                            null, Map.of());
                    case "SCRIPT_HOOK" -> new PlanStep(
                            id, type, completed,
                            null, 0L, null, 0, 0, false,
                            null, null, null, null, null, null,
                            reqString(sm, "hookId"),
                            asStringObjectMap(sm.get("params")));
                    default -> throw new IllegalArgumentException("Unknown step type: " + type);
                };
                steps.add(step);
            }

            return new DecodeResult.Parsed(new RewardPlan(
                    version, planKey, rootTableId, playerId, playerName, sourceType, sourceId,
                    difficulty, firstClear, serverFirst, noDeath, partySize, durationMillis, luck, attributes, steps));
        } catch (RuntimeException ex) {
            return new DecodeResult.Malformed(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    private static String reqString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof String s)) {
            throw new IllegalArgumentException("Expected string for key '" + key + "'");
        }
        return s;
    }

    private static String optString(Map<?, ?> map, String key, String def) {
        Object val = map.get(key);
        return val instanceof String s ? s : def;
    }

    private static @Nullable String optNullableString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }

    private static boolean optBoolean(Map<?, ?> map, String key, boolean def) {
        Object val = map.get(key);
        return val instanceof Boolean b ? b : def;
    }

    private static long reqLong(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof Number n)) {
            throw new IllegalArgumentException("Expected number for key '" + key + "'");
        }
        return n.longValue();
    }

    private static long optLong(Map<?, ?> map, String key, long def) {
        Object val = map.get(key);
        return val instanceof Number n ? n.longValue() : def;
    }

    private static double optDouble(Map<?, ?> map, String key, double def) {
        Object val = map.get(key);
        return val instanceof Number n ? n.doubleValue() : def;
    }

    private static Map<String, Object> asStringObjectMap(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static void writeJsonValue(StringBuilder sb, @Nullable Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue() ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            sb.append(value);
        } else if (value instanceof Double d) {
            sb.append(Double.isFinite(d) ? d.toString() : "0.0");
        } else if (value instanceof Float f) {
            sb.append(Float.isFinite(f) ? f.toString() : "0.0");
        } else if (value instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof CharSequence cs) {
            writeJsonString(sb, cs.toString());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeJsonValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> col) {
            sb.append('[');
            boolean first = true;
            for (Object elem : col) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonValue(sb, elem);
            }
            sb.append(']');
        } else {
            writeJsonString(sb, String.valueOf(value));
        }
    }

    private static void writeJsonString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class JsonParser {
        private final String input;
        private int pos;

        JsonParser(String input) {
            this.input = input;
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (pos != input.length()) {
                throw new IllegalArgumentException("Unexpected trailing JSON content at " + pos);
            }
            return value;
        }

        private @Nullable Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = input.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected JSON char '" + c + "' at " + pos);
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (peek('}')) {
                    pos++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    pos++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= input.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > input.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            int codePoint = Integer.parseInt(input.substring(pos, pos + 4), 16);
                            sb.append((char) codePoint);
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape char '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Number parseNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') {
                pos++;
            }
            boolean isFloat = false;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if ((c == '+' || c == '-') && pos == start) {
                        break;
                    }
                    if ((c == '+' || c == '-') && pos > start
                            && input.charAt(pos - 1) != 'e' && input.charAt(pos - 1) != 'E') {
                        break;
                    }
                    isFloat = true;
                    pos++;
                } else {
                    break;
                }
            }
            String token = input.substring(start, pos);
            if (!isFloat) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException ignored) {
                    return new BigInteger(token);
                }
            }
            return Double.parseDouble(token);
        }

        private <T> T parseLiteral(String literal, T value) {
            if (!input.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Expected '" + literal + "' at " + pos);
            }
            pos += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private boolean peek(char expected) {
            return pos < input.length() && input.charAt(pos) == expected;
        }

        private void expect(char expected) {
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + pos);
            }
            pos++;
        }
    }
}
