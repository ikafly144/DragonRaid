package net.sabafly.dragonraid.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotification;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PacketEventsContentUnlockNotifierTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path tempDir;

    private MessageService messageService;
    private RaidScheduler scheduler;

    @BeforeEach
    void setUp() {
        ConfigManager configManager = new ConfigManager(
                tempDir,
                name -> PacketEventsContentUnlockNotifierTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        messageService = new MessageService(configManager);
        messageService.reload();

        scheduler = mock(RaidScheduler.class);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(scheduler).runAtEntity(any(Player.class), any(Runnable.class));
    }

    @Test
    void dispatchesTitleSoundChatAndToastOnEntityThread() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.locale()).thenReturn(Locale.JAPAN);

        AtomicBoolean toastCalled = new AtomicBoolean(false);
        PacketEventsContentUnlockNotifier.ToastTransport toastTransport = (p, title, desc, icon, contentId) -> {
            toastCalled.set(true);
            assertEquals("ENDER_EYE", icon);
            assertEquals("dragon_normal", contentId);
            assertTrue(PLAIN.serialize(title).contains("コンテンツ開放"));
            assertTrue(PLAIN.serialize(desc).contains("終焉の竜"));
        };

        PacketEventsContentUnlockNotifier notifier = new PacketEventsContentUnlockNotifier(
                messageService,
                scheduler,
                id -> playerId.equals(id) ? player : null,
                toastTransport,
                Logger.getLogger("test")
        );

        notifier.notifyContentUnlocked(playerId, ContentUnlockNotification.defaults("dragon_normal", "boss.normal.name"));

        ArgumentCaptor<Title> titleCaptor = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(titleCaptor.capture());
        assertTrue(PLAIN.serialize(titleCaptor.getValue().title()).contains("コンテンツ開放"));
        assertTrue(PLAIN.serialize(titleCaptor.getValue().subtitle()).contains("終焉の竜"));
        verify(player).playSound(any(Sound.class));
        verify(player).sendMessage(any(Component.class));
        assertTrue(toastCalled.get());
    }

    @Test
    void skipsOfflinePlayersWithoutScheduling() {
        UUID playerId = UUID.randomUUID();
        Player offline = mock(Player.class);
        when(offline.isOnline()).thenReturn(false);

        PacketEventsContentUnlockNotifier notifier = new PacketEventsContentUnlockNotifier(
                messageService,
                scheduler,
                id -> offline,
                (p, t, d, i, c) -> {},
                Logger.getLogger("test")
        );

        notifier.notifyContentUnlocked(playerId, ContentUnlockNotification.defaults("dragon_normal", "dragon_normal"));
        verify(scheduler, never()).runAtEntity(any(Player.class), any(Runnable.class));
    }
}
