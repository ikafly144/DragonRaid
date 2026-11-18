package net.sabafly.dragonraid.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.color.Color;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustColorTransitionData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticlePowerData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.VisualSettings;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * PacketEvents-backed {@link SpatialTelegraphService} separating:
 * <ul>
 *   <li><b>Telegraphs</b>: Particle-free, fullbright FF14-style floor-lit {@code BLOCK_DISPLAY}
 *   zones &amp; {@code TEXT_DISPLAY} symbols with smooth cast-progress charge fills.</li>
 *   <li><b>Impacts</b>: Particle bursts, lightning bolts, shockwaves, and laser detonations
 *   fired strictly when damage/resolution triggers.</li>
 * </ul>
 */
public final class PacketEventsTelegraphService implements SpatialTelegraphService {

    /**
     * Block material presets for Display Entity floor telegraphs, decoupled from PacketEvents
     * static registries for unit testing.
     */
    public enum BlockVisualKind {
        DANGER_GLASS,
        DANGER_CHARGE_GLASS,
        DANGER_BORDER,
        WARNING_GLASS,
        WARNING_CHARGE_GLASS,
        WARNING_BORDER,
        SAFE_GLASS,
        SAFE_BORDER,
        STACK_GLASS,
        STACK_CHARGE_GLASS,
        STACK_BORDER,
        SPREAD_GLASS,
        SPREAD_CHARGE_GLASS,
        SPREAD_BORDER,
        TETHER_BAR,
        VOID_GLASS,
        VOID_BORDER
    }

    /**
     * Accent particle categories decoupled from PacketEvents static registries for unit testing.
     */
    public enum AccentParticle {
        END_ROD,
        FLAME,
        SOUL_FIRE_FLAME,
        WITCH,
        ELECTRIC_SPARK,
        DRAGON_BREATH,
        EXPLOSION,
        LARGE_SMOKE
    }

    /**
     * Packet transport abstraction for sending displays, particles, and virtual entities to viewers.
     */
    public interface TelegraphTransport {
        void sendDustParticle(
                Player viewer,
                double x,
                double y,
                double z,
                int red,
                int green,
                int blue,
                float scale);

        void sendAccentParticle(
                Player viewer,
                double x,
                double y,
                double z,
                AccentParticle accent);

        default void sendDustTransitionParticle(
                Player viewer,
                double x,
                double y,
                double z,
                int fromR,
                int fromG,
                int fromB,
                int toR,
                int toG,
                int toB,
                float scale) {
            sendDustParticle(viewer, x, y, z, fromR, fromG, fromB, scale);
        }

        default void sendLightningBolt(
                Player viewer,
                int entityId,
                UUID entityUuid,
                double x,
                double y,
                double z) {
            sendAccentParticle(viewer, x, y, z, AccentParticle.ELECTRIC_SPARK);
        }

        default void spawnScaledEnderman(
                Player viewer,
                int entityId,
                UUID entityUuid,
                double x,
                double y,
                double z,
                float yaw,
                double scale,
                boolean screaming) {
            sendAccentParticle(viewer, x, y, z, AccentParticle.WITCH);
        }

        default void teleportAndRotateEntity(
                Player viewer,
                int entityId,
                double x,
                double y,
                double z,
                float yaw,
                float pitch) {
        }

        default void spawnTextDisplay(
                Player viewer,
                int entityId,
                UUID entityUuid,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                Component text,
                float scale,
                boolean billboardCenter) {
        }

        default void updateTextDisplayTransform(
                Player viewer,
                int entityId,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                Component text,
                float scale,
                boolean billboardCenter,
                int interpolationTicks) {
            teleportAndRotateEntity(viewer, entityId, x, y, z, yaw, pitch);
        }

        default void spawnBlockDisplay(
                Player viewer,
                int entityId,
                UUID entityUuid,
                double x,
                double y,
                double z,
                float sx,
                float sy,
                float sz,
                float tx,
                float ty,
                float tz,
                float qx,
                float qy,
                float qz,
                float qw,
                BlockVisualKind blockKind,
                int interpolationTicks) {
        }

        default void updateBlockDisplay(
                Player viewer,
                int entityId,
                double x,
                double y,
                double z,
                float sx,
                float sy,
                float sz,
                float tx,
                float ty,
                float tz,
                float qx,
                float qy,
                float qz,
                float qw,
                BlockVisualKind blockKind,
                int interpolationTicks) {
        }

        default void destroyEntities(Player viewer, int... entityIds) {
        }
    }

    private static final double FLOOR_SURFACE_Y = 0.025;
    private static final double FLOOR_CHARGE_Y = 0.04;
    private static final double FLOOR_BORDER_Y = 0.05;
    private static final int CIRCLE_FILL_STRIPS = 4;
    private static final int CIRCLE_RIM_SEGMENTS = 20;
    private static final int DONUT_SEGMENTS = 16;
    private static final int CONE_WEDGES = 5;
    private static final int BOUNDARY_SEGMENTS = 24;
    private static final int DISPLAY_INTERPOLATION_TICKS = 2;
    private static final int FULLBRIGHT_LIGHT = (15 << 4) | (15 << 20);
    private static final byte ENTITY_FLAG_GLOWING = 0x40;
    private static final byte BILLBOARD_FIXED = 0;
    private static final byte BILLBOARD_CENTER = 3;
    private static final long EPHEMERAL_TTL_MS = 1_200L;
    private static final AtomicInteger FALLBACK_ENTITY_ID = new AtomicInteger(1_800_000);

    private final ConfigManager configManager;
    private final Logger logger;
    private final TelegraphTransport transport;
    private final IntSupplier entityIdGenerator;
    private final Map<String, TelegraphGroup> activeGroups = new ConcurrentHashMap<>();

    public PacketEventsTelegraphService(ConfigManager configManager, Logger logger) {
        this(
                configManager,
                logger,
                new PacketEventsTelegraphTransport(),
                PacketEventsTelegraphService::nextEntityIdSafe
        );
    }

    PacketEventsTelegraphService(
            ConfigManager configManager,
            Logger logger,
            TelegraphTransport transport) {
        this(configManager, logger, transport, PacketEventsTelegraphService::nextEntityIdSafe);
    }

