package net.sabafly.dragonraid.api.reward;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Folia-safe item delivery SPI bridging {@code dragonraid-reward} to Paper inventory operations.
 */
public interface RewardItemDelivery {

    CompletableFuture<Boolean> deliverVanillaItem(UUID playerId, String materialName, int amount);

    CompletableFuture<Boolean> deliverCustomItem(UUID playerId, String customItemId, int amount);
}
