package net.sabafly.dragonraid.api.economy;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Virtual currency ledger and cap management SPI.
 */
public interface RaidEconomyService {

    void registerCurrency(CurrencyDefinition definition);

    Optional<CurrencyDefinition> getCurrency(String currencyId);

    Collection<CurrencyDefinition> currencies();

    long balance(UUID playerId, String currencyId);

    default CompletableFuture<Long> balanceAsync(UUID playerId, String currencyId) {
        return CompletableFuture.completedFuture(balance(playerId, currencyId));
    }

    boolean deposit(UUID playerId, String currencyId, long amount);

    default CompletableFuture<Boolean> depositAsync(
            UUID playerId,
            @Nullable String playerName,
            String currencyId,
            long amount) {
        return CompletableFuture.completedFuture(deposit(playerId, currencyId, amount));
    }

    default CompletableFuture<Boolean> depositIdempotentAsync(
            UUID playerId,
            @Nullable String playerName,
            String currencyId,
            long amount,
            @Nullable String idempotencyKey,
            long claimedAtEpochMillis) {
        return depositAsync(playerId, playerName, currencyId, amount);
    }

    boolean withdraw(UUID playerId, String currencyId, long amount);

    default CompletableFuture<Boolean> withdrawAsync(UUID playerId, String currencyId, long amount) {
        return CompletableFuture.completedFuture(withdraw(playerId, currencyId, amount));
    }
}
