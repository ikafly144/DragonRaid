package net.sabafly.dragonraid.reward;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.reward.RewardItemDelivery;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
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
    public CompletableFuture<Boolean> deliverVanillaItem(UUID playerId, String materialName, int amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (materialName == null || materialName.isBlank() || amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        Material material = Material.matchMaterial(materialName.trim());
        if (material == null || !material.isItem()) {
            logger.warning("Ignoring unknown vanilla reward item material: " + materialName);
            return CompletableFuture.completedFuture(false);
        }
        Player player = playerLookup.apply(playerId);
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }
        int remaining = amount;
        CompletableFuture<Boolean> deliveryFuture = new CompletableFuture<>();
        try {
            TaskHandle handle = scheduler.runAtEntity(
                    player,
                    () -> {
                        try {
                            if (!player.isOnline()) {
                                deliveryFuture.complete(false);
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
                            deliveryFuture.complete(true);
                        } catch (Throwable ex) {
                            logger.log(Level.WARNING,
                                    "Failed to deliver vanilla reward item '" + materialName + "' to " + playerId, ex);
                            deliveryFuture.complete(false);
                        }
                    },
                    () -> deliveryFuture.complete(false)
            );
            if (handle == null || handle == TaskHandle.retired() || handle.isCancelled()) {
                deliveryFuture.complete(false);
            } else {
                handle.onCancel(() -> deliveryFuture.complete(false));
            }
        } catch (Throwable ex) {
            logger.log(Level.WARNING,
                    "Failed to schedule vanilla reward item delivery '" + materialName + "' for " + playerId, ex);
            deliveryFuture.complete(false);
        }
        return deliveryFuture;
    }

    @Override
    public CompletableFuture<Boolean> deliverCustomItem(UUID playerId, String customItemId, int amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (customItemId == null || customItemId.isBlank() || amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        if (itemService == null || itemService.getItem(customItemId).isEmpty()) {
            logger.warning("Cannot deliver unregistered custom item '" + customItemId + "' to " + playerId);
            return CompletableFuture.completedFuture(false);
        }
        // Full custom ItemStack builder arrives with M5 dragonraid-item implementation; do not mark applied/claimed yet.
        logger.warning("Custom reward item delivery is unavailable until M5 ('" + customItemId + "' x" + amount + " for " + playerId + ")");
        return CompletableFuture.completedFuture(false);
    }
}
