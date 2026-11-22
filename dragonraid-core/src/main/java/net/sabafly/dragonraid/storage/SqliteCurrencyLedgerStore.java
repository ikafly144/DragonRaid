package net.sabafly.dragonraid.storage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.Locale;
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
        return balanceAsync(playerId, currencyId).join();
    }

    @Override
    public CompletableFuture<Long> balanceAsync(UUID playerId, String currencyId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyId, "currencyId");
        if (currencyId.isBlank()) {
            return CompletableFuture.completedFuture(0L);
        }
        return repository.getCurrencyBalance(playerId, currencyId);
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
        return depositWithCapsIdempotentAsync(playerId, playerName, definition, amount, null, 0L);
    }

    @Override
    public CompletableFuture<Boolean> depositWithCapsIdempotentAsync(
            UUID playerId,
            @Nullable String playerName,
            CurrencyDefinition definition,
            long amount,
            @Nullable String idempotencyKey,
            long claimedAtEpochMillis) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(definition, "definition");
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        String weekKey = currentIsoWeekKey(clockMillis.getAsLong());
        return repository.addCurrencyWithCapsIdempotent(
                playerId,
                playerName,
                definition.currencyId(),
                amount,
                definition.maxBalance(),
                definition.weeklyCap(),
                weekKey,
                idempotencyKey,
                claimedAtEpochMillis
        ).thenApply(PointTransactionResult::success);
    }

    @Override
    public boolean withdraw(UUID playerId, String currencyId, long amount) {
        return withdrawAsync(playerId, currencyId, amount).join();
    }

    @Override
    public CompletableFuture<Boolean> withdrawAsync(UUID playerId, String currencyId, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currencyId, "currencyId");
        if (amount <= 0L || currencyId.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return repository.trySpendCurrency(playerId, currencyId, amount)
                .thenApply(PointTransactionResult::success);
    }

    static String currentIsoWeekKey(long epochMillis) {
        ZonedDateTime utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
        int year = utc.get(IsoFields.WEEK_BASED_YEAR);
        int week = utc.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }
}
