package net.sabafly.dragonraid.party.hud;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.ObjectiveMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.RenderType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore.Action;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.api.party.PartyChangeEvent;
import net.sabafly.dragonraid.api.party.PartyChangeListener;
import net.sabafly.dragonraid.api.party.PartyCombatStatusProvider;
import net.sabafly.dragonraid.api.party.PartyHudService;
import net.sabafly.dragonraid.api.party.PartyMemberCombatState;
import net.sabafly.dragonraid.api.party.PartyMemberVitals;
import net.sabafly.dragonraid.api.party.PartyRoleProvider;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe {@link PartyHudService} rendering an FF14-style party list on each viewer's
 * right sidebar via PacketEvents scoreboard packets without touching Bukkit's {@code ScoreboardManager}.
 *
 * <p>Vitals are sampled on each member's {@code EntityScheduler} and delta packets are emitted
 * only for changed rows so idle ticks send zero packets.
 */
public final class PacketEventsPartyHudService implements PartyHudService, PartyChangeListener {

    public static final String OBJECTIVE_NAME = "dr_party_hud";
    private static final int SIDEBAR_POSITION = 1;

    /**
     * Seam abstracting PacketEvents scoreboard packet delivery for unit tests.
     */
    public interface ScoreboardPacketTransport {
        boolean createObjective(Player viewer, String objectiveName, Component displayName);

        boolean updateObjective(Player viewer, String objectiveName, Component displayName);

        boolean removeObjective(Player viewer, String objectiveName);

        boolean displaySidebar(Player viewer, int position, String objectiveName);

        boolean updateLine(Player viewer, String objectiveName, String lineKey, int score, Component displayName);

        boolean resetLine(Player viewer, String objectiveName, String lineKey);
    }

    private record VitalsSample(String name, boolean online, double health, double maxHealth, boolean blocking) {
    }

    private record RenderedViewerState(Component title, List<Component> lines) {
    }

    private final RaidPartyService partyService;
    private final PartyCombatStatusProvider combatStatusProvider;
    private final PartyRoleProvider roleProvider;
    private final RaidScheduler scheduler;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final ScoreboardPacketTransport transport;
    private final Logger logger;
    private final boolean hudEnabledDefault;
    private final int maxMembersShown;

    private final Map<UUID, Boolean> hudPreferenceByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, VitalsSample> sampledVitals = new ConcurrentHashMap<>();
    private final Map<UUID, RenderedViewerState> renderedStates = new ConcurrentHashMap<>();
    private final Map<UUID, Object> viewerLocks = new ConcurrentHashMap<>();
    private volatile boolean shutdown;

    public PacketEventsPartyHudService(
            RaidPartyService partyService,
            PartyCombatStatusProvider combatStatusProvider,
            PartyRoleProvider roleProvider,
            RaidScheduler scheduler,
            Function<UUID, @Nullable Player> playerLookup,
            Logger logger,
            boolean hudEnabledDefault,
            int maxMembersShown) {
        this(
                partyService,
                combatStatusProvider,
                roleProvider,
                scheduler,
                playerLookup,
                new PacketEventsScoreboardTransport(logger),
                logger,
                hudEnabledDefault,
                maxMembersShown
        );
    }

    public PacketEventsPartyHudService(
            RaidPartyService partyService,
            PartyCombatStatusProvider combatStatusProvider,
            PartyRoleProvider roleProvider,
            RaidScheduler scheduler,
            Function<UUID, @Nullable Player> playerLookup,
            ScoreboardPacketTransport transport,
            Logger logger,
            boolean hudEnabledDefault,
            int maxMembersShown) {
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.combatStatusProvider = Objects.requireNonNull(combatStatusProvider, "combatStatusProvider");
        this.roleProvider = Objects.requireNonNull(roleProvider, "roleProvider");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.hudEnabledDefault = hudEnabledDefault;
        this.maxMembersShown = Math.max(1, Math.min(PartyHudFormatter.MAX_SIDEBAR_LINES - 1, maxMembersShown));
    }

    @Override
    public void setHudEnabled(UUID playerId, boolean enabled) {
        Objects.requireNonNull(playerId, "playerId");
        hudPreferenceByPlayer.put(playerId, enabled);
        if (!enabled) {
            clearViewer(playerId);
        } else {
            refreshViewer(playerId);
        }
    }

