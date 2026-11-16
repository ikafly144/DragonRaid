package net.sabafly.dragonraid.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.visual.PacketEventsSoulMarkerService.MarkerTransport;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PacketEventsSoulMarkerServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private MessageService messageService;
    private List<String> events;
    private PacketEventsSoulMarkerService service;
    private World endWorld;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> PacketEventsSoulMarkerServiceTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        messageService = new MessageService(configManager);
        messageService.reload();

        events = new ArrayList<>();
        AtomicInteger nextId = new AtomicInteger(1000);
        MarkerTransport transport = new MarkerTransport() {
            @Override
            public void spawnMarker(
                    Player viewer,
                    int soulEntityId,
                    UUID soulUuid,
                    int textEntityId,
                    UUID textUuid,
                    Location location,
                    boolean glow,
                    Component label) {
                events.add("SPAWN:" + viewer.getName() + ":" + soulEntityId + ":" + textEntityId
                        + ":" + glow + ":" + PLAIN.serialize(label));
            }

            @Override
            public void updateLabel(Player viewer, int textEntityId, Component label) {
                events.add("UPDATE:" + viewer.getName() + ":" + textEntityId + ":" + PLAIN.serialize(label));
            }

            @Override
            public void destroyMarker(Player viewer, int soulEntityId, int textEntityId) {
                events.add("DESTROY:" + viewer.getName() + ":" + soulEntityId + ":" + textEntityId);
            }
        };

        service = new PacketEventsSoulMarkerService(
                configManager,
                messageService,
                Logger.getLogger("SoulMarkerTest"),
                transport,
                nextId::getAndIncrement);

        endWorld = mock(World.class);
    }

    @Test
    void spawnsUpdatesAndDestroysPacketsRespectingRenderDistance() {
        UUID downedId = UUID.randomUUID();
        Location markerLoc = new Location(endWorld, 0.0, 64.0, 0.0);

        Player nearViewer = mockViewer("Alice", new Location(endWorld, 10.0, 64.0, 0.0));
        Player farViewer = mockViewer("FarBob", new Location(endWorld, 200.0, 64.0, 0.0));

        service.spawnOrUpdateMarker(downedId, "Steve", markerLoc, 45, 0.0, List.of(nearViewer, farViewer));

        assertEquals(1, events.size());
        assertTrue(events.get(0).startsWith("SPAWN:Alice:1000:1001:true:"));
        assertTrue(events.get(0).contains("Steve"));
        assertTrue(events.get(0).contains("45s"));

        // Subsequent update sends only label update to Alice
        events.clear();
        service.spawnOrUpdateMarker(downedId, "Steve", markerLoc, 40, 0.5, List.of(nearViewer, farViewer));
        assertEquals(1, events.size());
        assertTrue(events.get(0).startsWith("UPDATE:Alice:1001:"));
        assertTrue(events.get(0).contains("50%"));

        // Moving out of range triggers destroy
        events.clear();
        when(nearViewer.getLocation()).thenReturn(new Location(endWorld, 150.0, 64.0, 0.0));
        service.spawnOrUpdateMarker(downedId, "Steve", markerLoc, 39, 0.5, List.of(nearViewer));
        assertEquals(List.of("DESTROY:Alice:1000:1001"), events);

        // Re-entering and removing all cleans up cleanly
        events.clear();
        when(nearViewer.getLocation()).thenReturn(new Location(endWorld, 5.0, 64.0, 0.0));
        service.spawnOrUpdateMarker(downedId, "Steve", markerLoc, null, 1.0, List.of(nearViewer));
        assertEquals(1, events.size());
        assertTrue(events.get(0).startsWith("SPAWN:Alice:1000:1001:true:"));
        assertTrue(events.get(0).contains("100%"));

        events.clear();
        service.removeAllMarkers(List.of(nearViewer));
        assertEquals(List.of("DESTROY:Alice:1000:1001"), events);
    }

    private Player mockViewer(String name, Location loc) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.getWorld()).thenReturn(endWorld);
        lenient().when(p.getLocation()).thenReturn(loc);
        lenient().when(p.locale()).thenReturn(Locale.US);
        return p;
    }
}
