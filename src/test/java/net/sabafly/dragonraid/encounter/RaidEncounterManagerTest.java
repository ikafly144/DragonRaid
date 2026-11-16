package net.sabafly.dragonraid.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.protection.TemporaryBlockTracker;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.visual.SoulMarkerService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RaidEncounterManagerTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private MessageService messageService;
    private RaidScheduler scheduler;
    private RaidRegionService regionService;
    private TemporaryBlockTracker blockTracker;
    private SoulMarkerService soulMarkerService;

    private World endWorld;
    private Map<UUID, Player> onlinePlayers;
    private AtomicLong nowMillis;
    private AtomicBoolean livingDragonPresent;
    private List<Runnable> capturedGlobalTimers;
    private List<Runnable> capturedDelayedTasks;

    private DefaultRaidEncounterManager manager;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> RaidEncounterManagerTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        messageService = new MessageService(configManager);
        messageService.reload();

        endWorld = mock(World.class);
        lenient().when(endWorld.getUID()).thenReturn(UUID.randomUUID());
        lenient().when(endWorld.getName()).thenReturn("world_the_end");

        onlinePlayers = new ConcurrentHashMap<>();
        nowMillis = new AtomicLong(100_000L);
        livingDragonPresent = new AtomicBoolean(true);
        capturedGlobalTimers = new ArrayList<>();
        capturedDelayedTasks = new ArrayList<>();

        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntityLater(any(), any(), anyLong())).thenReturn(TaskHandle.retired());
        lenient().when(scheduler.runAtRegion(any(World.class), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runGlobalTimer(any(), anyLong(), anyLong())).thenAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            capturedGlobalTimers.add(task);
            return mock(TaskHandle.class);
        });
        lenient().when(scheduler.runGlobalLater(any(), anyLong())).thenAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            capturedDelayedTasks.add(task);
            return mock(TaskHandle.class);
        });

        regionService = mock(RaidRegionService.class);
        lenient().when(regionService.isRaidWorld(endWorld)).thenReturn(true);
        lenient().when(regionService.safeZoneSpawnLocation(endWorld))
                .thenReturn(new Location(endWorld, 100.5, 50.0, 0.5));
        lenient().when(regionService.isInArena(any())).thenAnswer(inv -> {
            Location l = inv.getArgument(0);
            return l != null && endWorld.equals(l.getWorld()) && (l.getX() * l.getX() + l.getZ() * l.getZ()) <= 120.0 * 120.0
                    && l.getY() >= 0.0 && l.getY() <= 256.0;
        });
        lenient().when(regionService.isInSafeZone(any())).thenAnswer(inv -> {
            Location l = inv.getArgument(0);
            return l != null && endWorld.equals(l.getWorld())
                    && l.getX() >= 92.0 && l.getX() <= 108.0
                    && l.getZ() >= -8.0 && l.getZ() <= 8.0;
        });
        lenient().when(regionService.isBelowVoidThreshold(any())).thenAnswer(inv -> {
            Location l = inv.getArgument(0);
            return l != null && endWorld.equals(l.getWorld()) && l.getY() < 0.0;
        });
        lenient().when(regionService.findNearestSafeArenaEdgeAsync(any(), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(new Location(endWorld, 10.5, 66.0, 0.5)));
        lenient().when(regionService.clampToArenaAnchor(any(), anyDouble()))
                .thenReturn(new Location(endWorld, 10.5, 66.0, 0.5));

        blockTracker = new TemporaryBlockTracker();
        soulMarkerService = mock(SoulMarkerService.class);

        manager = new DefaultRaidEncounterManager(
                configManager,
                messageService,
                scheduler,
                regionService,
                blockTracker,
                soulMarkerService,
                Logger.getLogger("EncounterTest"),
                () -> endWorld,
                () -> onlinePlayers.values(),
                id -> onlinePlayers.get(id),
                nowMillis::get,
                w -> livingDragonPresent.get());
    }

    @Test
    void pullCountdown_autoStartsOnArenaEnterFromSafeZoneAndCancelsWhenReturning() {
        Location safeZone = new Location(endWorld, 100.5, 50.0, 0.5);
        Location combatArena = new Location(endWorld, 80.0, 64.0, 0.0);
        Player alice = addPlayer("Alice", safeZone);

        when(alice.getLocation()).thenReturn(combatArena);
        manager.onPlayerMove(new PlayerMoveEvent(alice, safeZone, combatArena));

        assertEquals(RaidState.COUNTDOWN, manager.state());
        assertEquals(1, capturedGlobalTimers.size());

        // Returning back onto safe zone cancels the auto-pull countdown
        when(alice.getLocation()).thenReturn(safeZone);
        manager.onPlayerMove(new PlayerMoveEvent(alice, combatArena, safeZone));
        capturedGlobalTimers.get(0).run();

        assertEquals(RaidState.IDLE, manager.state());
    }

    @Test
    void activeEncounter_locksLateJoinersToSafeZone() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        manager.adminStart(alice, BossConfig.Difficulty.NORMAL);
        assertEquals(RaidState.COUNTDOWN, manager.state());

        // Tick 8 seconds down to ACTIVE
        Runnable countdown = capturedGlobalTimers.get(0);
        for (int i = 0; i < 8; i++) {
            countdown.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());
        assertTrue(manager.isParticipant(alice.getUniqueId()));
        assertEquals(1, manager.remainingRevives());

        // Late joiner Bob enters arena -> cancelled and teleported to safe zone
        Player bob = addPlayer("Bob", new Location(endWorld, 0.0, 64.0, 0.0));
        assertFalse(manager.isParticipant(bob.getUniqueId()));
        PlayerMoveEvent bobMove = new PlayerMoveEvent(
                bob, new Location(endWorld, 100.5, 50.0, 0.5), new Location(endWorld, 0.0, 64.0, 0.0));
        manager.onPlayerMove(bobMove);
        assertTrue(bobMove.isCancelled());
        verify(bob).teleportAsync(new Location(endWorld, 100.5, 50.0, 0.5));
    }

    @Test
    void lethalDamageAndReviveChannel_normalRulesAllow25sHoldAndDecrementRevives() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 1.0, 64.0, 0.0));
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);

        assertEquals(2, manager.remainingRevives());

        // Alice takes lethal damage -> cancelled & transitions to DOWNED
        EntityDamageEvent lethal = mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        manager.onPlayerDamage(alice, lethal);
        verify(lethal).setCancelled(true);
        assertTrue(manager.isDowned(alice.getUniqueId()));
        verify(alice).setInvulnerable(true);
        verify(alice).setInvisible(true);
        verify(soulMarkerService, atLeastOnce()).spawnOrUpdateMarker(
                eq(alice.getUniqueId()), eq("Alice"), any(), eq(null), eq(0.0), any());

        // Downed Alice cannot move X/Y/Z, only rotate
        PlayerMoveEvent moveAttempt = new PlayerMoveEvent(
                alice,
                new Location(endWorld, 0.0, 64.0, 0.0),
                new Location(endWorld, 5.0, 64.0, 0.0, 90.0f, 15.0f));
        manager.onPlayerMove(moveAttempt);
        assertEquals(0.0, moveAttempt.getTo().getX(), 1e-6);
        assertEquals(90.0f, moveAttempt.getTo().getYaw(), 1e-6);

        // Bob sneaks within 2.5m for 2.5 seconds (25 ticks of 0.1s monitor step)
        when(bob.isSneaking()).thenReturn(true);
        for (int i = 0; i < 25; i++) {
            manager.tickActiveEncounter();
        }

        assertEquals(ParticipantState.ALIVE, manager.participantState(alice.getUniqueId()).orElseThrow());
        assertEquals(1, manager.remainingRevives());
        verify(soulMarkerService, atLeastOnce()).removeMarker(eq(alice.getUniqueId()), any());
    }

    @Test
    void hardMode45SecondTimeout_transitionsToDeadSpectatorAndTriggersWipe() {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        Player bob = addPlayer("Bob", new Location(endWorld, 10.0, 64.0, 0.0));
        startActiveEncounter(alice, BossConfig.Difficulty.HARD);

        assertEquals(1, manager.remainingRevives()); // ceil(2 / 2) = 1

        // Alice downed at t = 100_000
        manager.onPlayerDamage(alice, mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertTrue(manager.isDowned(alice.getUniqueId()));

        // Advance clock by 45 seconds -> Alice expires to DEAD_SPECTATOR
        nowMillis.addAndGet(45_000L);
        manager.tickActiveEncounter();
        assertTrue(manager.isDeadSpectator(alice.getUniqueId()));
        verify(alice).setGameMode(GameMode.SPECTATOR);

        // Bob falls into void -> rescued & downed -> 0 ALIVE left -> WIPING
        when(bob.getLocation()).thenReturn(new Location(endWorld, 40.0, -3.0, 0.0));
        manager.tickActiveEncounter();
        verify(bob, atLeastOnce()).teleportAsync(new Location(endWorld, 10.5, 66.0, 0.5));
        assertEquals(RaidState.WIPING, manager.state());

        // Track a temporary block and verify wipe delayed task rolls back & returns to IDLE
        BlockData origData = mock(BlockData.class);
        when(origData.clone()).thenReturn(origData);
        blockTracker.tryRecordPlayerPlacement(new Location(endWorld, 3, 65, 3), bob.getUniqueId(), origData, 64, 512);
        org.bukkit.block.Block worldBlock = mock(org.bukkit.block.Block.class);
        when(endWorld.getBlockAt(3, 65, 3)).thenReturn(worldBlock);

        assertFalse(capturedDelayedTasks.isEmpty());
        capturedDelayedTasks.get(0).run();
        assertEquals(RaidState.IDLE, manager.state());
        verify(worldBlock).setBlockData(origData, false);
        verify(alice, atLeastOnce()).setGameMode(GameMode.SURVIVAL);
    }

    @Test
    void soloFallbackTotemAndDisconnectModesWorkAsConfigured() throws Exception {
        Player alice = addPlayer("Alice", new Location(endWorld, 0.0, 64.0, 0.0));
        ItemStack totem = mock(ItemStack.class);
        when(totem.getType()).thenReturn(Material.TOTEM_OF_UNDYING);
        EntityEquipment eq = mock(EntityEquipment.class);
        when(eq.getItemInMainHand()).thenReturn(totem);
        when(eq.getItemInOffHand()).thenReturn(mock(ItemStack.class));
        when(alice.getEquipment()).thenReturn(eq);

        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);

        // Lethal hit while holding Totem (N=1) is NOT intercepted -> allows vanilla Totem
        EntityDamageEvent lethalWithTotem = mockDamageEvent(50.0, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        manager.onPlayerDamage(alice, lethalWithTotem);
        verify(lethalWithTotem, never()).setCancelled(true);
        assertEquals(ParticipantState.ALIVE, manager.participantState(alice.getUniqueId()).orElseThrow());

        // Item loss protection applies on PlayerDeathEvent
        PlayerDeathEvent deathEvent = mock(PlayerDeathEvent.class);
        when(deathEvent.getDrops()).thenReturn(new ArrayList<>(List.of(totem)));
        manager.onParticipantDeath(alice, deathEvent);
        verify(deathEvent).setKeepInventory(true);
        verify(deathEvent).setKeepLevel(true);

        // Reset, enable down-on-disconnect: true, start 2-player raid, quit Alice -> DOWNED
        manager.adminReset(alice);
        String updatedCfg = Files.readString(tempDir.resolve("config.yml"), StandardCharsets.UTF_8)
                .replace("down-on-disconnect: false", "down-on-disconnect: true");
        Files.writeString(tempDir.resolve("config.yml"), updatedCfg, StandardCharsets.UTF_8);
        configManager.reload();

        Player bob = addPlayer("Bob", new Location(endWorld, 2.0, 64.0, 0.0));
        capturedGlobalTimers.clear();
        startActiveEncounter(alice, BossConfig.Difficulty.NORMAL);
        manager.onPlayerQuit(alice);
        assertTrue(manager.isDowned(alice.getUniqueId()));

        // Rejoin while raid still active restores DOWNED state
        manager.onPlayerJoin(alice);
        assertTrue(manager.isDowned(alice.getUniqueId()));
        assertTrue(manager.isParticipant(alice.getUniqueId()));
        verify(bob, never()).setGameMode(GameMode.SPECTATOR);
    }

    private void startActiveEncounter(Player initiator, BossConfig.Difficulty difficulty) {
        manager.adminStart(initiator, difficulty);
        Runnable countdown = capturedGlobalTimers.get(capturedGlobalTimers.size() - 1);
        int secs = configManager.pluginConfig().encounter().pullCountdownSeconds();
        for (int i = 0; i < secs; i++) {
            countdown.run();
        }
        assertEquals(RaidState.ACTIVE, manager.state());
    }

    private Player addPlayer(String name, Location loc) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.getWorld()).thenReturn(endWorld);
        lenient().when(p.getLocation()).thenReturn(loc);
        AtomicReference<GameMode> mode = new AtomicReference<>(GameMode.SURVIVAL);
        lenient().when(p.getGameMode()).thenAnswer(inv -> mode.get());
        lenient().doAnswer(inv -> {
            mode.set(inv.getArgument(0));
            return null;
        }).when(p).setGameMode(any());
        lenient().when(p.getHealth()).thenReturn(20.0);
        lenient().when(p.getMaxHealth()).thenReturn(20.0);
        lenient().when(p.isCollidable()).thenReturn(true);
        lenient().when(p.isInvisible()).thenReturn(false);
        lenient().when(p.isInvulnerable()).thenReturn(false);
        lenient().when(p.locale()).thenReturn(Locale.US);
        lenient().when(p.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
        onlinePlayers.put(id, p);
        return p;
    }

    private static EntityDamageEvent mockDamageEvent(double finalDamage, EntityDamageEvent.DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        lenient().when(event.getFinalDamage()).thenReturn(finalDamage);
        lenient().when(event.getCause()).thenReturn(cause);
        return event;
    }
}
