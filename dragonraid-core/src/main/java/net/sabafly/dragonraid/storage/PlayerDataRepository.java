package net.sabafly.dragonraid.storage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;

/**
 * Asynchronous repository contract for DragonRaid player statistics, virtual points,
 * named reward currencies, and player progression state.
 *
 * <p>All methods return {@link CompletableFuture} instances executed on the repository's
 * configured async executor so callers can safely bridge results back to Folia region/entity
 * schedulers.
 */
public interface PlayerDataRepository extends PlayerProgressionRepository, AutoCloseable {

    /**
     * Finds stored statistics for the given player UUID.
     */
    CompletableFuture<Optional<PlayerRaidStats>> findByUuid(UUID uuid);

    /**
     * Finds stored statistics by last known player username (case-insensitive).
     */
    CompletableFuture<Optional<PlayerRaidStats>> findByName(String name);

    /**
     * Loads statistics for the player, creating an empty row if none exists yet,
     * and updating {@code lastKnownName} if non-blank.
     */
    CompletableFuture<PlayerRaidStats> getOrCreate(UUID uuid, String lastKnownName);

    /**
     * Updates the player's last known username, creating an empty row if absent.
     */
    CompletableFuture<Void> updateLastKnownName(UUID uuid, String lastKnownName);

    /**
     * Atomically adds virtual points and total earned points to the player's account.
     */
    CompletableFuture<PointTransactionResult> addPoints(UUID uuid, String lastKnownName, long amount);

    /**
     * Atomically adds virtual points without modifying {@code lastKnownName}.
     */
    default CompletableFuture<PointTransactionResult> addPoints(UUID uuid, long amount) {
        return addPoints(uuid, null, amount);
    }

    /**
     * Atomically deducts virtual points only if the player's balance is at least {@code amount}.
     */
    CompletableFuture<PointTransactionResult> trySpendPoints(UUID uuid, long amount);

    /**
     * Sets the player's virtual point balance to {@code newBalance} (must be {@code >= 0}).
     */
    CompletableFuture<PointTransactionResult> setPoints(UUID uuid, String lastKnownName, long newBalance);

    /**
     * Records a raid clear for the given difficulty, incrementing clear count and updating
     * the personal best time if {@code clearTimeMs} is faster than the recorded best.
     */
    CompletableFuture<PlayerRaidStats> recordClear(
        UUID uuid,
        String lastKnownName,
        Difficulty difficulty,
        long clearTimeMs
    );

    /**
     * Atomically increments the player's death counter.
     */
    CompletableFuture<PlayerRaidStats> incrementDeaths(UUID uuid, String lastKnownName);

    /**
     * Atomically increments the player's death counter without modifying {@code lastKnownName}.
     */
    default CompletableFuture<PlayerRaidStats> incrementDeaths(UUID uuid) {
        return incrementDeaths(uuid, null);
    }

    /**
     * Atomically increments the player's revive counter.
     */
    CompletableFuture<PlayerRaidStats> incrementRevives(UUID uuid, String lastKnownName);

    /**
     * Atomically increments the player's revive counter without modifying {@code lastKnownName}.
     */
    default CompletableFuture<PlayerRaidStats> incrementRevives(UUID uuid) {
        return incrementRevives(uuid, null);
    }

    /**
     * Atomically adds a positive amount to a named currency balance (for example {@code dragon_token}).
     */
    CompletableFuture<PointTransactionResult> addCurrency(
        UUID uuid,
        String lastKnownName,
        String currencyId,
        long amount
    );

    /**
     * Atomically deducts from a named currency balance if sufficient funds exist.
     */
    CompletableFuture<PointTransactionResult> trySpendCurrency(UUID uuid, String currencyId, long amount);

    /**
     * Atomically deducts multiple named currency costs together; if any currency has
     * insufficient balance, all deductions are rolled back.
     */
    CompletableFuture<Boolean> trySpendCurrencies(UUID uuid, Map<String, Long> costs);

    /**
     * Sets a named currency balance to {@code newBalance} (must be {@code >= 0}).
     */
    CompletableFuture<PointTransactionResult> setCurrency(
        UUID uuid,
        String lastKnownName,
        String currencyId,
        long newBalance
    );

    /**
     * Returns the player's current balance for a named currency (0 if absent).
     */
    CompletableFuture<Long> getCurrencyBalance(UUID uuid, String currencyId);

    /**
     * Returns an immutable map of all non-zero or recorded currency balances for the player.
     */
    CompletableFuture<Map<String, Long>> getCurrencyBalances(UUID uuid);

    /**
     * Closes the underlying connection pool and releases resources.
     */
    @Override
    void close();
}
