package net.sabafly.dragonraid.protection;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.ProtectionSettings;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker.TrackOutcome;
import net.sabafly.dragonraid.region.RaidRegionService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Enforces sandbox terrain protection, bed/respawn-anchor explosion blocking, utility item rules,
 * and temporary block/liquid tracking inside the raid arena during an encounter.
 *
 * <p>Blocks and structures outside {@link RaidRegionService#isInArena} are never modified or cancelled.
 */
public final class ArenaProtectionListener implements Listener {

    private final ConfigManager configManager;
    private final RaidRegionService regionService;
    private final TemporaryBlockTracker blockTracker;
    private final MessageService messageService;
    private final BooleanSupplier protectionActive;

    public ArenaProtectionListener(
            ConfigManager configManager,
            RaidRegionService regionService,
            TemporaryBlockTracker blockTracker,
            MessageService messageService,
            BooleanSupplier protectionActive) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.regionService = Objects.requireNonNull(regionService, "regionService");
        this.blockTracker = Objects.requireNonNull(blockTracker, "blockTracker");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.protectionActive = Objects.requireNonNull(protectionActive, "protectionActive");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        Block block = event.getBlock();
        if (!regionService.isInArena(block.getLocation())) {
            return;
        }
        if (blockTracker.isTracked(block.getLocation())) {
            blockTracker.untrack(block.getLocation());
            return;
        }
        if (protection().protectOriginalArenaTerrain()) {
            event.setCancelled(true);
            messageService.send(event.getPlayer(), "raid.arena-protected");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (!regionService.isInArena(block.getLocation())) {
            return;
        }
        ProtectionSettings settings = protection();
        Player player = event.getPlayer();
        if (!settings.allowTemporaryBlocks()) {
            event.setCancelled(true);
            messageService.send(player, "raid.temp-block-disabled");
            return;
        }
        BlockData originalData = event.getBlockReplacedState().getBlockData();
        TrackOutcome outcome = blockTracker.tryRecordPlayerPlacement(
                block.getLocation(),
                player.getUniqueId(),
                originalData,
                settings.maxTemporaryBlocksPerPlayer(),
                settings.maxTemporaryBlocksTotal());
        handlePlacementLimit(event, player, outcome, settings);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        Block block = event.getBlock();
        if (!regionService.isInArena(block.getLocation())) {
            return;
        }
        ProtectionSettings settings = protection();
        Player player = event.getPlayer();
        Material bucket = event.getBucket();
        if (bucket == Material.WATER_BUCKET) {
            if (!settings.allowWaterBuckets()) {
                event.setCancelled(true);
                messageService.send(player, "raid.water-bucket-blocked");
                return;
            }
        } else if (!settings.allowTemporaryBlocks()) {
            event.setCancelled(true);
            messageService.send(player, "raid.temp-block-disabled");
            return;
        }

        TrackOutcome outcome = blockTracker.tryRecordPlayerPlacement(
                block.getLocation(),
                player.getUniqueId(),
                block.getBlockData(),
                settings.maxTemporaryBlocksPerPlayer(),
                settings.maxTemporaryBlocksTotal());
        handlePlacementLimit(event, player, outcome, settings);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        Block block = event.getBlock();
        if (regionService.isInArena(block.getLocation())) {
            blockTracker.untrack(block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        Block source = event.getBlock();
        Block target = event.getToBlock();
        if (!regionService.isInArena(target.getLocation())) {
            return;
        }
        if (!blockTracker.isTracked(source.getLocation())) {
            return;
        }
        TrackOutcome outcome = blockTracker.tryRecordSecondary(
                target.getLocation(),
                target.getBlockData(),
                protection().maxTemporaryBlocksTotal());
        if (outcome == TrackOutcome.TOTAL_LIMIT_REACHED) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        Block block = event.getBlock();
        if (!regionService.isInArena(block.getLocation())) {
            return;
        }
        if (blockTracker.isTracked(block.getLocation())) {
            return;
        }
        TrackOutcome outcome = blockTracker.tryRecordSecondary(
                block.getLocation(),
                block.getBlockData(),
                protection().maxTemporaryBlocksTotal());
        if (outcome == TrackOutcome.TOTAL_LIMIT_REACHED) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        boolean protectOriginal = protection().protectOriginalArenaTerrain();
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (!regionService.isInArena(block.getLocation())) {
                continue;
            }
            if (blockTracker.isTracked(block.getLocation())) {
                blockTracker.untrack(block.getLocation());
            } else if (protectOriginal) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        if (!regionService.isInArena(event.getBlock().getLocation())) {
            return;
        }
        if (protection().disableBedAndAnchorExplosions()) {
            event.setCancelled(true);
            event.blockList().clear();
            return;
        }
        if (protection().protectOriginalArenaTerrain()) {
            Iterator<Block> it = event.blockList().iterator();
            while (it.hasNext()) {
                Block block = it.next();
                if (!regionService.isInArena(block.getLocation())) {
                    continue;
                }
                if (blockTracker.isTracked(block.getLocation())) {
                    blockTracker.untrack(block.getLocation());
                } else {
                    it.remove();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        if (!protection().protectOriginalArenaTerrain()) {
            return;
        }
        Block block = event.getBlock();
        if (!regionService.isInArena(block.getLocation())) {
            return;
        }
        if (blockTracker.isTracked(block.getLocation())) {
            if (event.getTo().isAir()) {
                blockTracker.untrack(block.getLocation());
            }
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || (!regionService.isInArena(clicked.getLocation()) && !regionService.isInSafeZone(clicked.getLocation()))) {
            return;
        }
        if (protection().disableBedAndAnchorExplosions() && isBedOrAnchor(clicked.getType())) {
            event.setCancelled(true);
            messageService.send(event.getPlayer(), "raid.bed-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (shouldCancelPistonMovement(event.getBlock(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (shouldCancelPistonMovement(event.getBlock(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    private boolean shouldCancelPistonMovement(Block piston, List<Block> movedBlocks) {
        if (!protectionActive.getAsBoolean() || !protection().protectOriginalArenaTerrain()) {
            return false;
        }
        if (regionService.isInArena(piston.getLocation()) && !blockTracker.isTracked(piston.getLocation())) {
            return true;
        }
        for (Block block : movedBlocks) {
            if (regionService.isInArena(block.getLocation()) && !blockTracker.isTracked(block.getLocation())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBedOrAnchor(Material material) {
        return material == Material.RESPAWN_ANCHOR || material.name().endsWith("_BED");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!protectionActive.getAsBoolean()) {
            return;
        }
        if (!(event.getEntity().getShooter() instanceof Player player)) {
            return;
        }
        if (!regionService.isInArena(player.getLocation())) {
            return;
        }
        ProtectionSettings settings = protection();
        if (event.getEntity() instanceof EnderPearl && !settings.allowEnderPearls()) {
            event.setCancelled(true);
            messageService.send(player, "raid.ender-pearl-blocked");
        } else if (event.getEntity() instanceof WindCharge && !settings.allowWindCharges()) {
            event.setCancelled(true);
            messageService.send(player, "raid.wind-charge-blocked");
        }
    }

    private void handlePlacementLimit(
            org.bukkit.event.Cancellable event,
            Player player,
            TrackOutcome outcome,
            ProtectionSettings settings) {
        if (outcome == TrackOutcome.PLAYER_LIMIT_REACHED) {
            event.setCancelled(true);
            messageService.send(
                    player,
                    "raid.temp-block-limit",
                    Placeholder.unparsed("limit", String.valueOf(settings.maxTemporaryBlocksPerPlayer())));
        } else if (outcome == TrackOutcome.TOTAL_LIMIT_REACHED) {
            event.setCancelled(true);
            messageService.send(
                    player,
                    "raid.temp-block-limit",
                    Placeholder.unparsed("limit", String.valueOf(settings.maxTemporaryBlocksTotal())));
        }
    }

    private ProtectionSettings protection() {
        return configManager.pluginConfig().protection();
    }
}
