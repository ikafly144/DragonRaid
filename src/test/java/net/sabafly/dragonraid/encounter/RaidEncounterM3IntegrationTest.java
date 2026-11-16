package net.sabafly.dragonraid.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.boss.DragonBossController;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.gimmick.NormalGimmickExecutor;
import net.sabafly.dragonraid.gimmick.RaidCrystalManager;
import net.sabafly.dragonraid.hud.AdventureRaidHudService;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker;
import net.sabafly.dragonraid.region.DefaultRaidRegionService;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.storage.Difficulty;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.PlayerRaidStats;
import net.sabafly.dragonraid.visual.SoulMarkerService;
import net.sabafly.dragonraid.visual.SpatialTelegraphService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidEncounterM3IntegrationTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private MessageService messageService;
    private RaidScheduler scheduler;
    private RaidRegionService regionService;
    private TemporaryBlockTracker blockTracker;
    private SoulMarkerService soulMarkerService;
    private PlayerDataRepository playerDataRepository;
    private DragonBossController bossController;
    private AdventureRaidHudService hudService;
    private RaidCrystalManager crystalManager;
    private NormalGimmickExecutor gimmickExecutor;

    private World endWorld;
    private EnderDragon dragon;
    private DragonBattle dragonBattle;
    private Map<UUID, Player> onlinePlayers;
    private AtomicLong nowMillis;
    private List<Runnable> capturedGlobalTimers;
    private DefaultRaidEncounterManager manager;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> RaidEncounterM3IntegrationTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        messageService = new MessageService(configManager);
        messageService.reload();

        endWorld = mock(World.class);
        lenient().when(endWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(endWorld.getName()).thenReturn("world_the_end");
        lenient().when(endWorld.getMinHeight()).thenReturn(0);
        lenient().when(endWorld.getMaxHeight()).thenReturn(256);

        dragon = mock(EnderDragon.class);
        lenient().when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(dragon.getWorld()).thenReturn(endWorld);
        lenient().when(dragon.isDead()).thenReturn(false);

        dragonBattle = mock(DragonBattle.class);
        lenient().when(dragonBattle.hasBeenPreviouslyKilled()).thenReturn(false);
        lenient().when(dragonBattle.getEnderDragon()).thenReturn(dragon);
        lenient().when(endWorld.getEnderDragonBattle()).thenReturn(dragonBattle);

        onlinePlayers = new ConcurrentHashMap<>();
        nowMillis = new AtomicLong(100_000L);
        capturedGlobalTimers = new ArrayList<>();

        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtRegion(any(Location.class), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            capturedGlobalTimers.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        });

        regionService = new DefaultRaidRegionService(configManager, scheduler);
        blockTracker = new TemporaryBlockTracker();
        soulMarkerService = mock(SoulMarkerService.class);
        playerDataRepository = mock(PlayerDataRepository.class);
        lenient().when(playerDataRepository.recordClear(any(), any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PlayerRaidStats.empty(UUID.randomUUID(), "Alice")));

        bossController = mock(DragonBossController.class);
        lenient().when(bossController.bindOrSpawn(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossController.cleanup(any(Boolean.class))).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(bossController.hpRatio()).thenReturn(1.0);
        lenient().when(bossController.bossLocation()).thenReturn(new Location(endWorld, 0.0, 74.0, 0.0));

        hudService = new AdventureRaidHudService(messageService, scheduler);
        SpatialTelegraphService telegraphService = mock(SpatialTelegraphService.class);
        crystalManager = new RaidCrystalManager(
                configManager, scheduler, telegraphService, Logger.getLogger("M3Integration"));
        gimmickExecutor = new NormalGimmickExecutor(
                configManager, messageService, scheduler, telegraphService, crystalManager);

        manager = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                null,
                playerDataRepository,
                bossController,
                hudService,
                crystalManager,
                gimmickExecutor,
                Logger.getLogger("M3Integration"),
                () -> endWorld,
                onlinePlayers::values,
                onlinePlayers::get,
                nowMillis::get,
                w -> true
        );
    }

    private Player addPlayer(String name, double x, double y, double z) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.locale()).thenReturn(Locale.US);
        lenient().when(p.getWorld()).thenReturn(endWorld);
        lenient().when(p.getLocation()).thenReturn(new Location(endWorld, x, y, z));
        lenient().when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        lenient().when(p.getHealth()).thenReturn(20.0);
        onlinePlayers.put(id, p);
        return p;
    }

    @Test
    void firstClearInitAltarPullTicksM3SystemsAndRecordsVictory() {
        Player alice = addPlayer("Alice", 10.0, 64.0, 0.0);

        // First-clear join initializes idle dragon once
        manager.onPlayerJoin(alice);
        verify(bossController).holdFirstClearIdle(endWorld);
        verify(alice).sendMessage(any(Component.class));
        assertTrue(manager.isFirstClearEncounterAvailable());

        // Trigger Altar pull (zero countdown for test convenience)
        configManager.pluginConfig(); // loaded
        assertTrue(manager.startAltarPull(alice, BossConfig.Difficulty.NORMAL));
        assertEquals(RaidState.COUNTDOWN, manager.state());

        // Advance countdown (8 seconds)
        Runnable countdownTimer = capturedGlobalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());
        verify(bossController).bindOrSpawn(eq(endWorld), eq(1200.0));
        verify(alice, atLeastOnce()).showBossBar(any());

        // Suppresses EnderDragon explosion during active raid
        EntityExplodeEvent dragonExplode = mock(EntityExplodeEvent.class);
        lenient().when(dragonExplode.getEntity()).thenReturn(dragon);
        manager.onEntityExplode(dragon, dragonExplode);
        verify(dragonExplode).setCancelled(true);

        // Tick 5.0 seconds to trigger SPAWN_CRYSTALS + HUD updates
        for (int i = 0; i < 50; i++) {
            manager.tickActiveEncounter();
        }
        verify(bossController, atLeastOnce()).tick(0.1);
        verify(alice, atLeastOnce()).sendActionBar(any(Component.class));

        // Defeat dragon -> records clear, prepares vanilla defeat, resets state
        nowMillis.addAndGet(45_000L);
        manager.onDragonDefeated(dragon);
        assertEquals(RaidState.IDLE, manager.state());
        verify(bossController).prepareForVanillaDefeat();
        verify(playerDataRepository).recordClear(alice.getUniqueId(), "Alice", Difficulty.NORMAL, 45_000L);
        verify(alice, atLeastOnce()).hideBossBar(any());
    }

    @Test
    void adminResetCleansUpBossAndHud() {
        Player alice = addPlayer("Alice", 10.0, 64.0, 0.0);
        CommandSender admin = mock(CommandSender.class);

        manager.adminStart(admin, BossConfig.Difficulty.NORMAL);
        Runnable countdownTimer = capturedGlobalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());

        manager.adminReset(admin);
        assertEquals(RaidState.IDLE, manager.state());
        verify(bossController).cleanup(true);
        verify(alice, atLeastOnce()).hideBossBar(any());
    }

    @Test
    void abortsEncounterWhenBossSpawnFails() {
        Player alice = addPlayer("Alice", 10.0, 64.0, 0.0);
        CommandSender admin = mock(CommandSender.class);
        lenient().when(bossController.bindOrSpawn(any(), anyDouble()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("spawn failure")));

        manager.adminStart(admin, BossConfig.Difficulty.NORMAL);
        Runnable countdownTimer = capturedGlobalTimers.getFirst();
        for (int i = 0; i < 8; i++) {
            countdownTimer.run();
        }

        // Encounter must abort back to IDLE instead of remaining stuck in ACTIVE without a boss
        assertEquals(RaidState.IDLE, manager.state());
        verify(bossController).cleanup(true);
    }
}
