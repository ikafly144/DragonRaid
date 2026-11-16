package net.sabafly.dragonraid.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker.TrackOutcome;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.storage.RaidRecoveryRepository.PendingBlockRollback;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TemporaryBlockTrackerTest {

    private TemporaryBlockTracker tracker;
    private World world;
    private UUID worldId;

    @BeforeEach
    void setUp() {
        tracker = new TemporaryBlockTracker();
        worldId = UUID.randomUUID();
        world = mock(World.class);
        lenient().when(world.getUID()).thenReturn(worldId);
    }

    @Test
    void enforcesPerPlayerAndTotalLimitsAndFreesOnUntrack() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        BlockData air = mockAirData();

        assertEquals(TrackOutcome.RECORDED,
                tracker.tryRecordPlayerPlacement(loc(1, 64, 1), alice, air, 2, 3));
        assertEquals(TrackOutcome.RECORDED,
                tracker.tryRecordPlayerPlacement(loc(2, 64, 1), alice, air, 2, 3));
        // Duplicate at same coordinate returns ALREADY_TRACKED without incrementing counts
        assertEquals(TrackOutcome.ALREADY_TRACKED,
                tracker.tryRecordPlayerPlacement(loc(2, 64, 1), alice, mockAirData(), 2, 3));

        // 3rd block by Alice hits per-player cap (2)
        assertEquals(TrackOutcome.PLAYER_LIMIT_REACHED,
                tracker.tryRecordPlayerPlacement(loc(3, 64, 1), alice, air, 2, 3));

        // Bob can still place 1 block up to total cap (3)
        assertEquals(TrackOutcome.RECORDED,
                tracker.tryRecordPlayerPlacement(loc(4, 64, 1), bob, air, 2, 3));
        assertEquals(TrackOutcome.TOTAL_LIMIT_REACHED,
                tracker.tryRecordPlayerPlacement(loc(5, 64, 1), bob, air, 2, 3));

        assertEquals(3, tracker.totalCount());
        assertEquals(2, tracker.playerCount(alice));
        assertEquals(1, tracker.playerCount(bob));

        // Untracking Alice's block frees both her per-player slot and total slot
        assertTrue(tracker.untrack(loc(1, 64, 1)));
        assertFalse(tracker.untrack(loc(1, 64, 1)));
        assertEquals(2, tracker.totalCount());
        assertEquals(1, tracker.playerCount(alice));
    }

    @Test
    void rollbackAll_groupsByChunkAndRestoresOriginalDataViaRegionScheduler() {
        UUID alice = UUID.randomUUID();
        BlockData orig1 = mockAirData();
        BlockData orig2 = mockAirData();
        BlockData orig3 = mockAirData();

        // Two blocks in chunk (0, 0) and one block in chunk (2, -1)
        tracker.tryRecordPlayerPlacement(loc(1, 64, 1), alice, orig1, 10, 10);
        tracker.tryRecordPlayerPlacement(loc(2, 64, 1), alice, orig2, 10, 10);
        tracker.tryRecordSecondary(loc(35, 65, -5), orig3, 10);

        Block b1 = mock(Block.class);
        Block b2 = mock(Block.class);
        Block b3 = mock(Block.class);
        when(world.getBlockAt(1, 64, 1)).thenReturn(b1);
        when(world.getBlockAt(2, 64, 1)).thenReturn(b2);
        when(world.getBlockAt(35, 65, -5)).thenReturn(b3);

        RaidScheduler scheduler = mock(RaidScheduler.class);
        when(scheduler.runAtRegion(eq(world), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return TaskHandle.retired();
        });

        int count = tracker.rollbackAll(scheduler, world).join();
        assertEquals(3, count);
        assertEquals(0, tracker.totalCount());

        // Dispatched once per distinct chunk: (0,0) and (2,-1)
        verify(scheduler, times(1)).runAtRegion(eq(world), eq(0), eq(0), any());
        verify(scheduler, times(1)).runAtRegion(eq(world), eq(2), eq(-1), any());
        verify(b1).setBlockData(orig1, false);
        verify(b2).setBlockData(orig2, false);
        verify(b3).setBlockData(orig3, false);
    }

    @Test
    void exportAndRecoverPendingRollbacks_restoresSerializedBlocksAcrossChunks() {
        BlockData airData = mockAirData();
        TemporaryBlockTracker serializerTracker = new TemporaryBlockTracker(
                data -> "minecraft:air",
                str -> airData);

        UUID alice = UUID.randomUUID();
        serializerTracker.tryRecordPlayerPlacement(loc(1, 64, 1), alice, airData, 10, 10);
        serializerTracker.tryRecordPlayerPlacement(loc(20, 65, 20), alice, airData, 10, 10);

        List<PendingBlockRollback> exported = serializerTracker.exportPendingRollbacks();
        assertEquals(2, exported.size());
        // Export does not clear tracker
        assertEquals(2, serializerTracker.totalCount());

        Block b1 = mock(Block.class);
        Block b2 = mock(Block.class);
        when(world.getBlockAt(1, 64, 1)).thenReturn(b1);
        when(world.getBlockAt(20, 65, 20)).thenReturn(b2);

        RaidScheduler scheduler = mock(RaidScheduler.class);
        when(scheduler.runAtRegion(eq(world), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return TaskHandle.retired();
        });

        List<BlockPosKey> restored = serializerTracker.rollbackPendingEntries(
                scheduler,
                id -> worldId.equals(id) ? world : null,
                exported).join();

        assertEquals(2, restored.size());
        verify(b1).setBlockData(airData, false);
        verify(b2).setBlockData(airData, false);
    }

    private Location loc(int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    private static BlockData mockAirData() {
        BlockData data = mock(BlockData.class);
        lenient().when(data.clone()).thenReturn(data);
        return data;
    }
}
