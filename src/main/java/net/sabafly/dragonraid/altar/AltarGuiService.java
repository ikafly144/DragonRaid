package net.sabafly.dragonraid.altar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.command.RaidCommandHandler;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.AltarSettings;
import net.sabafly.dragonraid.config.PluginConfig.ItemCost;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.encounter.RaidState;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.PlayerRaidStats;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

/**
 * Coordinates the Exit Portal Altar GUI for Normal difficulty summoning, Hard/Savage preview/lock,
 * summon cost verification/deduction, and reward shop navigation.
 */
public final class AltarGuiService {

    public static final int INVENTORY_SIZE = 27;
    public static final int NORMAL_SLOT = 11;
    public static final int HARD_SLOT = 13;
    public static final int SHOP_SLOT = 15;

    /**
     * Abstraction decoupling Bukkit inventory/ItemStack creation from server runtime for unit tests.
     */
    public interface GuiBackend {
        Inventory createInventory(AltarInventoryHolder holder, int size, Component title);

        ItemStack createButton(Material material, Component displayName, List<Component> lore);
    }

    /**
     * Abstraction for inspecting and deducting summon costs from a player's inventory.
     */
    public interface InventoryCostHandler {
        boolean hasCosts(Player player, List<ItemCost> costs);

        void consumeCosts(Player player, List<ItemCost> costs);
    }

    private final ConfigManager configManager;
    private final MessageService messageService;
    private final RaidScheduler scheduler;
    private final RaidRegionService regionService;
    private final PlayerDataRepository playerDataRepository;
    private final RaidEncounterManager encounterManager;
    private final RaidCommandHandler commandHandler;
    private final Logger logger;
    private final GuiBackend guiBackend;
    private final InventoryCostHandler costHandler;

    public AltarGuiService(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            PlayerDataRepository playerDataRepository,
            RaidEncounterManager encounterManager,
            RaidCommandHandler commandHandler,
            Logger logger) {
        this(
                configManager,
                messageService,
                scheduler,
                regionService,
                playerDataRepository,
                encounterManager,
                commandHandler,
                logger,
                new BukkitGuiBackend(),
                new BukkitInventoryCostHandler()
        );
    }

