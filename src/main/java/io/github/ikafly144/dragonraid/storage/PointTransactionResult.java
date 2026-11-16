package io.github.ikafly144.dragonraid.storage;

import java.util.Objects;

/**
 * Immutable result of an atomic virtual point or currency balance update.
 *
 * @param success         whether the balance mutation succeeded
 * @param previousBalance balance prior to the operation
 * @param newBalance      balance after the operation (unchanged on failure)
 * @param amountChanged   signed delta applied ({@code newBalance - previousBalance})
 * @param failureReason   reason for failure, or {@link FailureReason#NONE} when successful
 */
public record PointTransactionResult(
    boolean success,
    long previousBalance,
    long newBalance,
    long amountChanged,
    FailureReason failureReason
) {

    /**
     * Reason a point transaction failed.
     */
    public enum FailureReason {
        NONE,
        INSUFFICIENT_FUNDS,
        PLAYER_NOT_FOUND,
        INVALID_AMOUNT,
        BALANCE_OVERFLOW
    }

    public PointTransactionResult {
        if (previousBalance < 0) {
            throw new IllegalArgumentException("previousBalance must be >= 0");
        }
        if (newBalance < 0) {
            throw new IllegalArgumentException("newBalance must be >= 0");
        }
        Objects.requireNonNull(failureReason, "failureReason must not be null");
        if (success && failureReason != FailureReason.NONE) {
            throw new IllegalArgumentException("Successful transaction must use FailureReason.NONE");
        }
        if (!success && failureReason == FailureReason.NONE) {
            throw new IllegalArgumentException("Failed transaction must specify a FailureReason");
        }
    }

    /**
     * Creates a successful transaction result.
     */
    public static PointTransactionResult succeeded(long previousBalance, long newBalance) {
        return new PointTransactionResult(
            true,
            previousBalance,
            newBalance,
            newBalance - previousBalance,
            FailureReason.NONE
        );
    }

    /**
     * Creates a failed transaction result leaving the balance unchanged.
     */
    public static PointTransactionResult failed(FailureReason reason, long currentBalance) {
        return new PointTransactionResult(
            false,
            currentBalance,
            currentBalance,
            0L,
            Objects.requireNonNull(reason, "reason must not be null")
        );
    }
}
