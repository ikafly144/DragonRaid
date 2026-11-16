package net.sabafly.dragonraid.altar;

import java.util.Objects;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

/**
 * Bukkit/Folia event listener opening the Exit Portal Altar GUI on right-click and
 * protecting/routing clicks inside {@link AltarInventoryHolder} menus.
 */
public final class AltarInteractionListener implements Listener {

    private final AltarGuiService altarGuiService;
    private final RaidEncounterManager encounterManager;

    public AltarInteractionListener(AltarGuiService altarGuiService, RaidEncounterManager encounterManager) {
        this.altarGuiService = Objects.requireNonNull(altarGuiService, "altarGuiService");
        this.encounterManager = Objects.requireNonNull(encounterManager, "encounterManager");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (encounterManager.isDowned(player.getUniqueId())) {
            return;
        }
        if (!altarGuiService.isAltarInteractionBlock(event.getClickedBlock())) {
            return;
        }
        // Prevent vanilla End Crystal placement on the altar rim and open the GUI once per interaction.
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            altarGuiService.openAltarGui(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof AltarInventoryHolder)) {
            return;
        }
        event.setCancelled(true);
        Inventory clicked = event.getClickedInventory();
        if (clicked != null
                && clicked.getHolder() instanceof AltarInventoryHolder
                && event.getWhoClicked() instanceof Player player) {
            altarGuiService.handleClick(player, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof AltarInventoryHolder) {
            event.setCancelled(true);
        }
    }
}
