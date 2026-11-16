package net.sabafly.dragonraid.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidTimelineEngineTest {

    @TempDir
    Path tempDir;

    private BossConfig normalBoss;
    private List<String> events;
    private RaidTimelineEngine engine;

    @BeforeEach
    void setUp() {
        ConfigManager configManager = new ConfigManager(
                tempDir,
                name -> RaidTimelineEngineTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        normalBoss = configManager.normalBossConfig();
        events = new ArrayList<>();

        engine = new RaidTimelineEngine(normalBoss, new TimelineListener() {
            @Override
            public void onPhaseChanged(BossConfig.PhaseConfig newPhase) {
                events.add("PHASE:" + newPhase.id());
            }

            @Override
            public void onCastStart(
                    BossConfig.TimelineStep step,
                    @Nullable BossConfig.AbilitySettings ability,
                    double totalCastSeconds) {
                events.add("CAST_START:" + step.action() + ":" + totalCastSeconds);
            }

            @Override
            public void onCastTick(
                    BossConfig.TimelineStep step,
                    @Nullable BossConfig.AbilitySettings ability,
                    double remainingSeconds,
                    double totalCastSeconds) {
                // Keep log concise; verified via engine state getters.
            }

            @Override
            public void onCastComplete(
                    BossConfig.TimelineStep step,
                    @Nullable BossConfig.AbilitySettings ability) {
                events.add("CAST_COMPLETE:" + step.action());
            }

            @Override
            public void onCastInterrupted(
                    BossConfig.TimelineStep step,
                    @Nullable BossConfig.AbilitySettings ability) {
                events.add("CAST_INTERRUPTED:" + step.action());
            }

            @Override
            public void onInstantAction(
                    BossConfig.TimelineStep step,
                    @Nullable BossConfig.AbilitySettings ability) {
                events.add("INSTANT:" + step.action());
            }
        });
    }

    @Test
    void normalPhase1ExecutesInstantCrystalsCastsAndLoops() {
        engine.start();
        assertTrue(engine.isRunning());
        assertNotNull(engine.currentPhase());
        assertEquals("phase-1", engine.currentPhase().id());
        assertEquals(List.of("PHASE:phase-1"), events);

        // 4.0s delay -> SPAWN_CRYSTALS (instant)
        tickSeconds(4.0, 1.0);
        assertEquals("INSTANT:SPAWN_CRYSTALS", events.getLast());

        // 7.0s delay -> FIREBALL_BARRAGE cast start (3.6s cast)
        tickSeconds(7.0, 0.90);
        assertTrue(engine.isCasting());
        assertEquals("CAST_START:FIREBALL_BARRAGE:3.6", events.getLast());
        tickSeconds(1.8, 0.90);
        assertTrue(engine.isCasting());
        assertEquals(1.8, engine.castRemainingSeconds(), 1e-6);

        // Remaining 1.8s completes FIREBALL_BARRAGE
        tickSeconds(1.8, 0.85);
        assertFalse(engine.isCasting());
        assertEquals("CAST_COMPLETE:FIREBALL_BARRAGE", events.getLast());

        // 8.0s -> DRAGON_DIVE_STACK (4.0s), then 8.0s -> LIGHTNING_STORM (3.2s),
        // then 8.0s -> ENDERMAN_ERUPTION (3.5s), then 10.0s LOOP + 4.0s -> SPAWN_CRYSTALS
        tickSeconds(8.0, 0.80);
        assertEquals("CAST_START:DRAGON_DIVE_STACK:4.0", events.getLast());
        tickSeconds(4.0, 0.80);
        assertEquals("CAST_COMPLETE:DRAGON_DIVE_STACK", events.getLast());

        tickSeconds(8.0, 0.75);
        assertEquals("CAST_START:LIGHTNING_STORM:3.2", events.getLast());
        tickSeconds(3.2, 0.75);
        assertEquals("CAST_COMPLETE:LIGHTNING_STORM", events.getLast());

        tickSeconds(8.0, 0.75);
        assertEquals("CAST_START:ENDERMAN_ERUPTION:3.5", events.getLast());
        tickSeconds(3.5, 0.75);
        assertEquals("CAST_COMPLETE:ENDERMAN_ERUPTION", events.getLast());

        // LOOP waits 10.0s + step-0 4.0s = 14.0s
        tickSeconds(10.0, 0.75);
        assertEquals("CAST_COMPLETE:ENDERMAN_ERUPTION", events.getLast());
        tickSeconds(4.0, 0.75);
        assertEquals("INSTANT:SPAWN_CRYSTALS", events.getLast());
    }

    @Test
    void hpDropTriggersImmediatePhaseTransitionAndInterruptsCast() {
        engine.start();
        tickSeconds(4.0, 1.0); // SPAWN_CRYSTALS
        tickSeconds(7.0, 0.80); // FIREBALL_BARRAGE starts casting
        assertTrue(engine.isCasting());

        // HP drops below 0.70 into phase-2
        engine.tick(0.1, 0.68);
        assertFalse(engine.isCasting());
        assertEquals("phase-2", engine.currentPhase().id());
        assertTrue(events.contains("CAST_INTERRUPTED:FIREBALL_BARRAGE"));
        assertEquals("PHASE:phase-2", events.getLast());

        // Minor regen back above 0.70 does NOT regress phase-2 -> phase-1
        engine.tick(0.1, 0.72);
        assertEquals("phase-2", engine.currentPhase().id());

        // Dropping into final (0.35) speeds timeline by 1.15x
        engine.tick(0.1, 0.35);
        assertEquals("final", engine.currentPhase().id());
        assertTrue(engine.currentPhase().softEnrage());
        // Final step-0 has delay 4.0 / 1.15 ≈ 3.478s
        tickSeconds(3.5, 0.35);
        assertEquals("INSTANT:SPAWN_CRYSTALS", events.getLast());
    }

    @Test
    void staggerPausesTimelineAndInterruptsActiveCast() {
        engine.start();
        tickSeconds(4.0, 1.0);
        tickSeconds(7.0, 0.9);
        assertTrue(engine.isCasting());

        engine.stagger(3.0);
        assertFalse(engine.isCasting());
        assertTrue(engine.isStaggered());
        assertEquals("CAST_INTERRUPTED:FIREBALL_BARRAGE", events.getLast());

        // While staggered, timeline does not advance toward DRAGON_DIVE_STACK
        tickSeconds(2.9, 0.9);
        assertTrue(engine.isStaggered());
        assertFalse(engine.isCasting());

        // Stagger finishes on next 0.1s tick, then 8.0s wait for DRAGON_DIVE_STACK begins
        engine.tick(0.1, 0.9);
        assertFalse(engine.isStaggered());
        tickSeconds(8.0, 0.9);
        assertEquals("CAST_START:DRAGON_DIVE_STACK:4.0", events.getLast());
    }

    private void tickSeconds(double totalSeconds, double hpRatio) {
        int steps = (int) Math.round(totalSeconds / 0.1);
        for (int i = 0; i < steps; i++) {
            engine.tick(0.1, hpRatio);
        }
    }
}
