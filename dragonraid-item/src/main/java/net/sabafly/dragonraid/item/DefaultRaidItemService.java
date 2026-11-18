package net.sabafly.dragonraid.item;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.item.CustomItemDefinition;
import net.sabafly.dragonraid.api.item.RaidItemService;

/**
 * Thread-safe M1 implementation of {@link RaidItemService} managing custom item metadata
 * and player average item level ({@code iLvl}) calculation.
 */
public final class DefaultRaidItemService implements RaidItemService {

    private final Map<String, CustomItemDefinition> items = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> equippedItemIdsByPlayer = new ConcurrentHashMap<>();

    @Override
    public void registerItem(CustomItemDefinition item) {
        Objects.requireNonNull(item, "item");
        items.put(item.itemId(), item);
    }

    @Override
    public Optional<CustomItemDefinition> getItem(String itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get(itemId));
    }

    @Override
    public Collection<CustomItemDefinition> allItems() {
        return List.copyOf(items.values());
    }

    public void setEquippedItems(UUID playerId, Collection<String> itemIds) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(itemIds, "itemIds");
        equippedItemIdsByPlayer.put(playerId, List.copyOf(itemIds));
    }

    public void clearEquippedItems(UUID playerId) {
        if (playerId != null) {
            equippedItemIdsByPlayer.remove(playerId);
        }
    }

    @Override
    public int averageItemLevel(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        List<String> equipped = equippedItemIdsByPlayer.get(playerId);
        if (equipped == null || equipped.isEmpty()) {
            return 0;
        }
        long total = 0L;
        for (String itemId : equipped) {
            CustomItemDefinition def = items.get(itemId);
            if (def != null) {
                total += def.itemLevel();
            }
        }
        return (int) Math.round((double) total / equipped.size());
    }
}
