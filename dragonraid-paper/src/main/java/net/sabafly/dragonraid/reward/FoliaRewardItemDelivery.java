package net.sabafly.dragonraid.reward;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.reward.RewardItemDelivery;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe {@link RewardItemDelivery} dispatching inventory additions onto the target
 * player's owning {@link net.sabafly.dragonraid.scheduler.RaidScheduler#runAtEntity} thread.
 */
public final class FoliaRewardItemDelivery implements RewardItemDelivery {

    private final RaidScheduler scheduler;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final @Nullable RaidItemService itemService;
    private final Logger logger;

    public FoliaRewardItemDelivery(
            RaidScheduler scheduler,
            Function<UUID, @Nullable Player> playerLookup,
            @Nullable RaidItemService itemService,
            Logger logger) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.itemService = itemService;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public FoliaRewardItemDelivery(RaidScheduler scheduler, @Nullable RaidItemService itemService, Logger logger) {
        this(scheduler, Bukkit::getPlayer, itemService, logger);
    }

    @Override
    public void deliverVanillaItem(UUID playerId, String materialName, int amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (materialName == null || materialName.isBlank() || amount <= 0) {
            return;
        }
        Material material = Material.matchMaterial(materialName.trim());
        if (material == null || !material.isItem()) {
            logger.warning("Ignoring unknown vanilla reward item material: " + materialName);
            return;
        }
        Player player = playerLookup.apply(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        int remaining = amount;
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            int left = remaining;
            int maxStack = Math.max(1, material.getMaxStackSize());
            while (left > 0) {
                int batch = Math.min(left, maxStack);
                left -= batch;
                ItemStack stack = new ItemStack(material, batch);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                for (ItemStack extra : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), extra);
                }
            }
        });
    }

    @Override
    public void deliverCustomItem(UUID playerId, String customItemId, int amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (customItemId == null || customItemId.isBlank() || amount <= 0) {
            return;
        }
        if (itemService == null || itemService.getItem(customItemId).isEmpty()) {
            logger.warning("Cannot deliver unregistered custom item '" + customItemId + "' to " + playerId);
            return;
        }
        // Full custom ItemStack factory arrives with M5 dragonraid-item implementation.
        logger.info("Deferred custom reward item delivery '" + customItemId + "' x" + amount + " for " + playerId);
    }
}
