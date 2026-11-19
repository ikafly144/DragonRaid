package net.sabafly.dragonraid.api.event;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable summary of a completed DragonRaid encounter consumed by reward and achievement modules.
 */
public record EncounterResultSnapshot(
        String encounterId,
        EncounterDifficulty difficulty,
        EncounterOutcome outcome,
        long durationMillis,
        boolean firstClear,
        int partySize,
        Map<UUID, ParticipantCombatMetrics> participantMetrics,
        Set<UUID> firstClearPlayerIds
) {

    public EncounterResultSnapshot {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(outcome, "outcome");
        participantMetrics = participantMetrics == null ? Map.of() : Map.copyOf(participantMetrics);
        firstClearPlayerIds = firstClearPlayerIds == null ? Set.of() : Set.copyOf(firstClearPlayerIds);
    }

    public EncounterResultSnapshot(
            String encounterId,
            EncounterDifficulty difficulty,
            EncounterOutcome outcome,
            long durationMillis,
            boolean firstClear,
            int partySize,
            Map<UUID, ParticipantCombatMetrics> participantMetrics) {
        this(
                encounterId,
                difficulty,
                outcome,
                durationMillis,
                firstClear,
                partySize,
                participantMetrics,
                firstClear && participantMetrics != null ? Set.copyOf(participantMetrics.keySet()) : Set.of()
        );
    }

    /**
     * Returns {@code true} if this encounter victory was a verified first clear specifically for {@code playerId}.
     */
    public boolean isFirstClearFor(@Nullable UUID playerId) {
        return playerId != null && firstClearPlayerIds.contains(playerId);
    }
}