    PacketEventsTelegraphService(
            ConfigManager configManager,
            Logger logger,
            TelegraphTransport transport,
            IntSupplier entityIdGenerator) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.entityIdGenerator = Objects.requireNonNull(entityIdGenerator, "entityIdGenerator");
    }

    private static int nextEntityIdSafe() {
        try {
            return SpigotReflectionUtil.generateEntityId();
        } catch (Throwable ignored) {
            return FALLBACK_ENTITY_ID.incrementAndGet();
        }
    }

    @Override
    public void renderCircleAoE(
            Location center,
            double radius,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        String key = ephemeralKey("circle", center);
        renderCircleAoE(key, center, radius, 1.0, style, viewers);
    }

    @Override
    public void renderCircleAoE(
            String telegraphKey,
            Location center,
            double radius,
            double castProgress,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(telegraphKey, "telegraphKey");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        if (radius <= 0.0 || center.getWorld() == null) {
            return;
        }
        double progress = clamp01(castProgress);
        List<BlockPiece> blocks = buildCirclePieces(center, radius, progress, style);
        syncDisplayGroup(telegraphKey, center, radius, blocks, List.of(), viewers);
    }

    @Override
    public void renderDonutAoE(
            Location center,
            double innerRadius,
            double outerRadius,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        String key = ephemeralKey("donut", center);
        renderDonutAoE(key, center, innerRadius, outerRadius, 1.0, style, viewers);
    }

    @Override
    public void renderDonutAoE(
            String telegraphKey,
            Location center,
            double innerRadius,
            double outerRadius,
            double castProgress,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(telegraphKey, "telegraphKey");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        if (outerRadius <= 0.0 || center.getWorld() == null) {
            return;
        }
        double progress = clamp01(castProgress);
        List<BlockPiece> blocks = buildDonutPieces(center, innerRadius, outerRadius, progress, style);
        syncDisplayGroup(telegraphKey, center, outerRadius, blocks, List.of(), viewers);
    }

    @Override
    public void renderLineAoE(
            Location start,
            Location end,
            double width,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        String key = ephemeralKey("line", start);
        renderLineAoE(key, start, end, width, 1.0, style, viewers);
    }

    @Override
    public void renderLineAoE(
            String telegraphKey,
            Location start,
            Location end,
            double width,
            double castProgress,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(telegraphKey, "telegraphKey");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        World world = start.getWorld();
        if (world == null || !world.equals(end.getWorld())) {
            return;
        }
        double halfLength = start.distance(end) * 0.5;
        if (halfLength < 1e-4) {
            return;
        }
        Location midpoint = new Location(
                world,
                (start.getX() + end.getX()) * 0.5,
                Math.min(start.getY(), end.getY()),
                (start.getZ() + end.getZ()) * 0.5
        );
        double progress = clamp01(castProgress);
        List<BlockPiece> blocks = buildLinePieces(start, end, Math.max(0.4, width), progress, style);
        if (blocks.isEmpty()) {
            return;
        }
        syncDisplayGroup(telegraphKey, midpoint, halfLength + width, blocks, List.of(), viewers);
    }

    @Override
    public void renderStackMarker(
            Location center,
            double radius,
            boolean soloMode,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        String key = ephemeralKey("stack", center);
        renderStackMarker(key, center, radius, 1.0, soloMode, viewers);
    }

    @Override
    public void renderStackMarker(
            String telegraphKey,
            Location center,
            double radius,
            double castProgress,
            boolean soloMode,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(telegraphKey, "telegraphKey");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(viewers, "viewers");
        if (radius <= 0.0 || center.getWorld() == null) {
            return;
        }
        double progress = clamp01(castProgress);
        TelegraphStyle ringStyle = soloMode ? TelegraphStyle.WARNING : TelegraphStyle.STACK;
        List<BlockPiece> blocks = buildCirclePieces(center, radius, progress, ringStyle);
        List<TextPiece> texts = soloMode ? List.of() : buildStackChevronPieces(center, radius, progress);
        syncDisplayGroup(telegraphKey, center, radius, blocks, texts, viewers);
    }

    @Override
    public void renderSpreadMarker(
            Location center,
            double radius,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        String key = ephemeralKey("spread", center);
        renderSpreadMarker(key, center, radius, 1.0, viewers);
    }

    @Override
    public void renderSpreadMarker(
            String telegraphKey,
            Location center,
            double radius,
            double castProgress,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(telegraphKey, "telegraphKey");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(viewers, "viewers");
        if (radius <= 0.0 || center.getWorld() == null) {
            return;
        }
        double progress = clamp01(castProgress);
        List<BlockPiece> blocks = buildCirclePieces(center, radius, progress, TelegraphStyle.SPREAD);
        syncDisplayGroup(telegraphKey, center, radius, blocks, List.of(), viewers);
    }

    @Override
    public void renderTetherLine(
            Location from,
            Location to,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        String key = ephemeralKey("tether", from);
        renderTetherLine(key, from, to, style, viewers);
    }

    @Override
    public void renderTetherLine(
            String telegraphKey,
            Location from,
            Location to,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(telegraphKey, "telegraphKey");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return;
        }
        double dist = from.distance(to);
        if (dist < 1e-4) {
            return;
        }
        Location midpoint = new Location(
                world,
                (from.getX() + to.getX()) * 0.5,
                (from.getY() + to.getY()) * 0.5,
                (from.getZ() + to.getZ()) * 0.5
        );
        List<BlockPiece> blocks = buildTether3dPieces(from, to, style);
        syncDisplayGroup(telegraphKey, midpoint, dist * 0.5, blocks, List.of(), viewers);
    }

    @Override
    public void renderArenaBoundary(
            Location center,
            double safeRadius,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        renderArenaBoundary(ephemeralKey("boundary", center), center, safeRadius, viewers);
    }

    @Override
    public void renderArenaBoundary(
            String telegraphKey,
            Location center,
            double safeRadius,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(telegraphKey, "telegraphKey");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(viewers, "viewers");
        if (safeRadius <= 0.0 || center.getWorld() == null) {
            return;
        }
        List<BlockPiece> blocks = buildArenaBoundaryPieces(center, safeRadius);
        syncDisplayGroup(telegraphKey, center, safeRadius, blocks, List.of(), viewers);
    }

    @Override
    public void renderDragonAuraTrail(
            Location dragonLocation,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(dragonLocation, "dragonLocation");
        Objects.requireNonNull(viewers, "viewers");
        List<Player> eligible = filterViewers(dragonLocation, viewers, 16.0);
        if (eligible.isEmpty()) {
            return;
        }
        double x = dragonLocation.getX();
        double y = dragonLocation.getY() + 2.2;
        double z = dragonLocation.getZ();
        for (Player viewer : eligible) {
            try {
                // True Ending dust_color_transition: magenta [255, 0, 248] -> deep blue [0, 26, 255]
                transport.sendDustTransitionParticle(viewer, x, y, z, 255, 0, 248, 0, 26, 255, 3.0f);
                transport.sendAccentParticle(viewer, x, y, z, AccentParticle.DRAGON_BREATH);
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to send dragon aura trail to " + viewer.getName(), ex);
            }
        }
    }

    @Override
    public void renderExpandingShockwave(
            Location origin,
            double currentRadius,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(viewers, "viewers");
        if (currentRadius <= 0.0 || origin.getWorld() == null) {
            return;
        }
        List<Player> eligible = filterViewers(origin, viewers, currentRadius);
        if (eligible.isEmpty()) {
            return;
        }
        int points = (int) Math.max(18, Math.min(72, Math.round((2.0 * Math.PI * currentRadius) / 0.75)));
        double y = origin.getY() + 0.35;
        for (int i = 0; i < points; i++) {
            double angle = (2.0 * Math.PI * i) / points;
            double px = origin.getX() + Math.cos(angle) * currentRadius;
            double pz = origin.getZ() + Math.sin(angle) * currentRadius;
            for (Player viewer : eligible) {
                transport.sendDustTransitionParticle(viewer, px, y, pz, 255, 20, 248, 80, 0, 220, 1.8f);
                if (i % 2 == 0) {
                    transport.sendAccentParticle(viewer, px, y + 0.15, pz, AccentParticle.ELECTRIC_SPARK);
                }
                if (i % 4 == 0) {
                    transport.sendAccentParticle(viewer, px, y + 0.25, pz, AccentParticle.DRAGON_BREATH);
                }
            }
        }
    }

    @Override
    public void renderLaserBeam(
            Location origin,
            Location impactPoint,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(impactPoint, "impactPoint");
        Objects.requireNonNull(viewers, "viewers");
        World world = origin.getWorld();
        if (world == null || !world.equals(impactPoint.getWorld())) {
            return;
        }
        List<Player> eligible = filterViewers(impactPoint, viewers, origin.distance(impactPoint));
        if (eligible.isEmpty()) {
            return;
        }
        double dx = impactPoint.getX() - origin.getX();
        double dy = impactPoint.getY() - origin.getY();
        double dz = impactPoint.getZ() - origin.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(4, Math.min(80, (int) Math.ceil(len / 0.6)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = origin.getX() + dx * t;
            double y = origin.getY() + dy * t;
            double z = origin.getZ() + dz * t;
            for (Player viewer : eligible) {
                transport.sendDustTransitionParticle(viewer, x, y, z, 255, 20, 248, 255, 255, 255, 2.2f);
                if (i % 2 == 0) {
                    transport.sendAccentParticle(viewer, x, y, z, AccentParticle.ELECTRIC_SPARK);
                }
                if (i % 3 == 0) {
                    transport.sendAccentParticle(viewer, x, y, z, AccentParticle.END_ROD);
                }
            }
        }
        // Impact burst
        for (Player viewer : eligible) {
            transport.sendAccentParticle(
                    viewer,
                    impactPoint.getX(),
                    impactPoint.getY() + 0.5,
                    impactPoint.getZ(),
                    AccentParticle.EXPLOSION
            );
            transport.sendAccentParticle(
                    viewer,
                    impactPoint.getX(),
                    impactPoint.getY() + 0.4,
                    impactPoint.getZ(),
                    AccentParticle.DRAGON_BREATH
            );
        }
    }

    @Override
    public void renderShieldSafeCone(
            Location blockerLocation,
            double directionYawDegrees,
            double coneLength,
            double halfAngleDegrees,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(blockerLocation, "blockerLocation");
        renderShieldSafeCone(
                ephemeralKey("cone", blockerLocation),
                blockerLocation,
                directionYawDegrees,
                coneLength,
                halfAngleDegrees,
                viewers
        );
    }

    @Override
    public void renderShieldSafeCone(
            String telegraphKey,
            Location blockerLocation,
            double directionYawDegrees,
            double coneLength,
            double halfAngleDegrees,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(telegraphKey, "telegraphKey");
        Objects.requireNonNull(blockerLocation, "blockerLocation");
        Objects.requireNonNull(viewers, "viewers");
        if (blockerLocation.getWorld() == null || coneLength <= 0.0) {
            return;
        }
        List<BlockPiece> blocks = buildConePieces(blockerLocation, directionYawDegrees, coneLength, halfAngleDegrees);
        syncDisplayGroup(telegraphKey, blockerLocation, coneLength, blocks, List.of(), viewers);
    }

    @Override
    public void renderImpactBurst(
            Location center,
            double radius,
            ImpactStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        if (center.getWorld() == null) {
            return;
        }
        double effectiveRadius = Math.max(1.0, radius);
        List<Player> eligible = filterViewers(center, viewers, effectiveRadius);
        if (eligible.isEmpty()) {
            return;
        }
        double y = center.getY() + 0.4;
        AccentParticle primaryAccent = switch (style) {
            case EXPLOSION, DIVE_SLAM -> AccentParticle.EXPLOSION;
            case STACK_IMPACT -> AccentParticle.END_ROD;
            case SPREAD_BURST -> AccentParticle.FLAME;
            case VOID_ERUPTION -> AccentParticle.DRAGON_BREATH;
            case LASER_SCORCH -> AccentParticle.ELECTRIC_SPARK;
        };
        AccentParticle secondaryAccent = switch (style) {
            case EXPLOSION, SPREAD_BURST -> AccentParticle.LARGE_SMOKE;
            case DIVE_SLAM, STACK_IMPACT -> AccentParticle.FLAME;
            case VOID_ERUPTION -> AccentParticle.WITCH;
            case LASER_SCORCH -> AccentParticle.EXPLOSION;
        };
        int[] colors = switch (style) {
            case EXPLOSION, SPREAD_BURST -> new int[]{255, 65, 25, 255, 190, 40};
            case DIVE_SLAM -> new int[]{255, 40, 220, 90, 0, 210};
            case STACK_IMPACT -> new int[]{255, 230, 60, 255, 130, 20};
            case VOID_ERUPTION -> new int[]{190, 40, 255, 60, 0, 140};
            case LASER_SCORCH -> new int[]{255, 40, 240, 255, 255, 255};
        };
        int ringPoints = (int) Math.max(8, Math.min(28, Math.round((2.0 * Math.PI * effectiveRadius) / 1.1)));
        for (Player viewer : eligible) {
            transport.sendAccentParticle(viewer, center.getX(), y + 0.3, center.getZ(), primaryAccent);
            transport.sendAccentParticle(viewer, center.getX(), y + 0.8, center.getZ(), secondaryAccent);
            for (int i = 0; i < ringPoints; i++) {
                double angle = (2.0 * Math.PI * i) / ringPoints;
                double px = center.getX() + Math.cos(angle) * effectiveRadius * 0.85;
                double pz = center.getZ() + Math.sin(angle) * effectiveRadius * 0.85;
                transport.sendDustTransitionParticle(
                        viewer,
                        px,
                        y,
                        pz,
                        colors[0], colors[1], colors[2],
                        colors[3], colors[4], colors[5],
                        2.1f
                );
                if (i % 3 == 0) {
                    transport.sendAccentParticle(viewer, px, y + 0.25, pz, secondaryAccent);
                }
            }
        }
    }

    @Override
    public void clearTelegraph(String telegraphKey, Collection<? extends Player> viewers) {
        if (telegraphKey == null) {
            return;
        }
        TelegraphGroup group = activeGroups.remove(telegraphKey);
        if (group != null) {
            destroyGroup(group, viewers);
        }
    }

    @Override
    public void clearTelegraphsByPrefix(String keyPrefix, Collection<? extends Player> viewers) {
        if (keyPrefix == null || keyPrefix.isEmpty()) {
            return;
        }
        for (String key : List.copyOf(activeGroups.keySet())) {
            if (key.startsWith(keyPrefix)) {
                clearTelegraph(key, viewers);
            }
        }
    }

    @Override
    public void clearAllTelegraphs(Collection<? extends Player> viewers) {
        for (String key : List.copyOf(activeGroups.keySet())) {
            clearTelegraph(key, viewers);
        }
    }

    @Override
    public void renderLightningStrike(
            Location strikeLocation,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(strikeLocation, "strikeLocation");
        Objects.requireNonNull(viewers, "viewers");
        List<Player> eligible = filterViewers(strikeLocation, viewers, 16.0);
        if (eligible.isEmpty()) {
            return;
        }
        int entityId = entityIdGenerator.getAsInt();
        UUID uuid = UUID.randomUUID();
        double x = strikeLocation.getX();
        double y = strikeLocation.getY();
        double z = strikeLocation.getZ();
        for (Player viewer : eligible) {
            try {
                transport.sendLightningBolt(viewer, entityId, uuid, x, y, z);
                transport.sendAccentParticle(viewer, x, y + 0.5, z, AccentParticle.ELECTRIC_SPARK);
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to send lightning packet to " + viewer.getName(), ex);
            }
        }
    }

    @Override
    public int spawnVirtualScaledEnderman(
            Location baseLocation,
            double scale,
            float yawDegrees,
            boolean screaming,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(baseLocation, "baseLocation");
        Objects.requireNonNull(viewers, "viewers");
        int entityId = entityIdGenerator.getAsInt();
        List<Player> eligible = filterViewers(baseLocation, viewers, 24.0);
        if (eligible.isEmpty()) {
            return entityId;
        }
        UUID uuid = UUID.randomUUID();
        for (Player viewer : eligible) {
            try {
                transport.spawnScaledEnderman(
                        viewer,
                        entityId,
                        uuid,
                        baseLocation.getX(),
                        baseLocation.getY(),
                        baseLocation.getZ(),
                        yawDegrees,
                        scale,
                        screaming
                );
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to spawn virtual scaled Enderman for " + viewer.getName(), ex);
            }
        }
        return entityId;
    }

    @Override
    public void moveAndRotateVirtualEnderman(
            int entityId,
            Location location,
            float yawDegrees,
            Collection<? extends Player> viewers) {
        if (entityId < 0) {
            return;
        }
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(viewers, "viewers");
        List<Player> eligible = filterViewers(location, viewers, 24.0);
        for (Player viewer : eligible) {
            try {
                transport.teleportAndRotateEntity(
                        viewer,
                        entityId,
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        yawDegrees,
                        0.0f
                );
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to rotate virtual Enderman for " + viewer.getName(), ex);
            }
        }
    }

    @Override
    public int spawnDirectionalArrowDisplay(
            Location location,
            float yawDegrees,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        int entityId = entityIdGenerator.getAsInt();
        List<Player> eligible = filterViewers(location, viewers, 20.0);
        if (eligible.isEmpty()) {
            return entityId;
        }
        UUID uuid = UUID.randomUUID();
        NamedTextColor textColor = switch (style) {
            case DANGER -> NamedTextColor.RED;
            case SAFE -> NamedTextColor.AQUA;
            case WARNING, STACK -> NamedTextColor.GOLD;
            default -> NamedTextColor.LIGHT_PURPLE;
        };
        Component arrowText = Component.text("⋙ ⋙ ⋙", textColor, TextDecoration.BOLD);
        for (Player viewer : eligible) {
            try {
                transport.spawnTextDisplay(
                        viewer,
                        entityId,
                        uuid,
                        location.getX(),
                        location.getY() + 0.35,
                        location.getZ(),
                        yawDegrees,
                        -90.0f,
                        arrowText,
                        2.2f,
                        false
                );
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to spawn directional arrow display for " + viewer.getName(), ex);
            }
        }
        return entityId;
    }

    @Override
    public int spawnOverheadDiceDisplay(
            Location abovePlayer,
            int diceNumber,
            boolean oddGroup,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(abovePlayer, "abovePlayer");
        Objects.requireNonNull(viewers, "viewers");
        int entityId = entityIdGenerator.getAsInt();
        List<Player> eligible = filterViewers(abovePlayer, viewers, 24.0);
        if (eligible.isEmpty()) {
            return entityId;
        }
        UUID uuid = UUID.randomUUID();
        int clamped = Math.max(1, Math.min(8, diceNumber));
        String pips = "✦".repeat(clamped);
        NamedTextColor color = oddGroup ? NamedTextColor.AQUA : NamedTextColor.GOLD;
        Component diceComp = Component.text("【 " + pips + " 】", color, TextDecoration.BOLD);
        for (Player viewer : eligible) {
            try {
                transport.spawnTextDisplay(
                        viewer,
                        entityId,
                        uuid,
                        abovePlayer.getX(),
                        abovePlayer.getY() + 2.6,
                        abovePlayer.getZ(),
                        0.0f,
                        0.0f,
                        diceComp,
                        1.8f,
                        true
                );
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to spawn dice display for " + viewer.getName(), ex);
            }
        }
        return entityId;
    }

    @Override
    public void destroyVirtualEntities(
            Collection<? extends Player> viewers,
            int... entityIds) {
        if (entityIds == null || entityIds.length == 0) {
            return;
        }
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) {
                try {
                    transport.destroyEntities(viewer, entityIds);
                } catch (RuntimeException ex) {
                    logger.log(Level.FINE, "Failed to destroy virtual entities for " + viewer.getName(), ex);
                }
            }
        }
    }

    private void syncDisplayGroup(
            String key,
            Location origin,
            double extraRadius,
            List<BlockPiece> blocks,
            List<TextPiece> texts,
            Collection<? extends Player> viewers) {
        purgeExpiredEphemeralGroups(viewers);
        List<Player> eligible = filterViewers(origin, viewers, extraRadius);
        Map<UUID, Player> eligibleById = new HashMap<>(eligible.size());
        for (Player p : eligible) {
            if (p.getUniqueId() != null) {
                eligibleById.put(p.getUniqueId(), p);
            }
        }

        TelegraphGroup group = activeGroups.compute(key, (k, existing) -> {
            if (existing == null
                    || existing.destroyed
                    || existing.blockIds.length != blocks.size()
                    || existing.textIds.length != texts.size()) {
                if (existing != null) {
                    destroyGroup(existing, viewers);
                }
                int[] bIds = new int[blocks.size()];
                UUID[] bUuids = new UUID[blocks.size()];
                for (int i = 0; i < blocks.size(); i++) {
                    bIds[i] = entityIdGenerator.getAsInt();
                    bUuids[i] = UUID.randomUUID();
                }
                int[] tIds = new int[texts.size()];
                UUID[] tUuids = new UUID[texts.size()];
                for (int i = 0; i < texts.size(); i++) {
                    tIds[i] = entityIdGenerator.getAsInt();
                    tUuids[i] = UUID.randomUUID();
                }
                return new TelegraphGroup(bIds, bUuids, tIds, tUuids, key.startsWith("anon:"));
            }
            return existing;
        });

        synchronized (group) {
            if (group.destroyed) {
                return;
            }
            group.lastTouchedMillis = System.currentTimeMillis();

            // Destroy for viewers that left range/world or whose Player instance changed
            Iterator<Map.Entry<UUID, WeakReference<Player>>> it = group.seenBy.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, WeakReference<Player>> entry = it.next();
                UUID viewerId = entry.getKey();
                Player prevPlayer = entry.getValue().get();
                Player current = eligibleById.get(viewerId);
                if (current == null || prevPlayer != current) {
                    if (prevPlayer != null && prevPlayer.isOnline()) {
                        sendDestroyForGroup(prevPlayer, group);
                    }
                    it.remove();
                }
            }

            // Spawn for newly eligible viewers or update in-place for existing viewers
            for (Player viewer : eligible) {
                UUID viewerId = viewer.getUniqueId();
                boolean alreadySeen = viewerId != null && group.seenBy.containsKey(viewerId);
                try {
                    if (!alreadySeen) {
                        for (int i = 0; i < blocks.size(); i++) {
                            BlockPiece bp = blocks.get(i);
                            transport.spawnBlockDisplay(
                                    viewer, group.blockIds[i], group.blockUuids[i],
                                    bp.x, bp.y, bp.z,
                                    bp.sx, bp.sy, bp.sz,
                                    bp.tx, bp.ty, bp.tz,
                                    bp.qx, bp.qy, bp.qz, bp.qw,
                                    bp.kind, DISPLAY_INTERPOLATION_TICKS
                            );
                        }
                        for (int i = 0; i < texts.size(); i++) {
                            TextPiece tp = texts.get(i);
                            transport.spawnTextDisplay(
                                    viewer, group.textIds[i], group.textUuids[i],
                                    tp.x, tp.y, tp.z,
                                    tp.yaw, tp.pitch, tp.text, tp.scale, tp.billboardCenter
                            );
                        }
                        if (viewerId != null) {
                            group.seenBy.put(viewerId, new WeakReference<>(viewer));
                        }
                    } else {
                        for (int i = 0; i < blocks.size(); i++) {
                            BlockPiece bp = blocks.get(i);
                            transport.updateBlockDisplay(
                                    viewer, group.blockIds[i],
                                    bp.x, bp.y, bp.z,
                                    bp.sx, bp.sy, bp.sz,
                                    bp.tx, bp.ty, bp.tz,
                                    bp.qx, bp.qy, bp.qz, bp.qw,
                                    bp.kind, DISPLAY_INTERPOLATION_TICKS
                            );
                        }
                        for (int i = 0; i < texts.size(); i++) {
                            TextPiece tp = texts.get(i);
                            transport.updateTextDisplayTransform(
                                    viewer, group.textIds[i],
                                    tp.x, tp.y, tp.z,
                                    tp.yaw, tp.pitch, tp.text, tp.scale, tp.billboardCenter,
                                    DISPLAY_INTERPOLATION_TICKS
                            );
                        }
                    }
                } catch (RuntimeException ex) {
                    logger.log(Level.FINE, "Failed to sync floor telegraph display for " + viewer.getName(), ex);
                }
            }
        }
    }

    private void purgeExpiredEphemeralGroups(Collection<? extends Player> viewers) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TelegraphGroup> entry : List.copyOf(activeGroups.entrySet())) {
            TelegraphGroup g = entry.getValue();
            if (g.ephemeral && (now - g.lastTouchedMillis) > EPHEMERAL_TTL_MS) {
                if (activeGroups.remove(entry.getKey(), g)) {
                    destroyGroup(g, viewers);
                }
            }
        }
    }

    private void destroyGroup(TelegraphGroup group, Collection<? extends Player> viewers) {
        synchronized (group) {
            if (group.destroyed) {
                return;
            }
            group.destroyed = true;
            for (WeakReference<Player> ref : group.seenBy.values()) {
                Player viewer = ref.get();
                if (viewer != null && viewer.isOnline()) {
                    sendDestroyForGroup(viewer, group);
                }
            }
            group.seenBy.clear();
            for (Player viewer : viewers) {
                if (viewer != null && viewer.isOnline()) {
                    sendDestroyForGroup(viewer, group);
                }
            }
        }
    }

    private void sendDestroyForGroup(Player viewer, TelegraphGroup group) {
        int total = group.blockIds.length + group.textIds.length;
        if (total == 0) {
            return;
        }
        int[] ids = new int[total];
        System.arraycopy(group.blockIds, 0, ids, 0, group.blockIds.length);
        System.arraycopy(group.textIds, 0, ids, group.blockIds.length, group.textIds.length);
        try {
            transport.destroyEntities(viewer, ids);
        } catch (RuntimeException ex) {
            logger.log(Level.FINE, "Failed to destroy telegraph group for " + viewer.getName(), ex);
        }
    }

    private List<BlockPiece> buildCirclePieces(
            Location center,
            double radius,
            double castProgress,
            TelegraphStyle style) {
        List<BlockPiece> out = new ArrayList<>(CIRCLE_FILL_STRIPS * 2 + CIRCLE_RIM_SEGMENTS);
        double floorY = center.getY();
        BlockVisualKind outerGlass = glassKindFor(style);
        BlockVisualKind chargeGlass = chargeGlassKindFor(style);
        BlockVisualKind border = borderKindFor(style);

        addFilledDiskPieces(center.getX(), floorY + FLOOR_SURFACE_Y, center.getZ(), radius, 0.025, outerGlass, out);
        double chargeRadius = Math.max(0.25, radius * Math.max(0.08, castProgress));
        addFilledDiskPieces(center.getX(), floorY + FLOOR_CHARGE_Y, center.getZ(), chargeRadius, 0.025, chargeGlass, out);
        addRingBorderPieces(center.getX(), floorY + FLOOR_BORDER_Y, center.getZ(), radius, CIRCLE_RIM_SEGMENTS, 0.16, 0.055, border, out);
        return out;
    }

    private List<BlockPiece> buildDonutPieces(
            Location center,
            double innerRadius,
            double outerRadius,
            double castProgress,
            TelegraphStyle style) {
        double inner = Math.max(0.0, Math.min(innerRadius, outerRadius - 0.5));
        double width = Math.max(0.5, outerRadius - inner);
        double midR = inner + width * 0.5;
        double floorY = center.getY();
        BlockVisualKind outerGlass = glassKindFor(style);
        BlockVisualKind chargeGlass = chargeGlassKindFor(style);
        BlockVisualKind border = borderKindFor(style);

        List<BlockPiece> out = new ArrayList<>(DONUT_SEGMENTS * 4);
        double sectorAngle = (2.0 * Math.PI) / DONUT_SEGMENTS;
        double chord = 2.0 * midR * Math.sin(sectorAngle * 0.5) + 0.1;
        double chargeWidth = Math.max(0.3, width * Math.max(0.08, castProgress));
        double chargeMidR = inner + chargeWidth * 0.5;
        double chargeChord = 2.0 * chargeMidR * Math.sin(sectorAngle * 0.5) + 0.1;

        for (int i = 0; i < DONUT_SEGMENTS; i++) {
            double theta = i * sectorAngle;
            double cos = Math.cos(theta);
            double sin = Math.sin(theta);
            out.add(horizontalRect(
                    center.getX() + cos * midR, floorY + FLOOR_SURFACE_Y, center.getZ() + sin * midR,
                    width, 0.025, chord, theta, outerGlass
            ));
            out.add(horizontalRect(
                    center.getX() + cos * chargeMidR, floorY + FLOOR_CHARGE_Y, center.getZ() + sin * chargeMidR,
                    chargeWidth, 0.025, chargeChord, theta, chargeGlass
            ));
        }
        if (inner > 0.25) {
            addRingBorderPieces(center.getX(), floorY + FLOOR_BORDER_Y, center.getZ(), inner, DONUT_SEGMENTS, 0.16, 0.055, BlockVisualKind.SAFE_BORDER, out);
        }
        addRingBorderPieces(center.getX(), floorY + FLOOR_BORDER_Y, center.getZ(), outerRadius, DONUT_SEGMENTS, 0.18, 0.055, border, out);
        return out;
    }

    private List<BlockPiece> buildLinePieces(
            Location start,
            Location end,
            double width,
            double castProgress,
            TelegraphStyle style) {
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double horizLen = Math.hypot(dx, dz);
        if (horizLen < 1e-4) {
            return List.of();
        }
        double groundY = Math.min(start.getY(), end.getY());
        double yawRad = Math.atan2(-dx, dz);
        double midX = (start.getX() + end.getX()) * 0.5;
        double midZ = (start.getZ() + end.getZ()) * 0.5;

        double nx = -Math.sin(yawRad);
        double nz = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        double halfW = width * 0.5;

        BlockVisualKind glass = glassKindFor(style);
        BlockVisualKind chargeGlass = chargeGlassKindFor(style);
        BlockVisualKind border = borderKindFor(style);

        List<BlockPiece> out = new ArrayList<>(4);
        out.add(horizontalRect(midX, groundY + FLOOR_SURFACE_Y, midZ, width, 0.025, horizLen, yawRad, glass));

        double chargeLen = Math.max(0.4, horizLen * Math.max(0.08, castProgress));
        double chargeMidX = start.getX() + nx * (chargeLen * 0.5);
        double chargeMidZ = start.getZ() + nz * (chargeLen * 0.5);
        out.add(horizontalRect(chargeMidX, groundY + FLOOR_CHARGE_Y, chargeMidZ, width * 0.92, 0.025, chargeLen, yawRad, chargeGlass));

        out.add(horizontalRect(midX + rightX * halfW, groundY + FLOOR_BORDER_Y, midZ + rightZ * halfW, 0.14, 0.055, horizLen, yawRad, border));
        out.add(horizontalRect(midX - rightX * halfW, groundY + FLOOR_BORDER_Y, midZ - rightZ * halfW, 0.14, 0.055, horizLen, yawRad, border));
        return out;
    }

    private List<BlockPiece> buildConePieces(
            Location origin,
            double directionYawDegrees,
            double coneLength,
            double halfAngleDegrees) {
        double groundY = origin.getY();
        double centerRad = Math.toRadians(directionYawDegrees);
        double halfRad = Math.toRadians(Math.max(5.0, Math.min(85.0, halfAngleDegrees)));
        List<BlockPiece> out = new ArrayList<>(CONE_WEDGES + 2);

        double stepRad = (halfRad * 2.0) / CONE_WEDGES;
        double wedgeMid = coneLength * 0.5;
        double wedgeW = 2.0 * wedgeMid * Math.tan(stepRad * 0.55);
        for (int i = 0; i < CONE_WEDGES; i++) {
            double yawRad = (centerRad - halfRad) + (i + 0.5) * stepRad;
            double mx = origin.getX() - Math.sin(yawRad) * wedgeMid;
            double mz = origin.getZ() + Math.cos(yawRad) * wedgeMid;
            out.add(horizontalRect(mx, groundY + FLOOR_SURFACE_Y, mz, Math.max(0.4, wedgeW), 0.03, coneLength, yawRad, BlockVisualKind.SAFE_GLASS));
        }
        double leftRad = centerRad - halfRad;
        double rightRad = centerRad + halfRad;
        out.add(horizontalRect(origin.getX() - Math.sin(leftRad) * wedgeMid, groundY + FLOOR_BORDER_Y, origin.getZ() + Math.cos(leftRad) * wedgeMid, 0.16, 0.06, coneLength, leftRad, BlockVisualKind.SAFE_BORDER));
        out.add(horizontalRect(origin.getX() - Math.sin(rightRad) * wedgeMid, groundY + FLOOR_BORDER_Y, origin.getZ() + Math.cos(rightRad) * wedgeMid, 0.16, 0.06, coneLength, rightRad, BlockVisualKind.SAFE_BORDER));
        return out;
    }

    private List<TextPiece> buildStackChevronPieces(
            Location center,
            double radius,
            double castProgress) {
        double y = center.getY() + 0.45;
        double dist = Math.max(0.9, radius * (0.95 - 0.55 * castProgress));
        List<TextPiece> texts = new ArrayList<>(4);
        Component arrow = Component.text("⋙", NamedTextColor.GOLD, TextDecoration.BOLD);
        for (int i = 0; i < 4; i++) {
            float yaw = i * 90.0f;
            double rad = Math.toRadians(yaw);
            // Position outward at (yaw + 180°) facing inward toward center
            double px = center.getX() + Math.sin(rad) * dist;
            double pz = center.getZ() - Math.cos(rad) * dist;
            texts.add(new TextPiece(px, y, pz, yaw, 0.0f, arrow, 2.0f, false));
        }
        return texts;
    }

    private List<BlockPiece> buildTether3dPieces(Location from, Location to, TelegraphStyle style) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) {
            return List.of();
        }
        double ux = dx / len;
        double uy = dy / len;
        double uz = dz / len;
        // Shortest-arc quaternion mapping (0, 0, 1) -> (ux, uy, uz)
        float qx;
        float qy;
        float qz;
        float qw;
        if (uz < -0.9999) {
            qx = 0.0f;
            qy = 1.0f;
            qz = 0.0f;
            qw = 0.0f;
        } else {
            double rx = -uy;
            double ry = ux;
            double rw = 1.0 + uz;
            double qLen = Math.sqrt(rx * rx + ry * ry + rw * rw);
            qx = (float) (rx / qLen);
            qy = (float) (ry / qLen);
            qz = 0.0f;
            qw = (float) (rw / qLen);
        }
        float thickness = 0.14f;
        float halfT = -thickness * 0.5f;
        float halfL = (float) (-len * 0.5);
        // Rotate local centering offset (halfT, halfT, halfL) by quaternion (qx, qy, qz, qw)
        float[] rotated = rotateVecByQuat(halfT, halfT, halfL, qx, qy, qz, qw);

        BlockVisualKind kind = switch (style) {
            case DANGER -> BlockVisualKind.DANGER_BORDER;
            case SAFE -> BlockVisualKind.SAFE_BORDER;
            case WARNING -> BlockVisualKind.WARNING_BORDER;
            case STACK -> BlockVisualKind.STACK_BORDER;
            default -> BlockVisualKind.TETHER_BAR;
        };
        return List.of(new BlockPiece(
                (from.getX() + to.getX()) * 0.5,
                (from.getY() + to.getY()) * 0.5,
                (from.getZ() + to.getZ()) * 0.5,
                thickness, thickness, (float) len,
                rotated[0], rotated[1], rotated[2],
                qx, qy, qz, qw,
                kind
        ));
    }

    private List<BlockPiece> buildArenaBoundaryPieces(Location center, double safeRadius) {
        List<BlockPiece> out = new ArrayList<>(BOUNDARY_SEGMENTS * 2);
        double floorY = center.getY();
        addRingBorderPieces(center.getX(), floorY + 0.05, center.getZ(), safeRadius, BOUNDARY_SEGMENTS, 0.25, 0.08, BlockVisualKind.VOID_BORDER, out);
        addRingBorderPieces(center.getX(), floorY + 0.13, center.getZ(), safeRadius, BOUNDARY_SEGMENTS, 0.12, 1.8, BlockVisualKind.VOID_GLASS, out);
        return out;
    }

    private static void addFilledDiskPieces(
            double cx,
            double y,
            double cz,
            double radius,
            double thicknessY,
            BlockVisualKind kind,
            List<BlockPiece> out) {
        double stripW = 2.0 * radius * Math.sin(Math.PI / 8.0);
        double stripL = 2.0 * radius * Math.cos(Math.PI / 8.0);
        for (int k = 0; k < CIRCLE_FILL_STRIPS; k++) {
            double angle = k * (Math.PI / CIRCLE_FILL_STRIPS);
            out.add(horizontalRect(cx, y, cz, stripW, thicknessY, stripL, angle, kind));
        }
    }

    private static void addRingBorderPieces(
            double cx,
            double y,
            double cz,
            double radius,
            int segments,
            double thicknessXZ,
            double heightY,
            BlockVisualKind kind,
            List<BlockPiece> out) {
        double segLen = 2.0 * radius * Math.sin(Math.PI / segments) + thicknessXZ * 0.45;
        for (int i = 0; i < segments; i++) {
            double theta = (2.0 * Math.PI * i) / segments;
            double px = cx + Math.cos(theta) * radius;
            double pz = cz + Math.sin(theta) * radius;
            out.add(horizontalRect(px, y, pz, thicknessXZ, heightY, segLen, theta, kind));
        }
    }

    private static BlockPiece horizontalRect(
            double cx,
            double y,
            double cz,
            double width,
            double height,
            double length,
            double yawRad,
            BlockVisualKind kind) {
        float qy = (float) -Math.sin(yawRad * 0.5);
        float qw = (float) Math.cos(yawRad * 0.5);
        double halfW = width * 0.5;
        double halfL = length * 0.5;
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        float tx = (float) (-halfW * cos + halfL * sin);
        float tz = (float) (-halfW * sin - halfL * cos);
        return new BlockPiece(
                cx, y, cz,
                (float) width, (float) height, (float) length,
                tx, 0.0f, tz,
                0.0f, qy, 0.0f, qw,
                kind
        );
    }

    private static float[] rotateVecByQuat(
            float vx, float vy, float vz,
            float qx, float qy, float qz, float qw) {
        float tx = 2.0f * (qy * vz - qz * vy);
        float ty = 2.0f * (qz * vx - qx * vz);
        float tz = 2.0f * (qx * vy - qy * vx);
        return new float[]{
                vx + qw * tx + (qy * tz - qz * ty),
                vy + qw * ty + (qz * tx - qx * tz),
                vz + qw * tz + (qx * ty - qy * tx)
        };
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String ephemeralKey(String shape, Location loc) {
        return "anon:" + shape + ":" + Math.round(loc.getX() * 2.0) + ":" + Math.round(loc.getZ() * 2.0);
    }

    private List<Player> filterViewers(Location origin, Collection<? extends Player> viewers, double extraRadius) {
        VisualSettings visuals = configManager.pluginConfig().visuals();
        if (!visuals.usePacketEvents() || origin.getWorld() == null) {
            return List.of();
        }
        World targetWorld = origin.getWorld();
        double effectiveRange = visuals.maxRenderDistance() + Math.max(0.0, extraRadius);
        double maxDistSq = effectiveRange * effectiveRange;

        List<Player> eligible = new ArrayList<>();
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            Location viewerLoc = viewer.getLocation();
            if (viewerLoc == null || !targetWorld.equals(viewerLoc.getWorld())) {
                continue;
            }
            if (viewerLoc.distanceSquared(origin) <= maxDistSq) {
                eligible.add(viewer);
            }
        }
        return eligible;
    }

    private static BlockVisualKind glassKindFor(TelegraphStyle style) {
        return switch (style) {
            case DANGER -> BlockVisualKind.DANGER_GLASS;
            case WARNING -> BlockVisualKind.WARNING_GLASS;
            case SAFE -> BlockVisualKind.SAFE_GLASS;
            case STACK -> BlockVisualKind.STACK_GLASS;
            case SPREAD -> BlockVisualKind.SPREAD_GLASS;
            case TETHER -> BlockVisualKind.TETHER_BAR;
            case VOID_CORRUPTION -> BlockVisualKind.VOID_GLASS;
        };
    }

    private static BlockVisualKind chargeGlassKindFor(TelegraphStyle style) {
        return switch (style) {
            case DANGER -> BlockVisualKind.DANGER_CHARGE_GLASS;
            case WARNING -> BlockVisualKind.WARNING_CHARGE_GLASS;
            case SAFE -> BlockVisualKind.SAFE_GLASS;
            case STACK -> BlockVisualKind.STACK_CHARGE_GLASS;
            case SPREAD -> BlockVisualKind.SPREAD_CHARGE_GLASS;
            case TETHER -> BlockVisualKind.TETHER_BAR;
            case VOID_CORRUPTION -> BlockVisualKind.VOID_GLASS;
        };
    }

    private static BlockVisualKind borderKindFor(TelegraphStyle style) {
        return switch (style) {
            case DANGER -> BlockVisualKind.DANGER_BORDER;
            case WARNING -> BlockVisualKind.WARNING_BORDER;
            case SAFE -> BlockVisualKind.SAFE_BORDER;
            case STACK -> BlockVisualKind.STACK_BORDER;
            case SPREAD -> BlockVisualKind.SPREAD_BORDER;
            case TETHER -> BlockVisualKind.TETHER_BAR;
            case VOID_CORRUPTION -> BlockVisualKind.VOID_BORDER;
        };
    }

    private record BlockPiece(
            double x,
            double y,
            double z,
            float sx,
            float sy,
            float sz,
            float tx,
            float ty,
            float tz,
            float qx,
            float qy,
            float qz,
            float qw,
            BlockVisualKind kind) {
    }

    private record TextPiece(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            Component text,
            float scale,
            boolean billboardCenter) {
    }

    private static final class TelegraphGroup {
        final int[] blockIds;
        final UUID[] blockUuids;
        final int[] textIds;
        final UUID[] textUuids;
        final boolean ephemeral;
        final Map<UUID, WeakReference<Player>> seenBy = new HashMap<>();
        volatile long lastTouchedMillis = System.currentTimeMillis();
        boolean destroyed;

        private TelegraphGroup(
                int[] blockIds,
                UUID[] blockUuids,
                int[] textIds,
                UUID[] textUuids,
                boolean ephemeral) {
            this.blockIds = blockIds;
            this.blockUuids = blockUuids;
            this.textIds = textIds;
            this.textUuids = textUuids;
            this.ephemeral = ephemeral;
        }
    }

    private static final class PacketEventsTelegraphTransport implements TelegraphTransport {

        @Override
        public void sendDustParticle(
                Player viewer,
                double x,
                double y,
                double z,
                int red,
                int green,
                int blue,
                float scale) {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null || !api.isInitialized()) {
                return;
            }
            Particle<ParticleDustData> particle = new Particle<>(
                    ParticleTypes.DUST,
                    new ParticleDustData(scale, new Color(red, green, blue))
            );
            WrapperPlayServerParticle packet = new WrapperPlayServerParticle(
                    particle,
                    true,
                    new Vector3d(x, y, z),
                    Vector3f.zero(),
                    0.0f,
                    1
            );
            api.getPlayerManager().sendPacket(viewer, packet);
        }

        @Override
        public void sendDustTransitionParticle(
                Player viewer,
                double x,
                double y,
                double z,
                int fromR,
                int fromG,
                int fromB,
                int toR,
                int toG,
                int toB,
                float scale) {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null || !api.isInitialized()) {
                return;
            }
            Particle<ParticleDustColorTransitionData> particle = new Particle<>(
                    ParticleTypes.DUST_COLOR_TRANSITION,
                    new ParticleDustColorTransitionData(
                            scale,
                            new Color(fromR, fromG, fromB),
                            new Color(toR, toG, toB)
                    )
            );
            WrapperPlayServerParticle packet = new WrapperPlayServerParticle(
                    particle,
                    true,
                    new Vector3d(x, y, z),
                    new Vector3f(0.25f, 0.25f, 0.25f),
                    0.0f,
                    1
            );
            api.getPlayerManager().sendPacket(viewer, packet);
        }

        @Override
        public void sendAccentParticle(
                Player viewer,
                double x,
                double y,
                double z,
                AccentParticle accent) {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null || !api.isInitialized()) {
                return;
            }
            Particle<?> particle = switch (accent) {
                case END_ROD -> new Particle<>(ParticleTypes.END_ROD);
                case FLAME -> new Particle<>(ParticleTypes.FLAME);
                case SOUL_FIRE_FLAME -> new Particle<>(ParticleTypes.SOUL_FIRE_FLAME);
                case WITCH -> new Particle<>(ParticleTypes.WITCH);
                case ELECTRIC_SPARK -> new Particle<>(ParticleTypes.ELECTRIC_SPARK);
                case DRAGON_BREATH -> new Particle<>(ParticleTypes.DRAGON_BREATH, new ParticlePowerData(1.0f));
                case EXPLOSION -> new Particle<>(ParticleTypes.EXPLOSION);
                case LARGE_SMOKE -> new Particle<>(ParticleTypes.LARGE_SMOKE);
            };
            WrapperPlayServerParticle packet = new WrapperPlayServerParticle(
                    particle,
                    true,
                    new Vector3d(x, y, z),
                    Vector3f.zero(),
                    0.0f,
                    1
            );
            api.getPlayerManager().sendPacket(viewer, packet);
        }

        @Override
        public void sendLightningBolt(
                Player viewer,
                int entityId,
                UUID entityUuid,
                double x,
                double y,
                double z) {
            sendPacket(viewer, new WrapperPlayServerSpawnEntity(
                    entityId,
                    Optional.of(entityUuid),
                    EntityTypes.LIGHTNING_BOLT,
                    new Vector3d(x, y, z),
                    0.0f,
                    0.0f,
                    0.0f,
                    0,
                    Optional.empty()
            ));
        }

        @Override
        public void spawnScaledEnderman(
                Player viewer,
                int entityId,
                UUID entityUuid,
                double x,
                double y,
                double z,
                float yaw,
                double scale,
                boolean screaming) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                    entityId,
                    Optional.of(entityUuid),
                    EntityTypes.ENDERMAN,
                    new Vector3d(x, y, z),
                    0.0f,
                    yaw,
                    yaw,
                    0,
                    Optional.empty()
            );
            List<EntityData<?>> metadata = new ArrayList<>();
            metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, ENTITY_FLAG_GLOWING));
            metadata.add(new EntityData<>(4, EntityDataTypes.BOOLEAN, true)); // Silent
            metadata.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true)); // NoGravity
            metadata.add(new EntityData<>(17, EntityDataTypes.BOOLEAN, screaming)); // Enderman screaming
            WrapperPlayServerEntityMetadata metaPacket = new WrapperPlayServerEntityMetadata(entityId, metadata);

            WrapperPlayServerUpdateAttributes.Property scaleProp =
                    new WrapperPlayServerUpdateAttributes.Property(Attributes.SCALE, scale, List.of());
            WrapperPlayServerUpdateAttributes attrPacket =
                    new WrapperPlayServerUpdateAttributes(entityId, List.of(scaleProp));

            sendPacket(viewer, spawn);
            sendPacket(viewer, metaPacket);
            sendPacket(viewer, attrPacket);
        }

        @Override
        public void teleportAndRotateEntity(
                Player viewer,
                int entityId,
                double x,
                double y,
                double z,
                float yaw,
                float pitch) {
            sendPacket(viewer, new WrapperPlayServerEntityTeleport(
                    entityId,
                    new Vector3d(x, y, z),
                    yaw,
                    pitch,
                    false
            ));
            sendPacket(viewer, new WrapperPlayServerEntityHeadLook(entityId, yaw));
        }

        @Override
        public void spawnTextDisplay(
                Player viewer,
                int entityId,
                UUID entityUuid,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                Component text,
                float scale,
                boolean billboardCenter) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                    entityId,
                    Optional.of(entityUuid),
                    EntityTypes.TEXT_DISPLAY,
                    new Vector3d(x, y, z),
                    pitch,
                    yaw,
                    yaw,
                    0,
                    Optional.empty()
            );
            List<EntityData<?>> data = new ArrayList<>();
            data.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true)); // NoGravity
            data.add(new EntityData<>(9, EntityDataTypes.INT, 2)); // transformation interpolation duration
            data.add(new EntityData<>(10, EntityDataTypes.INT, 2)); // pos/rot interpolation duration
            data.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)));
            data.add(new EntityData<>(13, EntityDataTypes.QUATERNION, new Quaternion4f(0.0f, 0.0f, 0.0f, 1.0f)));
            data.add(new EntityData<>(15, EntityDataTypes.BYTE, billboardCenter ? BILLBOARD_CENTER : BILLBOARD_FIXED));
            data.add(new EntityData<>(16, EntityDataTypes.INT, FULLBRIGHT_LIGHT));
            data.add(new EntityData<>(17, EntityDataTypes.FLOAT, 4.0f));
            data.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, text));
            data.add(new EntityData<>(24, EntityDataTypes.INT, 240));
            data.add(new EntityData<>(25, EntityDataTypes.INT, 0x40000000));
            data.add(new EntityData<>(27, EntityDataTypes.BYTE, (byte) 0x03));
            WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(entityId, data);

            sendPacket(viewer, spawn);
            sendPacket(viewer, meta);
        }

        @Override
        public void updateTextDisplayTransform(
                Player viewer,
                int entityId,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                Component text,
                float scale,
                boolean billboardCenter,
                int interpolationTicks) {
            teleportAndRotateEntity(viewer, entityId, x, y, z, yaw, pitch);
            List<EntityData<?>> data = new ArrayList<>();
            data.add(new EntityData<>(8, EntityDataTypes.INT, 0));
            data.add(new EntityData<>(9, EntityDataTypes.INT, interpolationTicks));
            data.add(new EntityData<>(10, EntityDataTypes.INT, interpolationTicks));
            data.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)));
            data.add(new EntityData<>(15, EntityDataTypes.BYTE, billboardCenter ? BILLBOARD_CENTER : BILLBOARD_FIXED));
            data.add(new EntityData<>(16, EntityDataTypes.INT, FULLBRIGHT_LIGHT));
            data.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, text));
            sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
        }

        @Override
        public void spawnBlockDisplay(
                Player viewer,
                int entityId,
                UUID entityUuid,
                double x,
                double y,
                double z,
                float sx,
                float sy,
                float sz,
                float tx,
                float ty,
                float tz,
                float qx,
                float qy,
                float qz,
                float qw,
                BlockVisualKind blockKind,
                int interpolationTicks) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                    entityId,
                    Optional.of(entityUuid),
                    EntityTypes.BLOCK_DISPLAY,
                    new Vector3d(x, y, z),
                    0.0f,
                    0.0f,
                    0.0f,
                    0,
                    Optional.empty()
            );
            List<EntityData<?>> data = buildBlockDisplayMetadata(
                    viewer, sx, sy, sz, tx, ty, tz, qx, qy, qz, qw, blockKind, interpolationTicks);
            sendPacket(viewer, spawn);
            sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
        }

        @Override
        public void updateBlockDisplay(
                Player viewer,
                int entityId,
                double x,
                double y,
                double z,
                float sx,
                float sy,
                float sz,
                float tx,
                float ty,
                float tz,
                float qx,
                float qy,
                float qz,
                float qw,
                BlockVisualKind blockKind,
                int interpolationTicks) {
            teleportAndRotateEntity(viewer, entityId, x, y, z, 0.0f, 0.0f);
            List<EntityData<?>> data = buildBlockDisplayMetadata(
                    viewer, sx, sy, sz, tx, ty, tz, qx, qy, qz, qw, blockKind, interpolationTicks);
            sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
        }

        private static List<EntityData<?>> buildBlockDisplayMetadata(
                Player viewer,
                float sx,
                float sy,
                float sz,
                float tx,
                float ty,
                float tz,
                float qx,
                float qy,
                float qz,
                float qw,
                BlockVisualKind blockKind,
                int interpolationTicks) {
            List<EntityData<?>> data = new ArrayList<>(12);
            data.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true)); // NoGravity
            data.add(new EntityData<>(8, EntityDataTypes.INT, 0)); // interpolation delay
            data.add(new EntityData<>(9, EntityDataTypes.INT, interpolationTicks));
            data.add(new EntityData<>(10, EntityDataTypes.INT, interpolationTicks));
            data.add(new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(tx, ty, tz)));
            data.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(sx, sy, sz)));
            data.add(new EntityData<>(13, EntityDataTypes.QUATERNION, new Quaternion4f(qx, qy, qz, qw)));
            data.add(new EntityData<>(14, EntityDataTypes.QUATERNION, new Quaternion4f(0.0f, 0.0f, 0.0f, 1.0f)));
            data.add(new EntityData<>(15, EntityDataTypes.BYTE, BILLBOARD_FIXED));
            data.add(new EntityData<>(16, EntityDataTypes.INT, FULLBRIGHT_LIGHT));
            data.add(new EntityData<>(17, EntityDataTypes.FLOAT, 4.0f));
            data.add(new EntityData<>(23, EntityDataTypes.BLOCK_STATE, resolveBlockStateGlobalId(viewer, blockKind)));
            return data;
        }

        private static int resolveBlockStateGlobalId(Player viewer, BlockVisualKind blockKind) {
            StateType stateType = switch (blockKind) {
                case DANGER_GLASS -> StateTypes.ORANGE_STAINED_GLASS;
                case DANGER_CHARGE_GLASS -> StateTypes.RED_STAINED_GLASS;
                case DANGER_BORDER -> StateTypes.RED_CONCRETE;
                case WARNING_GLASS -> StateTypes.YELLOW_STAINED_GLASS;
                case WARNING_CHARGE_GLASS -> StateTypes.ORANGE_STAINED_GLASS;
                case WARNING_BORDER -> StateTypes.ORANGE_CONCRETE;
                case SAFE_GLASS -> StateTypes.CYAN_STAINED_GLASS;
                case SAFE_BORDER -> StateTypes.SEA_LANTERN;
                case STACK_GLASS -> StateTypes.YELLOW_STAINED_GLASS;
                case STACK_CHARGE_GLASS -> StateTypes.ORANGE_STAINED_GLASS;
                case STACK_BORDER -> StateTypes.YELLOW_CONCRETE;
                case SPREAD_GLASS -> StateTypes.MAGENTA_STAINED_GLASS;
                case SPREAD_CHARGE_GLASS -> StateTypes.RED_STAINED_GLASS;
                case SPREAD_BORDER -> StateTypes.MAGENTA_CONCRETE;
                case TETHER_BAR -> StateTypes.PURPLE_CONCRETE;
                case VOID_GLASS -> StateTypes.PURPLE_STAINED_GLASS;
                case VOID_BORDER -> StateTypes.AMETHYST_BLOCK;
            };
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            ClientVersion clientVersion = api != null && api.isInitialized()
                    ? api.getPlayerManager().getClientVersion(viewer)
                    : null;
            WrappedBlockState state = clientVersion != null
                    ? stateType.createBlockState(clientVersion)
                    : stateType.createBlockState();
            return state.getGlobalId();
        }

        @Override
        public void destroyEntities(Player viewer, int... entityIds) {
            sendPacket(viewer, new WrapperPlayServerDestroyEntities(entityIds));
        }

        private static void sendPacket(Player viewer, PacketWrapper<?> packet) {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null || !api.isInitialized()) {
                return;
            }
            api.getPlayerManager().sendPacket(viewer, packet);
        }
    }
}
