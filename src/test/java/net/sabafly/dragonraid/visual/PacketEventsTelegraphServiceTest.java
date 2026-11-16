package net.sabafly.dragonraid.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.visual.PacketEventsTelegraphService.AccentParticle;
import net.sabafly.dragonraid.visual.PacketEventsTelegraphService.TelegraphTransport;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PacketEventsTelegraphServiceTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private List<String> dustPackets;
    private List<String> accentPackets;
    private PacketEventsTelegraphService service;
    private World endWorld;
    private World overworld;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> PacketEventsTelegraphServiceTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        dustPackets = new ArrayList<>();
        accentPackets = new ArrayList<>();
        TelegraphTransport transport = new TelegraphTransport() {
            @Override
            public void sendDustParticle(
                    Player viewer, double x, double y, double z,
                    int red, int green, int blue, float scale) {
                dustPackets.add(viewer.getName() + ":" + red + "," + green + "," + blue + "@" + Math.round(x) + "," + Math.round(z));
            }

            @Override
            public void sendAccentParticle(
                    Player viewer, double x, double y, double z,
                    AccentParticle accent) {
                accentPackets.add(viewer.getName() + ":" + accent.name());
            }
        };

        service = new PacketEventsTelegraphService(
                configManager,
                Logger.getLogger("TelegraphTest"),
                transport
        );
        endWorld = mock(World.class);
        overworld = mock(World.class);
    }

    private Player mockViewer(String name, World world, double x, double y, double z) {
        Player p = mock(Player.class);
        lenient().when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.getLocation()).thenReturn(new Location(world, x, y, z));
        return p;
    }

    @Test
    void renderCircleAndDonutFilterViewersByWorldAndDistance() {
        Player near = mockViewer("Near", endWorld, 10.0, 64.0, 0.0);
        Player far = mockViewer("Far", endWorld, 250.0, 64.0, 0.0);
        Player otherWorld = mockViewer("Other", overworld, 0.0, 64.0, 0.0);
        Location center = new Location(endWorld, 0.0, 64.0, 0.0);

        service.renderCircleAoE(center, 3.5, TelegraphStyle.DANGER, List.of(near, far, otherWorld));

        assertTrue(dustPackets.stream().allMatch(s -> s.startsWith("Near:")));
        assertTrue(dustPackets.size() >= 12);

        dustPackets.clear();
        accentPackets.clear();
        service.renderDonutAoE(center, 10.0, 38.0, TelegraphStyle.DANGER, List.of(near));
        assertTrue(dustPackets.stream().anyMatch(s -> s.contains("45,220,140")));
        assertTrue(dustPackets.stream().anyMatch(s -> s.contains("235,45,45")));
        assertTrue(accentPackets.stream().anyMatch(s -> s.endsWith(":FLAME")));
    }

    @Test
    void renderStackSpreadLineTetherAndBoundaryEmitExpectedParticles() {
        Player viewer = mockViewer("Alice", endWorld, 0.0, 64.0, 0.0);
        Location p1 = new Location(endWorld, 0.0, 64.0, 0.0);
        Location p2 = new Location(endWorld, 10.0, 74.0, 0.0);

        service.renderStackMarker(p1, 4.5, false, List.of(viewer));
        assertEquals(4, accentPackets.stream().filter(s -> s.endsWith(":END_ROD")).count());

        dustPackets.clear();
        accentPackets.clear();
        service.renderStackMarker(p1, 4.5, true, List.of(viewer));
        assertEquals(0, accentPackets.size());
        assertTrue(dustPackets.stream().anyMatch(s -> s.contains("255,195,30")));

        dustPackets.clear();
        accentPackets.clear();
        service.renderSpreadMarker(p1, 4.5, List.of(viewer));
        assertTrue(accentPackets.stream().anyMatch(s -> s.endsWith(":ELECTRIC_SPARK")));

        dustPackets.clear();
        accentPackets.clear();
        service.renderLineAoE(p1, new Location(endWorld, 20.0, 64.0, 0.0), 3.0, TelegraphStyle.WARNING, List.of(viewer));
        assertTrue(dustPackets.size() > 20);

        dustPackets.clear();
        accentPackets.clear();
        service.renderTetherLine(p1, p2, TelegraphStyle.TETHER, List.of(viewer));
        assertTrue(dustPackets.stream().anyMatch(s -> s.contains("195,70,245")));
        assertTrue(accentPackets.stream().anyMatch(s -> s.endsWith(":WITCH")));

        dustPackets.clear();
        accentPackets.clear();
        service.renderArenaBoundary(p1, 30.0, List.of(viewer));
        assertTrue(dustPackets.stream().anyMatch(s -> s.contains("135,25,195")));
    }
}
