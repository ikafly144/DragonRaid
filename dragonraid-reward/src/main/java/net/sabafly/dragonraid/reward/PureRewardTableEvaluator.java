package net.sabafly.dragonraid.reward;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import net.sabafly.dragonraid.api.reward.ContentUnlockGrant;
import net.sabafly.dragonraid.api.reward.CurrencyGrant;
import net.sabafly.dragonraid.api.reward.CustomItemGrant;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardConditionSpec;
import net.sabafly.dragonraid.api.reward.RewardConditionType;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardEntrySpec;
import net.sabafly.dragonraid.api.reward.RewardFunctionSpec;
import net.sabafly.dragonraid.api.reward.RewardGrant;
import net.sabafly.dragonraid.api.reward.RewardPool;
import net.sabafly.dragonraid.api.reward.RewardPoolMode;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.ScriptHookGrant;
import net.sabafly.dragonraid.api.reward.VanillaItemGrant;
import net.sabafly.dragonraid.api.script.ScriptHookContext;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.api.service.ReadOnlyRaidServiceRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * Side-effect-free evaluator turning a {@link RewardTable} and {@link RewardContext} into a {@link RewardBundle}.
 */
public final class PureRewardTableEvaluator {

    public static final int MAX_NESTING_DEPTH = 16;
    public static final int MAX_EVALUATED_ENTRIES = 10_000;
    public static final int MAX_GENERATED_GRANTS = 10_000;
    public static final String INTERNAL_SF_CLAIM_PREFIX = "__sys:sf:";

    private final @Nullable ScriptHookRegistry scriptHookRegistry;
    private final @Nullable RaidServiceRegistry serviceRegistry;

    public PureRewardTableEvaluator() {
        this(null, null);
    }

    public PureRewardTableEvaluator(
            @Nullable ScriptHookRegistry scriptHookRegistry,
            @Nullable RaidServiceRegistry serviceRegistry) {
        this.scriptHookRegistry = scriptHookRegistry;
        this.serviceRegistry = serviceRegistry;
    }

    public RewardBundle evaluate(
            Function<String, Optional<RewardTable>> tableLookup,
            String rootTableId,
            RewardContext context,
            RandomGenerator random) {
        Objects.requireNonNull(tableLookup, "tableLookup");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(random, "random");
        if (rootTableId == null || rootTableId.isBlank()) {
            throw new IllegalArgumentException("rootTableId must not be blank");
        }
        String normalizedRoot = rootTableId.trim();
        RewardTable root = tableLookup.apply(normalizedRoot)
                .orElseThrow(() -> new IllegalArgumentException("Unknown reward table: " + normalizedRoot));

        List<RewardGrant> grants = new ArrayList<>();
        Set<String> activeStack = new LinkedHashSet<>();
        EvaluationBudget budget = new EvaluationBudget();
        evaluateTable(tableLookup, root, context, random, activeStack, 0, null, budget, grants);
        return new RewardBundle(normalizedRoot, context.playerId(), grants);
    }

    public RewardBundle evaluateAll(
            Function<String, Optional<RewardTable>> tableLookup,
            Collection<String> tableIds,
            RewardContext context,
            RandomGenerator random) {
        Objects.requireNonNull(tableLookup, "tableLookup");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(random, "random");
        if (tableIds == null || tableIds.isEmpty()) {
            return RewardBundle.empty("", context.playerId());
        }
        EvaluationBudget budget = new EvaluationBudget();
        List<RewardGrant> grants = new ArrayList<>();
        StringBuilder rootIds = new StringBuilder();
        for (String tableId : tableIds) {
            if (tableId == null || tableId.isBlank()) {
                continue;
            }
            String normalizedRoot = tableId.trim();
            RewardTable root = tableLookup.apply(normalizedRoot)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown reward table: " + normalizedRoot));
            evaluateTable(tableLookup, root, context, random, new LinkedHashSet<>(), 0, null, budget, grants);
            if (!rootIds.isEmpty()) {
                rootIds.append(',');
            }
            rootIds.append(normalizedRoot);
        }
        return rootIds.isEmpty()
                ? RewardBundle.empty("", context.playerId())
                : new RewardBundle(rootIds.toString(), context.playerId(), grants);
    }

