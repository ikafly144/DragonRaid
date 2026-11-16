package io.github.ikafly144.dragonraid.command;

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

import io.github.ikafly144.dragonraid.command.RaidCommandHandler.Difficulty;
import io.github.ikafly144.dragonraid.command.RaidCommandHandler.PointsAction;
import io.github.ikafly144.dragonraid.command.RaidCommandHandler.RegionAction;
import io.github.ikafly144.dragonraid.config.ConfigManager;
import io.github.ikafly144.dragonraid.config.RegionConfig;
import io.github.ikafly144.dragonraid.i18n.MessageService;
import io.github.ikafly144.dragonraid.scheduler.RaidScheduler;
import io.github.ikafly144.dragonraid.scheduler.TaskHandle;
import io.github.ikafly144.dragonraid.storage.SqlitePlayerDataRepository;
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
        repository.recordClear(aliceUuid, "Alice", io.github.ikafly144.dragonraid.storage.Difficulty.NORMAL, 125_340L)
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
    void milestoneNoticesAndPermissionMessagesSendFeedback() throws IOException {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.US);
        CommandSender console = mock(CommandSender.class);

        handler.openShop(player);
        handler.ready(player);
        handler.onNoPermission(player);
        handler.start(console);
        handler.adminStart(console, Difficulty.HARD);
        handler.adminReset(console);
        handler.onPlayerOnly(console);

        List<String> playerMsgs = captureMessages(player);
        assertEquals(3, playerMsgs.size());
        assertTrue(playerMsgs.get(0).contains("Milestone 4"));
        assertTrue(playerMsgs.get(1).contains("Milestone 2"));
        assertTrue(playerMsgs.get(2).contains("permission"));

        List<String> consoleMsgs = captureMessages(console);
        assertEquals(4, consoleMsgs.size());
        assertTrue(consoleMsgs.get(0).contains("Milestone 2"));
        assertTrue(consoleMsgs.get(1).contains("hard"));
        assertTrue(consoleMsgs.get(2).contains("Milestone 2"));
        assertTrue(consoleMsgs.get(3).contains("プレイヤー") || consoleMsgs.get(3).contains("player"));

        // Verify altar-gui-only mode notice on /dr start and /dr ready
        Path configFile = tempDir.resolve("config.yml");
        String updated = Files.readString(configFile, StandardCharsets.UTF_8)
            .replace("altar-gui-only: false", "altar-gui-only: true");
        Files.writeString(configFile, updated, StandardCharsets.UTF_8);
        configManager.reload();

        Player altarPlayer = mock(Player.class);
        when(altarPlayer.locale()).thenReturn(Locale.US);
        handler.ready(altarPlayer);
        handler.start(altarPlayer);
        List<String> altarMsgs = captureMessages(altarPlayer);
        assertEquals(2, altarMsgs.size());
        assertTrue(altarMsgs.get(0).contains("Altar"));
        assertTrue(altarMsgs.get(1).contains("Altar"));
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

    private static List<String> captureMessages(CommandSender sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream()
            .map(PLAIN::serialize)
            .toList();
    }
}
