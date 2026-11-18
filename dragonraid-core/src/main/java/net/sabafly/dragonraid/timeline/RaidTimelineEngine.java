package net.sabafly.dragonraid.timeline;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.sabafly.dragonraid.config.BossConfig;
import org.jetbrains.annotations.Nullable;

/**
 * Deterministic timeline and phase-transition driver backed by {@link BossConfig}.
 */
public final class RaidTimelineEngine {

    private static final String LOOP_ACTION = "LOOP";
    private static final int MAX_STEPS_PER_TICK = 32;
    private static final double EPSILON = 1e-6;

    private final BossConfig bossConfig;
    private final TimelineListener listener;

    private boolean running;
    private int currentPhaseIndex = -1;
    private @Nullable BossConfig.PhaseConfig currentPhase;
    private int stepIndex;
    private double waitRemainingSeconds;
    private double staggerRemainingSeconds;

    private @Nullable ActiveCast activeCast;

    public RaidTimelineEngine(BossConfig bossConfig, TimelineListener listener) {
        this.bossConfig = Objects.requireNonNull(bossConfig, "bossConfig");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Starts the timeline engine in the opening phase (HP ratio 1.0).
     */
    public void start() {
        List<BossConfig.PhaseConfig> phases = bossConfig.phases();
        if (phases.isEmpty()) {
            throw new IllegalStateException("BossConfig defines no phases");
        }
        this.running = true;
        this.staggerRemainingSeconds = 0.0;
        this.activeCast = null;
        BossConfig.PhaseConfig initial = bossConfig.phaseForHpRatio(1.0);
        int idx = indexOfPhase(phases, initial);
        enterPhase(idx, initial);
    }

    /**
     * Advances phase transitions, stagger pauses, cast timers, and step delays by {@code deltaSeconds}.
     */
    public void tick(double deltaSeconds, double bossHpRatio) {
        if (!running || deltaSeconds <= 0.0) {
            return;
        }

        // Check forward phase transition based on current HP ratio.
        BossConfig.PhaseConfig targetPhase = bossConfig.phaseForHpRatio(bossHpRatio);
        List<BossConfig.PhaseConfig> phases = bossConfig.phases();
        int targetIndex = indexOfPhase(phases, targetPhase);
        if (targetIndex > currentPhaseIndex) {
            interruptActiveCastIfAny();
            this.staggerRemainingSeconds = 0.0;
            enterPhase(targetIndex, targetPhase);
            return;
        }

        if (staggerRemainingSeconds > EPSILON) {
            staggerRemainingSeconds = Math.max(0.0, staggerRemainingSeconds - deltaSeconds);
            if (staggerRemainingSeconds <= EPSILON) {
                staggerRemainingSeconds = 0.0;
            }
            return;
        }

        if (activeCast != null) {
            activeCast.remainingSeconds = Math.max(0.0, activeCast.remainingSeconds - deltaSeconds);
            if (activeCast.remainingSeconds <= EPSILON) {
                ActiveCast completed = activeCast;
                activeCast = null;
                listener.onCastComplete(completed.step, completed.ability);
                if (running) {
                    advanceToNextStep();
                }
            } else {
                listener.onCastTick(
                        activeCast.step,
                        activeCast.ability,
                        activeCast.remainingSeconds,
                        activeCast.totalSeconds
                );
            }
            return;
        }

        if (waitRemainingSeconds > EPSILON) {
            waitRemainingSeconds = Math.max(0.0, waitRemainingSeconds - deltaSeconds);
            if (waitRemainingSeconds > EPSILON) {
                return;
            }
            waitRemainingSeconds = 0.0;
        }

        executeDueSteps();
    }

    /**
     * Interrupts any active cast and pauses timeline progression for {@code seconds}.
     */
    public void stagger(double seconds) {
        if (!running || seconds <= 0.0) {
            return;
        }
        boolean wasCasting = activeCast != null;
        interruptActiveCastIfAny();
        if (wasCasting) {
            advanceToNextStep();
        }
        this.staggerRemainingSeconds = Math.max(this.staggerRemainingSeconds, seconds);
    }

    /**
     * Stops the timeline engine and clears any pending cast.
     */
    public void stop() {
        this.running = false;
        this.activeCast = null;
        this.staggerRemainingSeconds = 0.0;
        this.waitRemainingSeconds = 0.0;
    }

    public boolean isRunning() {
        return running;
    }

    public @Nullable BossConfig.PhaseConfig currentPhase() {
        return currentPhase;
    }

    public boolean isCasting() {
        return activeCast != null;
    }

    public boolean isStaggered() {
        return staggerRemainingSeconds > 0.0;
    }

    public @Nullable BossConfig.AbilitySettings activeCastAbility() {
        return activeCast != null ? activeCast.ability : null;
    }

    public @Nullable BossConfig.TimelineStep activeCastStep() {
        return activeCast != null ? activeCast.step : null;
    }

    public double castRemainingSeconds() {
        return activeCast != null ? activeCast.remainingSeconds : 0.0;
    }

    public double castTotalSeconds() {
        return activeCast != null ? activeCast.totalSeconds : 0.0;
    }

    private void enterPhase(int index, BossConfig.PhaseConfig phase) {
        this.currentPhaseIndex = index;
        this.currentPhase = phase;
        this.stepIndex = 0;
        List<BossConfig.TimelineStep> steps = phase.timeline();
        this.waitRemainingSeconds = steps.isEmpty()
                ? 0.0
                : scaledDelay(steps.getFirst().delaySeconds(), phase.timelineSpeed());
        listener.onPhaseChanged(phase);
        for (String actionId : phase.onEnterActions()) {
            if (!running) {
                break;
            }
            if (actionId == null || actionId.isBlank()) {
                continue;
            }
            BossConfig.AbilitySettings ability = bossConfig.ability(actionId.toLowerCase(Locale.ROOT)).orElse(null);
            listener.onInstantAction(
                    new BossConfig.TimelineStep(0.0, actionId, ability != null ? ability.id() : null),
                    ability
            );
        }
    }

    private void executeDueSteps() {
        BossConfig.PhaseConfig phase = this.currentPhase;
        if (phase == null || phase.timeline().isEmpty()) {
            return;
        }
        List<BossConfig.TimelineStep> steps = phase.timeline();
        double speed = phase.timelineSpeed();

        for (int guard = 0; guard < MAX_STEPS_PER_TICK && running; guard++) {
            if (stepIndex >= steps.size()) {
                stepIndex = 0;
            }
            BossConfig.TimelineStep step = steps.get(stepIndex);
            if (LOOP_ACTION.equalsIgnoreCase(step.action())) {
                stepIndex = 0;
                double firstDelay = scaledDelay(steps.getFirst().delaySeconds(), speed);
                waitRemainingSeconds = firstDelay;
                if (firstDelay > EPSILON) {
                    return;
                }
                waitRemainingSeconds = 0.0;
                continue;
            }

            BossConfig.AbilitySettings ability = step.ability() != null
                    ? bossConfig.ability(step.ability()).orElse(null)
                    : null;
            double castSeconds = ability != null ? ability.castSeconds(0.0) : 0.0;
            if (castSeconds > EPSILON) {
                this.activeCast = new ActiveCast(step, ability, castSeconds, castSeconds);
                listener.onCastStart(step, ability, castSeconds);
                return;
            }

            listener.onInstantAction(step, ability);
            if (!running) {
                return;
            }
            advanceToNextStep();
            if (waitRemainingSeconds > EPSILON) {
                return;
            }
            waitRemainingSeconds = 0.0;
        }
    }

    private void advanceToNextStep() {
        BossConfig.PhaseConfig phase = this.currentPhase;
        if (phase == null || phase.timeline().isEmpty()) {
            return;
        }
        List<BossConfig.TimelineStep> steps = phase.timeline();
        stepIndex++;
        if (stepIndex >= steps.size()) {
            stepIndex = 0;
        }
        BossConfig.TimelineStep next = steps.get(stepIndex);
        this.waitRemainingSeconds = scaledDelay(next.delaySeconds(), phase.timelineSpeed());
    }

    private void interruptActiveCastIfAny() {
        ActiveCast cancelled = this.activeCast;
        if (cancelled != null) {
            this.activeCast = null;
            listener.onCastInterrupted(cancelled.step, cancelled.ability);
        }
    }

    private static double scaledDelay(double rawDelaySeconds, double timelineSpeed) {
        double speed = timelineSpeed > 0.0 ? timelineSpeed : 1.0;
        return Math.max(0.0, rawDelaySeconds / speed);
    }

    private static int indexOfPhase(List<BossConfig.PhaseConfig> phases, BossConfig.PhaseConfig target) {
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i).id().equals(target.id())) {
                return i;
            }
        }
        return 0;
    }

    private static final class ActiveCast {
        final BossConfig.TimelineStep step;
        final @Nullable BossConfig.AbilitySettings ability;
        final double totalSeconds;
        double remainingSeconds;

        ActiveCast(
                BossConfig.TimelineStep step,
                @Nullable BossConfig.AbilitySettings ability,
                double totalSeconds,
                double remainingSeconds) {
            this.step = step;
            this.ability = ability;
            this.totalSeconds = totalSeconds;
            this.remainingSeconds = remainingSeconds;
        }
    }
}
