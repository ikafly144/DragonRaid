package net.sabafly.dragonraid.api.finder;

import java.util.Set;
import java.util.UUID;

/**
 * SPI tracking data-driven content unlock flags per player.
 */
public interface ContentUnlockService {

    boolean isUnlocked(UUID playerId, String contentId);

    boolean unlock(UUID playerId, String contentId);

    Set<String> unlockedContents(UUID playerId);
}
