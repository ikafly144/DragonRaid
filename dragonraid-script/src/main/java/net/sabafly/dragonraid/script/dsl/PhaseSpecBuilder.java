package net.sabafly.dragonraid.script.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.sabafly.dragonraid.api.script.EncounterPhaseSpec;
import net.sabafly.dragonraid.api.script.TimelineEntrySpec;

/**
 * Fluent DSL builder for {@link EncounterPhaseSpec} timelines and rotations.
 */
public final class PhaseSpecBuilder {

    private final String id;
    private String displayNameKey = "";
    private double hpThresholdMax = 1.0;
    private double hpThresholdMin = 0.0;
    private double timelineSpeed = 1.0;
    private boolean softEnrage = false;
    private double periodicIntervalSeconds = 8.0;
    private final List<String> rotation = new ArrayList<>();
    private final List<String> onEnterActions = new ArrayList<>();
    private final List<TimelineEntrySpec> timeline = new ArrayList<>();

    public PhaseSpecBuilder(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public PhaseSpecBuilder displayNameKey(String key) {
        this.displayNameKey = Objects.requireNonNull(key, "key");
        return this;
    }

    public PhaseSpecBuilder hpRange(double maxRatio, double minRatio) {
        this.hpThresholdMax = maxRatio;
        this.hpThresholdMin = minRatio;
        return this;
    }

    public PhaseSpecBuilder timelineSpeed(double speed) {
        this.timelineSpeed = speed;
        return this;
    }

    public PhaseSpecBuilder softEnrage(boolean enabled) {
        this.softEnrage = enabled;
        return this;
    }

    public PhaseSpecBuilder periodicInterval(double seconds) {
        this.periodicIntervalSeconds = seconds;
        return this;
    }

    public PhaseSpecBuilder onEnter(String actionId) {
        this.onEnterActions.add(Objects.requireNonNull(actionId, "actionId"));
        return this;
    }

    public PhaseSpecBuilder step(double delaySeconds, String actionId, String abilityId) {
        this.timeline.add(new TimelineEntrySpec(delaySeconds, actionId, abilityId));
        if (!"LOOP".equals(actionId) && !actionId.startsWith("SPAWN_") && !this.rotation.contains(actionId)) {
            this.rotation.add(actionId);
        }
        return this;
    }

    public PhaseSpecBuilder loop(double delaySeconds) {
        this.timeline.add(new TimelineEntrySpec(delaySeconds, "LOOP", ""));
        return this;
    }

    public EncounterPhaseSpec build() {
        return new EncounterPhaseSpec(
                id,
                displayNameKey,
                hpThresholdMax,
                hpThresholdMin,
                timelineSpeed,
                softEnrage,
                periodicIntervalSeconds,
                rotation,
                onEnterActions,
                timeline
        );
    }
}
