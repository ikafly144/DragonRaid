package net.sabafly.dragonraid.api.progression;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Portal gate specification evaluated when a player attempts to enter the vanilla End dimension.
 */
public record EndEntryGateSpec(
        EndEntryGateMode mode,
        String targetId,
        String denialMessageKey,
        boolean allowBypassPermission,
        boolean pushBackOnDeny
) {

    public EndEntryGateSpec {
        Objects.requireNonNull(mode, "mode");
        targetId = normalize(targetId);
        denialMessageKey = normalize(denialMessageKey);
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
