package net.sabafly.dragonraid.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.color.Color;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleData;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.VisualSettings;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * PacketEvents-backed {@link SpatialTelegraphService} emitting lightweight client-side particle
 * packets for AoEs, stack/spread indicators, crystal tethers, and Soft Enrage boundaries.
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
        ELECTRIC_SPARK
    }

    /**
     * Packet transport abstraction for sending individual telegraph particles to viewers.
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
    }

    private static final double Y_SURFACE_OFFSET = 0.25;
    private static final double MIN_CIRCLE_POINTS = 12;
    private static final double MAX_CIRCLE_POINTS = 64;
    private static final int MAX_LINE_POINTS = 72;

    private final ConfigManager configManager;
    private final Logger logger;
    private final TelegraphTransport transport;

    public PacketEventsTelegraphService(ConfigManager configManager, Logger logger) {
        this(configManager, logger, new PacketEventsTelegraphTransport());
    }

    PacketEventsTelegraphService(
            ConfigManager configManager,
            Logger logger,
            TelegraphTransport transport) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.transport = Objects.requireNonNull(transport, "transport");
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
            // 4 cardinal inward markers to indicate allies should gather inside.
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
            ParticleType<ParticleData> type = switch (accent) {
                case END_ROD -> ParticleTypes.END_ROD;
                case FLAME -> ParticleTypes.FLAME;
                case SOUL_FIRE_FLAME -> ParticleTypes.SOUL_FIRE_FLAME;
                case WITCH -> ParticleTypes.WITCH;
                case ELECTRIC_SPARK -> ParticleTypes.ELECTRIC_SPARK;
            };
            WrapperPlayServerParticle packet = new WrapperPlayServerParticle(
                    new Particle<>(type),
                    true,
                    new Vector3d(x, y, z),
                    Vector3f.zero(),
                    0.0f,
                    1
            );
            api.getPlayerManager().sendPacket(viewer, packet);
        }
    }
}
