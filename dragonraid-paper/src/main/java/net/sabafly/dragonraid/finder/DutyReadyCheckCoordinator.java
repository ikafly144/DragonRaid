package net.sabafly.dragonraid.finder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ReadyCheckListener;
import net.sabafly.dragonraid.api.finder.ReadyCheckResponse;
import net.sabafly.dragonraid.api.finder.ReadyCheckSession;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterBlueprintRegistry;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.FinderSettings;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.finder.gui.ContentFinderMenu;
import net.sabafly.dragonraid.finder.gui.FinderMenuFactory;
import net.sabafly.dragonraid.finder.gui.ReadyCheckMenu;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Coordinates FF14-style Ready Check visuals ("Shakiin!" SE, Title, countdown BossBar, clickable chat,
 * and GUI sync) and safely captures pre-entry player {@link Location}s on Folia entity threads before
 * launching {@link RaidEncounterManager#startEncounter}.
 */
public final class DutyReadyCheckCoordinator implements ReadyCheckListener {

    private static final Title.Times READY_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(200),
            Duration.ofMillis(2500),
            Duration.ofMillis(500)
    );

    private record CapturedPlayer(Player player, Location returnLocation) {
    }

    private final ConfigManager configManager;
    private final ContentFinderService finderService;
    private final EncounterBlueprintRegistry blueprintRegistry;
    private final RaidEncounterManager encounterManager;
    private final MessageService messageService;
    private final RaidScheduler scheduler;
    private final FinderMenuFactory menuFactory;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final LongSupplier clockMillis;
    private final Logger logger;

    private record TrackedBar(UUID sessionId, BossBar bar) {
    }

    private record TrackedMenu(UUID sessionId, ReadyCheckMenu menu) {
    }

    private final Object uiLock = new Object();
    private final Map<UUID, TrackedBar> activeBars = new ConcurrentHashMap<>();
    private final Map<UUID, TrackedMenu> openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, ReadyCheckSession> trackedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRenderedSecond = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> endedSessionIds = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > 256;
        }
    };
    private volatile boolean shutdown;

    public DutyReadyCheckCoordinator(
            ConfigManager configManager,
            ContentFinderService finderService,
            EncounterBlueprintRegistry blueprintRegistry,
            RaidEncounterManager encounterManager,
            MessageService messageService,
            RaidScheduler scheduler,
            FinderMenuFactory menuFactory,
            Function<UUID, @Nullable Player> playerLookup,
            LongSupplier clockMillis,
            Logger logger) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.finderService = Objects.requireNonNull(finderService, "finderService");
        this.blueprintRegistry = Objects.requireNonNull(blueprintRegistry, "blueprintRegistry");
        this.encounterManager = Objects.requireNonNull(encounterManager, "encounterManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.menuFactory = Objects.requireNonNull(menuFactory, "menuFactory");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void openReadyCheckMenu(Player player, ReadyCheckSession session) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(session, "session");
        UUID sessionId = session.sessionId();
        synchronized (uiLock) {
            if (shutdown || endedSessionIds.containsKey(sessionId)) {
                return;
            }
        }
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline() || !isSessionUiEligible(sessionId)) {
                return;
            }
            ReadyCheckMenu menu = new ReadyCheckMenu(
                    player,
                    session,
                    clockMillis.getAsLong(),
                    finderService,
                    messageService,
                    menuFactory,
                    playerLookup
            );
            synchronized (uiLock) {
                if (shutdown || !player.isOnline() || endedSessionIds.containsKey(sessionId)) {
                    return;
                }
                openMenus.put(player.getUniqueId(), new TrackedMenu(sessionId, menu));
            }
            player.openInventory(menu.getInventory());
        });
    }

    public void tickVisuals() {
        if (shutdown || trackedSessions.isEmpty()) {
            return;
        }
        long now = clockMillis.getAsLong();
        for (ReadyCheckSession session : List.copyOf(trackedSessions.values())) {
            UUID sessionId = session.sessionId();
            long remainingSec = Math.max(0L, (long) Math.ceil(session.remainingMillis(now) / 1000.0));
            synchronized (uiLock) {
                if (!trackedSessions.containsKey(sessionId) || endedSessionIds.containsKey(sessionId)) {
                    continue;
                }
                Long prevSec = lastRenderedSecond.get(sessionId);
                if (prevSec != null && prevSec == remainingSec) {
                    continue;
                }
                lastRenderedSecond.put(sessionId, remainingSec);
            }
            long totalMs = Math.max(1000L, session.expiresAtMillis() - session.createdAtMillis());
            float progress = (float) Math.max(0.0, Math.min(1.0, (double) session.remainingMillis(now) / totalMs));
            for (UUID participantId : session.participantIds()) {
                Player viewer = playerLookup.apply(participantId);
                if (viewer == null || !viewer.isOnline()) {
                    continue;
                }
                TrackedBar trackedBar = activeBars.get(participantId);
                if (trackedBar != null && trackedBar.sessionId().equals(sessionId)) {
                    trackedBar.bar().name(formatBossBarTitle(viewer, session, remainingSec));
                    trackedBar.bar().progress(progress);
                }
                TrackedMenu trackedMenu = openMenus.get(participantId);
                if (trackedMenu != null && trackedMenu.sessionId().equals(sessionId)) {
                    ReadyCheckMenu menu = trackedMenu.menu();
                    scheduler.runAtEntity(viewer, () -> {
                        if (viewer.isOnline() && isSessionActiveForUi(sessionId) && isViewingMenu(viewer, menu)) {
                            menu.refresh(session, now);
                        }
                    });
                }
            }
        }
    }

    @Override
    public void onSessionStarted(ReadyCheckSession session) {
        UUID sessionId = session.sessionId();
        long now = clockMillis.getAsLong();
        long remainingSec = Math.max(0L, (long) Math.ceil(session.remainingMillis(now) / 1000.0));
        synchronized (uiLock) {
            if (shutdown || endedSessionIds.containsKey(sessionId)) {
                return;
            }
            trackedSessions.put(sessionId, session);
            lastRenderedSecond.put(sessionId, remainingSec);
        }

        FinderSettings settings = configManager.pluginConfig().finder();
        Key soundKey = parseSoundKey(settings.commenceSound());
        Sound commenceSound = Sound.sound(
                soundKey,
                Sound.Source.MASTER,
                settings.commenceSoundVolume(),
                settings.commenceSoundPitch()
        );

        for (UUID memberId : session.participantIds()) {
            Player member = playerLookup.apply(memberId);
            if (member == null || !member.isOnline()) {
                continue;
            }
            scheduler.runAtEntity(member, () -> {
                if (!member.isOnline() || !isSessionActiveForUi(sessionId)) {
                    return;
                }
                BossBar newBar = BossBar.bossBar(
                        formatBossBarTitle(member, session, remainingSec),
                        1.0f,
                        BossBar.Color.BLUE,
                        BossBar.Overlay.PROGRESS
                );
                ReadyCheckMenu menu = new ReadyCheckMenu(
                        member, session, now, finderService, messageService, menuFactory, playerLookup);
                BossBar previousBar;
                synchronized (uiLock) {
                    if (shutdown || !member.isOnline()
                            || !trackedSessions.containsKey(sessionId)
                            || endedSessionIds.containsKey(sessionId)) {
                        return;
                    }
                    TrackedBar prevTracked = activeBars.put(memberId, new TrackedBar(sessionId, newBar));
                    previousBar = prevTracked != null ? prevTracked.bar() : null;
                    openMenus.put(memberId, new TrackedMenu(sessionId, menu));
                }
                Component dutyName = resolveDutyName(member, session.contentId());
                Component titleComp = messageService.render(member, "finder.readycheck.title");
                Component subComp = messageService.render(
                        member,
                        "finder.readycheck.subtitle",
                        Placeholder.component("content", dutyName),
                        Placeholder.unparsed("seconds", String.valueOf(remainingSec))
                );
                member.playSound(commenceSound);
                member.showTitle(Title.title(titleComp, subComp, READY_TITLE_TIMES));
                messageService.send(
                        member,
                        "finder.readycheck.chat-prompt",
                        Placeholder.component("content", dutyName),
                        Placeholder.unparsed("seconds", String.valueOf(remainingSec))
                );
                if (previousBar != null) {
                    member.hideBossBar(previousBar);
                }
                member.showBossBar(newBar);
                member.openInventory(menu.getInventory());
            });
        }
    }

    @Override
    public void onResponseUpdated(ReadyCheckSession session, UUID responderId, ReadyCheckResponse response) {
        UUID sessionId = session.sessionId();
        synchronized (uiLock) {
            if (shutdown || !trackedSessions.containsKey(sessionId) || endedSessionIds.containsKey(sessionId)) {
                return;
            }
            trackedSessions.put(sessionId, session);
        }
        long now = clockMillis.getAsLong();
        long remainingSec = Math.max(0L, (long) Math.ceil(session.remainingMillis(now) / 1000.0));
        for (UUID memberId : session.participantIds()) {
            Player member = playerLookup.apply(memberId);
            if (member == null || !member.isOnline()) {
                continue;
            }
            TrackedBar trackedBar = activeBars.get(memberId);
            if (trackedBar != null && trackedBar.sessionId().equals(sessionId)) {
                trackedBar.bar().name(formatBossBarTitle(member, session, remainingSec));
            }
            TrackedMenu trackedMenu = openMenus.get(memberId);
            if (trackedMenu != null && trackedMenu.sessionId().equals(sessionId)) {
                ReadyCheckMenu menu = trackedMenu.menu();
                scheduler.runAtEntity(member, () -> {
                    if (member.isOnline() && isSessionActiveForUi(sessionId) && isViewingMenu(member, menu)) {
                        menu.refresh(session, now);
                    }
                });
            }
        }
    }

    @Override
    public void onSessionCommenced(ReadyCheckSession session) {
        boolean skippedUi = !trackedSessions.containsKey(session.sessionId());
        cleanupSessionUi(session, true);
        ContentEntrySpec spec = finderService.getContent(session.contentId()).orElse(null);
        EncounterBlueprint blueprint = spec != null ? blueprintRegistry.get(spec.blueprintId()).orElse(null) : null;
        if (spec == null || blueprint == null) {
            notifyEntryFailed(session.participantIds());
            return;
        }
        if (skippedUi) {
            FinderSettings settings = configManager.pluginConfig().finder();
            Sound sound = Sound.sound(
                    parseSoundKey(settings.commenceSound()),
                    Sound.Source.MASTER,
                    settings.commenceSoundVolume(),
                    settings.commenceSoundPitch()
            );
            for (UUID memberId : session.participantIds()) {
                Player member = playerLookup.apply(memberId);
                if (member != null && member.isOnline()) {
                    scheduler.runAtEntity(member, () -> {
                        if (member.isOnline()) {
                            member.playSound(sound);
                            messageService.send(
                                    member,
                                    "finder.readycheck.solo-commenced",
                                    Placeholder.component("content", resolveDutyName(member, session.contentId()))
                            );
                        }
                    });
                }
            }
        }

        List<CompletableFuture<Optional<CapturedPlayer>>> captureFutures = new ArrayList<>(session.participantIds().size());
        for (UUID memberId : session.participantIds()) {
            Player member = playerLookup.apply(memberId);
            if (member == null || !member.isOnline()) {
                captureFutures.add(CompletableFuture.completedFuture(Optional.empty()));
                continue;
            }
            CompletableFuture<Optional<CapturedPlayer>> capturedFuture = new CompletableFuture<>();
            TaskHandle handle = scheduler.runAtEntity(member, () -> {
                if (!member.isOnline()) {
                    capturedFuture.complete(Optional.empty());
                    return;
                }
                Location loc = member.getLocation().clone();
                capturedFuture.complete(Optional.of(new CapturedPlayer(member, loc)));
            }, () -> capturedFuture.complete(Optional.empty()));
            if (handle == null) {
                capturedFuture.complete(Optional.empty());
            }
            captureFutures.add(capturedFuture);
        }

        CompletableFuture.allOf(captureFutures.toArray(CompletableFuture[]::new)).whenComplete((ignored, err) -> {
            if (err != null) {
                logger.log(Level.WARNING, "Failed capturing pre-entry player locations for duty " + session.contentId(), err);
                notifyEntryFailed(session.participantIds());
                return;
            }
            List<Player> players = new ArrayList<>();
            Map<UUID, Location> returnLocations = new LinkedHashMap<>();
            for (CompletableFuture<Optional<CapturedPlayer>> cf : captureFutures) {
                cf.join().ifPresent(captured -> {
                    players.add(captured.player());
                    if (captured.returnLocation() != null && captured.returnLocation().getWorld() != null) {
                        returnLocations.put(captured.player().getUniqueId(), captured.returnLocation());
                    }
                });
            }
            if (players.size() != session.participantIds().size()) {
                notifyEntryFailed(session.participantIds());
                return;
            }
            scheduler.runGlobal(() -> {
                boolean started = encounterManager.startEncounter(blueprint, players, returnLocations);
                if (!started) {
                    notifyEntryFailed(session.participantIds());
                }
            });
        });
    }

    @Override
    public void onSessionEnded(ReadyCheckSession session, @Nullable UUID actorId, @Nullable String reason) {
        cleanupSessionUi(session, false);
        String msgKey = switch (session.status()) {
            case DECLINED -> "finder.readycheck.ended-declined";
            case TIMED_OUT -> "finder.readycheck.ended-timeout";
            case CANCELLED -> (reason != null && reason.startsWith("party_changed:"))
                    ? "finder.readycheck.ended-party-changed"
                    : "finder.readycheck.ended-cancelled";
            default -> "finder.readycheck.ended-cancelled";
        };
        Player actor = actorId != null ? playerLookup.apply(actorId) : null;
        String actorName = actor != null ? actor.getName() : "";
        for (UUID memberId : session.participantIds()) {
            Player member = playerLookup.apply(memberId);
            if (member != null && member.isOnline()) {
                scheduler.runAtEntity(member, () -> messageService.send(
                        member,
                        msgKey,
                        Placeholder.unparsed("player", actorName)
                ));
            }
        }
    }

    public void shutdown() {
        List<Map.Entry<UUID, TrackedBar>> barsToHide;
        synchronized (uiLock) {
            shutdown = true;
            barsToHide = List.copyOf(activeBars.entrySet());
            activeBars.clear();
            openMenus.clear();
            trackedSessions.clear();
            lastRenderedSecond.clear();
            endedSessionIds.clear();
        }
        for (Map.Entry<UUID, TrackedBar> entry : barsToHide) {
            Player member = playerLookup.apply(entry.getKey());
            if (member != null && member.isOnline()) {
                member.hideBossBar(entry.getValue().bar());
            }
        }
    }

    private void cleanupSessionUi(ReadyCheckSession session, boolean closeFinderMenu) {
        UUID sessionId = session.sessionId();
        Map<UUID, BossBar> barsToHide = new LinkedHashMap<>();
        Map<UUID, ReadyCheckMenu> menusToClose = new LinkedHashMap<>();
        synchronized (uiLock) {
            endedSessionIds.put(sessionId, Boolean.TRUE);
            trackedSessions.remove(sessionId);
            lastRenderedSecond.remove(sessionId);
            for (UUID memberId : session.participantIds()) {
                TrackedBar trackedBar = activeBars.get(memberId);
                if (trackedBar != null && trackedBar.sessionId().equals(sessionId)) {
                    activeBars.remove(memberId, trackedBar);
                    barsToHide.put(memberId, trackedBar.bar());
                }
                TrackedMenu trackedMenu = openMenus.get(memberId);
                if (trackedMenu != null && trackedMenu.sessionId().equals(sessionId)) {
                    openMenus.remove(memberId, trackedMenu);
                    menusToClose.put(memberId, trackedMenu.menu());
                }
            }
        }
        for (UUID memberId : session.participantIds()) {
            BossBar bar = barsToHide.get(memberId);
            ReadyCheckMenu menu = menusToClose.get(memberId);
            Player member = playerLookup.apply(memberId);
            if (member != null && member.isOnline() && (bar != null || menu != null || closeFinderMenu)) {
                scheduler.runAtEntity(member, () -> {
                    if (bar != null) {
                        member.hideBossBar(bar);
                    }
                    var view = member.getOpenInventory();
                    if (view != null && view.getTopInventory() != null) {
                        Object holder = view.getTopInventory().getHolder();
                        if ((menu != null && holder == menu)
                                || (closeFinderMenu && holder instanceof ContentFinderMenu)) {
                            member.closeInventory();
                        }
                    }
                });
            }
        }
    }

    private boolean isSessionActiveForUi(UUID sessionId) {
        synchronized (uiLock) {
            return !shutdown && trackedSessions.containsKey(sessionId) && !endedSessionIds.containsKey(sessionId);
        }
    }

    private boolean isSessionUiEligible(UUID sessionId) {
        synchronized (uiLock) {
            return !shutdown && !endedSessionIds.containsKey(sessionId);
        }
    }

    private void notifyEntryFailed(List<UUID> participantIds) {
        for (UUID memberId : participantIds) {
            Player member = playerLookup.apply(memberId);
            if (member != null && member.isOnline()) {
                scheduler.runAtEntity(member, () -> messageService.send(member, "finder.entry-failed"));
            }
        }
    }

    private Component formatBossBarTitle(Player viewer, ReadyCheckSession session, long remainingSec) {
        Component dutyName = resolveDutyName(viewer, session.contentId());
        return messageService.render(
                viewer,
                "finder.readycheck.bossbar",
                Placeholder.component("content", dutyName),
                Placeholder.unparsed("accepted", String.valueOf(session.acceptedCount())),
                Placeholder.unparsed("total", String.valueOf(session.participantIds().size())),
                Placeholder.unparsed("seconds", String.valueOf(remainingSec))
        );
    }

    private Component resolveDutyName(Player viewer, String contentId) {
        ContentEntrySpec spec = finderService.getContent(contentId).orElse(null);
        if (spec == null || spec.displayNameKey().isBlank()) {
            return Component.text(contentId);
        }
        String key = spec.displayNameKey();
        String locale = messageService.resolveLocale(viewer);
        if (messageService.hasKey(locale, key) || messageService.hasKey("en_US", key)) {
            return messageService.render(viewer, key);
        }
        return Component.text(key);
    }

    private static boolean isViewingMenu(Player viewer, ReadyCheckMenu expected) {
        var view = viewer.getOpenInventory();
        return view != null && view.getTopInventory() != null && view.getTopInventory().getHolder() == expected;
    }

    private static Key parseSoundKey(String raw) {
        try {
            return Key.key(raw);
        } catch (RuntimeException ex) {
            return Key.key("minecraft:block.bell.use");
        }
    }
}
