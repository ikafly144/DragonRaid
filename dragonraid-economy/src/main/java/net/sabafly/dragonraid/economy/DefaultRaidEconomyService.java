package net.sabafly.dragonraid.economy;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.economy.CurrencyDefinition;
import net.sabafly.dragonraid.api.economy.RaidEconomyService;

/**
 * Thread-safe in-memory M1 implementation of {@link RaidEconomyService} enforcing balance and weekly caps.
 */
public final class DefaultRaidEconomyService implements RaidEconomyService {

    private final Object lock = new Object();
    private final Map<String, CurrencyDefinition> currencies = new ConcurrentHashMap<>();
    private final Map<LedgerKey, Long> balances = new ConcurrentHashMap<>();
    private final Map<LedgerKey, Long> weeklyEarned = new ConcurrentHashMap<>();

    @Override
    public void registerCurrency(CurrencyDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        currencies.put(definition.currencyId(), definition);
    }

    @Override
    public Optional<CurrencyDefinition> getCurrency(String currencyId) {
        if (currencyId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(currencies.get(currencyId));
    }

    @Override
    public Collection<CurrencyDefinition> currencies() {
        return List.copyOf(currencies.values());
    }

    @Override
    public long balance(UUID playerId, String currencyId) {
        if (playerId == null || currencyId == null) {
            return 0L;
        }
        return balances.getOrDefault(new LedgerKey(playerId, currencyId), 0L);
    }

    @Override
    public boolean deposit(UUID playerId, String currencyId, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyId, "currencyId");
        if (amount <= 0L) {
            return false;
        }
        CurrencyDefinition definition = currencies.get(currencyId);
        if (definition == null) {
            return false;
        }
        LedgerKey key = new LedgerKey(playerId, currencyId);
        synchronized (lock) {
            long current = balances.getOrDefault(key, 0L);
            if (definition.maxBalance() > 0L && current + amount > definition.maxBalance()) {
                return false;
            }
            long earned = weeklyEarned.getOrDefault(key, 0L);
            if (definition.weeklyCap() > 0L && earned + amount > definition.weeklyCap()) {
                return false;
            }
            balances.put(key, current + amount);
            weeklyEarned.put(key, earned + amount);
            return true;
        }
    }

    @Override
    public boolean withdraw(UUID playerId, String currencyId, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyId, "currencyId");
        if (amount <= 0L || !currencies.containsKey(currencyId)) {
            return false;
        }
        LedgerKey key = new LedgerKey(playerId, currencyId);
        synchronized (lock) {
            long current = balances.getOrDefault(key, 0L);
            if (current < amount) {
                return false;
            }
            balances.put(key, current - amount);
            return true;
        }
    }

    private record LedgerKey(UUID playerId, String currencyId) {
    }
}
