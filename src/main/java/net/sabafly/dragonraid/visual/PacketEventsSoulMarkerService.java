package net.sabafly.dragonraid.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.VisualSettings;
import net.sabafly.dragonraid.i18n.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * PacketEvents-backed {@link SoulMarkerService} spawning a glowing Allay marker and a
 * floating {@code TEXT_DISPLAY} per downed player without creating server-side entities.
 */
public final class PacketEventsSoulMarkerService implements SoulMarkerService {

    /**
     * Packet transport abstraction decoupling viewer/range/lifecycle tracking from PacketEvents static registries.
     */
    public interface MarkerTransport {
        void spawnMarker(
                Player viewer,
                int soulEntityId,
                UUID soulUuid,
                int textEntityId,
                UUID textUuid,
                Location location,
                boolean glow,
                Component label);

        void updateLabel(Player viewer, int textEntityId, Component label);

        void destroyMarker(Player viewer, int soulEntityId, int textEntityId);
    }

    private static final double SOUL_Y_OFFSET = 0.65;
    private static final double TEXT_Y_OFFSET = 1.55;
    private static final byte ENTITY_FLAG_GLOWING = 0x40;
    private static final byte BILLBOARD_CENTER = 3;
    private static final int TEXT_LINE_WIDTH = 220;
    private static final int TEXT_BACKGROUND_COLOR = 0x60000000;
    private static final byte TEXT_FLAGS_SHADOW_SEE_THROUGH = 0x03;

    private final ConfigManager configManager;
    private final MessageService messageService;
    private final Logger logger;
    private final MarkerTransport transport;
    private final IntSupplier entityIdGenerator;
    private final Map<UUID, MarkerState> markers = new ConcurrentHashMap<>();

    public PacketEventsSoulMarkerService(
            ConfigManager configManager,
            MessageService messageService,
            Logger logger) {
        this(
                configManager,
                messageService,
                logger,
                new PacketEventsMarkerTransport(logger),
                SpigotReflectionUtil::generateEntityId
        );
    }

