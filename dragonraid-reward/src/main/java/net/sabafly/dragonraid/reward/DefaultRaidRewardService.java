package net.sabafly.dragonraid.reward;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotifier;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardApplier;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardCurrencySink;
import net.sabafly.dragonraid.api.reward.RewardEntrySpec;
import net.sabafly.dragonraid.api.reward.RewardEntryType;
import net.sabafly.dragonraid.api.reward.RewardItemDelivery;
import net.sabafly.dragonraid.api.reward.RewardPool;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.RewardTableExtension;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * Registry of Loot-Table-style {@link RewardTable} and cross-pack {@link RewardTableExtension}
 * definitions combining pure evaluation ({@link PureRewardTableEvaluator}) with separated
 * side-effect application ({@link RewardApplier}).
 */
public final class DefaultRaidRewardService implements RaidRewardService {

    private final PureRewardTableEvaluator evaluator;
    private final RewardApplier applier;

    private final Map<String, RewardTable> staticTables = new ConcurrentHashMap<>();
    private final Map<String, RewardTable> packTables = new ConcurrentHashMap<>();
    private final Map<String, RewardTableExtension> staticExtensions = new ConcurrentHashMap<>();
    private final Map<String, RewardTableExtension> packExtensions = new ConcurrentHashMap<>();
    private volatile Map<String, RewardTable> effectiveTables = Map.of();

    public DefaultRaidRewardService() {
        this(new PureRewardTableEvaluator(), new DefaultRewardApplier());
    }

