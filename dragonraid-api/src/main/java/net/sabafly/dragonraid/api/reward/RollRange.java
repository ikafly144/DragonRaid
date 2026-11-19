package net.sabafly.dragonraid.api.reward;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Inclusive integer roll or quantity range analogous to Minecraft Loot Table number providers.
 */
public record RollRange(int min, int max) {

    public RollRange {
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0: " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException("max (" + max + ") must be >= min (" + min + ")");
        }
    }

    public static RollRange exact(int value) {
        return new RollRange(value, value);
    }

    public static RollRange between(int min, int max) {
        return new RollRange(min, max);
    }

    /**
     * Rolls an inclusive integer within {@code [min, max]} using the supplied RNG.
     */
    public int roll(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (min == max) {
            return min;
        }
        int span = max - min + 1;
        if (span <= 0) {
            long wideSpan = (long) max - (long) min + 1L;
            return (int) (min + random.nextLong(wideSpan));
        }
        return min + random.nextInt(span);
    }
}
