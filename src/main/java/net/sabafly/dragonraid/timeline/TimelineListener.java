package net.sabafly.dragonraid.timeline;

import net.sabafly.dragonraid.config.BossConfig;
import org.jetbrains.annotations.Nullable;

/**
 * Callback interface receiving phase transitions, cast lifecycle events, and instant timeline actions
 * from {@link RaidTimelineEngine}.
 */
public interface TimelineListener {

    /**
     * Called when the encounter enters a phase (on start or HP threshold transition).
     */
    void onPhaseChanged(BossConfig.PhaseConfig newPhase);

    /**
     * Called when a timeline step with a positive cast duration begins casting.
     */
    void onCastStart(
            BossConfig.TimelineStep step,
            @Nullable BossConfig.AbilitySettings ability,
            double totalCastSeconds);

    /**
     * Called on each tick while an ability cast is progressing.
     */
    void onCastTick(
            BossConfig.TimelineStep step,
            @Nullable BossConfig.AbilitySettings ability,
            double remainingSeconds,
            double totalCastSeconds);

    /**
     * Called when an ability cast reaches completion without interruption.
     */
    void onCastComplete(
            BossConfig.TimelineStep step,
            @Nullable BossConfig.AbilitySettings ability);

    /**
     * Called when an in-progress cast is interrupted (e.g. stagger or phase transition).
     */
    void onCastInterrupted(
            BossConfig.TimelineStep step,
            @Nullable BossConfig.AbilitySettings ability);

    /**
     * Called when an instant timeline step (zero cast duration) executes.
     */
    void onInstantAction(
            BossConfig.TimelineStep step,
            @Nullable BossConfig.AbilitySettings ability);
}
