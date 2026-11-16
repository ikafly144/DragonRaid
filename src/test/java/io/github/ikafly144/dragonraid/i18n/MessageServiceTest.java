package io.github.ikafly144.dragonraid.i18n;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.ikafly144.dragonraid.config.BossConfig;
import io.github.ikafly144.dragonraid.config.ConfigManager;
import io.github.ikafly144.dragonraid.config.RewardsConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static ConfigManager initConfig(Path tempDir) {
        ConfigManager manager = new ConfigManager(
                tempDir, name -> MessageServiceTest.class.getClassLoader().getResourceAsStream(name));
        manager.initialize();
        return manager;
    }

    @Test
    void loadsBothLocalesAndValidatesAllConfigReferencedKeys(@TempDir Path tempDir) {
        ConfigManager config = initConfig(tempDir);
        MessageService messages = new MessageService(config);
        messages.reload();

        assertTrue(messages.loadedLocales().contains("ja_JP"));
        assertTrue(messages.loadedLocales().contains("en_US"));

        // Verify every translation key referenced in boss_normal, boss_hard, and rewards exists in both locales
        for (BossConfig boss : List.of(config.normalBossConfig(), config.hardBossConfig())) {
            assertKeysPresent(messages, boss.displayNameKey());
            assertKeysPresent(messages, boss.enrage().hardEnrage().abilityNameKey());
            boss.abilities().values().forEach(a -> assertKeysPresent(messages, a.nameKey()));
            boss.phases().forEach(p -> assertKeysPresent(messages, p.nameKey()));
        }

        RewardsConfig rewards = config.rewardsConfig();
        rewards.currencies().values().forEach(c -> assertKeysPresent(messages, c.nameKey()));
        rewards.customGear().values().forEach(g -> {
            assertKeysPresent(messages, g.nameKey());
            assertKeysPresent(messages, g.loreKey());
        });
        rewards.customEnchants().values().forEach(e -> {
            assertKeysPresent(messages, e.nameKey());
            assertKeysPresent(messages, e.loreKey());
        });
        assertKeysPresent(messages, rewards.shop().titleKey());

        // Verify <prefix> and placeholders resolve cleanly
        Component rendered = messages.render("en_US", "command.points-updated",
                Placeholder.unparsed("player", "Steve"),
                Placeholder.unparsed("currency", "Dragon Token"),
                Placeholder.unparsed("amount", "50"),
                Placeholder.unparsed("action", "add"));
        String plain = PLAIN.serialize(rendered);
        assertTrue(plain.startsWith("[DragonRaid] "));
        assertTrue(plain.contains("Steve"));
        assertTrue(plain.contains("50"));
        assertFalse(plain.contains("<prefix>"));
    }

    private static void assertKeysPresent(MessageService messages, String key) {
        assertTrue(messages.hasKey("ja_JP", key), "Missing ja_JP key: " + key);
        assertTrue(messages.hasKey("en_US", key), "Missing en_US key: " + key);
    }

    @Test
    void resolveLocale_mapsPlayerLocaleAndFallsBackToDefault(@TempDir Path tempDir) {
        ConfigManager config = initConfig(tempDir);
        MessageService messages = new MessageService(config);
        messages.reload();

        Player jpPlayer = mock(Player.class);
        when(jpPlayer.locale()).thenReturn(Locale.JAPAN);
        assertEquals("ja_JP", messages.resolveLocale(jpPlayer));

        Player usPlayer = mock(Player.class);
        when(usPlayer.locale()).thenReturn(Locale.US);
        assertEquals("en_US", messages.resolveLocale(usPlayer));

        Player ukPlayer = mock(Player.class);
        when(ukPlayer.locale()).thenReturn(Locale.UK);
        assertEquals("en_US", messages.resolveLocale(ukPlayer));

        Player frPlayer = mock(Player.class);
        when(frPlayer.locale()).thenReturn(Locale.FRANCE);
        assertEquals("ja_JP", messages.resolveLocale(frPlayer));

        CommandSender console = mock(CommandSender.class);
        assertEquals("ja_JP", messages.resolveLocale(console));
    }

    @Test
    void fallbackChain_fallsBackToEnUsAndThenKey(@TempDir Path tempDir) throws IOException {
        ConfigManager config = initConfig(tempDir);

        Path jaFile = tempDir.resolve("lang/ja_JP.yml");
        String modified = Files.readString(jaFile, StandardCharsets.UTF_8)
                .replace("reload-success:", "reload-success-removed:");
        Files.writeString(jaFile, modified, StandardCharsets.UTF_8);

        MessageService messages = new MessageService(config);
        messages.reload();

        assertFalse(messages.hasKey("ja_JP", "command.reload-success"));
        assertEquals(
                messages.raw("en_US", "command.reload-success"),
                messages.raw("ja_JP", "command.reload-success")
        );
        assertEquals("non.existent.key", messages.raw("ja_JP", "non.existent.key"));
    }

    @Test
    void renderList_splitsMultilineLore(@TempDir Path tempDir) {
        ConfigManager config = initConfig(tempDir);
        MessageService messages = new MessageService(config);
        messages.reload();

        List<Component> lore = messages.renderList("en_US", "altar.normal-lore");
        assertEquals(3, lore.size());
        assertEquals("3 Phases / Ground-focused action", PLAIN.serialize(lore.get(0)));
        assertEquals("Click to summon", PLAIN.serialize(lore.get(2)));
    }

    @Test
    void send_dispatchesComponentToSender(@TempDir Path tempDir) {
        ConfigManager config = initConfig(tempDir);
        MessageService messages = new MessageService(config);
        messages.reload();

        Player player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.US);

        messages.send(player, "command.reload-success");

        verify(player).sendMessage(any(Component.class));
    }
}
