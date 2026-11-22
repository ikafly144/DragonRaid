package net.sabafly.dragonraid.api.reward;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.jetbrains.annotations.Nullable;

/**
 * Loot-Table-style pure reward evaluator and side-effect applier SPI.
 */
public interface RaidRewardService {

    void registerTable(RewardTable table);

    void registerExtension(RewardTableExtension extension);

    void replacePackTables(Collection<RewardTable> packTables, Collection<RewardTableExtension> packExtensions);

    /**
     * Returns the merged effective {@link RewardTable} (including applicable {@code inject-into} extensions).
     */
    Optional<RewardTable> getTable(String tableId);

    Collection<RewardTable> tables();

    Collection<RewardTableExtension> extensions();

    Set<String> staticTableIds();

    default Collection<RewardTable> staticTables() {
        return List.of();
    }

    default Collection<RewardTableExtension> staticExtensions() {
        return List.of();
    }

    default Map<String, RewardTable> effectiveTablesSnapshot() {
        Map<String, RewardTable> snapshot = new LinkedHashMap<>();
        for (RewardTable table : tables()) {
            snapshot.put(table.id(), table);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Pure evaluation producing an immutable {@link RewardBundle} with zero side effects.
     */
    RewardBundle evaluate(String tableId, RewardContext context, RandomGenerator random);

    default RewardBundle evaluate(
            Map<String, RewardTable> tableSnapshot,
            String tableId,
            RewardContext context,
            RandomGenerator random) {
        return evaluate(tableId, context, random);
    }

    default RewardBundle evaluate(String tableId, RewardContext context) {
        return evaluate(tableId, context, ThreadLocalRandom.current());
    }

    /**
     * Purely evaluates multiple reward tables in order and merges all grants into a single {@link RewardBundle}.
     */
    RewardBundle evaluateAll(Collection<String> tableIds, RewardContext context, RandomGenerator random);

    default RewardBundle evaluateAll(
            Map<String, RewardTable> tableSnapshot,
            Collection<String> tableIds,
            RewardContext context,
            RandomGenerator random) {
        return evaluateAll(tableIds, context, random);
    }

    default RewardBundle evaluateAll(Collection<String> tableIds, RewardContext context) {
        return evaluateAll(tableIds, context, ThreadLocalRandom.current());
    }

    CompletableFuture<RewardApplyResult> apply(RewardBundle bundle, RewardContext context);

    CompletableFuture<RewardApplyResult> evaluateAndApply(String tableId, RewardContext context, RandomGenerator random);

    default CompletableFuture<RewardApplyResult> evaluateAndApply(String tableId, RewardContext context) {
        return evaluateAndApply(tableId, context, ThreadLocalRandom.current());
    }

    CompletableFuture<RewardApplyResult> evaluateAndApplyAll(Collection<String> tableIds, RewardContext context, RandomGenerator random);

    default CompletableFuture<RewardApplyResult> evaluateAndApplyAll(
            Map<String, RewardTable> tableSnapshot,
            Collection<String> tableIds,
            RewardContext context,
            RandomGenerator random) {
        return evaluateAndApplyAll(tableIds, context, random);
    }

    default CompletableFuture<RewardApplyResult> evaluateAndApplyAll(Collection<String> tableIds, RewardContext context) {
        return evaluateAndApplyAll(tableIds, context, ThreadLocalRandom.current());
    }

    default CompletableFuture<List<RewardApplyResult>> resumePendingPlans(UUID playerId, @Nullable String playerName) {
        return CompletableFuture.completedFuture(List.of());
    }
}
