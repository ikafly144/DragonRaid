package net.sabafly.dragonraid.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.region.DefaultRaidRegionService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaProtectionListenerTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private TemporaryBlockTracker tracker;
    private AtomicBoolean protectionActive;
    private ArenaProtectionListener listener;
    private World endWorld;
    private Player player;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> ArenaProtectionListenerTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        MessageService messageService = new MessageService(configManager);
        messageService.reload();

        RaidScheduler scheduler = mock(RaidScheduler.class);
        DefaultRaidRegionService regionService = new DefaultRaidRegionService(configManager, scheduler);
        tracker = new TemporaryBlockTracker();
        protectionActive = new AtomicBoolean(true);
        listener = new ArenaProtectionListener(
                configManager,
                regionService,
                tracker,
                messageService,
                protectionActive::get);

        endWorld = mock(World.class);
        lenient().when(endWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(endWorld.getName()).thenReturn("world_the_end");

        player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(player.getName()).thenReturn("Steve");
        lenient().when(player.locale()).thenReturn(Locale.US);
    }

    @Test
    void protectsOriginalArenaTerrain_allowsTemporaryBlocksAndIgnoresOutsideStructures() {
        Block arenaPillar = mockBlock(0, 70, 0, Material.OBSIDIAN);
        BlockBreakEvent breakOriginal = new BlockBreakEvent(arenaPillar, player);
        listener.onBlockBreak(breakOriginal);
        assertTrue(breakOriginal.isCancelled());
        verify(player).sendMessage(any(Component.class));

        // Outside arena (e.g. Enderman trap at x=400) is NOT cancelled
        Block trapBlock = mockBlock(400, 64, 0, Material.OBSIDIAN);
        BlockBreakEvent breakOutside = new BlockBreakEvent(trapBlock, player);
        listener.onBlockBreak(breakOutside);
        assertFalse(breakOutside.isCancelled());

        // Temporary block placed inside arena is tracked, then allowed to be broken
        Block tempBlock = mockBlock(5, 65, 5, Material.COBBLESTONE);
        BlockState replacedState = mock(BlockState.class);
        BlockData airData = mockData();
        when(replacedState.getBlockData()).thenReturn(airData);
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(
                tempBlock, replacedState, arenaPillar, mock(ItemStack.class), player, true, EquipmentSlot.HAND);
        listener.onBlockPlace(placeEvent);
        assertFalse(placeEvent.isCancelled());
        assertTrue(tracker.isTracked(tempBlock.getLocation()));

        BlockBreakEvent breakTemp = new BlockBreakEvent(tempBlock, player);
        listener.onBlockBreak(breakTemp);
        assertFalse(breakTemp.isCancelled());
        assertFalse(tracker.isTracked(tempBlock.getLocation()));
    }

    @Test
    void cancelsBedAndRespawnAnchorInteractionsAndBlockExplosionsInArena() {
        Block bed = mockBlock(10, 64, 10, Material.RED_BED);
        PlayerInteractEvent bedUse = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null, bed, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(bedUse);
        assertTrue(bedUse.isCancelled());

        Block anchor = mockBlock(12, 64, 10, Material.RESPAWN_ANCHOR);
        PlayerInteractEvent anchorUse = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null, anchor, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(anchorUse);
        assertTrue(anchorUse.isCancelled());

        BlockExplodeEvent blockExplode = new BlockExplodeEvent(
                bed, bed.getState(), new ArrayList<>(List.of(bed)), 5.0f, org.bukkit.ExplosionResult.DESTROY);
        listener.onBlockExplode(blockExplode);
        assertTrue(blockExplode.isCancelled());
        assertTrue(blockExplode.blockList().isEmpty());
    }

    @Test
    void entityExplode_preservesOriginalArenaBlocksAndUntracksDestroyedTempBlocks() {
        Block original = mockBlock(1, 64, 1, Material.END_STONE);
        Block temp = mockBlock(2, 64, 2, Material.DIRT);
        Block outside = mockBlock(300, 64, 0, Material.END_STONE);
        tracker.tryRecordPlayerPlacement(temp.getLocation(), player.getUniqueId(), mockData(), 64, 512);

        List<Block> blocks = new ArrayList<>(List.of(original, temp, outside));
        EntityExplodeEvent explode = new EntityExplodeEvent(
                mock(Entity.class), original.getLocation(), blocks, 4.0f, org.bukkit.ExplosionResult.DESTROY);
        listener.onEntityExplode(explode);

        assertEquals(List.of(temp, outside), explode.blockList());
        assertFalse(tracker.isTracked(temp.getLocation()));
    }

    @Test
    void tracksWaterBucketAndFlowAndRespectsInactiveProtection() {
        Block waterTarget = mockBlock(15, 65, 15, Material.AIR);
        Block clicked = mockBlock(15, 64, 15, Material.END_STONE);
        PlayerBucketEmptyEvent bucketEmpty = new PlayerBucketEmptyEvent(
                player, waterTarget, clicked, org.bukkit.block.BlockFace.UP,
                Material.WATER_BUCKET, mock(ItemStack.class), EquipmentSlot.HAND);
        listener.onBucketEmpty(bucketEmpty);
        assertFalse(bucketEmpty.isCancelled());
        assertTrue(tracker.isTracked(waterTarget.getLocation()));

        Block flowTo = mockBlock(16, 65, 15, Material.AIR);
        BlockFromToEvent flow = new BlockFromToEvent(waterTarget, flowTo);
        listener.onBlockFromTo(flow);
        assertFalse(flow.isCancelled());
        assertTrue(tracker.isTracked(flowTo.getLocation()));

        // When protection is inactive, original arena terrain can be broken normally
        protectionActive.set(false);
        BlockBreakEvent inactiveBreak = new BlockBreakEvent(clicked, player);
        listener.onBlockBreak(inactiveBreak);
        assertFalse(inactiveBreak.isCancelled());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void cancelsPistonMovementAffectingOriginalArenaTerrain() {
        Block piston = mockBlock(20, 64, 20, Material.PISTON);
        Block originalTarget = mockBlock(21, 64, 20, Material.END_STONE);
        BlockPistonExtendEvent extend = new BlockPistonExtendEvent(
                piston, List.of(originalTarget), org.bukkit.block.BlockFace.EAST);
        listener.onPistonExtend(extend);
        assertTrue(extend.isCancelled());
    }

    private Block mockBlock(int x, int y, int z, Material material) {
        Block block = mock(Block.class);
        Location loc = new Location(endWorld, x, y, z);
        BlockData data = mockData();
        lenient().when(block.getWorld()).thenReturn(endWorld);
        lenient().when(block.getX()).thenReturn(x);
        lenient().when(block.getY()).thenReturn(y);
        lenient().when(block.getZ()).thenReturn(z);
        lenient().when(block.getLocation()).thenReturn(loc);
        lenient().when(block.getType()).thenReturn(material);
        lenient().when(block.getBlockData()).thenReturn(data);
        lenient().when(block.getState()).thenReturn(mock(BlockState.class));
        return block;
    }

    private static BlockData mockData() {
        BlockData data = mock(BlockData.class);
        lenient().when(data.clone()).thenReturn(data);
        return data;
    }
}
