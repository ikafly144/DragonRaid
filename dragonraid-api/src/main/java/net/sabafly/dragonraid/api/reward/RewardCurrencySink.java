package net.sabafly.dragonraid.api.reward;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Currency deposit sink decoupling {@code dragonraid-reward} from concrete economy and storage implementations.
 */
@FunctionalInterface
public interface RewardCurrencySink {

    CompletableFuture<Boolean> grantCurrency(
            UUID playerId,
            @Nullable String playerName,
            String currencyId,
            long amount
    );

    default CompletableFuture<Boolean> grantCurrencyIdempotent(
            UUID playerId,
            @Nullable String playerName,
            String currencyId,
            long amount,
            @Nullable String idempotencyKey,
            long claimedAtEpochMillis) {
        return grantCurrency(playerId, playerName, currencyId, amount);
    }
}
