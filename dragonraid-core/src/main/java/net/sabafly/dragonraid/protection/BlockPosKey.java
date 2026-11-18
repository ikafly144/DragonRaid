package net.sabafly.dragonraid.protection;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;

/**
 * Immutable block coordinate key scoped to a world UUID.
 */
public record BlockPosKey(UUID worldId, int x, int y, int z) {

    public BlockPosKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    public static BlockPosKey of(Location location) {
        Objects.requireNonNull(location, "location");
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Location world must not be null");
        }
        return new BlockPosKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static BlockPosKey of(Block block) {
        Objects.requireNonNull(block, "block");
        return new BlockPosKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public long chunkKey() {
        return ((long) chunkX() << 32) | (chunkZ() & 0xFFFFFFFFL);
    }
}
