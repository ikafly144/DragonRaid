package net.sabafly.dragonraid.api.reward;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

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

    /**
     * Pure evaluation producing an immutable {@link RewardBundle} with zero side effects.
     */
    RewardBundle evaluate(String tableId, RewardContext context, RandomGenerator random);

    default RewardBundle evaluate(String tableId, RewardContext context) {
        return evaluate(tableId, context, ThreadLocalRandom.current());
    }

    /**
     * Purely evaluates multiple reward tables in order and merges all grants into a single {@link RewardBundle}.
     */
    RewardBundle evaluateAll(Collection<String> tableIds, RewardContext context, RandomGenerator random);

    default RewardBundle evaluateAll(Collection<String> tableIds, RewardContext context) {
        return evaluateAll(tableIds, context, ThreadLocalRandom.current());
    }

    CompletableFuture<RewardApplyResult> apply(RewardBundle bundle, RewardContext context);

    CompletableFuture<RewardApplyResult> evaluateAndApply(String tableId, RewardContext context, RandomGenerator random);

    default CompletableFuture<RewardApplyResult> evaluateAndApply(String tableId, RewardContext context) {
        return evaluateAndApply(tableId, context, ThreadLocalRandom.current());
    }

    CompletableFuture<RewardApplyResult> evaluateAndApplyAll(Collection<String> tableIds, RewardContext context, RandomGenerator random);

    default CompletableFuture<RewardApplyResult> evaluateAndApplyAll(Collection<String> tableIds, RewardContext context) {
        return evaluateAndApplyAll(tableIds, context, ThreadLocalRandom.current());
    }
}