    public DefaultRaidRewardService(PureRewardTableEvaluator evaluator, RewardApplier applier) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.applier = Objects.requireNonNull(applier, "applier");
    }

    public DefaultRaidRewardService(
            @Nullable ContentUnlockService unlockService,
            @Nullable ContentFinderService contentFinderService,
            @Nullable ContentUnlockNotifier unlockNotifier,
            @Nullable RewardCurrencySink currencySink,
            @Nullable RewardItemDelivery itemDelivery,
            @Nullable ScriptHookRegistry scriptHookRegistry,
            @Nullable PlayerProgressionRepository progressionRepository,
            @Nullable RaidServiceRegistry serviceRegistry,
            LongSupplier clockMillis,
            Logger logger) {
        this(
                unlockService,
                contentFinderService,
                unlockNotifier,
                currencySink,
                itemDelivery,
                scriptHookRegistry,
                progressionRepository,
                serviceRegistry,
                clockMillis,
                logger,
                null
        );
    }

    public DefaultRaidRewardService(
            @Nullable ContentUnlockService unlockService,
            @Nullable ContentFinderService contentFinderService,
            @Nullable ContentUnlockNotifier unlockNotifier,
            @Nullable RewardCurrencySink currencySink,
            @Nullable RewardItemDelivery itemDelivery,
            @Nullable ScriptHookRegistry scriptHookRegistry,
            @Nullable PlayerProgressionRepository progressionRepository,
            @Nullable RaidServiceRegistry serviceRegistry,
            LongSupplier clockMillis,
            Logger logger,
            @Nullable Executor scriptExecutor) {
        this(
                new PureRewardTableEvaluator(scriptHookRegistry, serviceRegistry),
                new DefaultRewardApplier(
                        unlockService,
                        contentFinderService,
                        unlockNotifier,
                        currencySink,
                        itemDelivery,
                        scriptHookRegistry,
                        progressionRepository,
                        serviceRegistry,
                        clockMillis,
                        logger,
                        scriptExecutor
                )
        );
    }

    @Override
    public synchronized void registerTable(RewardTable table) {
        Objects.requireNonNull(table, "table");
        Map<String, RewardTable> candidateStatic = new LinkedHashMap<>(staticTables);
        candidateStatic.put(table.id(), table);
        Map<String, RewardTable> nextEffective = mergeAndValidate(
                candidateStatic, packTables, staticExtensions, packExtensions);
        staticTables.put(table.id(), table);
        this.effectiveTables = nextEffective;
    }

    @Override
    public synchronized void registerExtension(RewardTableExtension extension) {
        Objects.requireNonNull(extension, "extension");
        Map<String, RewardTableExtension> candidateExt = new LinkedHashMap<>(staticExtensions);
        candidateExt.put(extension.id(), extension);
        Map<String, RewardTable> nextEffective = mergeAndValidate(
                staticTables, packTables, candidateExt, packExtensions);
        staticExtensions.put(extension.id(), extension);
        this.effectiveTables = nextEffective;
    }

    @Override
    public synchronized void replacePackTables(
            Collection<RewardTable> tables,
            Collection<RewardTableExtension> extensions) {
        Map<String, RewardTable> candidatePackTables = new LinkedHashMap<>();
        if (tables != null) {
            for (RewardTable table : tables) {
                if (table != null) {
                    candidatePackTables.put(table.id(), table);
                }
            }
        }
        Map<String, RewardTableExtension> candidatePackExtensions = new LinkedHashMap<>();
        if (extensions != null) {
            for (RewardTableExtension extension : extensions) {
                if (extension != null) {
                    candidatePackExtensions.put(extension.id(), extension);
                }
            }
        }
        Map<String, RewardTable> nextEffective = mergeAndValidate(
                staticTables, candidatePackTables, staticExtensions, candidatePackExtensions);
        packTables.clear();
        packTables.putAll(candidatePackTables);
        packExtensions.clear();
        packExtensions.putAll(candidatePackExtensions);
        this.effectiveTables = nextEffective;
    }

    @Override
    public Optional<RewardTable> getTable(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(effectiveTables.get(tableId.trim()));
    }

    @Override
    public Collection<RewardTable> tables() {
        return List.copyOf(effectiveTables.values());
    }

    @Override
    public Collection<RewardTableExtension> extensions() {
        Map<String, RewardTableExtension> merged = new LinkedHashMap<>(staticExtensions);
        merged.putAll(packExtensions);
        return List.copyOf(merged.values());
    }

    @Override
    public Set<String> staticTableIds() {
        return Set.copyOf(staticTables.keySet());
    }

    @Override
    public Collection<RewardTable> staticTables() {
        return List.copyOf(staticTables.values());
    }

    @Override
    public Collection<RewardTableExtension> staticExtensions() {
        return List.copyOf(staticExtensions.values());
    }

    @Override
    public Map<String, RewardTable> effectiveTablesSnapshot() {
        return this.effectiveTables;
    }

    @Override
    public RewardBundle evaluate(String tableId, RewardContext context, RandomGenerator random) {
        return evaluate(effectiveTables, tableId, context, random);
    }

    @Override
    public RewardBundle evaluate(
            Map<String, RewardTable> tableSnapshot,
            String tableId,
            RewardContext context,
            RandomGenerator random) {
        Objects.requireNonNull(tableSnapshot, "tableSnapshot");
        return evaluator.evaluate(id -> Optional.ofNullable(tableSnapshot.get(id)), tableId, context, random);
    }

    @Override
    public RewardBundle evaluateAll(Collection<String> tableIds, RewardContext context, RandomGenerator random) {
        return evaluateAll(effectiveTables, tableIds, context, random);
    }

    @Override
    public RewardBundle evaluateAll(
            Map<String, RewardTable> tableSnapshot,
            Collection<String> tableIds,
            RewardContext context,
            RandomGenerator random) {
        Objects.requireNonNull(tableSnapshot, "tableSnapshot");
        return evaluator.evaluateAll(id -> Optional.ofNullable(tableSnapshot.get(id)), tableIds, context, random);
    }

    @Override
    public CompletableFuture<RewardApplyResult> apply(RewardBundle bundle, RewardContext context) {
        return applier.apply(bundle, context);
    }

    @Override
    public CompletableFuture<RewardApplyResult> evaluateAndApply(
            String tableId,
            RewardContext context,
            RandomGenerator random) {
        RewardBundle bundle = evaluate(tableId, context, random);
        return apply(bundle, context);
    }

    @Override
    public CompletableFuture<RewardApplyResult> evaluateAndApplyAll(
            Collection<String> tableIds,
            RewardContext context,
            RandomGenerator random) {
        return evaluateAndApplyAll(effectiveTables, tableIds, context, random);
    }

    @Override
    public CompletableFuture<RewardApplyResult> evaluateAndApplyAll(
            Map<String, RewardTable> tableSnapshot,
            Collection<String> tableIds,
            RewardContext context,
            RandomGenerator random) {
        RewardBundle bundle = evaluateAll(tableSnapshot, tableIds, context, random);
        return apply(bundle, context);
    }

    @Override
    public CompletableFuture<List<RewardApplyResult>> resumePendingPlans(UUID playerId, @Nullable String playerName) {
        Objects.requireNonNull(playerId, "playerId");
        if (applier instanceof DefaultRewardApplier defaultApplier) {
            return defaultApplier.resumePendingPlans(playerId, playerName);
        }
        return CompletableFuture.completedFuture(List.of());
    }

    private static Map<String, RewardTable> mergeAndValidate(
            Map<String, RewardTable> statics,
            Map<String, RewardTable> packs,
            Map<String, RewardTableExtension> staticExts,
            Map<String, RewardTableExtension> packExts) {
        Map<String, RewardTable> base = new LinkedHashMap<>(statics);
        base.putAll(packs);

        Map<String, RewardTableExtension> mergedExtensions = new LinkedHashMap<>(staticExts);
        mergedExtensions.putAll(packExts);

        List<RewardTableExtension> sortedExtensions = mergedExtensions.values().stream()
                .sorted(Comparator.comparingInt(RewardTableExtension::priority)
                        .thenComparing(RewardTableExtension::packId)
                        .thenComparing(RewardTableExtension::id))
                .toList();

        Map<String, List<RewardPool>> poolsByTable = new LinkedHashMap<>();
        for (Map.Entry<String, RewardTable> entry : base.entrySet()) {
            poolsByTable.put(entry.getKey(), new ArrayList<>(entry.getValue().pools()));
        }
        for (RewardTableExtension extension : sortedExtensions) {
            List<RewardPool> targetPools = poolsByTable.get(extension.targetTableId());
            if (targetPools != null) {
                targetPools.addAll(extension.appendPools());
            }
        }

        Map<String, RewardTable> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, RewardTable> entry : base.entrySet()) {
            RewardTable orig = entry.getValue();
            List<RewardPool> pools = poolsByTable.get(entry.getKey());
            rebuilt.put(entry.getKey(), new RewardTable(orig.id(), orig.packId(), pools));
        }

        validateNoTableCycles(rebuilt);
        return Collections.unmodifiableMap(rebuilt);
    }

    private static void validateNoTableCycles(Map<String, RewardTable> effective) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (RewardTable table : effective.values()) {
            Set<String> targets = new LinkedHashSet<>();
            for (RewardPool pool : table.pools()) {
                for (RewardEntrySpec entry : pool.entries()) {
                    if (entry.type() == RewardEntryType.TABLE_REF) {
                        targets.add(entry.target());
                    }
                }
            }
            edges.put(table.id(), targets);
        }
        Map<String, Integer> visitState = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String tableId : edges.keySet()) {
            if (visitState.getOrDefault(tableId, 0) == 0) {
                dfsDetectCycle(tableId, edges, visitState, stack);
            }
        }
    }

    private record DfsFrame(String node, Iterator<String> neighbors) {
    }

    private static void dfsDetectCycle(
            String start,
            Map<String, Set<String>> edges,
            Map<String, Integer> visitState,
            Deque<String> path) {
        Deque<DfsFrame> frames = new ArrayDeque<>();
        visitState.put(start, 1);
        path.addLast(start);
        frames.addLast(new DfsFrame(start, edges.getOrDefault(start, Set.of()).iterator()));
        while (!frames.isEmpty()) {
            DfsFrame top = frames.peekLast();
            if (!top.neighbors().hasNext()) {
                frames.removeLast();
                path.removeLast();
                visitState.put(top.node(), 2);
                continue;
            }
            String next = top.neighbors().next();
            int state = visitState.getOrDefault(next, 0);
            if (state == 1) {
                throw new IllegalStateException(
                        "Circular reward table reference detected: " + String.join(" -> ", path) + " -> " + next);
            }
            if (state == 0 && edges.containsKey(next)) {
                visitState.put(next, 1);
                path.addLast(next);
                frames.addLast(new DfsFrame(next, edges.getOrDefault(next, Set.of()).iterator()));
            }
        }
    }
}
