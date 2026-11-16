package io.github.ikafly144.dragonraid.storage;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Immutable snapshot of a player's DragonRaid statistics and virtual point balance.
 *
 * @param uuid              the player's unique Minecraft UUID
 * @param lastKnownName     the player's last recorded username, or {@code null} if unknown
 * @param virtualPoints     current spendable virtual point balance
 * @param totalEarnedPoints cumulative virtual points earned across all raids
 * @param normalClears      number of Normal difficulty raid clears
 * @param hardClears        number of Hard / Savage difficulty raid clears
 * @param bestNormalTimeMs  fastest Normal clear duration in milliseconds, or {@code null} if never cleared
 * @param bestHardTimeMs    fastest Hard clear duration in milliseconds, or {@code null} if never cleared
 * @param totalDeaths       cumulative raid deaths / downed transitions
 * @param totalRevives      cumulative raid revives performed
 */
public record PlayerRaidStats(
    UUID uuid,
    String lastKnownName,
    long virtualPoints,
    long totalEarnedPoints,
    int normalClears,
    int hardClears,
    Long bestNormalTimeMs,
    Long bestHardTimeMs,
    int totalDeaths,
    int totalRevives
) {

    public PlayerRaidStats {
        Objects.requireNonNull(uuid, "uuid must not be null");
        if (lastKnownName != null && lastKnownName.isBlank()) {
            lastKnownName = null;
        }
        if (virtualPoints < 0) {
            throw new IllegalArgumentException("virtualPoints must be >= 0");
        }
        if (totalEarnedPoints < 0) {
            throw new IllegalArgumentException("totalEarnedPoints must be >= 0");
        }
        if (normalClears < 0) {
            throw new IllegalArgumentException("normalClears must be >= 0");
        }
        if (hardClears < 0) {
            throw new IllegalArgumentException("hardClears must be >= 0");
        }
        if (bestNormalTimeMs != null && bestNormalTimeMs < 0) {
            throw new IllegalArgumentException("bestNormalTimeMs must be >= 0");
        }
        if (bestHardTimeMs != null && bestHardTimeMs < 0) {
            throw new IllegalArgumentException("bestHardTimeMs must be >= 0");
        }
        if (totalDeaths < 0) {
            throw new IllegalArgumentException("totalDeaths must be >= 0");
        }
        if (totalRevives < 0) {
            throw new IllegalArgumentException("totalRevives must be >= 0");
        }
    }

    /**
     * Creates an empty statistics record with zeroed counters and no clear times.
     */
    public static PlayerRaidStats empty(UUID uuid, String lastKnownName) {
        return new PlayerRaidStats(uuid, lastKnownName, 0L, 0L, 0, 0, null, null, 0, 0);
    }

    /**
     * Returns total clears across all difficulties.
     */
    public int totalClears() {
        return normalClears + hardClears;
    }

    /**
     * Returns clear count for the given difficulty.
     */
    public int clears(Difficulty difficulty) {
        Objects.requireNonNull(difficulty, "difficulty must not be null");
        return switch (difficulty) {
            case NORMAL -> normalClears;
            case HARD -> hardClears;
        };
    }

    /**
     * Returns the best clear time in milliseconds for the given difficulty, if cleared.
     */
    public OptionalLong bestTimeMs(Difficulty difficulty) {
        Objects.requireNonNull(difficulty, "difficulty must not be null");
        Long value = switch (difficulty) {
            case NORMAL -> bestNormalTimeMs;
            case HARD -> bestHardTimeMs;
        };
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
