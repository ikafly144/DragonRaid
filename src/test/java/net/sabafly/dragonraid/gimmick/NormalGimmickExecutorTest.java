package net.sabafly.dragonraid.gimmick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.boss.DragonPosture;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scaling.RaidScalingCalculator;
import net.sabafly.dragonraid.scaling.ScaledEncounterParameters;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.timeline.RaidTimelineEngine;
import net.sabafly.dragonraid.timeline.TimelineListener;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormalGimmickExecutorTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private BossConfig normalBoss;
    private RaidScheduler scheduler;
    private SpatialTelegraphService telegraphService;
    private RaidCrystalManager crystalManager;
    private World endWorld;
    private AtomicLong nowMillis;
    private Map<UUID, Double> damageTaken;
    private DragonBossController bossController;
    private RaidTimelineEngine timelineEngine;
    private Map<UUID, Boolean> occludedPlayers;
    private NormalGimmickExecutor executor;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> NormalGimmickExecutorTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        normalBoss = configManager.normalBossConfig();

        MessageService messageService = new MessageService(configManager);
        messageService.reload();

        endWorld = mock(World.class);
        lenient().when(endWorld.getMinHeight()).thenReturn(0);
        lenient().when(endWorld.getMaxHeight()).thenReturn(256);

        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        telegraphService = mock(SpatialTelegraphService.class);
        crystalManager = new RaidCrystalManager(
                configManager, scheduler, telegraphService, Logger.getLogger("GimmickTest"));

        nowMillis = new AtomicLong(10_000L);
        damageTaken = new HashMap<>();
        occludedPlayers = new HashMap<>();

        bossController = mock(DragonBossController.class);
        lenient().when(bossController.bossLocation()).thenReturn(new Location(endWorld, 0.0, 82.0, 0.0));
        lenient().when(bossController.isStaggered()).thenReturn(false);

        timelineEngine = new RaidTimelineEngine(normalBoss, new TimelineListener() {
            @Override public void onPhaseChanged(BossConfig.PhaseConfig newPhase) {}
            @Override public void onCastStart(BossConfig.TimelineStep s, BossConfig.AbilitySettings a, double t) {}
            @Override public void onCastTick(BossConfig.TimelineStep s, BossConfig.AbilitySettings a, double r, double t) {}
            @Override public void onCastComplete(BossConfig.TimelineStep s, BossConfig.AbilitySettings a) {}
            @Override public void onCastInterrupted(BossConfig.TimelineStep s, BossConfig.AbilitySettings a) {}
            @Override public void onInstantAction(BossConfig.TimelineStep s, BossConfig.AbilitySettings a) {}
        });
        timelineEngine.start();

        executor = new NormalGimmickExecutor(
                configManager,
                messageService,
                scheduler,
                telegraphService,
                crystalManager,
                nowMillis::get,
                (player, amount) -> damageTaken.merge(player.getUniqueId(), amount, Double::sum),
                (bossOrigin, playerEye) -> CompletableFuture.completedFuture(
                        occludedPlayers.values().stream().findFirst().orElse(false))
        );
    }

    private Player mockPlayer(String name, double x, double y, double z, boolean blocking, boolean hasShield) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.locale()).thenReturn(Locale.US);
        lenient().when(p.getWorld()).thenReturn(endWorld);
        lenient().when(p.getLocation()).thenReturn(new Location(endWorld, x, y, z));
        lenient().when(p.isBlocking()).thenReturn(blocking);

        EntityEquipment eq = mock(EntityEquipment.class);
        ItemStack shield = mock(ItemStack.class);
        lenient().when(shield.getType()).thenReturn(hasShield ? Material.SHIELD : Material.AIR);
        ItemStack air = mock(ItemStack.class);
        lenient().when(air.getType()).thenReturn(Material.AIR);
        lenient().when(eq.getItemInMainHand()).thenReturn(air);
        lenient().when(eq.getItemInOffHand()).thenReturn(shield);
        lenient().when(p.getEquipment()).thenReturn(eq);
        return p;
    }

    @Test
    void tankBuster_handlesUnblockedShieldGuardAndJustGuardStagger() {
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 2);
        BossConfig.AbilitySettings ability = normalBoss.ability("tank_buster").orElseThrow();
        BossConfig.TimelineStep step = new BossConfig.TimelineStep(8.0, "TANK_BUSTER", "tank_buster");

        // 1. Unblocked -> full 24.0
        Player tank = mockPlayer("Tank", 2.0, 64.0, 0.0, false, false);
        executor.startCast(step, ability, endWorld, List.of(tank), List.of(tank), scaled, bossController);
        verify(bossController).beginDive(any(Location.class), anyDouble());
        executor.completeCast(step, ability, List.of(tank), List.of(tank), scaled, bossController, timelineEngine);
        assertEquals(24.0, damageTaken.get(tank.getUniqueId()), 1e-6);

        // 2. Normal guard (raised > windowMs ago) -> 0.25 * 24 = 6.0
        damageTaken.clear();
        Player guarder = mockPlayer("Guarder", 2.0, 64.0, 0.0, false, true);
        executor.recordShieldPulse(guarder);
        lenient().when(guarder.isBlocking()).thenReturn(true);
        nowMillis.addAndGet(2000L);
        executor.startCast(step, ability, endWorld, List.of(guarder), List.of(guarder), scaled, bossController);
        executor.completeCast(step, ability, List.of(guarder), List.of(guarder), scaled, bossController, timelineEngine);
        assertEquals(6.0, damageTaken.get(guarder.getUniqueId()), 1e-6);

        // 3. Just Guard (raised within 500ms) -> 0.10 * 24 = 2.4 + stagger boss & timeline
        damageTaken.clear();
        Player parrier = mockPlayer("Parrier", 2.0, 64.0, 0.0, false, true);
        executor.startCast(step, ability, endWorld, List.of(parrier), List.of(parrier), scaled, bossController);
        nowMillis.addAndGet(200L);
        executor.recordShieldPulse(parrier);
        lenient().when(parrier.isBlocking()).thenReturn(true);
        nowMillis.addAndGet(250L);
        executor.completeCast(step, ability, List.of(parrier), List.of(parrier), scaled, bossController, timelineEngine);
        assertEquals(2.4, damageTaken.get(parrier.getUniqueId()), 1e-6);
        verify(bossController).stagger(3.0);
        assertTrue(timelineEngine.isStaggered());
    }

    @Test
    void stack_splitsAmongPartyAndAppliesSoloFallback() {
        BossConfig.AbilitySettings ability = normalBoss.ability("stack").orElseThrow();
        BossConfig.TimelineStep step = new BossConfig.TimelineStep(10.0, "STACK", "stack");

        // Party of 2 stacked within radius (32.0 / 2 = 16.0 each)
        ScaledEncounterParameters partyScaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 2);
        Player p1 = mockPlayer("P1", 0.0, 64.0, 0.0, false, false);
        Player p2 = mockPlayer("P2", 2.0, 64.0, 0.0, false, false);
        executor.startCast(step, ability, endWorld, List.of(p1, p2), List.of(p1, p2), partyScaled, bossController);
        executor.completeCast(step, ability, List.of(p1, p2), List.of(p1, p2), partyScaled, bossController, timelineEngine);
        assertEquals(16.0, damageTaken.get(p1.getUniqueId()), 1e-6);
        assertEquals(16.0, damageTaken.get(p2.getUniqueId()), 1e-6);

        // Solo fallback (32.0 * 0.35 = 11.2)
        damageTaken.clear();
        ScaledEncounterParameters soloScaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 1);
        Player solo = mockPlayer("Solo", 0.0, 64.0, 0.0, false, false);
        executor.startCast(step, ability, endWorld, List.of(solo), List.of(solo), soloScaled, bossController);
        executor.completeCast(step, ability, List.of(solo), List.of(solo), soloScaled, bossController, timelineEngine);
        assertEquals(32.0 * 0.35, damageTaken.get(solo.getUniqueId()), 1e-6);
    }

    @Test
    void spread_appliesOverlapPenaltyWhenAlliesCluster() {
        BossConfig.AbilitySettings ability = normalBoss.ability("spread").orElseThrow();
        BossConfig.TimelineStep step = new BossConfig.TimelineStep(10.0, "SPREAD", "spread");
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 2);

        // 2 players overlapping within 4.5m -> 14.0 * 2.0 = 28.0
        Player a = mockPlayer("A", 0.0, 64.0, 0.0, false, false);
        Player b = mockPlayer("B", 1.5, 64.0, 0.0, false, false);
        executor.startCast(step, ability, endWorld, List.of(a, b), List.of(a, b), scaled, bossController);
        executor.completeCast(step, ability, List.of(a, b), List.of(a, b), scaled, bossController, timelineEngine);
        assertEquals(28.0, damageTaken.get(a.getUniqueId()), 1e-6);

        // 2 players far apart -> base 14.0
        damageTaken.clear();
        Player c = mockPlayer("C", -20.0, 64.0, 0.0, false, false);
        Player d = mockPlayer("D", 20.0, 64.0, 0.0, false, false);
        executor.startCast(step, ability, endWorld, List.of(c, d), List.of(c, d), scaled, bossController);
        executor.completeCast(step, ability, List.of(c, d), List.of(c, d), scaled, bossController, timelineEngine);
        assertEquals(14.0, damageTaken.get(c.getUniqueId()), 1e-6);
    }

    @Test
    void meteorTowers_dealsSoakOrUnsoakedRaidPenalty() {
        BossConfig.AbilitySettings ability = normalBoss.ability("meteor_towers").orElseThrow();
        BossConfig.TimelineStep step = new BossConfig.TimelineStep(4.0, "METEOR_TOWERS", "meteor_towers");
        // For N=1, towerCount = 1 at angle 0 (x=18, z=0)
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 1);

        // Player inside tower (18, 64, 0) -> soak damage 8.0
        Player soaker = mockPlayer("Soaker", 18.0, 64.0, 0.0, false, false);
        executor.startCast(step, ability, endWorld, List.of(soaker), List.of(soaker), scaled, bossController);
        verify(bossController, atLeastOnce()).setPosture(DragonPosture.HIGH_HOVER);
        executor.completeCast(step, ability, List.of(soaker), List.of(soaker), scaled, bossController, timelineEngine);
        assertEquals(8.0, damageTaken.get(soaker.getUniqueId()), 1e-6);

        // Player outside tower (towerCount=1 at angle PI for wave 1 -> x=-18, z=0) -> unsoaked penalty 22.0
        damageTaken.clear();
        Player missed = mockPlayer("Missed", 0.0, 64.0, 50.0, false, false);
        executor.startCast(step, ability, endWorld, List.of(missed), List.of(missed), scaled, bossController);
        executor.completeCast(step, ability, List.of(missed), List.of(missed), scaled, bossController, timelineEngine);
        assertEquals(22.0, damageTaken.get(missed.getUniqueId()), 1e-6);
    }

    @Test
    void losBreath_damagesExposedAndSparesOccluded() {
        BossConfig.AbilitySettings ability = normalBoss.ability("los_breath").orElseThrow();
        BossConfig.TimelineStep step = new BossConfig.TimelineStep(10.0, "LOS_BREATH", "los_breath");
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 1);

        // Exposed
        Player exposed = mockPlayer("Exposed", 10.0, 64.0, 0.0, false, false);
        occludedPlayers.put(exposed.getUniqueId(), false);
        executor.startCast(step, ability, endWorld, List.of(exposed), List.of(exposed), scaled, bossController);
        executor.completeCast(step, ability, List.of(exposed), List.of(exposed), scaled, bossController, timelineEngine);
        assertEquals(26.0, damageTaken.get(exposed.getUniqueId()), 1e-6);

        // Occluded behind pillar
        damageTaken.clear();
        occludedPlayers.clear();
        Player hidden = mockPlayer("Hidden", 10.0, 64.0, 0.0, false, false);
        occludedPlayers.put(hidden.getUniqueId(), true);
        executor.startCast(step, ability, endWorld, List.of(hidden), List.of(hidden), scaled, bossController);
        executor.completeCast(step, ability, List.of(hidden), List.of(hidden), scaled, bossController, timelineEngine);
        assertFalse(damageTaken.containsKey(hidden.getUniqueId()));
    }

    @Test
    void softEnrage_shrinksSafeBoundaryAndDamagesOutsidePlayers() {
        BossConfig.PhaseConfig finalPhase = normalBoss.phases().getLast();
        assertTrue(finalPhase.softEnrage());
        Player outside = mockPlayer("Outside", 125.0, 64.0, 0.0, false, false);

        for (int i = 0; i < 10; i++) {
            executor.tickSoftEnrage(0.1, normalBoss, finalPhase, endWorld, List.of(outside), List.of(outside));
        }

        assertTrue(executor.isSoftEnrageStarted());
        assertEquals(120.0 - 0.25, executor.safeArenaRadius(), 1e-4);
        assertEquals(4.0, damageTaken.get(outside.getUniqueId()), 1e-6);
    }

    @Test
    void deferredDamageTaskIsSkippedAfterResetOrCancel() {
        List<Runnable> queuedEntityTasks = new java.util.ArrayList<>();
        RaidScheduler deferredScheduler = mock(RaidScheduler.class);
        lenient().when(deferredScheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(deferredScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            queuedEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });

        NormalGimmickExecutor deferredExecutor = new NormalGimmickExecutor(
                configManager,
                new MessageService(configManager),
                deferredScheduler,
                telegraphService,
                crystalManager,
                nowMillis::get,
                (player, amount) -> damageTaken.merge(player.getUniqueId(), amount, Double::sum),
                (b, e) -> CompletableFuture.completedFuture(false)
        );
        damageTaken.clear();

        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 1);
        BossConfig.AbilitySettings ability = normalBoss.ability("tank_buster").orElseThrow();
        BossConfig.TimelineStep step = new BossConfig.TimelineStep(8.0, "TANK_BUSTER", "tank_buster");
        Player target = mockPlayer("Target", 2.0, 64.0, 0.0, false, false);

        deferredExecutor.startCast(step, ability, endWorld, List.of(target), List.of(target), scaled, bossController);
        queuedEntityTasks.clear();
        deferredExecutor.completeCast(step, ability, List.of(target), List.of(target), scaled, bossController, timelineEngine);
        assertFalse(queuedEntityTasks.isEmpty());

        // Reset before the queued entity task runs -> damage must be suppressed
        deferredExecutor.resetState();
        queuedEntityTasks.forEach(Runnable::run);
        assertFalse(damageTaken.containsKey(target.getUniqueId()));
    }

    @Test
    void groundSlamDive_attenuatesDamageByDistanceAndSpawnsShockwave() {
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 2);
        BossConfig.AbilitySettings ability = normalBoss.ability("ground_slam_dive").orElseThrow();
        BossConfig.TimelineStep step = new BossConfig.TimelineStep(7.0, "GROUND_SLAM_DIVE", "ground_slam_dive");

        Player epicenter = mockPlayer("Epicenter", 0.0, 64.0, 0.0, false, false);
        Player far = mockPlayer("Far", 21.0, 64.0, 0.0, false, false);
        executor.startCast(step, ability, endWorld, List.of(epicenter, far), List.of(epicenter, far), scaled, bossController);
        executor.completeCast(step, ability, List.of(epicenter, far), List.of(epicenter, far), scaled, bossController, timelineEngine);

        // Epicenter takes full 36.0; far (21m / 28m falloff = 0.25 * 36 = 9.0) takes much less
        assertEquals(36.0, damageTaken.get(epicenter.getUniqueId()), 1e-6);
        assertEquals(9.0, damageTaken.get(far.getUniqueId()), 1e-6);

        // Subsequent telegraphs tick advances the expanding shockwave ring
        executor.tickActiveTelegraphs(List.of(epicenter, far), List.of(epicenter, far), bossController);
        verify(telegraphService, atLeastOnce()).renderExpandingShockwave(any(), anyDouble(), any());
    }

    @Test
    void shieldWallBreath_protectsAlliesInsideShieldBlockerRearCone() {
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), normalBoss, 3);
        BossConfig.AbilitySettings ability = normalBoss.ability("shield_wall_breath").orElseThrow();
        BossConfig.TimelineStep step = new BossConfig.TimelineStep(8.0, "SHIELD_WALL_BREATH", "shield_wall_breath");

        // Boss is at (0, 82, 0). Blocker is at (0, 64, 10) holding shield.
        // Protected ally is directly behind blocker at (0, 64, 18).
        // Exposed ally is off to the side at (15, 64, 0).
        Player blocker = mockPlayer("Blocker", 0.0, 64.0, 10.0, true, true);
        Player sheltered = mockPlayer("Sheltered", 0.0, 64.0, 18.0, false, false);
        Player exposed = mockPlayer("Exposed", 15.0, 64.0, 0.0, false, false);

        List<Player> party = List.of(blocker, sheltered, exposed);
        executor.startCast(step, ability, endWorld, party, party, scaled, bossController);
        executor.tickActiveTelegraphs(party, party, bossController);
        executor.completeCast(step, ability, party, party, scaled, bossController, timelineEngine);

        // Blocker takes 20% chip (34.0 * 0.20 = 6.8), sheltered takes 0, exposed takes full 34.0
        assertEquals(6.8, damageTaken.get(blocker.getUniqueId()), 1e-6);
        assertFalse(damageTaken.containsKey(sheltered.getUniqueId()));
        assertEquals(34.0, damageTaken.get(exposed.getUniqueId()), 1e-6);
    }

    @Test
    void hardCoopMechanics_rotatingEndermenDualDiveDiceAndTetherRelayExecuteCleanly() {
        BossConfig hardBoss = configManager.hardBossConfig();
        ScaledEncounterParameters scaled = RaidScalingCalculator.calculate(
                configManager.pluginConfig().scaling(), hardBoss, 4);

        Player p1 = mockPlayer("P1", 0.0, 64.0, 12.0, false, false);
        Player p2 = mockPlayer("P2", 0.0, 64.0, -12.0, false, false);
        Player p3 = mockPlayer("P3", 2.0, 64.0, 12.0, false, false);
        Player p4 = mockPlayer("P4", 2.0, 64.0, -12.0, false, false);
        List<Player> party = List.of(p1, p2, p3, p4);

        // 1. Dual Dive Dice (2-group odd/even simultaneous stack dive)
        BossConfig.AbilitySettings diceAbility = hardBoss.ability("dual_dive_dice").orElseThrow();
        BossConfig.TimelineStep diceStep = new BossConfig.TimelineStep(6.5, "DUAL_DIVE_DICE", "dual_dive_dice");
        executor.startCast(diceStep, diceAbility, endWorld, party, party, scaled, bossController);
        executor.completeCast(diceStep, diceAbility, party, party, scaled, bossController, timelineEngine);
        // P1+P3 share odd dive (44 / 2 = 22), P2+P4 share even dive (44 / 2 = 22)
        assertEquals(22.0, damageTaken.get(p1.getUniqueId()), 1e-6);
        assertEquals(22.0, damageTaken.get(p2.getUniqueId()), 1e-6);
        assertEquals(22.0, damageTaken.get(p3.getUniqueId()), 1e-6);
        assertEquals(22.0, damageTaken.get(p4.getUniqueId()), 1e-6);

        // 2. Tether Dive Relay (nearest player intercepts; consecutive hits apply 3x vulnerability)
        damageTaken.clear();
        BossConfig.AbilitySettings relayAbility = hardBoss.ability("tether_dive_relay").orElseThrow();
        BossConfig.TimelineStep relayStep = new BossConfig.TimelineStep(6.5, "TETHER_DIVE_RELAY", "tether_dive_relay");
        Player closeTank = mockPlayer("CloseTank", 1.0, 64.0, 0.0, false, false);
        executor.startCast(relayStep, relayAbility, endWorld, List.of(closeTank), List.of(closeTank), scaled, bossController);
        // Advance through cast ticks to trigger first relay hit, then completeCast for final hit
        for (int i = 0; i < 25; i++) {
            executor.tickActiveTelegraphs(List.of(closeTank), List.of(closeTank), bossController);
        }
        executor.completeCast(relayStep, relayAbility, List.of(closeTank), List.of(closeTank), scaled, bossController, timelineEngine);
        assertTrue(damageTaken.getOrDefault(closeTank.getUniqueId(), 0.0) >= 80.0);
    }
}
