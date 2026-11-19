package net.sabafly.dragonraid.storage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import net.sabafly.dragonraid.api.economy.CurrencyDefinition;
import net.sabafly.dragonraid.api.economy.CurrencyLedgerStore;
import org.jetbrains.annotations.Nullable;

/**
 * {@link CurrencyLedgerStore} backed by {@link PlayerDataRepository}, enforcing registered
 * currency {@code maxBalance} and ISO-week UTC {@code weeklyCap} inside atomic SQLite transactions.
 */
public final class SqliteCurrencyLedgerStore implements CurrencyLedgerStore {

    private final PlayerDataRepository repository;
    private final LongSupplier clockMillis;

    public SqliteCurrencyLedgerStore(PlayerDataRepository repository, LongSupplier clockMillis) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    @Override
    public long balance(UUID playerId, String currencyId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyId, "currencyId");
        return repository.getCurrencyBalance(playerId, currencyId).join();
    }

    @Override
    public boolean depositWithCaps(
            UUID playerId,
            @Nullable String playerName,
            CurrencyDefinition definition,
            long amount) {
        return depositWithCapsAsync(playerId, playerName, definition, amount).join();
    }

    @Override
    public CompletableFuture<Boolean> depositWithCapsAsync(
            UUID playerId,
            @Nullable String playerName,
            CurrencyDefinition definition,
            long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(definition, "definition");
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        String weekKey = currentIsoWeekKey(clockMillis.getAsLong());
        return repository.addCurrencyWithCaps(
                playerId,
                playerName,
                definition.currencyId(),
                amount,
                definition.maxBalance(),
                definition.weeklyCap(),
                weekKey
        ).thenApply(PointTransactionResult::success);
    }

    @Override
    public boolean withdraw(UUID playerId, String currencyId, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyId, "currencyId");
        if (amount <= 0L) {
            return false;
        }
        return repository.trySpendCurrency(playerId, currencyId, amount)
                .thenApply(PointTransactionResult::success)
                .join();
    }

    static String currentIsoWeekKey(long epochMillis) {
        ZonedDateTime utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
        int year = utc.get(IsoFields.WEEK_BASED_YEAR);
        int week = utc.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format("%04d-W%02d", year, week);
    }
}
