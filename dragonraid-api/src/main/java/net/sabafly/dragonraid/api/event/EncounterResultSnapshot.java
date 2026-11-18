package net.sabafly.dragonraid.api.event;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;

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
        Map<UUID, ParticipantCombatMetrics> participantMetrics
) {

    public EncounterResultSnapshot {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(outcome, "outcome");
        participantMetrics = participantMetrics == null ? Map.of() : Map.copyOf(participantMetrics);
    }
}
