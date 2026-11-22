package net.sabafly.dragonraid.api.finder;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Listener notified of Content Finder ready-check transitions and completion.
 */
public interface ReadyCheckListener {

    default void onSessionStarted(ReadyCheckSession session) {
    }

    default void onResponseUpdated(ReadyCheckSession session, UUID responderId, ReadyCheckResponse response) {
    }

    default void onSessionCommenced(ReadyCheckSession session) {
    }

    default void onSessionEnded(ReadyCheckSession session, @Nullable UUID actorId, @Nullable String reason) {
    }
}
