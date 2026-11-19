package net.sabafly.dragonraid.api.reward;

import java.util.UUID;

/**
 * Folia-safe item delivery SPI bridging {@code dragonraid-reward} to Paper inventory operations.
 */
public interface RewardItemDelivery {

    void deliverVanillaItem(UUID playerId, String materialName, int amount);

    void deliverCustomItem(UUID playerId, String customItemId, int amount);
}
