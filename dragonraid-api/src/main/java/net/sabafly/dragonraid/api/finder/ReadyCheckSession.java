package net.sabafly.dragonraid.api.finder;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of an FF14-style Content Finder ready-check ("Shakiin!") session.
 */
public record ReadyCheckSession(
        UUID sessionId,
        String contentId,
        @Nullable UUID partyId,
        UUID initiatorId,
        List<UUID> participantIds,
        Map<UUID, ReadyCheckResponse> responses,
        ReadyCheckStatus status,
        long createdAtMillis,
        long expiresAtMillis
) {

    public ReadyCheckSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(initiatorId, "initiatorId");
        participantIds = List.copyOf(Objects.requireNonNull(participantIds, "participantIds"));
        responses = Map.copyOf(Objects.requireNonNull(responses, "responses"));
        Objects.requireNonNull(status, "status");
    }

    public int acceptedCount() {
        int count = 0;
        for (ReadyCheckResponse response : responses.values()) {
            if (response == ReadyCheckResponse.ACCEPTED) {
                count++;
            }
        }
        return count;
    }

    public long remainingMillis(long nowMillis) {
        return Math.max(0L, expiresAtMillis - nowMillis);
    }
}
