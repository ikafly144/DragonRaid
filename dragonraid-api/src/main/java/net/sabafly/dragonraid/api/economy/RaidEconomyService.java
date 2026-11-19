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

    boolean deposit(UUID playerId, String currencyId, long amount);

    default CompletableFuture<Boolean> depositAsync(
            UUID playerId,
            @Nullable String playerName,
            String currencyId,
            long amount) {
        return CompletableFuture.completedFuture(deposit(playerId, currencyId, amount));
    }

    boolean withdraw(UUID playerId, String currencyId, long amount);
}
