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
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
 * PacketEvents-backed {@link SpatialTelegraphService} emitting client-side particles,
 * color-transition dragon trails, virtual scaled Endermen (`generic.scale`), Display Entity
 * directional arrows & overhead dice markers, and lightning strike packets.
 */
public final class PacketEventsTelegraphService implements SpatialTelegraphService {

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
     * Packet transport abstraction for sending particles and virtual entities to viewers.
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

        default void destroyEntities(Player viewer, int... entityIds) {
        }
    }

    private static final double Y_SURFACE_OFFSET = 0.25;
    private static final double MIN_CIRCLE_POINTS = 12;
    private static final double MAX_CIRCLE_POINTS = 64;
    private static final int MAX_LINE_POINTS = 72;
    private static final byte ENTITY_FLAG_GLOWING = 0x40;
    private static final byte BILLBOARD_FIXED = 0;
    private static final byte BILLBOARD_CENTER = 3;
    private static final AtomicInteger FALLBACK_ENTITY_ID = new AtomicInteger(1_800_000);

    private final ConfigManager configManager;
    private final Logger logger;
    private final TelegraphTransport transport;
    private final IntSupplier entityIdGenerator;

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
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        if (radius <= 0.0 || center.getWorld() == null) {
            return;
        }
        List<Player> eligible = filterViewers(center, viewers, radius);
        if (eligible.isEmpty()) {
            return;
        }
        emitCirclePoints(center, center.getY() + Y_SURFACE_OFFSET, radius, style, eligible, false);
    }

    @Override
    public void renderDonutAoE(
            Location center,
            double innerRadius,
            double outerRadius,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        if (outerRadius <= 0.0 || center.getWorld() == null) {
            return;
        }
        List<Player> eligible = filterViewers(center, viewers, outerRadius);
        if (eligible.isEmpty()) {
            return;
        }
        double y = center.getY() + Y_SURFACE_OFFSET;
        if (innerRadius > 0.0) {
            emitCirclePoints(center, y, innerRadius, TelegraphStyle.SAFE, eligible, false);
        }
        emitCirclePoints(center, y, outerRadius, style, eligible, true);
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
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        World world = start.getWorld();
        if (world == null || !world.equals(end.getWorld())) {
            return;
        }
        Location midpoint = new Location(
                world,
                (start.getX() + end.getX()) * 0.5,
                (start.getY() + end.getY()) * 0.5,
                (start.getZ() + end.getZ()) * 0.5
        );
        double halfLength = start.distance(end) * 0.5;
        List<Player> eligible = filterViewers(midpoint, viewers, halfLength);
        if (eligible.isEmpty()) {
            return;
        }

        double dx = end.getX() - start.getX();
        double dy = end.getY() - start.getY();
        double dz = end.getZ() - start.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) {
            return;
        }
        VisualSettings visuals = configManager.pluginConfig().visuals();
        double step = Math.max(0.4, visuals.tetherParticleStep());
        int count = Math.max(2, Math.min(MAX_LINE_POINTS, (int) Math.ceil(len / step)));

        double horizLen = Math.hypot(dx, dz);
        double perpX = horizLen > 1e-4 ? (-dz / horizLen) * (width * 0.5) : 0.0;
        double perpZ = horizLen > 1e-4 ? (dx / horizLen) * (width * 0.5) : 0.0;

        RgbColor color = colorFor(style);
        for (int i = 0; i <= count; i++) {
            double t = (double) i / count;
            double x = start.getX() + dx * t;
            double y = start.getY() + dy * t;
            double z = start.getZ() + dz * t;
            sendDust(eligible, x, y, z, color, 1.35f);
            if (width > 0.5 && (i % 2 == 0)) {
                sendDust(eligible, x + perpX, y, z + perpZ, color, 1.0f);
                sendDust(eligible, x - perpX, y, z - perpZ, color, 1.0f);
            }
        }
    }

    @Override
    public void renderStackMarker(
            Location center,
            double radius,
            boolean soloMode,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(viewers, "viewers");
        if (radius <= 0.0 || center.getWorld() == null) {
            return;
        }
        List<Player> eligible = filterViewers(center, viewers, radius);
        if (eligible.isEmpty()) {
            return;
        }
        double y = center.getY() + Y_SURFACE_OFFSET;
        TelegraphStyle ringStyle = soloMode ? TelegraphStyle.WARNING : TelegraphStyle.STACK;
        emitCirclePoints(center, y, radius, ringStyle, eligible, false);
        if (!soloMode) {
            double innerR = Math.max(0.8, radius * 0.45);
            for (int i = 0; i < 4; i++) {
                double angle = i * (Math.PI * 0.5);
                double px = center.getX() + Math.cos(angle) * innerR;
                double pz = center.getZ() + Math.sin(angle) * innerR;
                sendAccent(eligible, px, y + 0.6, pz, AccentParticle.END_ROD);
            }
        }
    }

    @Override
    public void renderSpreadMarker(
            Location center,
            double radius,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(viewers, "viewers");
        if (radius <= 0.0 || center.getWorld() == null) {
            return;
        }
        List<Player> eligible = filterViewers(center, viewers, radius);
        if (eligible.isEmpty()) {
            return;
        }
        double y = center.getY() + Y_SURFACE_OFFSET;
        emitCirclePoints(center, y, radius, TelegraphStyle.SPREAD, eligible, true);
    }

    @Override
    public void renderTetherLine(
            Location from,
            Location to,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(viewers, "viewers");
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return;
        }
        Location midpoint = new Location(
                world,
                (from.getX() + to.getX()) * 0.5,
                (from.getY() + to.getY()) * 0.5,
                (from.getZ() + to.getZ()) * 0.5
        );
        double halfDist = from.distance(to) * 0.5;
        List<Player> eligible = filterViewers(midpoint, viewers, halfDist);
        if (eligible.isEmpty()) {
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) {
            return;
        }
        double step = Math.max(0.3, configManager.pluginConfig().visuals().tetherParticleStep());
        int count = Math.max(2, Math.min(MAX_LINE_POINTS, (int) Math.ceil(len / step)));
        RgbColor color = colorFor(style);
        for (int i = 0; i <= count; i++) {
            double t = (double) i / count;
            double x = from.getX() + dx * t;
            double y = from.getY() + dy * t;
            double z = from.getZ() + dz * t;
            sendDust(eligible, x, y, z, color, 1.25f);
            if (i % 4 == 0) {
                sendAccent(eligible, x, y, z, AccentParticle.WITCH);
            }
        }
    }

    @Override
    public void renderArenaBoundary(
            Location center,
            double safeRadius,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(viewers, "viewers");
        if (safeRadius <= 0.0 || center.getWorld() == null) {
            return;
        }
        List<Player> eligible = filterViewers(center, viewers, safeRadius);
        if (eligible.isEmpty()) {
            return;
        }
        emitCirclePoints(center, center.getY() + 0.6, safeRadius, TelegraphStyle.VOID_CORRUPTION, eligible, true);
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
        Objects.requireNonNull(viewers, "viewers");
        List<Player> eligible = filterViewers(blockerLocation, viewers, coneLength);
        if (eligible.isEmpty()) {
            return;
        }
        double y = blockerLocation.getY() + 0.4;
        double radCenter = Math.toRadians(directionYawDegrees);
        double radLeft = Math.toRadians(directionYawDegrees - halfAngleDegrees);
        double radRight = Math.toRadians(directionYawDegrees + halfAngleDegrees);

        int steps = Math.max(6, (int) Math.ceil(coneLength / 0.8));
        RgbColor safeColor = colorFor(TelegraphStyle.SAFE);
        for (int i = 1; i <= steps; i++) {
            double dist = (coneLength * i) / steps;
            double lx = blockerLocation.getX() - Math.sin(radLeft) * dist;
            double lz = blockerLocation.getZ() + Math.cos(radLeft) * dist;
            double rx = blockerLocation.getX() - Math.sin(radRight) * dist;
            double rz = blockerLocation.getZ() + Math.cos(radRight) * dist;
            sendDust(eligible, lx, y, lz, safeColor, 1.5f);
            sendDust(eligible, rx, y, rz, safeColor, 1.5f);
            if (i % 3 == 0) {
                double cx = blockerLocation.getX() - Math.sin(radCenter) * dist;
                double cz = blockerLocation.getZ() + Math.cos(radCenter) * dist;
                sendAccent(eligible, cx, y + 0.2, cz, AccentParticle.SOUL_FIRE_FLAME);
            }
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

    private void emitCirclePoints(
            Location center,
            double y,
            double radius,
            TelegraphStyle style,
            List<Player> viewers,
            boolean withAccent) {
        int points = (int) Math.max(
                MIN_CIRCLE_POINTS,
                Math.min(MAX_CIRCLE_POINTS, Math.round((2.0 * Math.PI * radius) / 0.85))
        );
        RgbColor color = colorFor(style);
        AccentParticle accent = accentFor(style);
        for (int i = 0; i < points; i++) {
            double angle = (2.0 * Math.PI * i) / points;
            double px = center.getX() + Math.cos(angle) * radius;
            double pz = center.getZ() + Math.sin(angle) * radius;
            sendDust(viewers, px, y, pz, color, 1.4f);
            if (withAccent && i % 4 == 0 && accent != null) {
                sendAccent(viewers, px, y + 0.2, pz, accent);
            }
        }
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

    private void sendDust(List<Player> viewers, double x, double y, double z, RgbColor color, float scale) {
        for (Player viewer : viewers) {
            try {
                transport.sendDustParticle(viewer, x, y, z, color.r(), color.g(), color.b(), scale);
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to send telegraph dust particle to " + viewer.getName(), ex);
            }
        }
    }

    private void sendAccent(List<Player> viewers, double x, double y, double z, AccentParticle accent) {
        for (Player viewer : viewers) {
            try {
                transport.sendAccentParticle(viewer, x, y, z, accent);
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to send telegraph accent particle to " + viewer.getName(), ex);
            }
        }
    }

    private static RgbColor colorFor(TelegraphStyle style) {
        return switch (style) {
            case DANGER -> new RgbColor(235, 45, 45);
            case SAFE -> new RgbColor(45, 220, 140);
            case WARNING -> new RgbColor(255, 195, 30);
            case STACK -> new RgbColor(255, 220, 60);
            case SPREAD -> new RgbColor(255, 115, 20);
            case TETHER -> new RgbColor(195, 70, 245);
            case VOID_CORRUPTION -> new RgbColor(135, 25, 195);
        };
    }

    private static AccentParticle accentFor(TelegraphStyle style) {
        return switch (style) {
            case DANGER -> AccentParticle.FLAME;
            case SAFE -> AccentParticle.SOUL_FIRE_FLAME;
            case WARNING, STACK -> AccentParticle.END_ROD;
            case SPREAD -> AccentParticle.ELECTRIC_SPARK;
            case TETHER, VOID_CORRUPTION -> AccentParticle.WITCH;
        };
    }

    private record RgbColor(int r, int g, int b) {
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
            data.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)));
            data.add(new EntityData<>(13, EntityDataTypes.QUATERNION, new Quaternion4f(0.0f, 0.0f, 0.0f, 1.0f)));
            data.add(new EntityData<>(15, EntityDataTypes.BYTE, billboardCenter ? BILLBOARD_CENTER : BILLBOARD_FIXED));
            data.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, text));
            data.add(new EntityData<>(24, EntityDataTypes.INT, 240));
            data.add(new EntityData<>(25, EntityDataTypes.INT, 0x40000000));
            data.add(new EntityData<>(27, EntityDataTypes.BYTE, (byte) 0x03));
            WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(entityId, data);

            sendPacket(viewer, spawn);
            sendPacket(viewer, meta);
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