    PacketEventsSoulMarkerService(
            ConfigManager configManager,
            MessageService messageService,
            Logger logger,
            MarkerTransport transport,
            IntSupplier entityIdGenerator) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.entityIdGenerator = Objects.requireNonNull(entityIdGenerator, "entityIdGenerator");
    }

    @Override
    public void spawnOrUpdateMarker(
            UUID downedPlayerId,
            String downedPlayerName,
            Location location,
            @Nullable Integer remainingSeconds,
            double progressRatio,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(downedPlayerId, "downedPlayerId");
        Objects.requireNonNull(downedPlayerName, "downedPlayerName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(viewers, "viewers");

        VisualSettings visuals = configManager.pluginConfig().visuals();
        if (!visuals.usePacketEvents() || location.getWorld() == null) {
            return;
        }

        MarkerState state = markers.computeIfAbsent(downedPlayerId, ignored -> new MarkerState(
                entityIdGenerator.getAsInt(),
                UUID.randomUUID(),
                entityIdGenerator.getAsInt(),
                UUID.randomUUID(),
                location.clone()
        ));
        state.location = location.clone();

        double maxDistSq = visuals.maxRenderDistance() * visuals.maxRenderDistance();
        World targetWorld = location.getWorld();
        int percent = Math.max(0, Math.min(100, (int) Math.round(progressRatio * 100.0)));

        Map<UUID, Player> eligibleViewers = new HashMap<>();
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            if (!targetWorld.equals(viewer.getWorld())) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(location) <= maxDistSq) {
                eligibleViewers.put(viewer.getUniqueId(), viewer);
            }
        }

        synchronized (state) {
            // Destroy for viewers that left range or world.
            Iterator<UUID> existingIt = state.viewerIds.iterator();
            while (existingIt.hasNext()) {
                UUID existingId = existingIt.next();
                if (!eligibleViewers.containsKey(existingId)) {
                    Player leftPlayer = findViewer(existingId, viewers);
                    if (leftPlayer != null && leftPlayer.isOnline()) {
                        safeDestroy(leftPlayer, state);
                    }
                    existingIt.remove();
                }
            }

            // Spawn for new viewers and update text metadata for all eligible viewers.
            for (Player viewer : eligibleViewers.values()) {
                Component label = renderLabel(viewer, downedPlayerName, remainingSeconds, percent);
                boolean isNewViewer = state.viewerIds.add(viewer.getUniqueId());
                if (isNewViewer) {
                    safeSpawn(viewer, state, location, visuals.soulMarkerGlow(), label);
                } else {
                    safeUpdateLabel(viewer, state, label);
                }
            }
        }
    }

    @Override
    public void removeMarker(UUID downedPlayerId, Collection<? extends Player> viewers) {
        Objects.requireNonNull(downedPlayerId, "downedPlayerId");
        Objects.requireNonNull(viewers, "viewers");
        MarkerState state = markers.remove(downedPlayerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            for (UUID viewerId : state.viewerIds) {
                Player viewer = findViewer(viewerId, viewers);
                if (viewer != null && viewer.isOnline()) {
                    safeDestroy(viewer, state);
                }
            }
            state.viewerIds.clear();
        }
    }

    @Override
    public void removeAllMarkers(Collection<? extends Player> viewers) {
        Objects.requireNonNull(viewers, "viewers");
        for (UUID downedId : List.copyOf(markers.keySet())) {
            removeMarker(downedId, viewers);
        }
    }

    private Component renderLabel(
            Player viewer,
            String downedPlayerName,
            @Nullable Integer remainingSeconds,
            int progressPercent) {
        if (remainingSeconds != null) {
            return messageService.render(
                    viewer,
                    "revive.soul-marker-label",
                    Placeholder.unparsed("player", downedPlayerName),
                    Placeholder.unparsed("seconds", String.valueOf(Math.max(0, remainingSeconds))),
                    Placeholder.unparsed("progress", String.valueOf(progressPercent))
            );
        }
        return messageService.render(
                viewer,
                "revive.soul-marker-label-no-timer",
                Placeholder.unparsed("player", downedPlayerName),
                Placeholder.unparsed("progress", String.valueOf(progressPercent))
        );
    }

    private void safeSpawn(Player viewer, MarkerState state, Location location, boolean glow, Component label) {
        try {
            transport.spawnMarker(
                    viewer,
                    state.soulEntityId,
                    state.soulUuid,
                    state.textEntityId,
                    state.textUuid,
                    location,
                    glow,
                    label
            );
        } catch (RuntimeException ex) {
            logger.log(Level.FINE, "Failed to spawn soul marker for " + viewer.getName(), ex);
        }
    }

    private void safeUpdateLabel(Player viewer, MarkerState state, Component label) {
        try {
            transport.updateLabel(viewer, state.textEntityId, label);
        } catch (RuntimeException ex) {
            logger.log(Level.FINE, "Failed to update soul marker for " + viewer.getName(), ex);
        }
    }

    private void safeDestroy(Player viewer, MarkerState state) {
        try {
            transport.destroyMarker(viewer, state.soulEntityId, state.textEntityId);
        } catch (RuntimeException ex) {
            logger.log(Level.FINE, "Failed to destroy soul marker for " + viewer.getName(), ex);
        }
    }

    private static @Nullable Player findViewer(UUID id, Collection<? extends Player> viewers) {
        for (Player viewer : viewers) {
            if (viewer != null && viewer.getUniqueId().equals(id)) {
                return viewer;
            }
        }
        return Bukkit.getServer() != null ? Bukkit.getPlayer(id) : null;
    }

    private static final class PacketEventsMarkerTransport implements MarkerTransport {

        private final Logger logger;

        PacketEventsMarkerTransport(Logger logger) {
            this.logger = logger;
        }

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
            Vector3d soulPos = new Vector3d(location.getX(), location.getY() + SOUL_Y_OFFSET, location.getZ());
            WrapperPlayServerSpawnEntity spawnSoul = new WrapperPlayServerSpawnEntity(
                    soulEntityId,
                    Optional.of(soulUuid),
                    EntityTypes.ALLAY,
                    soulPos,
                    0.0f,
                    0.0f,
                    0.0f,
                    0,
                    Optional.empty()
            );
            byte flags = glow ? ENTITY_FLAG_GLOWING : 0x00;
            WrapperPlayServerEntityMetadata soulMeta = new WrapperPlayServerEntityMetadata(
                    soulEntityId,
                    List.of(
                            new EntityData<>(0, EntityDataTypes.BYTE, flags),
                            new EntityData<>(5, EntityDataTypes.BOOLEAN, true)
                    )
            );

            Vector3d textPos = new Vector3d(location.getX(), location.getY() + TEXT_Y_OFFSET, location.getZ());
            WrapperPlayServerSpawnEntity spawnText = new WrapperPlayServerSpawnEntity(
                    textEntityId,
                    Optional.of(textUuid),
                    EntityTypes.TEXT_DISPLAY,
                    textPos,
                    0.0f,
                    0.0f,
                    0.0f,
                    0,
                    Optional.empty()
            );
            List<EntityData<?>> textData = new ArrayList<>();
            textData.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true));
            textData.add(new EntityData<>(15, EntityDataTypes.BYTE, BILLBOARD_CENTER));
            textData.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, label));
            textData.add(new EntityData<>(24, EntityDataTypes.INT, TEXT_LINE_WIDTH));
            textData.add(new EntityData<>(25, EntityDataTypes.INT, TEXT_BACKGROUND_COLOR));
            textData.add(new EntityData<>(27, EntityDataTypes.BYTE, TEXT_FLAGS_SHADOW_SEE_THROUGH));
            WrapperPlayServerEntityMetadata textMeta = new WrapperPlayServerEntityMetadata(textEntityId, textData);

            sendPacket(viewer, spawnSoul);
            sendPacket(viewer, soulMeta);
            sendPacket(viewer, spawnText);
            sendPacket(viewer, textMeta);
        }

        @Override
        public void updateLabel(Player viewer, int textEntityId, Component label) {
            sendPacket(viewer, new WrapperPlayServerEntityMetadata(
                    textEntityId,
                    List.of(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, label))
            ));
        }

        @Override
        public void destroyMarker(Player viewer, int soulEntityId, int textEntityId) {
            sendPacket(viewer, new WrapperPlayServerDestroyEntities(soulEntityId, textEntityId));
        }

        private void sendPacket(Player player, PacketWrapper<?> packet) {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null || !api.isInitialized()) {
                return;
            }
            try {
                api.getPlayerManager().sendPacket(player, packet);
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "PacketEvents send failed for " + player.getName(), ex);
            }
        }
    }

    private static final class MarkerState {
        final int soulEntityId;
        final UUID soulUuid;
        final int textEntityId;
        final UUID textUuid;
        final Set<UUID> viewerIds = new HashSet<>();
        Location location;

        MarkerState(int soulEntityId, UUID soulUuid, int textEntityId, UUID textUuid, Location location) {
            this.soulEntityId = soulEntityId;
            this.soulUuid = soulUuid;
            this.textEntityId = textEntityId;
            this.textUuid = textUuid;
            this.location = location;
        }
    }
}