    @Override
    public boolean isHudEnabled(UUID playerId) {
        if (playerId == null) {
            return hudEnabledDefault;
        }
        return hudPreferenceByPlayer.getOrDefault(playerId, hudEnabledDefault);
    }

    @Override
    public void refreshViewer(UUID viewerId) {
        if (viewerId == null || shutdown) {
            return;
        }
        Optional<RaidParty> partyOpt = partyService.partyOf(viewerId);
        if (partyOpt.isEmpty() || !isHudEnabled(viewerId)) {
            clearViewer(viewerId);
            return;
        }
        RaidParty party = partyOpt.get();
        samplePartyVitals(party);
        renderViewer(viewerId, party);
    }

    @Override
    public void refreshParty(UUID partyId) {
        if (partyId == null || shutdown) {
            return;
        }
        Optional<RaidParty> partyOpt = partyService.partyById(partyId);
        if (partyOpt.isEmpty()) {
            return;
        }
        RaidParty party = partyOpt.get();
        samplePartyVitals(party);
        for (UUID memberId : party.memberIds()) {
            if (isHudEnabled(memberId)) {
                renderViewer(memberId, party);
            } else {
                clearViewer(memberId);
            }
        }
    }

    @Override
    public void clearViewer(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        Object vLock = viewerLocks.computeIfAbsent(viewerId, ignored -> new Object());
        synchronized (vLock) {
            RenderedViewerState previous = renderedStates.remove(viewerId);
            if (previous == null) {
                return;
            }
            Player viewer = playerLookup.apply(viewerId);
            if (viewer != null && viewer.isOnline()) {
                try {
                    transport.removeObjective(viewer, OBJECTIVE_NAME);
                } catch (RuntimeException ex) {
                    logger.log(Level.FINE, "Failed to remove party HUD objective for " + viewerId, ex);
                }
            }
        }
    }

    @Override
    public void tickAll() {
        if (shutdown) {
            return;
        }
        Set<UUID> renderedCopy = Set.copyOf(renderedStates.keySet());
        Set<UUID> activePartyIds = new HashSet<>();
        for (UUID viewerId : renderedCopy) {
            Optional<RaidParty> partyOpt = partyService.partyOf(viewerId);
            if (partyOpt.isEmpty() || !isHudEnabled(viewerId)) {
                clearViewer(viewerId);
            } else {
                activePartyIds.add(partyOpt.get().partyId());
            }
        }

        // Also check online players who might be in a party that hasn't rendered yet.
        if (Bukkit.getServer() != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                partyService.partyOf(online.getUniqueId()).ifPresent(p -> activePartyIds.add(p.partyId()));
            }
        }

