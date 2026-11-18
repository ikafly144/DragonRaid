package net.sabafly.dragonraid.api.enemy;

import org.jetbrains.annotations.Nullable;

/**
 * Defines cross-enemy gameplay links (such as healing a parent boss while alive and staggering it
 * when all linked instances are destroyed).
 *
 * @param linkedTargetEnemyId enemy ID affected by this enemy (e.g. {@code "ancient_dragon"})
 * @param healTargetPerSecond HP healed per second on the target while any instance is alive
 * @param staggerTargetOnAllDestroyed whether destroying all instances staggers the target
 * @param staggerAnimationKey animation key played on the target when staggered
 * @param staggerDurationSeconds duration of the stagger window in seconds
 */
public record EnemyLinkSpec(
        @Nullable String linkedTargetEnemyId,
        double healTargetPerSecond,
        boolean staggerTargetOnAllDestroyed,
        @Nullable String staggerAnimationKey,
        double staggerDurationSeconds
) {

    public EnemyLinkSpec {
        if (!Double.isFinite(healTargetPerSecond) || healTargetPerSecond < 0.0) {
            throw new IllegalArgumentException("healTargetPerSecond must be >= 0: " + healTargetPerSecond);
        }
        if (!Double.isFinite(staggerDurationSeconds) || staggerDurationSeconds < 0.0) {
            throw new IllegalArgumentException("staggerDurationSeconds must be >= 0: " + staggerDurationSeconds);
        }
    }
}
