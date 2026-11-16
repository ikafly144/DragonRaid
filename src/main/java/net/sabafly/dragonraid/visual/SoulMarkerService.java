package net.sabafly.dragonraid.visual;

import java.util.Collection;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Manages client-side soul markers and revive progress displays for {@code DOWNED} players.
 */
public interface SoulMarkerService {

    /**
     * Spawns or updates a soul marker at {@code location} for eligible viewers.
     *
     * @param downedPlayerId UUID of the downed participant
     * @param downedPlayerName display name of the downed participant
     * @param location world location of the soul marker
     * @param remainingSeconds remaining seconds before spectator expiration, or {@code null} if unlimited
     * @param progressRatio revive completion progress in {@code [0.0, 1.0]}
     * @param viewers candidate viewers (typically online players in the raid world)
     */
    void spawnOrUpdateMarker(
            UUID downedPlayerId,
            String downedPlayerName,
            Location location,
            @Nullable Integer remainingSeconds,
            double progressRatio,
            Collection<? extends Player> viewers);

    /**
     * Removes the soul marker for {@code downedPlayerId} from all active viewers.
     */
    void removeMarker(UUID downedPlayerId, Collection<? extends Player> viewers);

    /**
     * Removes all currently tracked soul markers from all active viewers.
     */
    void removeAllMarkers(Collection<? extends Player> viewers);

    /**
     * Invalidates any cached spawn state for {@code viewerId} (e.g. on player reconnect) so the next
     * update sends a fresh marker spawn rather than metadata-only updates.
     */
    void invalidateViewer(UUID viewerId);
}
