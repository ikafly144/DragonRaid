package net.sabafly.dragonraid.api.script;

import java.util.List;
import java.util.Objects;

/**
 * Script/blueprint specification of one encounter phase, supporting both HP-bounded
 * sequenced timelines and periodic mechanic rotations.
 */
public record EncounterPhaseSpec(
        String id,
        String displayNameKey,
        double hpThresholdMax,
        double hpThresholdMin,
        double timelineSpeed,
        boolean softEnrage,
        double periodicIntervalSeconds,
        List<String> mechanicRotation,
        List<String> onEnterActions,
        List<TimelineEntrySpec> timeline
) {

    public EncounterPhaseSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(displayNameKey, "displayNameKey");
        mechanicRotation = mechanicRotation == null ? List.of() : List.copyOf(mechanicRotation);
        onEnterActions = onEnterActions == null ? List.of() : List.copyOf(onEnterActions);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    public EncounterPhaseSpec(
            String id,
            String displayNameKey,
            double hpThreshold,
            double periodicIntervalSeconds,
            List<String> mechanicRotation,
            List<String> onEnterActions) {
        this(id, displayNameKey, hpThreshold, 0.0, 1.0, false, periodicIntervalSeconds, mechanicRotation, onEnterActions, List.of());
    }

    public double hpThreshold() {
        return hpThresholdMax;
    }
}
