package net.sabafly.dragonraid.finder.gui;

import java.util.Objects;
import java.util.UUID;
import net.sabafly.dragonraid.command.ContentFinderCommandHandler;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Cancels all item movement inside Content Finder & Ready Check GUIs and dispatches button actions
 * onto the clicking player's Folia entity scheduler.
 */
public final class FinderGuiListener implements Listener {

    private final ContentFinderCommandHandler commandHandler;
    private final RaidScheduler scheduler;

    public FinderGuiListener(ContentFinderCommandHandler commandHandler, RaidScheduler scheduler) {
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof ContentFinderMenu) && !(holder instanceof ReadyCheckMenu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getInventory().getSize()) {
            return;
        }
        if (holder instanceof ContentFinderMenu menu) {
            if (menu.hasPrevPageButton(rawSlot)) {
                scheduler.runAtEntity(player, () -> menu.render(menu.page() - 1));
                return;
            }
            if (menu.hasNextPageButton(rawSlot)) {
                scheduler.runAtEntity(player, () -> menu.render(menu.page() + 1));
                return;
            }
            if (menu.hasRetryButton(rawSlot)) {
                scheduler.runAtEntity(player, () -> {
                    player.closeInventory();
                    commandHandler.retryEncounter(player);
                });
                return;
            }
            if (menu.hasLeaveButton(rawSlot)) {
                scheduler.runAtEntity(player, () -> {
                    player.closeInventory();
                    commandHandler.leaveEncounter(player);
                });
                return;
            }
            String contentId = menu.contentIdAt(rawSlot);
            if (contentId != null) {
                scheduler.runAtEntity(player, () -> commandHandler.applyContent(player, contentId));
            }
        } else if (holder instanceof ReadyCheckMenu rcMenu) {
            UUID sessionId = rcMenu.sessionId();
            if (rawSlot == ReadyCheckMenu.SLOT_ACCEPT) {
                scheduler.runAtEntity(player, () -> commandHandler.acceptReadyCheck(player, sessionId));
            } else if (rawSlot == ReadyCheckMenu.SLOT_DECLINE) {
                scheduler.runAtEntity(player, () -> {
                    player.closeInventory();
                    commandHandler.declineReadyCheck(player, sessionId);
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ContentFinderMenu || holder instanceof ReadyCheckMenu) {
            event.setCancelled(true);
        }
    }
}
