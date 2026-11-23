package net.sabafly.dragonraid.finder.gui;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

/**
 * Production Paper {@link FinderMenuFactory} backed by {@link Bukkit#createInventory} and {@link ItemStack}.
 */
public final class PaperFinderMenuFactory implements FinderMenuFactory {

    @Override
    public Inventory createInventory(InventoryHolder owner, int size, Component title) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(title, "title");
        return Bukkit.createInventory(owner, size, title);
    }

    @Override
    public ItemStack createItem(String materialName, Component displayName, List<Component> lore, boolean glint) {
        Material mat = resolveMaterial(materialName, Material.ENDER_EYE);
        ItemStack stack = new ItemStack(mat, 1);
        applyMeta(stack, displayName, lore, glint);
        return stack;
    }

    @Override
    public ItemStack createPlayerHead(@Nullable UUID playerId, String fallbackName, Component displayName, List<Component> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        ItemMeta rawMeta = head.getItemMeta();
        if (rawMeta instanceof SkullMeta skull) {
            if (playerId != null && Bukkit.getServer() != null) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(playerId));
            }
            skull.displayName(displayName);
            skull.lore(lore);
            skull.addItemFlags(ItemFlag.values());
            head.setItemMeta(skull);
        }
        return head;
    }

    private static void applyMeta(ItemStack stack, Component displayName, List<Component> lore, boolean glint) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.displayName(displayName);
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore);
        }
        if (glint) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
    }

    private static Material resolveMaterial(@Nullable String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material direct = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return direct != null && direct.isItem() ? direct : fallback;
    }
}
