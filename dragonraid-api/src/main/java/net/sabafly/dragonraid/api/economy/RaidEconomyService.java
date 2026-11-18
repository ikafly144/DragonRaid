package net.sabafly.dragonraid.api.economy;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Virtual currency ledger and cap management SPI.
 */
public interface RaidEconomyService {

    void registerCurrency(CurrencyDefinition definition);

    Optional<CurrencyDefinition> getCurrency(String currencyId);

    Collection<CurrencyDefinition> currencies();

    long balance(UUID playerId, String currencyId);

    boolean deposit(UUID playerId, String currencyId, long amount);

    boolean withdraw(UUID playerId, String currencyId, long amount);
}
