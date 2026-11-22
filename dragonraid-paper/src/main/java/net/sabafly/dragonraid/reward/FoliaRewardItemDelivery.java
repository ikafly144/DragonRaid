package net.sabafly.dragonraid.reward;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.reward.ItemDeliveryResult;
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
 * player's owning {@link net.sabafly.dragonraid.scheduler.RaidScheduler#runAtEntity} thread,
 * enforcing stack limits and returning accurate partial-delivery counts.
 */
public final class FoliaRewardItemDelivery implements RewardItemDelivery {

    public static final int MAX_DELIVERY_STACKS = 64;

    private final RaidScheduler scheduler;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final @Nullable RaidItemService itemService;
    private final Logger logger;
    private final BiFunction<Material, Integer, ItemStack> itemStackFactory;

    public FoliaRewardItemDelivery(
            RaidScheduler scheduler,
            Function<UUID, @Nullable Player> playerLookup,
            @Nullable RaidItemService itemService,
            Logger logger) {
        this(scheduler, playerLookup, itemService, logger, ItemStack::new);
    }

    FoliaRewardItemDelivery(
            RaidScheduler scheduler,
            Function<UUID, @Nullable Player> playerLookup,
            @Nullable RaidItemService itemService,
            Logger logger,
            BiFunction<Material, Integer, ItemStack> itemStackFactory) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.itemService = itemService;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.itemStackFactory = Objects.requireNonNull(itemStackFactory, "itemStackFactory");
    }

    public FoliaRewardItemDelivery(RaidScheduler scheduler, @Nullable RaidItemService itemService, Logger logger) {
        this(scheduler, Bukkit::getPlayer, itemService, logger);
    }

    @Override
    public CompletableFuture<Boolean> deliverVanillaItem(UUID playerId, String materialName, int amount) {
        return deliverVanillaItemDetailed(playerId, materialName, amount)
                .thenApply(ItemDeliveryResult::completed);
    }

    @Override
    public CompletableFuture<ItemDeliveryResult> deliverVanillaItemDetailed(
            UUID playerId,
            String materialName,
            int amount) {
        Objects.requireNonNull(playerId, "playerId");
        int safeRequested = Math.max(0, amount);
        if (materialName == null || materialName.isBlank() || amount <= 0) {
            return CompletableFuture.completedFuture(ItemDeliveryResult.failed(safeRequested));
        }
        Material material = Material.matchMaterial(materialName.trim());
        if (material == null || !material.isItem()) {
            logger.warning("Ignoring unknown vanilla reward item material: " + materialName);
            return CompletableFuture.completedFuture(ItemDeliveryResult.failed(safeRequested));
        }
        int maxStack = Math.max(1, material.getMaxStackSize());
        long stackCount = ((long) amount + maxStack - 1L) / maxStack;
        if (stackCount > MAX_DELIVERY_STACKS) {
            logger.warning("Rejecting excessive vanilla reward item delivery '" + materialName
                    + "' x" + amount + " (" + stackCount + " stacks > limit " + MAX_DELIVERY_STACKS + ") for " + playerId);
            return CompletableFuture.completedFuture(ItemDeliveryResult.failed(safeRequested));
        }
        Player player = playerLookup.apply(playerId);
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(ItemDeliveryResult.failed(safeRequested));
        }
        CompletableFuture<ItemDeliveryResult> deliveryFuture = new CompletableFuture<>();
        try {
            TaskHandle handle = scheduler.runAtEntity(
                    player,
                    () -> {
                        int delivered = 0;
                        try {
                            int left = amount;
                            while (left > 0) {
                                if (!player.isOnline()) {
                                    deliveryFuture.complete(outcomeForDelivered(amount, delivered));
                                    return;
                                }
                                int batch = Math.min(left, maxStack);
                                ItemStack stack = itemStackFactory.apply(material, batch);
                                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                                int notStored = 0;
                                if (overflow != null) {
                                    for (ItemStack extra : overflow.values()) {
                                        if (extra != null) {
                                            notStored += Math.max(0, extra.getAmount());
                                        }
                                    }
                                }
                                delivered += Math.max(0, batch - notStored);
                                if (overflow != null) {
                                    for (ItemStack extra : overflow.values()) {
                                        if (extra != null && extra.getAmount() > 0) {
                                            player.getWorld().dropItemNaturally(player.getLocation(), extra);
                                            delivered += extra.getAmount();
                                        }
                                    }
                                }
                                left -= batch;
                            }
                            deliveryFuture.complete(outcomeForDelivered(amount, delivered));
                        } catch (Throwable ex) {
                            logger.log(Level.WARNING,
                                    "Failed to deliver vanilla reward item '" + materialName + "' to " + playerId
                                            + " (delivered " + delivered + "/" + amount + ")", ex);
                            deliveryFuture.complete(outcomeForDelivered(amount, delivered));
                        }
                    },
                    () -> deliveryFuture.complete(ItemDeliveryResult.failed(safeRequested))
            );
            if (handle == null || handle == TaskHandle.retired() || handle.isCancelled()) {
                deliveryFuture.complete(ItemDeliveryResult.failed(safeRequested));
            } else {
                handle.onCancel(() -> deliveryFuture.complete(ItemDeliveryResult.failed(safeRequested)));
            }
        } catch (Throwable ex) {
            logger.log(Level.WARNING,
                    "Failed to schedule vanilla reward item delivery '" + materialName + "' for " + playerId, ex);
            deliveryFuture.complete(ItemDeliveryResult.failed(safeRequested));
        }
        return deliveryFuture;
    }

    private static ItemDeliveryResult outcomeForDelivered(int requested, int rawDelivered) {
        int clamped = Math.max(0, Math.min(requested, rawDelivered));
        if (clamped >= requested && requested > 0) {
            return ItemDeliveryResult.full(requested);
        }
        if (clamped > 0) {
            return ItemDeliveryResult.partial(requested, clamped);
        }
        return ItemDeliveryResult.failed(requested);
    }

    @Override
    public CompletableFuture<Boolean> deliverCustomItem(UUID playerId, String customItemId, int amount) {
        return deliverCustomItemDetailed(playerId, customItemId, amount)
                .thenApply(ItemDeliveryResult::completed);
    }

    @Override
    public CompletableFuture<ItemDeliveryResult> deliverCustomItemDetailed(
            UUID playerId,
            String customItemId,
            int amount) {
        Objects.requireNonNull(playerId, "playerId");
        int safeRequested = Math.max(0, amount);
        if (customItemId == null || customItemId.isBlank() || amount <= 0) {
            return CompletableFuture.completedFuture(ItemDeliveryResult.failed(safeRequested));
        }
        if (itemService == null || itemService.getItem(customItemId).isEmpty()) {
            logger.warning("Cannot deliver unregistered custom item '" + customItemId + "' to " + playerId);
            return CompletableFuture.completedFuture(ItemDeliveryResult.failed(safeRequested));
        }
        // Full custom ItemStack builder arrives with M5 dragonraid-item implementation; do not mark applied/claimed yet.
        logger.warning("Custom reward item delivery is unavailable until M5 ('" + customItemId + "' x" + amount + " for " + playerId + ")");
        return CompletableFuture.completedFuture(ItemDeliveryResult.failed(safeRequested));
    }
}
