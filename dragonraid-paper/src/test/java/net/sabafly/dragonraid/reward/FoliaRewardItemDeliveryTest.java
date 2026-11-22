package net.sabafly.dragonraid.reward;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.item.CustomItemDefinition;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.reward.ItemDeliveryResult;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

class FoliaRewardItemDeliveryTest {

    @Test
    void deliverCustomItem_returnsFalseEvenWhenCustomItemRegistered_untilM5() {
        RaidScheduler scheduler = mock(RaidScheduler.class);
        RaidItemService itemService = mock(RaidItemService.class);
        UUID playerId = UUID.randomUUID();
        when(itemService.getItem("dragon_sovereign_crest"))
                .thenReturn(Optional.of(new CustomItemDefinition("dragon_sovereign_crest", "item.crest", 120, Map.of())));

        FoliaRewardItemDelivery withoutItemService = new FoliaRewardItemDelivery(
                scheduler, id -> null, null, Logger.getLogger("item-test"));
        FoliaRewardItemDelivery withItemService = new FoliaRewardItemDelivery(
                scheduler, id -> null, itemService, Logger.getLogger("item-test"));

        assertFalse(withoutItemService.deliverCustomItem(playerId, "dragon_sovereign_crest", 1).join());
        assertFalse(withItemService.deliverCustomItem(playerId, "dragon_sovereign_crest", 1).join());
        assertFalse(withItemService.deliverCustomItem(playerId, "", 1).join());
        assertFalse(withItemService.deliverCustomItem(playerId, "dragon_sovereign_crest", 0).join());
    }

    @Test
    void deliverVanillaItem_handlesValidationOfflineLogoutRetiredAndCancel() {
        UUID playerId = UUID.randomUUID();
        Player onlinePlayer = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(onlinePlayer.getInventory()).thenReturn(inventory);

        AtomicReference<Player> lookupRef = new AtomicReference<>(onlinePlayer);
        AtomicReference<Runnable> capturedTask = new AtomicReference<>();
        AtomicReference<Runnable> capturedRetired = new AtomicReference<>();
        AtomicReference<Runnable> capturedCancelCallback = new AtomicReference<>();

        RaidScheduler scheduler = mock(RaidScheduler.class);
        when(scheduler.runAtEntity(eq(onlinePlayer), any(Runnable.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    capturedTask.set(inv.getArgument(1, Runnable.class));
                    capturedRetired.set(inv.getArgument(2, Runnable.class));
                    TaskHandle handle = mock(TaskHandle.class);
                    doAnswer(cb -> {
                        capturedCancelCallback.set(cb.getArgument(0, Runnable.class));
                        return null;
                    }).when(handle).onCancel(any(Runnable.class));
                    return handle;
                });

        FoliaRewardItemDelivery delivery = new FoliaRewardItemDelivery(
                scheduler,
                id -> playerId.equals(id) ? lookupRef.get() : null,
                null,
                Logger.getLogger("item-test"),
                (mat, count) -> {
                    ItemStack stack = mock(ItemStack.class);
                    org.mockito.Mockito.lenient().when(stack.getAmount()).thenReturn(count);
                    return stack;
                });

        Material mockDiamond = mock(Material.class);
        when(mockDiamond.isItem()).thenReturn(true);
        when(mockDiamond.getMaxStackSize()).thenReturn(64);
        try (org.mockito.MockedStatic<Material> materialStatic = org.mockito.Mockito.mockStatic(Material.class)) {
            materialStatic.when(() -> Material.matchMaterial("DIAMOND")).thenReturn(mockDiamond);
            materialStatic.when(() -> Material.matchMaterial("NOT_A_REAL_MATERIAL_XYZ")).thenReturn(null);

            // Invalid inputs return false immediately without scheduling
            assertFalse(delivery.deliverVanillaItem(playerId, "", 1).join());
            assertFalse(delivery.deliverVanillaItem(playerId, "DIAMOND", 0).join());
            assertFalse(delivery.deliverVanillaItem(playerId, "NOT_A_REAL_MATERIAL_XYZ", 1).join());

            // Missing or offline player returns false immediately
            lookupRef.set(null);
            assertFalse(delivery.deliverVanillaItem(playerId, "DIAMOND", 1).join());
            lookupRef.set(onlinePlayer);
            when(onlinePlayer.isOnline()).thenReturn(false);
            assertFalse(delivery.deliverVanillaItem(playerId, "DIAMOND", 1).join());

            // Player logs out between scheduling and entity-thread execution -> returns false, no inventory write
            when(onlinePlayer.isOnline()).thenReturn(true, false);
            CompletableFuture<Boolean> logoutFuture = delivery.deliverVanillaItem(playerId, "DIAMOND", 1);
            assertFalse(logoutFuture.isDone());
            capturedTask.get().run();
            assertTrue(logoutFuture.isDone());
            assertFalse(logoutFuture.join());
            verify(inventory, never()).addItem(any());

            // Entity retired before task executes -> returns false
            when(onlinePlayer.isOnline()).thenReturn(true);
            CompletableFuture<Boolean> retiredFuture = delivery.deliverVanillaItem(playerId, "DIAMOND", 1);
            assertFalse(retiredFuture.isDone());
            capturedRetired.get().run();
            assertTrue(retiredFuture.isDone());
            assertFalse(retiredFuture.join());

            // TaskHandle cancelled via onCancel -> returns false
            CompletableFuture<Boolean> cancelledFuture = delivery.deliverVanillaItem(playerId, "DIAMOND", 1);
            assertFalse(cancelledFuture.isDone());
            capturedCancelCallback.get().run();
            assertTrue(cancelledFuture.isDone());
            assertFalse(cancelledFuture.join());

            // #23: Reject excessive stack count before scheduling side effects
            int overLimitAmount = (FoliaRewardItemDelivery.MAX_DELIVERY_STACKS + 1) * 64;
            ItemDeliveryResult overLimitRes = delivery.deliverVanillaItemDetailed(playerId, "DIAMOND", overLimitAmount).join();
            assertFalse(overLimitRes.completed());
            org.junit.jupiter.api.Assertions.assertEquals(0, overLimitRes.deliveredAmount());

            // #22: Partial batch delivery reports exact delivered count when batch 2 throws
            when(inventory.addItem(any()))
                    .thenReturn(new java.util.HashMap<>())
                    .thenThrow(new IllegalStateException("inventory fail on batch 2"));
            CompletableFuture<ItemDeliveryResult> partialFuture =
                    delivery.deliverVanillaItemDetailed(playerId, "DIAMOND", 128);
            capturedTask.get().run();
            assertTrue(partialFuture.isDone());
            ItemDeliveryResult partialRes = partialFuture.join();
            assertFalse(partialRes.completed());
            org.junit.jupiter.api.Assertions.assertEquals(64, partialRes.deliveredAmount());
            org.junit.jupiter.api.Assertions.assertEquals(64, partialRes.remainingAmount());
        }
    }
}
