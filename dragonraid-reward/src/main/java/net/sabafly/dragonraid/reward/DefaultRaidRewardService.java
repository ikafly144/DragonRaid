package net.sabafly.dragonraid.reward;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
                        logger
                )
        );
    }

    @Override
    public synchronized void registerTable(RewardTable table) {
        Objects.requireNonNull(table, "table");
        staticTables.put(table.id(), table);
        rebuildEffectiveTables();
    }

    @Override
    public synchronized void registerExtension(RewardTableExtension extension) {
        Objects.requireNonNull(extension, "extension");
        staticExtensions.put(extension.id(), extension);
        rebuildEffectiveTables();
    }

    @Override
    public synchronized void replacePackTables(
            Collection<RewardTable> tables,
            Collection<RewardTableExtension> extensions) {
        packTables.clear();
        if (tables != null) {
            for (RewardTable table : tables) {
                if (table != null) {
                    packTables.put(table.id(), table);
                }
            }
        }
        packExtensions.clear();
        if (extensions != null) {
            for (RewardTableExtension extension : extensions) {
                if (extension != null) {
                    packExtensions.put(extension.id(), extension);
                }
            }
        }
        rebuildEffectiveTables();
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
    public RewardBundle evaluate(String tableId, RewardContext context, RandomGenerator random) {
        Map<String, RewardTable> snapshot = effectiveTables;
        return evaluator.evaluate(id -> Optional.ofNullable(snapshot.get(id)), tableId, context, random);
    }

    @Override
    public RewardBundle evaluateAll(Collection<String> tableIds, RewardContext context, RandomGenerator random) {
        Map<String, RewardTable> snapshot = effectiveTables;
        return evaluator.evaluateAll(id -> Optional.ofNullable(snapshot.get(id)), tableIds, context, random);
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
        RewardBundle bundle = evaluateAll(tableIds, context, random);
        return apply(bundle, context);
    }

    private void rebuildEffectiveTables() {
        Map<String, RewardTable> base = new LinkedHashMap<>(staticTables);
        base.putAll(packTables);

        Map<String, RewardTableExtension> mergedExtensions = new LinkedHashMap<>(staticExtensions);
        mergedExtensions.putAll(packExtensions);

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
        this.effectiveTables = Collections.unmodifiableMap(rebuilt);
    }
}
