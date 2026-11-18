package net.sabafly.dragonraid.model;

import java.util.Objects;
import net.sabafly.dragonraid.api.enemy.BoneOffset;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/**
 * Shared local-to-world coordinate helper for bone offsets across vanilla and BetterModel actors.
 */
public final class BoneMath {

    private BoneMath() {
    }

    /**
     * Resolves a {@link BoneOffset} relative to {@code origin} using horizontal yaw and {@code scale}.
     */
    public static @Nullable Location resolveWorldOffset(
            @Nullable Location origin,
            BoneOffset offset,
            double scale) {
        if (origin == null) {
            return null;
        }
        Objects.requireNonNull(offset, "offset");
        double yawRad = Math.toRadians(origin.getYaw());
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        double dx = (offset.forward() * forwardX + offset.right() * rightX) * scale;
        double dy = offset.up() * scale;
        double dz = (offset.forward() * forwardZ + offset.right() * rightZ) * scale;
        return origin.clone().add(dx, dy, dz);
    }
}
