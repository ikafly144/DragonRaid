package net.sabafly.dragonraid.api.item;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom item/equipment registry and average item-level calculation SPI.
 */
public interface RaidItemService {

    void registerItem(CustomItemDefinition item);

    Optional<CustomItemDefinition> getItem(String itemId);

    Collection<CustomItemDefinition> allItems();

    int averageItemLevel(UUID playerId);
}
