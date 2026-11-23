package net.sabafly.dragonraid.finder.gui;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Factory seam creating Bukkit {@link Inventory} windows and GUI {@link ItemStack} icons so GUI logic
 * can be unit-tested without an initialized Paper server registry.
 */
public interface FinderMenuFactory {

    Inventory createInventory(InventoryHolder owner, int size, Component title);

    ItemStack createItem(String materialName, Component displayName, List<Component> lore, boolean glint);

    ItemStack createPlayerHead(@Nullable UUID playerId, String fallbackName, Component displayName, List<Component> lore);
}
