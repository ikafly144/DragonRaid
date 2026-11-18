package net.sabafly.dragonraid.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.visual.PacketEventsTelegraphService.AccentParticle;
import net.sabafly.dragonraid.visual.PacketEventsTelegraphService.BlockVisualKind;
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
    private List<BlockEvent> blockSpawns;
    private List<BlockEvent> blockUpdates;
    private List<String> textSpawns;
    private List<String> textUpdates;
    private List<Integer> destroyedEntityIds;
    private PacketEventsTelegraphService service;
    private World endWorld;
    private World overworld;

    private record BlockEvent(String viewerName, int entityId, BlockVisualKind visualKind, double sx, double sz) {
    }

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> PacketEventsTelegraphServiceTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        dustPackets = new ArrayList<>();
        accentPackets = new ArrayList<>();
        blockSpawns = new ArrayList<>();
        blockUpdates = new ArrayList<>();
        textSpawns = new ArrayList<>();
        textUpdates = new ArrayList<>();
        destroyedEntityIds = new ArrayList<>();
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

            @Override
            public void spawnBlockDisplay(
                    Player viewer, int entityId, UUID entityUuid,
                    double x, double y, double z,
                    float sx, float sy, float sz,
                    float tx, float ty, float tz,
                    float qx, float qy, float qz, float qw,
                    BlockVisualKind blockKind, int interpolationTicks) {
                blockSpawns.add(new BlockEvent(viewer.getName(), entityId, blockKind, sx, sz));
            }

            @Override
            public void updateBlockDisplay(
                    Player viewer, int entityId,
                    double x, double y, double z,
                    float sx, float sy, float sz,
                    float tx, float ty, float tz,
                    float qx, float qy, float qz, float qw,
                    BlockVisualKind blockKind, int interpolationTicks) {
                blockUpdates.add(new BlockEvent(viewer.getName(), entityId, blockKind, sx, sz));
            }

            @Override
            public void spawnTextDisplay(
                    Player viewer, int entityId, UUID entityUuid,
                    double x, double y, double z, float yaw, float pitch,
                    Component text, float scale, boolean billboardCenter) {
                textSpawns.add(viewer.getName() + ":" + entityId);
            }

            @Override
            public void updateTextDisplayTransform(
                    Player viewer, int entityId,
                    double x, double y, double z, float yaw, float pitch,
                    Component text, float scale, boolean billboardCenter, int interpolationTicks) {
                textUpdates.add(viewer.getName() + ":" + entityId);
            }

            @Override
            public void destroyEntities(Player viewer, int... entityIds) {
                for (int id : entityIds) {
                    destroyedEntityIds.add(id);
                }
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
    void telegraphsFilterViewersAndUseDisplaysWithoutAnyParticles() {
        Player near = mockViewer("Near", endWorld, 10.0, 64.0, 0.0);
        Player far = mockViewer("Far", endWorld, 250.0, 64.0, 0.0);
        Player otherWorld = mockViewer("Other", overworld, 0.0, 64.0, 0.0);
        Location center = new Location(endWorld, 0.0, 64.0, 0.0);

        service.renderCircleAoE("circle:test", center, 4.0, 0.5, TelegraphStyle.DANGER, List.of(near, far, otherWorld));

        assertTrue(dustPackets.isEmpty(), "Circle telegraph must not emit dust particles");
        assertTrue(accentPackets.isEmpty(), "Circle telegraph must not emit accent particles");
        assertFalse(blockSpawns.isEmpty(), "Circle telegraph should spawn BlockDisplay floor panels");
        assertTrue(blockSpawns.stream().allMatch(b -> "Near".equals(b.viewerName())));
        assertTrue(blockSpawns.stream().anyMatch(b -> b.visualKind() == BlockVisualKind.DANGER_BORDER));
        assertTrue(blockSpawns.stream().anyMatch(b -> b.visualKind() == BlockVisualKind.DANGER_CHARGE_GLASS));

        blockSpawns.clear();
        service.renderDonutAoE("donut:test", center, 8.0, 18.0, 0.4, TelegraphStyle.DANGER, List.of(near));
        service.renderLineAoE("line:test", center, new Location(endWorld, 20.0, 64.0, 0.0), 3.0, 0.6, TelegraphStyle.WARNING, List.of(near));
        service.renderStackMarker("stack:solo", center, 4.5, 0.5, true, List.of(near));
        service.renderStackMarker("stack:party", center, 4.5, 0.5, false, List.of(near));
        service.renderSpreadMarker("spread:test", center, 4.5, 0.5, List.of(near));
        service.renderTetherLine("tether:test", center, new Location(endWorld, 10.0, 74.0, 0.0), TelegraphStyle.TETHER, List.of(near));
        service.renderShieldSafeCone("cone:test", center, 90.0, 20.0, 32.0, List.of(near));
        service.renderArenaBoundary("arena:test", center, 30.0, List.of(near));

        assertTrue(dustPackets.isEmpty(), "No telegraph type may emit dust particles");
        assertTrue(accentPackets.isEmpty(), "No telegraph type may emit accent particles");
        assertFalse(blockSpawns.isEmpty());
        assertFalse(textSpawns.isEmpty(), "Stack markers should spawn TextDisplay indicators");
    }

    @Test
    void retainedTelegraphsUpdateInPlaceAndClearCleanly() {
        Player viewer = mockViewer("Alice", endWorld, 0.0, 64.0, 0.0);
        Location center = new Location(endWorld, 0.0, 64.0, 0.0);

        service.renderCircleAoE("cast:buster", center, 4.0, 0.25, TelegraphStyle.DANGER, List.of(viewer));
        int initialCount = blockSpawns.size();
        assertTrue(initialCount > 0);
        List<Integer> initialIds = blockSpawns.stream().map(BlockEvent::entityId).toList();

        blockSpawns.clear();
        service.renderCircleAoE("cast:buster", center, 4.0, 0.75, TelegraphStyle.DANGER, List.of(viewer));
        assertTrue(blockSpawns.isEmpty(), "Retained telegraph update must reuse entity IDs instead of respawning");
        assertEquals(initialCount, blockUpdates.size());
        assertTrue(blockUpdates.stream().map(BlockEvent::entityId).allMatch(initialIds::contains));

        service.clearTelegraphsByPrefix("cast:", List.of(viewer));
        assertTrue(destroyedEntityIds.containsAll(initialIds));

        destroyedEntityIds.clear();
        blockSpawns.clear();
        service.renderCircleAoE(center, 3.0, TelegraphStyle.WARNING, List.of(viewer));
        List<Integer> ephemeralIds = blockSpawns.stream().map(BlockEvent::entityId).toList();
        assertFalse(ephemeralIds.isEmpty());
        service.clearAllTelegraphs(List.of(viewer));
        assertTrue(destroyedEntityIds.containsAll(ephemeralIds));
    }

    @Test
    void impactAndDetonationEffectsEmitParticlesAtImpactOnly() {
        Player viewer = mockViewer("Alice", endWorld, 0.0, 64.0, 0.0);
        Location center = new Location(endWorld, 0.0, 64.0, 0.0);
        Location end = new Location(endWorld, 18.0, 68.0, 0.0);

        service.renderImpactBurst(center, 4.0, ImpactStyle.EXPLOSION, List.of(viewer));
        assertFalse(dustPackets.isEmpty(), "renderImpactBurst must emit explosion dust particles");
        assertFalse(accentPackets.isEmpty(), "renderImpactBurst must emit accent particles");

        dustPackets.clear();
        accentPackets.clear();
        service.renderLightningStrike(center, List.of(viewer));
        assertFalse(accentPackets.isEmpty(), "renderLightningStrike must emit impact particles");

        dustPackets.clear();
        accentPackets.clear();
        service.renderLaserBeam(center, end, List.of(viewer));
        assertFalse(dustPackets.isEmpty(), "renderLaserBeam must emit beam dust particles");

        dustPackets.clear();
        accentPackets.clear();
        service.renderExpandingShockwave(center, 10.0, List.of(viewer));
        assertFalse(dustPackets.isEmpty(), "renderExpandingShockwave must emit shockwave particles");
    }
}
