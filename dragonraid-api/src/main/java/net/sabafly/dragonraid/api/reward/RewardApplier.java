package net.sabafly.dragonraid.api.reward;

import java.util.concurrent.CompletableFuture;

/**
 * Side-effect applier consuming an evaluated {@link RewardBundle} and executing grants
 * with atomic {@code claimKey} protection.
 */
public interface RewardApplier {

    CompletableFuture<RewardApplyResult> apply(RewardBundle bundle, RewardContext context);
}
