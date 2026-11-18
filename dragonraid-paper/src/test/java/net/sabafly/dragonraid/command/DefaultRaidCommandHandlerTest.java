package net.sabafly.dragonraid.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.sabafly.dragonraid.command.RaidCommandHandler.Difficulty;
import net.sabafly.dragonraid.command.RaidCommandHandler.PointsAction;
import net.sabafly.dragonraid.command.RaidCommandHandler.RegionAction;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.RegionConfig;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.storage.SqlitePlayerDataRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultRaidCommandHandlerTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path tempDir;

    @Mock
    private RaidScheduler scheduler;

    private ConfigManager configManager;
    private MessageService messageService;
    private SqlitePlayerDataRepository repository;
    private Map<String, Player> onlinePlayers;
    private DefaultRaidCommandHandler handler;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
            tempDir,
            name -> DefaultRaidCommandHandlerTest.class.getClassLoader().getResourceAsStream(name)
        );
        configManager.initialize();

        messageService = new MessageService(configManager);
        messageService.reload();

        Executor directExecutor = Runnable::run;
        lenient().when(scheduler.asyncExecutor()).thenReturn(directExecutor);
        lenient().when(scheduler.runGlobal(any())).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return TaskHandle.retired();
        });
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return TaskHandle.retired();
        });

        repository = new SqlitePlayerDataRepository(tempDir.resolve("dragonraid.db"), 2, directExecutor);
        onlinePlayers = new HashMap<>();
        handler = new DefaultRaidCommandHandler(
            configManager,
            messageService,
            scheduler,
            repository,
            Logger.getLogger("DragonRaidTest"),
            name -> Optional.ofNullable(onlinePlayers.get(name))
        );
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void adminReloadSucceedsAndReportsFailureOnInvalidConfig() throws IOException {
        CommandSender console = mock(CommandSender.class);

        handler.adminReload(console);
        List<String> okMessages = captureMessages(console);
        assertEquals(1, okMessages.size());
        assertTrue(okMessages.get(0).contains("リロード") || okMessages.get(0).contains("reloaded"));

        Files.writeString(tempDir.resolve("config.yml"), "language: [broken", StandardCharsets.UTF_8);
        CommandSender console2 = mock(CommandSender.class);
        handler.adminReload(console2);
        List<String> failMessages = captureMessages(console2);
        assertEquals(1, failMessages.size());
        assertTrue(failMessages.get(0).contains("失敗") || failMessages.get(0).contains("Failed"));
    }

    @Test
    void showStatsDisplaysSelfAndOfflinePlayerStatsAndHandlesMissing() {
        UUID aliceUuid = UUID.randomUUID();
        repository.addPoints(aliceUuid, "Alice", 150L).join();
        repository.addCurrency(aliceUuid, "Alice", "dragon_token", 10L).join();
        repository.recordClear(aliceUuid, "Alice", net.sabafly.dragonraid.storage.Difficulty.NORMAL, 125_340L)
            .join();

        Player alice = mock(Player.class);
        when(alice.getUniqueId()).thenReturn(aliceUuid);
        when(alice.getName()).thenReturn("Alice");
        when(alice.locale()).thenReturn(Locale.US);

        handler.showStats(alice, null);
        verify(scheduler).runAtEntity(eq(alice), any());
        List<String> aliceMessages = captureMessages(alice);
        assertEquals(4, aliceMessages.size());
        assertTrue(aliceMessages.get(0).contains("Alice"));
        assertTrue(aliceMessages.get(1).contains("Normal 1 / Hard 0"));
        assertTrue(aliceMessages.get(2).contains("02:05.34"));
        assertTrue(aliceMessages.get(3).contains("150 (dragon_token: 10)"));

        CommandSender console = mock(CommandSender.class);
        handler.showStats(console, "Alice");
        verify(scheduler).runGlobal(any());
        List<String> consoleMessages = captureMessages(console);
        assertEquals(4, consoleMessages.size());
        assertTrue(consoleMessages.get(0).contains("Alice"));

        CommandSender missingSender = mock(CommandSender.class);
        handler.showStats(missingSender, "Nobody");
        List<String> missingMessages = captureMessages(missingSender);
        assertEquals(1, missingMessages.size());
        assertTrue(missingMessages.get(0).contains("Nobody"));

        CommandSender noArgConsole = mock(CommandSender.class);
        handler.showStats(noArgConsole, null);
        assertEquals(1, captureMessages(noArgConsole).size());
    }

    @Test
    void adminPointsMutatesVirtualAndNamedCurrenciesAndHandlesErrors() {
        UUID bobUuid = UUID.randomUUID();
        Player bob = mock(Player.class);
        when(bob.getUniqueId()).thenReturn(bobUuid);
        when(bob.getName()).thenReturn("Bob");
        onlinePlayers.put("Bob", bob);

        CommandSender console = mock(CommandSender.class);
        handler.adminPoints(console, PointsAction.ADD, "Bob", null, 100);
        handler.adminPoints(console, PointsAction.TAKE, "Bob", null, 40);
        handler.adminPoints(console, PointsAction.TAKE, "Bob", null, 999);
        handler.adminPoints(console, PointsAction.SET, "Bob", "dragon_token", 25);

        assertEquals(60L, repository.findByUuid(bobUuid).join().orElseThrow().virtualPoints());
        assertEquals(25L, repository.getCurrencyBalance(bobUuid, "dragon_token").join());

        List<String> messages = captureMessages(console);
        assertEquals(4, messages.size());
        assertTrue(messages.get(0).contains("100"));
        assertTrue(messages.get(1).contains("60"));
        assertTrue(messages.get(2).contains("不足") || messages.get(2).contains("Insufficient"));
        assertTrue(messages.get(3).contains("dragon_token"));

        CommandSender ghostSender = mock(CommandSender.class);
        handler.adminPoints(ghostSender, PointsAction.ADD, "Ghost", null, 10);
        List<String> ghostMsgs = captureMessages(ghostSender);
        assertEquals(1, ghostMsgs.size());
        assertTrue(ghostMsgs.get(0).contains("Ghost"));

        // Unknown currency is rejected before touching storage
        CommandSender invalidCurrencySender = mock(CommandSender.class);
        handler.adminPoints(invalidCurrencySender, PointsAction.ADD, "Bob", "unknown_coin", 50);
        List<String> invalidCurrencyMsgs = captureMessages(invalidCurrencySender);
        assertEquals(1, invalidCurrencyMsgs.size());
        assertTrue(invalidCurrencyMsgs.get(0).contains("dragon_token"));

        // TAKE on an online player with no prior DB row reports insufficient funds rather than player-not-found
        UUID newbieUuid = UUID.randomUUID();
        Player newbie = mock(Player.class);
        when(newbie.getUniqueId()).thenReturn(newbieUuid);
        when(newbie.getName()).thenReturn("Newbie");
        onlinePlayers.put("Newbie", newbie);

        Player takeSender = mock(Player.class);
        when(takeSender.locale()).thenReturn(Locale.US);
        handler.adminPoints(takeSender, PointsAction.TAKE, "Newbie", null, 10);
        List<String> newbieMsgs = captureMessages(takeSender);
        assertEquals(1, newbieMsgs.size());
        assertTrue(newbieMsgs.get(0).contains("Insufficient"));
        assertTrue(newbieMsgs.get(0).contains("Newbie"));
    }

    @Test
    void adminRegionDisplaysAndUpdatesArenaAndSafeZone() {
        CommandSender console = mock(CommandSender.class);
        handler.adminRegion(console, null, RegionAction.INFO);
        List<String> infoMsgs = captureMessages(console);
        assertEquals(2, infoMsgs.size());
        assertTrue(infoMsgs.get(0).contains("arena"));
        assertTrue(infoMsgs.get(1).contains("safe-zone"));

        World world = mock(World.class);
        Location newArenaLoc = new Location(world, 12.5, 70.0, -24.5);
        Player admin = mock(Player.class);
        when(admin.locale()).thenReturn(Locale.US);

        handler.adminRegion(admin, newArenaLoc, RegionAction.SET_ARENA);
        RegionConfig afterArena = configManager.pluginConfig().regions();
        assertEquals(12.5, afterArena.arena().center().x());
        assertEquals(70.0, afterArena.arena().center().y());
        assertEquals(-24.5, afterArena.arena().center().z());

        Location newSafeLoc = new Location(world, 200.0, 65.0, 10.0, 180.0f, 5.0f);
        handler.adminRegion(admin, newSafeLoc, RegionAction.SET_SAFEZONE);
        RegionConfig afterSafe = configManager.pluginConfig().regions();
        assertEquals(200.0, afterSafe.safeZone().spawnPoint().x());
        assertEquals(180.0f, afterSafe.safeZone().spawnPoint().yaw());
        assertTrue(afterSafe.safeZone().contains(200.0, 65.0, 10.0));

        List<String> adminMsgs = captureMessages(admin);
        assertEquals(2, adminMsgs.size());
        assertTrue(adminMsgs.get(0).contains("arena"));
        assertTrue(adminMsgs.get(1).contains("safe-zone"));

        CommandSender consoleSet = mock(CommandSender.class);
        handler.adminRegion(consoleSet, null, RegionAction.SET_ARENA);
        assertEquals(1, captureMessages(consoleSet).size());
    }

    @Test
    void milestoneNoticesAndPermissionMessagesSendFeedback() {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.US);
        CommandSender console = mock(CommandSender.class);

        handler.openShop(player);
        handler.onNoPermission(player);
        handler.adminStart(console, Difficulty.HARD);
        handler.adminReset(console);
        handler.onPlayerOnly(console);

        List<String> playerMsgs = captureMessages(player);
        assertEquals(2, playerMsgs.size());
        assertTrue(playerMsgs.get(0).contains("Milestone 4"));
        assertTrue(playerMsgs.get(1).contains("permission"));

        List<String> consoleMsgs = captureMessages(console);
        assertEquals(3, consoleMsgs.size());
        assertTrue(consoleMsgs.get(0).contains("hard"));
        assertTrue(consoleMsgs.get(1).contains("Milestone 2"));
        assertTrue(consoleMsgs.get(2).contains("プレイヤー") || consoleMsgs.get(2).contains("player"));
    }

    @Test
    void adminReloadAndRegionUpdateRunIoAsyncAndReplyOnSenderThread() {
        List<Runnable> asyncQueue = new ArrayList<>();
        List<Runnable> globalQueue = new ArrayList<>();
        List<Runnable> entityQueue = new ArrayList<>();

        doReturn((Executor) asyncQueue::add).when(scheduler).asyncExecutor();
        doAnswer(inv -> {
            globalQueue.add(inv.getArgument(0));
            return mock(TaskHandle.class);
        }).when(scheduler).runGlobal(any(Runnable.class));
        doAnswer(inv -> {
            entityQueue.add(inv.getArgument(1));
            return mock(TaskHandle.class);
        }).when(scheduler).runAtEntity(any(Player.class), any(Runnable.class));

        CommandSender console = mock(CommandSender.class);
        handler.adminReload(console);
        assertEquals(1, asyncQueue.size());
        assertEquals(0, globalQueue.size());
        asyncQueue.removeFirst().run();
        assertEquals(1, globalQueue.size());
        globalQueue.removeFirst().run();
        assertEquals(1, captureMessages(console).size());

        World world = mock(World.class);
        Player admin = mock(Player.class);
        when(admin.locale()).thenReturn(Locale.US);
        handler.adminRegion(admin, new Location(world, 30.0, 68.0, -10.0), RegionAction.SET_ARENA);
        assertEquals(1, asyncQueue.size());
        assertEquals(0, entityQueue.size());
        asyncQueue.removeFirst().run();
        assertEquals(1, entityQueue.size());
        entityQueue.removeFirst().run();
        assertEquals(30.0, configManager.pluginConfig().regions().arena().center().x(), 1e-6);
        assertEquals(1, captureMessages(admin).size());
    }

    @Test
    void showHelpUsesLocalizedMessageServicePerLocale() {
        Player english = mock(Player.class);
        when(english.locale()).thenReturn(Locale.US);
        handler.showHelp(english);
        List<String> enMsgs = captureMessages(english);
        assertEquals(1, enMsgs.size());
        assertTrue(enMsgs.get(0).contains("<region|start|reset|points|reload>"));

        Player japanese = mock(Player.class);
        when(japanese.locale()).thenReturn(Locale.JAPAN);
        handler.showHelp(japanese);
        List<String> jaMsgs = captureMessages(japanese);
        assertEquals(1, jaMsgs.size());
        assertTrue(jaMsgs.get(0).contains("コマンド一覧"));
    }

    @Test
    void concurrentArenaAndSafeZoneUpdatesDoNotOverwriteEachOther() {
        List<Runnable> asyncQueue = new ArrayList<>();
        doReturn((Executor) asyncQueue::add).when(scheduler).asyncExecutor();
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(scheduler).runGlobal(any(Runnable.class));

        World world = mock(World.class);
        CommandSender admin = mock(CommandSender.class);
        handler.adminRegion(admin, new Location(world, 40.0, 72.0, -15.0), RegionAction.SET_ARENA);
        handler.adminRegion(admin, new Location(world, 220.0, 66.0, 30.0, 45.0f, -10.0f), RegionAction.SET_SAFEZONE);

        assertEquals(2, asyncQueue.size());
        asyncQueue.removeFirst().run();
        asyncQueue.removeFirst().run();

        RegionConfig updated = configManager.pluginConfig().regions();
        assertEquals(40.0, updated.arena().center().x(), 1e-6);
        assertEquals(-15.0, updated.arena().center().z(), 1e-6);
        assertEquals(220.0, updated.safeZone().spawnPoint().x(), 1e-6);
        assertEquals(45.0f, updated.safeZone().spawnPoint().yaw(), 1e-6);
    }

    @Test
    void extendedRegionCommandsAndEncounterDelegationWork() {
        CommandSender admin = mock(CommandSender.class);
        handler.adminSetArenaRadius(admin, 160.0);
        handler.adminSetVoidY(admin, -20.0);
        assertEquals(160.0, configManager.pluginConfig().regions().arena().radius(), 1e-6);
        assertEquals(-20.0, configManager.pluginConfig().regions().voidYThreshold(), 1e-6);

        handler.adminSetArenaBounds(admin, -90.0, 10.0, -90.0, 90.0, 220.0, 90.0);
        assertEquals(RegionConfig.Shape.CUBOID, configManager.pluginConfig().regions().arena().shape());
        assertEquals(-90.0, configManager.pluginConfig().regions().arena().min().x(), 1e-6);

        handler.adminSetSafeZoneBounds(admin, 80.0, 40.0, -12.0, 120.0, 70.0, 12.0);
        assertEquals(RegionConfig.Shape.CUBOID, configManager.pluginConfig().regions().safeZone().shape());
        assertEquals(80.0, configManager.pluginConfig().regions().safeZone().min().x(), 1e-6);

        net.sabafly.dragonraid.encounter.RaidEncounterManager enc =
                mock(net.sabafly.dragonraid.encounter.RaidEncounterManager.class);
        DefaultRaidCommandHandler wired = new DefaultRaidCommandHandler(
                configManager, messageService, scheduler, repository, Logger.getLogger("WiredTest"), enc);
        wired.adminStart(admin, Difficulty.HARD);
        wired.adminReset(admin);

        verify(enc).adminStart(admin, net.sabafly.dragonraid.config.BossConfig.Difficulty.HARD);
        verify(enc).adminReset(admin);

        // Arena region edits are blocked when encounterManager rejects tryBeginArenaRegionUpdate()
        when(enc.tryBeginArenaRegionUpdate()).thenReturn(false);
        net.sabafly.dragonraid.config.RegionConfig.ArenaRegion arenaBefore =
                configManager.pluginConfig().regions().arena();
        wired.adminSetArenaRadius(admin, 99.0);
        wired.adminSetArenaBounds(admin, -50, 0, -50, 50, 200, 50);
        assertEquals(arenaBefore, configManager.pluginConfig().regions().arena());
    }

    private static List<String> captureMessages(CommandSender sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream()
            .map(PLAIN::serialize)
            .toList();
    }
}