    private void evaluateTable(
            Function<String, Optional<RewardTable>> tableLookup,
            RewardTable table,
            RewardContext context,
            RandomGenerator random,
            Set<String> activeStack,
            int depth,
            @Nullable String inheritedClaimKey,
            EvaluationBudget budget,
            List<RewardGrant> out) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IllegalStateException(
                    "Reward table nesting depth exceeded " + MAX_NESTING_DEPTH + " at " + table.id());
        }
        if (!activeStack.add(table.id())) {
            throw new IllegalStateException(
                    "Circular reward table reference detected: " + String.join(" -> ", activeStack) + " -> " + table.id());
        }
        try {
            List<RewardPool> pools = table.pools();
            for (int poolIndex = 0; poolIndex < pools.size(); poolIndex++) {
                RewardPool pool = pools.get(poolIndex);
                String poolKeySegment = pool.id().isBlank() ? "#" + poolIndex : pool.id();
                String effectiveClaimKey = pool.claimKey() != null ? pool.claimKey() : inheritedClaimKey;
                if (effectiveClaimKey == null
                        && context.serverFirst()
                        && hasServerFirstCondition(pool.conditions())) {
                    effectiveClaimKey = INTERNAL_SF_CLAIM_PREFIX
                            + resolveSourceScope(context, table) + ":" + table.id() + ":" + poolKeySegment;
                }
                if (effectiveClaimKey != null && context.claimedKeys().contains(effectiveClaimKey)) {
                    continue;
                }
                if (!allConditionsMatch(pool.conditions(), context, random)) {
                    continue;
                }
                int baseRolls = pool.rolls().roll(random);
                double bonusRolls = Math.floor(pool.bonusRollsPerLuck() * context.luck());
                double rawTotalRolls = baseRolls + bonusRolls;
                if (!Double.isFinite(rawTotalRolls)) {
                    throw new IllegalArgumentException(
                            "Effective roll count must be finite for pool " + pool.id());
                }
                if (rawTotalRolls > RewardPool.MAX_EFFECTIVE_ROLLS) {
                    throw new IllegalArgumentException(
                            "Effective roll count (" + rawTotalRolls + ") exceeds maximum "
                                    + RewardPool.MAX_EFFECTIVE_ROLLS + " for pool " + pool.id());
                }
                int totalRolls = rawTotalRolls <= 0.0 ? 0 : (int) rawTotalRolls;

                for (int rollIndex = 0; rollIndex < totalRolls; rollIndex++) {
                    List<IndexedEntry> eligible = filterEligibleEntries(pool.entries(), context, random);
                    if (eligible.isEmpty()) {
                        continue;
                    }
                    if (pool.mode() == RewardPoolMode.ALL) {
                        for (IndexedEntry indexed : eligible) {
                            String entryClaimKey = resolveEntryClaimKey(
                                    effectiveClaimKey, context, table, poolKeySegment, indexed.index(), indexed.entry());
                            if (entryClaimKey != null && context.claimedKeys().contains(entryClaimKey)) {
                                continue;
                            }
                            evaluateEntry(
                                    tableLookup, table.id(), pool, indexed.entry(), context, random,
                                    activeStack, depth, entryClaimKey, budget, out);
                        }
                    } else {
                        IndexedEntry picked = pickWeighted(eligible, context.luck(), random);
                        if (picked != null) {
                            String entryClaimKey = resolveEntryClaimKey(
                                    effectiveClaimKey, context, table, poolKeySegment, picked.index(), picked.entry());
                            if (entryClaimKey == null || !context.claimedKeys().contains(entryClaimKey)) {
                                evaluateEntry(
                                        tableLookup, table.id(), pool, picked.entry(), context, random,
                                        activeStack, depth, entryClaimKey, budget, out);
                            }
                        }
                    }
                }
            }
        } finally {
            activeStack.remove(table.id());
        }
    }

    private record IndexedEntry(int index, RewardEntrySpec entry) {
    }

    private List<IndexedEntry> filterEligibleEntries(
            List<RewardEntrySpec> entries,
            RewardContext context,
            RandomGenerator random) {
        List<IndexedEntry> eligible = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            RewardEntrySpec entry = entries.get(i);
            if (allConditionsMatch(entry.conditions(), context, random)) {
                eligible.add(new IndexedEntry(i, entry));
            }
        }
        return eligible;
    }

    private static @Nullable IndexedEntry pickWeighted(
            List<IndexedEntry> entries,
            double luck,
            RandomGenerator random) {
        double[] weights = new double[entries.size()];
        double totalWeight = 0.0;
        int lastPositiveIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            RewardEntrySpec entry = entries.get(i).entry();
            double w = Math.max(0.0, entry.weight() + entry.quality() * luck);
            weights[i] = w;
            if (w > 0.0) {
                totalWeight += w;
                lastPositiveIndex = i;
            }
        }
        if (totalWeight <= 0.0 || lastPositiveIndex < 0) {
            return null;
        }
        double target = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (int i = 0; i < entries.size(); i++) {
            if (weights[i] <= 0.0) {
                continue;
            }
            cumulative += weights[i];
            if (cumulative > target) {
                return entries.get(i);
            }
        }
        return entries.get(lastPositiveIndex);
    }

    private void evaluateEntry(
            Function<String, Optional<RewardTable>> tableLookup,
            String tableId,
            RewardPool pool,
            RewardEntrySpec entry,
            RewardContext context,
            RandomGenerator random,
            Set<String> activeStack,
            int depth,
            @Nullable String effectiveClaimKey,
            EvaluationBudget budget,
            List<RewardGrant> out) {
        String location = tableId + "/" + (pool.id().isBlank() ? "<unnamed>" : pool.id());
        budget.consumeEntry(location);
        switch (entry.type()) {
            case EMPTY -> {
                // Intentionally produces no grant.
            }
            case TABLE_REF -> {
                RewardTable nested = tableLookup.apply(entry.target())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown referenced reward table: " + entry.target()));
                List<RewardGrant> subGrants = new ArrayList<>();
                evaluateTable(tableLookup, nested, context, random, activeStack, depth + 1, effectiveClaimKey, budget, subGrants);
                if (entry.functions().isEmpty() && pool.functions().isEmpty()) {
                    out.addAll(subGrants);
                } else {
                    for (RewardGrant grant : subGrants) {
                        RewardGrant transformed = transformGrantAmount(grant, entry.functions(), pool.functions(), context, random);
                        if (transformed != null) {
                            out.add(transformed);
                        }
                    }
                }
            }
            case CURRENCY -> {
                long amount = resolveFinalAmount(entry.amount().roll(random), entry.functions(), pool.functions(), context, random);
                if (amount > 0L) {
                    recordGrant(out, new CurrencyGrant(entry.target(), amount, effectiveClaimKey), budget, location);
                }
            }
            case VANILLA_ITEM -> {
                long amount = resolveFinalAmount(entry.amount().roll(random), entry.functions(), pool.functions(), context, random);
                if (amount > 0L) {
                    recordGrant(out, new VanillaItemGrant(entry.target(), clampItemAmount(amount), effectiveClaimKey), budget, location);
                }
            }
            case CUSTOM_ITEM -> {
                long amount = resolveFinalAmount(entry.amount().roll(random), entry.functions(), pool.functions(), context, random);
                if (amount > 0L) {
                    recordGrant(out, new CustomItemGrant(entry.target(), clampItemAmount(amount), effectiveClaimKey), budget, location);
                }
            }
            case UNLOCK_CONTENT -> recordGrant(out, new ContentUnlockGrant(
                    entry.target(),
                    entry.notifyPlayer(),
                    entry.titleKey(),
                    entry.subtitleKey(),
                    entry.toastTitleKey(),
                    entry.toastDescriptionKey(),
                    entry.iconMaterial(),
                    entry.soundKey(),
                    effectiveClaimKey
            ), budget, location);
            case SCRIPT_HOOK -> recordGrant(
                    out, new ScriptHookGrant(entry.target(), entry.params(), effectiveClaimKey), budget, location);
        }
    }

    private static void recordGrant(
            List<RewardGrant> out,
            RewardGrant grant,
            EvaluationBudget budget,
            String location) {
        budget.consumeGrant(location);
        out.add(grant);
    }

    private static @Nullable String resolveEntryClaimKey(
            @Nullable String poolClaimKey,
            RewardContext context,
            RewardTable table,
            String poolKeySegment,
            int entryIndex,
            RewardEntrySpec entry) {
        if (poolClaimKey != null) {
            return poolClaimKey;
        }
        if (context.serverFirst() && hasServerFirstCondition(entry.conditions())) {
            return INTERNAL_SF_CLAIM_PREFIX
                    + resolveSourceScope(context, table)
                    + ":" + table.id()
                    + ":" + poolKeySegment
                    + "#e" + entryIndex;
        }
        return null;
    }

    private static boolean hasServerFirstCondition(List<RewardConditionSpec> conditions) {
        for (RewardConditionSpec spec : conditions) {
            if (spec.type() == RewardConditionType.SERVER_FIRST && !spec.negate()) {
                return true;
            }
        }
        return false;
    }

    private static String resolveSourceScope(RewardContext context, RewardTable table) {
        if (!context.sourceType().isBlank() && !context.sourceId().isBlank()) {
            return context.sourceType() + ":" + context.sourceId();
        }
        if (!context.sourceId().isBlank()) {
            return context.sourceId();
        }
        if (!table.packId().isBlank()) {
            return table.packId();
        }
        return "table";
    }

    private static final class EvaluationBudget {
        private int evaluatedEntries;
        private int generatedGrants;

        void consumeEntry(String location) {
            if (++evaluatedEntries > MAX_EVALUATED_ENTRIES) {
                throw new IllegalStateException(
                        "Reward table evaluation exceeded entry budget (" + MAX_EVALUATED_ENTRIES + ") at " + location);
            }
        }

        void consumeGrant(String location) {
            if (++generatedGrants > MAX_GENERATED_GRANTS) {
                throw new IllegalStateException(
                        "Reward table evaluation exceeded grant budget (" + MAX_GENERATED_GRANTS + ") at " + location);
            }
        }
    }

    private @Nullable RewardGrant transformGrantAmount(
            RewardGrant grant,
            List<RewardFunctionSpec> entryFunctions,
            List<RewardFunctionSpec> poolFunctions,
            RewardContext context,
            RandomGenerator random) {
        return switch (grant) {
            case CurrencyGrant cg -> {
                long next = resolveFinalAmount(cg.amount(), entryFunctions, poolFunctions, context, random);
                yield next > 0L ? new CurrencyGrant(cg.currencyId(), next, cg.claimKey()) : null;
            }
            case VanillaItemGrant vg -> {
                long next = resolveFinalAmount(vg.amount(), entryFunctions, poolFunctions, context, random);
                yield next > 0L ? new VanillaItemGrant(vg.materialName(), clampItemAmount(next), vg.claimKey()) : null;
            }
            case CustomItemGrant ig -> {
                long next = resolveFinalAmount(ig.amount(), entryFunctions, poolFunctions, context, random);
                yield next > 0L ? new CustomItemGrant(ig.customItemId(), clampItemAmount(next), ig.claimKey()) : null;
            }
            case ContentUnlockGrant ug -> ug;
            case ScriptHookGrant sg -> sg;
        };
    }

    private long resolveFinalAmount(
            long initialAmount,
            List<RewardFunctionSpec> entryFunctions,
            List<RewardFunctionSpec> poolFunctions,
            RewardContext context,
            RandomGenerator random) {
        long amount = applyFunctions(initialAmount, entryFunctions, context, random);
        return applyFunctions(amount, poolFunctions, context, random);
    }

    private long applyFunctions(
            long initialAmount,
            List<RewardFunctionSpec> functions,
            RewardContext context,
            RandomGenerator random) {
        long amount = initialAmount;
        for (RewardFunctionSpec fn : functions) {
            if (!allConditionsMatch(fn.conditions(), context, random)) {
                continue;
            }
            switch (fn.type()) {
                case SET_AMOUNT -> amount = fn.amountRange() != null ? fn.amountRange().roll(random) : amount;
                case ADD_AMOUNT -> {
                    long delta = fn.amountRange() != null ? fn.amountRange().roll(random) : 0L;
                    amount = saturatingAdd(amount, delta);
                }
                case MULTIPLY_AMOUNT -> {
                    double scaled = amount * fn.factor();
                    long rounded = switch (fn.roundingMode()) {
                        case FLOOR -> (long) Math.floor(scaled);
                        case ROUND -> Math.round(scaled);
                        case CEIL -> (long) Math.ceil(scaled);
                    };
                    amount = Math.max(0L, rounded);
                }
                case ADD_METRIC_SCALED -> {
                    double metricValue = readMetric(fn, context);
                    double steps = Math.floor(Math.max(0.0, metricValue) / fn.step());
                    long bonus = Math.max(0L, (long) Math.floor(steps * fn.perStep()));
                    if (fn.maxBound() > 0L) {
                        bonus = Math.min(bonus, fn.maxBound());
                    }
                    amount = saturatingAdd(amount, bonus);
                }
                case CLAMP_AMOUNT -> amount = Math.max(fn.minBound(), Math.min(fn.maxBound(), amount));
            }
        }
        return amount;
    }

    private boolean allConditionsMatch(
            List<RewardConditionSpec> conditions,
            RewardContext context,
            RandomGenerator random) {
        for (RewardConditionSpec spec : conditions) {
            boolean raw = evaluateCondition(spec, context, random);
            boolean passed = spec.negate() ? !raw : raw;
            if (!passed) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateCondition(
            RewardConditionSpec spec,
            RewardContext context,
            RandomGenerator random) {
        return switch (spec.type()) {
            case FIRST_CLEAR -> context.firstClear();
            case SERVER_FIRST -> context.serverFirst();
            case NO_DEATH -> context.noDeath();
            case DIFFICULTY -> context.difficulty() == spec.difficulty();
            case PARTY_SIZE -> context.partySize() >= spec.minInt()
                    && (spec.maxInt() <= 0 || context.partySize() <= spec.maxInt());
            case MAX_DURATION_MS -> context.durationMillis() > 0L && context.durationMillis() <= spec.longValue();
            case MIN_BOSS_DAMAGE -> context.participantMetrics() != null
                    && context.participantMetrics().totalBossDamage() >= spec.doubleValue();
            case MIN_CRYSTALS_DESTROYED -> context.participantMetrics() != null
                    && context.participantMetrics().totalCrystalsDestroyed() >= spec.minInt();
            case MIN_REVIVES_COMPLETED -> context.participantMetrics() != null
                    && context.participantMetrics().revivesCompleted() >= spec.minInt();
            case RANDOM_CHANCE -> {
                double threshold = Math.max(0.0, Math.min(1.0, spec.doubleValue() + spec.luckMultiplier() * context.luck()));
                yield random.nextDouble() < threshold;
            }
            case HAS_ACHIEVEMENT -> context.unlockedAchievements().contains(spec.target());
            case HAS_UNLOCK -> context.unlockedContents().contains(spec.target());
            case NOT_CLAIMED -> !context.claimedKeys().contains(spec.target());
            case ATTRIBUTE_EQUALS -> {
                Object value = context.attributes().get(spec.target());
                yield value != null && Objects.equals(String.valueOf(value), spec.expectedValue());
            }
            case CUSTOM_SCRIPT -> {
                if (scriptHookRegistry == null) {
                    yield false;
                }
                Collection<UUID> participantIds = context.encounterSnapshot() != null
                        ? List.copyOf(context.encounterSnapshot().participantMetrics().keySet())
                        : List.of(context.playerId());
                ScriptHookContext hookCtx = new ScriptHookContext(
                        ReadOnlyRaidServiceRegistry.wrap(serviceRegistry),
                        context.playerId(),
                        context.playerName(),
                        participantIds,
                        context.encounterSnapshot(),
                        spec.params(),
                        context.attributes()
                );
                yield scriptHookRegistry.evaluateCondition(spec.target(), hookCtx);
            }
        };
    }

    private static double readMetric(RewardFunctionSpec fn, RewardContext context) {
        if (fn.metric() == null) {
            return 0.0;
        }
        return switch (fn.metric()) {
            case BOSS_DAMAGE -> context.participantMetrics() != null
                    ? context.participantMetrics().totalBossDamage()
                    : 0.0;
            case CRYSTALS_DESTROYED -> context.participantMetrics() != null
                    ? context.participantMetrics().totalCrystalsDestroyed()
                    : 0.0;
            case REVIVES_COMPLETED -> context.participantMetrics() != null
                    ? context.participantMetrics().revivesCompleted()
                    : 0.0;
            case PARTY_SIZE -> context.partySize();
            case DURATION_SECONDS -> context.durationMillis() / 1000.0;
            case LUCK -> context.luck();
            case ATTRIBUTE -> {
                Object raw = context.attributes().get(fn.attributeKey());
                if (raw instanceof Number number) {
                    yield number.doubleValue();
                }
                if (raw != null) {
                    try {
                        yield Double.parseDouble(raw.toString().trim());
                    } catch (NumberFormatException ignored) {
                        yield 0.0;
                    }
                }
                yield 0.0;
            }
        };
    }

    private static long saturatingAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return b >= 0L ? Long.MAX_VALUE : 0L;
        }
    }

    private static int clampItemAmount(long amount) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, amount));
    }
}