        for (UUID partyId : activePartyIds) {
            refreshParty(partyId);
        }
    }

    @Override
    public void onPartyChanged(PartyChangeEvent event) {
        if (event == null || shutdown) {
            return;
        }
        switch (event.type()) {
            case LEFT, KICKED -> {
                if (event.targetId() != null) {
                    clearViewer(event.targetId());
                    sampledVitals.remove(event.targetId());
                }
                refreshParty(event.partyId());
            }
            case DISBANDED -> {
                if (event.before() != null) {
                    for (UUID memberId : event.before().memberIds()) {
                        clearViewer(memberId);
                        sampledVitals.remove(memberId);
                    }
                }
            }
            case DISCONNECTED -> {
                if (event.targetId() != null) {
                    clearViewer(event.targetId());
                }
                refreshParty(event.partyId());
            }
            case SEAT_EXPIRED -> {
                if (event.targetId() != null) {
                    clearViewer(event.targetId());
                    sampledVitals.remove(event.targetId());
                }
                refreshParty(event.partyId());
            }
            case CREATED, JOINED, LEADER_TRANSFERRED, RECONNECTED -> refreshParty(event.partyId());
            case INVITED, INVITE_DECLINED -> {
                // No sidebar change needed until member joins.
            }
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        for (UUID viewerId : List.copyOf(renderedStates.keySet())) {
            clearViewer(viewerId);
        }
        renderedStates.clear();
        sampledVitals.clear();
        viewerLocks.clear();
    }

    private void samplePartyVitals(RaidParty party) {
        Set<UUID> disconnectedIds = partyService.disconnectedMemberIds(party.partyId());
        for (UUID memberId : party.memberIds()) {
            Player member = playerLookup.apply(memberId);
            if (member == null || !member.isOnline() || disconnectedIds.contains(memberId)) {
                sampledVitals.compute(memberId, (id, prev) -> new VitalsSample(
                        prev != null ? prev.name() : (member != null ? member.getName() : id.toString().substring(0, 8)),
                        false,
                        0.0,
                        prev != null ? prev.maxHealth() : 20.0,
                        false
                ));
                continue;
            }
            scheduler.runAtEntity(member, () -> {
                boolean onlineNow = member.isOnline();
                double maxHp = readMaxHealth(member);
                double hp = Math.max(0.0, Math.min(maxHp, member.getHealth()));
                boolean blocking = member.isBlocking();
                sampledVitals.put(memberId, new VitalsSample(member.getName(), onlineNow, hp, maxHp, blocking));
            });
        }
    }

    private void renderViewer(UUID viewerId, RaidParty party) {
        Player viewer = playerLookup.apply(viewerId);
        if (viewer == null || !viewer.isOnline() || partyService.isDisconnected(viewerId)) {
            clearViewer(viewerId);
            return;
        }

        Set<UUID> disconnectedIds = partyService.disconnectedMemberIds(party.partyId());
        Map<UUID, PartyMemberVitals> vitalsMap = new HashMap<>(party.size());
        for (UUID memberId : party.memberIds()) {
            boolean disconnected = disconnectedIds.contains(memberId);
            VitalsSample sample = sampledVitals.get(memberId);
            String displayName;
            boolean online;
            double hp;
            double maxHp;
            boolean blocking;
            if (sample != null) {
                displayName = sample.name();
                online = sample.online() && !disconnected;
                hp = sample.health();
                maxHp = sample.maxHealth();
                blocking = sample.blocking();
            } else {
                Player lookedUp = playerLookup.apply(memberId);
                online = lookedUp != null && lookedUp.isOnline() && !disconnected;
                displayName = lookedUp != null ? lookedUp.getName() : memberId.toString().substring(0, 8);
                hp = 20.0;
                maxHp = 20.0;
                blocking = false;
            }
            PartyMemberCombatState combatState = combatStatusProvider.combatState(memberId, online);
            int revives = combatStatusProvider.remainingRevives(memberId);
            vitalsMap.put(memberId, new PartyMemberVitals(memberId, displayName, online, hp, maxHp, blocking, combatState, revives));
        }

        Component nextTitle = PartyHudFormatter.formatTitle(party);
        List<Component> nextLines = PartyHudFormatter.formatLines(
                party,
                vitalsMap,
                disconnectedIds,
                roleProvider,
                maxMembersShown
        );

        Object vLock = viewerLocks.computeIfAbsent(viewerId, ignored -> new Object());
        synchronized (vLock) {
            if (shutdown || !isHudEnabled(viewerId)) {
                return;
            }
            RenderedViewerState prev = renderedStates.get(viewerId);
            boolean sent = true;
            try {
                if (prev == null) {
                    sent = transport.createObjective(viewer, OBJECTIVE_NAME, nextTitle)
                            && transport.displaySidebar(viewer, SIDEBAR_POSITION, OBJECTIVE_NAME);
                    for (int i = 0; i < nextLines.size() && sent; i++) {
                        int score = nextLines.size() - i;
                        sent = transport.updateLine(viewer, OBJECTIVE_NAME, lineKey(i), score, nextLines.get(i));
                    }
                    if (sent) {
                        renderedStates.put(viewerId, new RenderedViewerState(nextTitle, nextLines));
                    } else {
                        invalidateUnsyncedHud(viewerId, viewer);
                    }
                    return;
                }

                boolean changed = false;
                if (!prev.title().equals(nextTitle)) {
                    changed = true;
                    sent = transport.updateObjective(viewer, OBJECTIVE_NAME, nextTitle);
                }
                List<Component> prevLines = prev.lines();
                boolean sizeChanged = prevLines.size() != nextLines.size();
                int minSize = Math.min(prevLines.size(), nextLines.size());
                for (int i = 0; i < minSize && sent; i++) {
                    if (sizeChanged || !prevLines.get(i).equals(nextLines.get(i))) {
                        int score = nextLines.size() - i;
                        changed = true;
                        sent = transport.updateLine(viewer, OBJECTIVE_NAME, lineKey(i), score, nextLines.get(i));
                    }
                }
                for (int i = minSize; i < nextLines.size() && sent; i++) {
                    int score = nextLines.size() - i;
                    changed = true;
                    sent = transport.updateLine(viewer, OBJECTIVE_NAME, lineKey(i), score, nextLines.get(i));
                }
                for (int i = nextLines.size(); i < prevLines.size() && sent; i++) {
                    changed = true;
                    sent = transport.resetLine(viewer, OBJECTIVE_NAME, lineKey(i));
                }
                if (!sent) {
                    invalidateUnsyncedHud(viewerId, viewer);
                } else if (changed) {
                    renderedStates.put(viewerId, new RenderedViewerState(nextTitle, nextLines));
                }
            } catch (RuntimeException ex) {
                invalidateUnsyncedHud(viewerId, viewer);
                logger.log(Level.FINE, "PacketEvents party HUD send failed for " + viewer.getName(), ex);
            }
        }
    }

    private void invalidateUnsyncedHud(UUID viewerId, Player viewer) {
        renderedStates.remove(viewerId);
        try {
            transport.removeObjective(viewer, OBJECTIVE_NAME);
        } catch (RuntimeException ignored) {
            // Best-effort cleanup so partial scoreboard state does not linger.
        }
    }

    private static String lineKey(int index) {
        return "dr_p_" + (index < 10 ? "0" + index : Integer.toString(index));
    }

    private static double readMaxHealth(Player player) {
        if (Bukkit.getServer() != null) {
            AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                return attr.getValue();
            }
        }
        return legacyMaxHealth(player);
    }

    @SuppressWarnings("deprecation")
    private static double legacyMaxHealth(Player player) {
        return player.getMaxHealth();
    }

    private static final class PacketEventsScoreboardTransport implements ScoreboardPacketTransport {

        private final Logger logger;

        private PacketEventsScoreboardTransport(Logger logger) {
            this.logger = logger;
        }

        private @Nullable PacketEventsAPI<?> api() {
            try {
                PacketEventsAPI<?> api = PacketEvents.getAPI();
                return (api != null && api.isInitialized()) ? api : null;
            } catch (NoClassDefFoundError | RuntimeException ex) {
                return null;
            }
        }

        @Override
        public boolean createObjective(Player viewer, String objectiveName, Component displayName) {
            return sendPacket(viewer, new WrapperPlayServerScoreboardObjective(
                    objectiveName,
                    ObjectiveMode.CREATE,
                    displayName,
                    RenderType.INTEGER,
                    ScoreFormat.blankScore()
            ));
        }

        @Override
        public boolean updateObjective(Player viewer, String objectiveName, Component displayName) {
            return sendPacket(viewer, new WrapperPlayServerScoreboardObjective(
                    objectiveName,
                    ObjectiveMode.UPDATE,
                    displayName,
                    RenderType.INTEGER,
                    ScoreFormat.blankScore()
            ));
        }

        @Override
        public boolean removeObjective(Player viewer, String objectiveName) {
            return sendPacket(viewer, new WrapperPlayServerScoreboardObjective(
                    objectiveName,
                    ObjectiveMode.REMOVE,
                    Component.empty(),
                    RenderType.INTEGER,
                    ScoreFormat.blankScore()
            ));
        }

        @Override
        public boolean displaySidebar(Player viewer, int position, String objectiveName) {
            return sendPacket(viewer, new WrapperPlayServerDisplayScoreboard(position, objectiveName));
        }

        @Override
        public boolean updateLine(Player viewer, String objectiveName, String lineKey, int score, Component displayName) {
            return sendPacket(viewer, new WrapperPlayServerUpdateScore(
                    lineKey,
                    Action.CREATE_OR_UPDATE_ITEM,
                    objectiveName,
                    score,
                    displayName,
                    ScoreFormat.blankScore()
            ));
        }

        @Override
        public boolean resetLine(Player viewer, String objectiveName, String lineKey) {
            return sendPacket(viewer, new WrapperPlayServerResetScore(lineKey, objectiveName));
        }

        private boolean sendPacket(Player viewer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
            PacketEventsAPI<?> api = api();
            if (api == null) {
                return false;
            }
            try {
                api.getPlayerManager().sendPacket(viewer, packet);
                return true;
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed sending PacketEvents party scoreboard packet", ex);
                return false;
            }
        }
    }
}
