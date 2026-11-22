package net.sabafly.dragonraid.api.economy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Persistence backing SPI for atomic registered-currency cap checks and balance mutations.
 */
public interface CurrencyLedgerStore {

    long balance(UUID playerId, String currencyId);

    boolean depositWithCaps(UUID playerId, @Nullable String playerName, CurrencyDefinition definition, long amount);

    boolean withdraw(UUID playerId, String currencyId, long amount);

    default CompletableFuture<Long> balanceAsync(UUID playerId, String currencyId) {
        return CompletableFuture.completedFuture(balance(playerId, currencyId));
    }

    default CompletableFuture<Boolean> depositWithCapsAsync(
            UUID playerId,
            @Nullable String playerName,
            CurrencyDefinition definition,
            long amount) {
        return CompletableFuture.completedFuture(depositWithCaps(playerId, playerName, definition, amount));
    }

    default CompletableFuture<Boolean> depositWithCapsIdempotentAsync(
            UUID playerId,
            @Nullable String playerName,
            CurrencyDefinition definition,
            long amount,
            @Nullable String idempotencyKey,
            long claimedAtEpochMillis) {
        return depositWithCapsAsync(playerId, playerName, definition, amount);
    }

    default CompletableFuture<Boolean> withdrawAsync(UUID playerId, String currencyId, long amount) {
        return CompletableFuture.completedFuture(withdraw(playerId, currencyId, amount));
    }
}