    AltarGuiService(
            ConfigManager configManager,
            MessageService messageService,
            RaidScheduler scheduler,
            RaidRegionService regionService,
            PlayerDataRepository playerDataRepository,
            RaidEncounterManager encounterManager,
            RaidCommandHandler commandHandler,
            Logger logger,
            GuiBackend guiBackend,
            InventoryCostHandler costHandler) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.regionService = Objects.requireNonNull(regionService, "regionService");
        this.playerDataRepository = Objects.requireNonNull(playerDataRepository, "playerDataRepository");
        this.encounterManager = Objects.requireNonNull(encounterManager, "encounterManager");
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.guiBackend = Objects.requireNonNull(guiBackend, "guiBackend");
        this.costHandler = Objects.requireNonNull(costHandler, "costHandler");
    }

    /**
     * Returns {@code true} if {@code block} represents the Exit Portal Altar within its configured radius.
     */
    public boolean isAltarInteractionBlock(@Nullable Block block) {
        if (block == null) {
            return false;
        }
        Location loc = block.getLocation();
        if (!regionService.isRaidWorld(loc.getWorld())) {
            return false;
        }
        AltarSettings altar = configManager.pluginConfig().encounter().altar();
        RegionConfig.Point3D altarCenter = altar.location();
        double dx = (loc.getX() + 0.5) - altarCenter.x();
        double dy = (loc.getY() + 0.5) - altarCenter.y();
        double dz = (loc.getZ() + 0.5) - altarCenter.z();
        double radius = altar.interactRadius();
        if (dx * dx + dy * dy + dz * dz <= radius * radius) {
            Material type = block.getType();
            return type == Material.BEDROCK
                    || type == Material.END_PORTAL_FRAME
                    || type == Material.END_PORTAL
                    || type == Material.END_STONE
                    || type == Material.OBSIDIAN;
        }
        return false;
    }

    /**
     * Opens the Altar GUI for {@code player}, asynchronously checking Normal clear unlock status
     * and dispatching the inventory open onto the player's entity thread.
     */
    public void openAltarGui(Player player) {
        Objects.requireNonNull(player, "player");
        playerDataRepository.findByUuid(player.getUniqueId())
                .whenComplete((optStats, error) -> {
                    if (error != null) {
                        logger.log(Level.FINE, "Failed to load player stats for Altar GUI", error);
                    }
                    int normalClears = optStats != null ? optStats.map(PlayerRaidStats::normalClears).orElse(0) : 0;
                    scheduler.runAtEntity(player, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        Inventory inventory = buildAltarInventory(player, normalClears);
                        player.openInventory(inventory);
                    });
                });
    }

    Inventory buildAltarInventory(Player player, int normalClears) {
        AltarSettings altar = configManager.pluginConfig().encounter().altar();
        boolean firstClearFree = encounterManager.isFirstClearEncounterAvailable();
        boolean hardUnlocked = !altar.unlockHardAfterNormalClear() || normalClears > 0;

        AltarInventoryHolder holder = new AltarInventoryHolder();
        Component title = messageService.render(player, "altar.title");
        Inventory inventory = guiBackend.createInventory(holder, INVENTORY_SIZE, title);
        holder.setInventory(inventory);

        ItemStack filler = guiBackend.createButton(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            inventory.setItem(i, filler);
        }

        // Slot 11: Normal Difficulty
        List<Component> normalLore = new ArrayList<>(messageService.renderList(player, "altar.normal-lore"));
        if (firstClearFree) {
            normalLore.add(messageService.render(player, "altar.cost-free"));
        } else if (!altar.normalCosts().isEmpty()) {
            normalLore.add(messageService.render(
                    player,
                    "altar.cost-line",
                    Placeholder.unparsed("cost", formatCosts(altar.normalCosts()))
            ));
        }
        inventory.setItem(NORMAL_SLOT, guiBackend.createButton(
                Material.END_CRYSTAL,
                messageService.render(player, "altar.normal-name"),
                normalLore
        ));

        // Slot 13: Hard / Savage (Preview / Locked in M3)
        List<Component> hardLore = new ArrayList<>(messageService.renderList(player, "altar.hard-lore"));
        if (!hardUnlocked) {
            hardLore.add(messageService.render(player, "altar.hard-locked"));
        } else if (!altar.hardCosts().isEmpty()) {
            hardLore.add(messageService.render(
                    player,
                    "altar.cost-line",
                    Placeholder.unparsed("cost", formatCosts(altar.hardCosts()))
            ));
        }
        inventory.setItem(HARD_SLOT, guiBackend.createButton(
                hardUnlocked ? Material.NETHER_STAR : Material.BARRIER,
                messageService.render(player, "altar.hard-name"),
                hardLore
        ));

        // Slot 15: Reward Shop
        inventory.setItem(SHOP_SLOT, guiBackend.createButton(
                Material.EMERALD,
                messageService.render(player, "altar.open-shop-button"),
                List.of()
        ));

        return inventory;
    }

    /**
     * Handles clicks inside the Altar GUI on the clicking player's entity thread.
     */
    public void handleClick(Player player, int rawSlot) {
        Objects.requireNonNull(player, "player");
        switch (rawSlot) {
            case NORMAL_SLOT -> handleNormalSummonClick(player);
            case HARD_SLOT -> handleHardPreviewClick(player);
            case SHOP_SLOT -> {
                player.closeInventory();
                commandHandler.openShop(player);
            }
            default -> {
                // Filler slot: keep inventory open and ignore.
            }
        }
    }

    private void handleNormalSummonClick(Player player) {
        player.closeInventory();
        if (encounterManager.state() != RaidState.IDLE) {
            messageService.send(player, "command.already-running");
            return;
        }
        boolean freeFirstClear = encounterManager.isFirstClearEncounterAvailable();
        List<ItemCost> costs = configManager.pluginConfig().encounter().altar().normalCosts();
        if (!freeFirstClear && !costs.isEmpty() && !costHandler.hasCosts(player, costs)) {
            messageService.send(
                    player,
                    "altar.insufficient-cost",
                    Placeholder.unparsed("cost", formatCosts(costs))
            );
            return;
        }
        boolean started = encounterManager.startAltarPull(player, BossConfig.Difficulty.NORMAL);
        if (started && !freeFirstClear && !costs.isEmpty()) {
            costHandler.consumeCosts(player, costs);
        }
    }

    private void handleHardPreviewClick(Player player) {
        player.closeInventory();
        AltarSettings altar = configManager.pluginConfig().encounter().altar();
        if (!altar.unlockHardAfterNormalClear()) {
            messageService.send(player, "altar.hard-unavailable");
            return;
        }
        playerDataRepository.findByUuid(player.getUniqueId())
                .whenComplete((optStats, ex) -> {
                    int clears = optStats != null ? optStats.map(PlayerRaidStats::normalClears).orElse(0) : 0;
                    scheduler.runAtEntity(player, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (clears <= 0) {
                            messageService.send(player, "altar.hard-locked");
                        } else {
                            messageService.send(player, "altar.hard-unavailable");
                        }
                    });
                });
    }

    public static String formatCosts(List<ItemCost> costs) {
        if (costs == null || costs.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < costs.size(); i++) {
            ItemCost cost = costs.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cost.material()).append(" x").append(cost.amount());
        }
        return sb.toString();
    }

    private static final class BukkitGuiBackend implements GuiBackend {

        @Override
        public Inventory createInventory(AltarInventoryHolder holder, int size, Component title) {
            return Bukkit.createInventory(holder, size, title);
        }

        @Override
        public ItemStack createButton(Material material, Component displayName, List<Component> lore) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(displayName);
                if (!lore.isEmpty()) {
                    meta.lore(lore);
                }
                item.setItemMeta(meta);
            }
            return item;
        }
    }

    private static final class BukkitInventoryCostHandler implements InventoryCostHandler {

        @Override
        public boolean hasCosts(Player player, List<ItemCost> costs) {
            PlayerInventory inv = player.getInventory();
            for (ItemCost cost : costs) {
                Material material = resolveMaterial(cost.material());
                if (material == null || !inv.containsAtLeast(new ItemStack(material), cost.amount())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void consumeCosts(Player player, List<ItemCost> costs) {
            PlayerInventory inv = player.getInventory();
            for (ItemCost cost : costs) {
                Material material = resolveMaterial(cost.material());
                if (material != null && cost.amount() > 0) {
                    inv.removeItemAnySlot(new ItemStack(material, cost.amount()));
                }
            }
        }

        private static @Nullable Material resolveMaterial(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        }
    }
}
