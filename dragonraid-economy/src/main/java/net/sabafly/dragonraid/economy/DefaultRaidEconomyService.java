package net.sabafly.dragonraid.economy;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.economy.CurrencyDefinition;
import net.sabafly.dragonraid.api.economy.CurrencyLedgerStore;
import net.sabafly.dragonraid.api.economy.RaidEconomyService;

/**
 * Thread-safe {@link RaidEconomyService} enforcing balance and weekly caps, optionally
 * backed by a persistent {@link CurrencyLedgerStore} so economy ledger and SQLite share one truth.
 */
public final class DefaultRaidEconomyService implements RaidEconomyService {

    private final Object lock = new Object();
    private final Map<String, CurrencyDefinition> currencies = new ConcurrentHashMap<>();
    private final Map<LedgerKey, Long> balances = new ConcurrentHashMap<>();
    private final Map<LedgerKey, Long> weeklyEarned = new ConcurrentHashMap<>();
    private final CurrencyLedgerStore ledgerStore;

    public DefaultRaidEconomyService() {
        this(null);
    }

    public DefaultRaidEconomyService(CurrencyLedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    @Override
    public void registerCurrency(CurrencyDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        synchronized (lock) {
            currencies.put(definition.currencyId(), definition);
        }
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
        if (ledgerStore != null) {
            return ledgerStore.balance(playerId, currencyId);
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
        if (ledgerStore != null) {
            CurrencyDefinition definition = currencies.get(currencyId);
            if (definition == null) {
                return false;
            }
            return ledgerStore.depositWithCaps(playerId, null, definition, amount);
        }
        LedgerKey key = new LedgerKey(playerId, currencyId);
        synchronized (lock) {
            CurrencyDefinition definition = currencies.get(currencyId);
            if (definition == null) {
                return false;
            }
            long current = balances.getOrDefault(key, 0L);
            if (amount > Long.MAX_VALUE - current) {
                return false;
            }
            if (definition.maxBalance() > 0L && amount > definition.maxBalance() - current) {
                return false;
            }
            long earned = weeklyEarned.getOrDefault(key, 0L);
            if (amount > Long.MAX_VALUE - earned) {
                return false;
            }
            if (definition.weeklyCap() > 0L && amount > definition.weeklyCap() - earned) {
                return false;
            }
            balances.put(key, current + amount);
            weeklyEarned.put(key, earned + amount);
            return true;
        }
    }

    @Override
    public CompletableFuture<Boolean> depositAsync(
            UUID playerId,
            String playerName,
            String currencyId,
            long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyId, "currencyId");
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        if (ledgerStore != null) {
            CurrencyDefinition definition = currencies.get(currencyId);
            if (definition == null) {
                return CompletableFuture.completedFuture(false);
            }
            return ledgerStore.depositWithCapsAsync(playerId, playerName, definition, amount);
        }
        return CompletableFuture.completedFuture(deposit(playerId, currencyId, amount));
    }

    @Override
    public boolean withdraw(UUID playerId, String currencyId, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyId, "currencyId");
        if (amount <= 0L) {
            return false;
        }
        if (ledgerStore != null) {
            if (!currencies.containsKey(currencyId)) {
                return false;
            }
            return ledgerStore.withdraw(playerId, currencyId, amount);
        }
        LedgerKey key = new LedgerKey(playerId, currencyId);
        synchronized (lock) {
            if (!currencies.containsKey(currencyId)) {
                return false;
            }
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
