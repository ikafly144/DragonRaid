package net.sabafly.dragonraid.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.EnrageRuleSpec;
import net.sabafly.dragonraid.api.script.GimmickAction;
import net.sabafly.dragonraid.api.script.RaidScriptContext;
import net.sabafly.dragonraid.api.script.ReviveRuleSpec;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.script.DefaultGimmickActionRegistry;
import net.sabafly.dragonraid.script.dsl.EncounterBlueprintBuilder;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class RaidTimelineEngineTest {

    @TempDir
    Path tempDir;

    private BossConfig sampleBoss;
    private List<String> events;
    private RaidTimelineEngine engine;

    @BeforeEach
    void setUp() {
        ConfigManager configManager = new ConfigManager(
                tempDir,
                name -> RaidTimelineEngineTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        sampleBoss = BossConfig.fromBlueprint(new EncounterBlueprintBuilder("sample_timeline")
                .difficulty(EncounterDifficulty.NORMAL)
                .displayNameKey("boss.normal.name")
                .primaryBoss("boss")
                .revive(ReviveRuleSpec.normalDefault())
                .enrage(EnrageRuleSpec.normalDefault())
                .enemy("boss", e -> e.displayNameKey("boss.normal.name").stats(1200.0, 0.65, false, true, true))
                .mechanic("crystals", Map.of("name-key", "ability.crystals.name"))
                .mechanic("fireball_barrage", Map.of("name-key", "ability.fireball_barrage.name", "cast-seconds", 2.0))
                .mechanic("dragon_dive_stack", Map.of("name-key", "ability.dragon_dive_stack.name", "cast-seconds", 4.0))
                .mechanic("lightning_storm", Map.of("name-key", "ability.lightning_storm.name", "cast-seconds", 3.2))
                .mechanic("enderman_eruption", Map.of("name-key", "ability.enderman_eruption.name", "cast-seconds", 3.5))
                .phase("phase-1", p -> p
                        .displayNameKey("phase.p1")
                        .hpRange(1.0, 0.70)
                        .timelineSpeed(1.0)
                        .step(4.0, "SPAWN_CRYSTALS", "crystals")
                        .step(7.0, "FIREBALL_BARRAGE", "fireball_barrage")
                        .step(8.0, "DRAGON_DIVE_STACK", "dragon_dive_stack")
                        .step(8.0, "LIGHTNING_STORM", "lightning_storm")
                        .step(8.0, "ENDERMAN_ERUPTION", "enderman_eruption")
                        .loop(10.0))
                .phase("phase-2", p -> p
                        .displayNameKey("phase.p2")
                        .hpRange(0.70, 0.35)
                        .timelineSpeed(1.0)
                        .step(4.0, "SPAWN_CRYSTALS", "crystals")
                        .loop(10.0))
                .phase("final", p -> p
                        .displayNameKey("phase.final")
                        .hpRange(0.35, 0.0)
                        .timelineSpeed(1.15)
                        .softEnrage(true)
                        .step(4.0, "SPAWN_CRYSTALS", "crystals")
                        .loop(8.0))
                .build());
        events = new ArrayList<>();

        engine = new RaidTimelineEngine(sampleBoss, new TimelineListener() {
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
    void phase1ExecutesInstantCastsAndLoops() {
        engine.start();
        assertEquals(List.of("PHASE:phase-1"), events);

        tickSeconds(3.9, 1.0);
        assertEquals(1, events.size());

        engine.tick(0.1, 1.0);
        assertEquals(List.of("PHASE:phase-1", "INSTANT:SPAWN_CRYSTALS"), events);

        tickSeconds(7.0, 0.85);
        assertTrue(engine.isCasting());
        assertEquals("CAST_START:FIREBALL_BARRAGE:2.0", events.getLast());

        tickSeconds(2.0, 0.85);
        assertFalse(engine.isCasting());
        assertEquals("CAST_COMPLETE:FIREBALL_BARRAGE", events.getLast());

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

        tickSeconds(10.0, 0.75);
        assertEquals("CAST_COMPLETE:ENDERMAN_ERUPTION", events.getLast());
        tickSeconds(4.0, 0.75);
        assertEquals("INSTANT:SPAWN_CRYSTALS", events.getLast());
    }

    @Test
    void hpDropTriggersImmediatePhaseTransitionAndInterruptsCast() {
        engine.start();
        tickSeconds(4.0, 1.0);
        tickSeconds(7.0, 0.80);
        assertTrue(engine.isCasting());

        engine.tick(0.1, 0.68);
        assertFalse(engine.isCasting());
        assertEquals("phase-2", engine.currentPhase().id());
        assertTrue(events.contains("CAST_INTERRUPTED:FIREBALL_BARRAGE"));
        assertEquals("PHASE:phase-2", events.getLast());

        engine.tick(0.1, 0.72);
        assertEquals("phase-2", engine.currentPhase().id());

        engine.tick(0.1, 0.35);
        assertEquals("final", engine.currentPhase().id());
        assertTrue(engine.currentPhase().softEnrage());
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

        tickSeconds(2.9, 0.9);
        assertTrue(engine.isStaggered());
        assertFalse(engine.isCasting());

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

    @Test
    void phaseOnEnterActionsFireOnPhaseEntryViaGimmickRegistry() {
        BossConfig customBoss = new BossConfig(
                sampleBoss.difficulty(),
                sampleBoss.displayNameKey(),
                sampleBoss.baseHp(),
                sampleBoss.baseAttackDamage(),
                sampleBoss.revive(),
                sampleBoss.enrage(),
                sampleBoss.abilities(),
                List.of(new BossConfig.PhaseConfig(
                        "phase-enter",
                        "boss.phase1",
                        1.0,
                        0.0,
                        1.0,
                        false,
                        List.of(),
                        List.of("CUSTOM_ENTER_SPAWN", "CUSTOM_ENTER_BUFF")
                ))
        );

        DefaultGimmickActionRegistry actionRegistry = new DefaultGimmickActionRegistry();
        AtomicInteger spawnExecutions = new AtomicInteger();
        AtomicInteger buffExecutions = new AtomicInteger();
        actionRegistry.register(new GimmickAction() {
            @Override
            public String id() {
                return "CUSTOM_ENTER_SPAWN";
            }

            @Override
            public void execute(RaidScriptContext context, Map<String, Object> params) {
                spawnExecutions.incrementAndGet();
            }
        });
        actionRegistry.register(new GimmickAction() {
            @Override
            public String id() {
                return "CUSTOM_ENTER_BUFF";
            }

            @Override
            public void execute(RaidScriptContext context, Map<String, Object> params) {
                buffExecutions.incrementAndGet();
            }
        });

        List<String> enterEvents = new ArrayList<>();
        RaidTimelineEngine enterEngine = new RaidTimelineEngine(customBoss, new TimelineListener() {
            @Override
            public void onPhaseChanged(BossConfig.PhaseConfig newPhase) {
                enterEvents.add("PHASE:" + newPhase.id());
            }

            @Override
            public void onCastStart(BossConfig.TimelineStep step, @Nullable BossConfig.AbilitySettings ability, double totalCastSeconds) {
            }

            @Override
            public void onCastTick(BossConfig.TimelineStep step, @Nullable BossConfig.AbilitySettings ability, double remainingSeconds, double totalCastSeconds) {
            }

            @Override
            public void onCastComplete(BossConfig.TimelineStep step, @Nullable BossConfig.AbilitySettings ability) {
            }

            @Override
            public void onCastInterrupted(BossConfig.TimelineStep step, @Nullable BossConfig.AbilitySettings ability) {
            }

            @Override
            public void onInstantAction(BossConfig.TimelineStep step, @Nullable BossConfig.AbilitySettings ability) {
                enterEvents.add("INSTANT:" + step.action());
                actionRegistry.get(step.action())
                        .ifPresent(action -> action.execute(Mockito.mock(RaidScriptContext.class), Map.of()));
            }
        });

        enterEngine.start();
        assertEquals(
                List.of("PHASE:phase-enter", "INSTANT:CUSTOM_ENTER_SPAWN", "INSTANT:CUSTOM_ENTER_BUFF"),
                enterEvents
        );
        assertEquals(1, spawnExecutions.get());
        assertEquals(1, buffExecutions.get());
    }
}
