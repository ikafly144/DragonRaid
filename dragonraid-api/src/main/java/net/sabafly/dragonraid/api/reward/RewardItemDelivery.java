package net.sabafly.dragonraid.api.reward;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Folia-safe item delivery SPI bridging {@code dragonraid-reward} to Paper inventory operations.
 */
public interface RewardItemDelivery {

    CompletableFuture<Boolean> deliverVanillaItem(UUID playerId, String materialName, int amount);

    CompletableFuture<Boolean> deliverCustomItem(UUID playerId, String customItemId, int amount);

    default CompletableFuture<ItemDeliveryResult> deliverVanillaItemDetailed(
            UUID playerId, String materialName, int amount) {
        int safeAmount = Math.max(0, amount);
        return deliverVanillaItem(playerId, materialName, amount)
                .thenApply(ok -> Boolean.TRUE.equals(ok) && safeAmount > 0
                        ? ItemDeliveryResult.full(safeAmount)
                        : ItemDeliveryResult.failed(safeAmount));
    }

    default CompletableFuture<ItemDeliveryResult> deliverCustomItemDetailed(
            UUID playerId, String customItemId, int amount) {
        int safeAmount = Math.max(0, amount);
        return deliverCustomItem(playerId, customItemId, amount)
                .thenApply(ok -> Boolean.TRUE.equals(ok) && safeAmount > 0
                        ? ItemDeliveryResult.full(safeAmount)
                        : ItemDeliveryResult.failed(safeAmount));
    }
}
