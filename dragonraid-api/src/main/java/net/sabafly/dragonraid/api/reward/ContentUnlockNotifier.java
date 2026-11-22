package net.sabafly.dragonraid.api.reward;

import java.util.UUID;

/**
 * Presentation SPI delivering "Content Unlocked!" Title/Subtitle/Sound/Toast feedback.
 */
@FunctionalInterface
public interface ContentUnlockNotifier {

    void notifyContentUnlocked(UUID playerId, ContentUnlockNotification notification);
}
