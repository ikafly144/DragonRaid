package net.sabafly.dragonraid.encounter;

import java.util.Objects;
import net.sabafly.dragonraid.i18n.MessageService;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Bukkit/Folia event listener bridging player lifecycle, combat damage, movement,
 * right-click revive holds, and dragon defeat hooks to {@link RaidEncounterManager}.
 */
public final class RaidPlayerLifecycleListener implements Listener {

    private final RaidEncounterManager encounterManager;
    private final MessageService messageService;

    public RaidPlayerLifecycleListener(RaidEncounterManager encounterManager, MessageService messageService) {
        this.encounterManager = Objects.requireNonNull(encounterManager, "encounterManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        encounterManager.onPlayerMove(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        encounterManager.onPlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        encounterManager.onPlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        encounterManager.onPlayerChangedWorld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            encounterManager.onPlayerDamage(player, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && encounterManager.isDowned(attacker.getUniqueId())) {
            event.setCancelled(true);
            attacker.sendActionBar(messageService.render(attacker, "revive.downed-action-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(messageService.render(player, "revive.downed-action-blocked"));
            return;
        }
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            encounterManager.recordRightClickPulse(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(messageService.render(player, "revive.downed-action-blocked"));
            return;
        }
        encounterManager.recordRightClickPulse(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(messageService.render(player, "revive.downed-action-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(messageService.render(player, "revive.downed-action-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(messageService.render(player, "revive.downed-action-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(messageService.render(player, "revive.downed-action-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendActionBar(messageService.render(player, "revive.downed-action-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        TeleportCause cause = event.getCause();
        if ((cause == TeleportCause.ENDER_PEARL || cause == TeleportCause.CONSUMABLE_EFFECT)
                && encounterManager.isDowned(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        encounterManager.onParticipantDeath(event.getEntity(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            encounterManager.onDragonDefeated(dragon);
        }
    }
}
