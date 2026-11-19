package net.sabafly.dragonraid.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.sabafly.dragonraid.api.economy.CurrencyDefinition;
import net.sabafly.dragonraid.api.economy.CurrencyLedgerStore;
import org.junit.jupiter.api.Test;

class DefaultRaidEconomyServiceTest {

    @Test
    void enforcesMaxBalanceAndWeeklyCapsAcrossDepositsAndWithdrawals() {
        DefaultRaidEconomyService economy = new DefaultRaidEconomyService();
        economy.registerCurrency(new CurrencyDefinition("tomestone_astrality", "currency.astrality", 500L, 450L));

        UUID player = UUID.randomUUID();
        assertTrue(economy.deposit(player, "tomestone_astrality", 300L));
        assertEquals(300L, economy.balance(player, "tomestone_astrality"));

        // Exceeds weekly cap (300 + 200 = 500 > 450)
        assertFalse(economy.deposit(player, "tomestone_astrality", 200L));
        assertTrue(economy.deposit(player, "tomestone_astrality", 150L));
        assertEquals(450L, economy.balance(player, "tomestone_astrality"));

        assertFalse(economy.withdraw(player, "tomestone_astrality", 500L));
        assertTrue(economy.withdraw(player, "tomestone_astrality", 120L));
        assertEquals(330L, economy.balance(player, "tomestone_astrality"));

        // Rejects Long.MAX_VALUE overflow on both capped and uncapped currencies
        assertFalse(economy.deposit(player, "tomestone_astrality", Long.MAX_VALUE));
        assertEquals(330L, economy.balance(player, "tomestone_astrality"));

        economy.registerCurrency(new CurrencyDefinition("unlimited_gil", "currency.gil", 0L, 0L));
        assertTrue(economy.deposit(player, "unlimited_gil", 100L));
        assertFalse(economy.deposit(player, "unlimited_gil", Long.MAX_VALUE));
        assertEquals(100L, economy.balance(player, "unlimited_gil"));
    }

    @Test
    void delegatesRegisteredCurrencyDepositsAndCapsToPersistentLedgerStore() {
        Map<String, Long> persistentBalances = new HashMap<>();
        CurrencyLedgerStore ledgerStore = new CurrencyLedgerStore() {
            @Override
            public long balance(UUID playerId, String currencyId) {
                return persistentBalances.getOrDefault(playerId + ":" + currencyId, 0L);
            }

            @Override
            public boolean depositWithCaps(UUID playerId, String playerName, CurrencyDefinition def, long amount) {
                String k = playerId + ":" + def.currencyId();
                long current = persistentBalances.getOrDefault(k, 0L);
                if (def.maxBalance() > 0L && amount > def.maxBalance() - current) {
                    return false;
                }
                persistentBalances.put(k, current + amount);
                return true;
            }

            @Override
            public boolean withdraw(UUID playerId, String currencyId, long amount) {
                String k = playerId + ":" + currencyId;
                long current = persistentBalances.getOrDefault(k, 0L);
                if (current < amount) {
                    return false;
                }
                persistentBalances.put(k, current - amount);
                return true;
            }
        };

        DefaultRaidEconomyService economy = new DefaultRaidEconomyService(ledgerStore);
        economy.registerCurrency(new CurrencyDefinition("dragon_scale", "currency.scale", 100L, 0L));
        UUID alice = UUID.randomUUID();
        assertTrue(economy.depositAsync(alice, "Alice", "dragon_scale", 100L).join());
        assertFalse(economy.depositAsync(alice, "Alice", "dragon_scale", 1L).join());
        assertEquals(100L, economy.balance(alice, "dragon_scale"));
        assertEquals(100L, persistentBalances.get(alice + ":dragon_scale"));
    }
}
