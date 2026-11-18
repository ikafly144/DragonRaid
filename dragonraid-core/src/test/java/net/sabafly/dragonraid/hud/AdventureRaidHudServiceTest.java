package net.sabafly.dragonraid.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.encounter.ParticipantState;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.script.dsl.EncounterBlueprintBuilder;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AdventureRaidHudServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path tempDir;

    private BossConfig normalBoss;
    private AdventureRaidHudService hudService;

    @BeforeEach
    void setUp() {
        ConfigManager configManager = new ConfigManager(
                tempDir,
                name -> AdventureRaidHudServiceTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        normalBoss = BossConfig.fromBlueprint(new EncounterBlueprintBuilder("hud_sample")
                .difficulty(EncounterDifficulty.NORMAL)
                .displayNameKey("boss.normal.name")
                .primaryBoss("boss")
                .enemy("boss", e -> e.displayNameKey("boss.normal.name").stats(1200.0, 0.65, false, true, true))
                .mechanic("tank_buster", Map.of("name-key", "ability.tank_buster.name", "cast-seconds", 3.0))
                .phase("phase-1", p -> p.displayNameKey("phase.p1.name").hpRange(1.0, 0.0).step(4.0, "TANK_BUSTER", "tank_buster"))
                .build());

        MessageService messageService = new MessageService(configManager);
        messageService.reload();

        RaidScheduler scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        hudService = new AdventureRaidHudService(messageService, scheduler);
    }

    private Player mockPlayer(String name, Locale locale) {
        Player p = mock(Player.class);
        lenient().when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.locale()).thenReturn(locale);
        return p;
    }

    @Test
    void updatesLocalizedBossBarsActionBarsTitlesAndClearsCleanly() {
        Player alice = mockPlayer("Alice", Locale.US);
        BossConfig.PhaseConfig p1 = normalBoss.phases().getFirst();
        BossConfig.AbilitySettings tankBuster = normalBoss.ability("tank_buster").orElseThrow();

        hudService.updateMainBossBar(normalBoss, p1, 0.85, List.of(alice));
        ArgumentCaptor<BossBar> barCaptor = ArgumentCaptor.forClass(BossBar.class);
        verify(alice, times(1)).showBossBar(barCaptor.capture());
        BossBar mainBar = barCaptor.getValue();
        assertEquals(0.85f, mainBar.progress(), 1e-4f);
        assertTrue(PLAIN.serialize(mainBar.name()).contains("85%"));
        assertTrue(PLAIN.serialize(mainBar.name()).contains("Phase 1"));

        // Cast bar shows and hides cleanly
        hudService.updateCastBossBar(tankBuster, 1.5, 3.0, List.of(alice));
        verify(alice, times(2)).showBossBar(barCaptor.capture());
        BossBar castBar = barCaptor.getValue();
        assertEquals(0.5f, castBar.progress(), 1e-4f);
        assertTrue(PLAIN.serialize(castBar.name()).contains("1.5s"));

        hudService.updateCastBossBar(null, 0.0, 0.0, List.of(alice));
        verify(alice, times(1)).hideBossBar(castBar);

        // Action bar respects suppression predicate
        hudService.updateActionBars(
                List.of(alice),
                id -> ParticipantState.ALIVE,
                2,
                id -> Component.text("Tank Buster"),
                id -> true
        );
        verify(alice, never()).sendActionBar(any(Component.class));

        hudService.updateActionBars(
                List.of(alice),
                id -> ParticipantState.ALIVE,
                2,
                id -> Component.text("Tank Buster"),
                id -> false
        );
        ArgumentCaptor<Component> abCaptor = ArgumentCaptor.forClass(Component.class);
        verify(alice, times(1)).sendActionBar(abCaptor.capture());
        String abText = PLAIN.serialize(abCaptor.getValue());
        assertTrue(abText.contains("ALIVE"));
        assertTrue(abText.contains("2"));
        assertTrue(abText.contains("Tank Buster"));

        // Phase announcement shows Title and plays growl sound
        hudService.announcePhaseTransition(p1, List.of(alice));
        verify(alice, atLeastOnce()).showTitle(any(Title.class));
        verify(alice, atLeastOnce()).playSound(any(Sound.class));

        // Clear all hides main bar
        hudService.clearAll(List.of(alice));
        verify(alice, times(1)).hideBossBar(mainBar);
    }

    @Test
    void clearAllWithEmptyListHidesCastBarShownWithoutMainBar() {
        Player bob = mockPlayer("Bob", Locale.US);
        BossConfig.AbilitySettings tankBuster = normalBoss.ability("tank_buster").orElseThrow();

        hudService.updateCastBossBar(tankBuster, 2.0, 3.0, List.of(bob));
        ArgumentCaptor<BossBar> barCaptor = ArgumentCaptor.forClass(BossBar.class);
        verify(bob, times(1)).showBossBar(barCaptor.capture());

        hudService.clearAll(List.of());
        verify(bob, times(1)).hideBossBar(barCaptor.getValue());
    }
}
