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

    private final Map<UUID, BossBar> activeBars = new ConcurrentHashMap<>();
    private final Map<UUID, ReadyCheckMenu> openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, ReadyCheckSession> trackedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRenderedSecond = new ConcurrentHashMap<>();

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
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) {
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
            openMenus.put(player.getUniqueId(), menu);
            player.openInventory(menu.getInventory());
        });
    }

    public void tickVisuals() {
        if (trackedSessions.isEmpty()) {
            return;
        }
        long now = clockMillis.getAsLong();
        for (ReadyCheckSession session : List.copyOf(trackedSessions.values())) {
            long remainingSec = Math.max(0L, (long) Math.ceil(session.remainingMillis(now) / 1000.0));
            Long prevSec = lastRenderedSecond.get(session.sessionId());
            if (prevSec != null && prevSec == remainingSec) {
                continue;
            }
            lastRenderedSecond.put(session.sessionId(), remainingSec);
            long totalMs = Math.max(1000L, session.expiresAtMillis() - session.createdAtMillis());
            float progress = (float) Math.max(0.0, Math.min(1.0, (double) session.remainingMillis(now) / totalMs));
            for (UUID participantId : session.participantIds()) {
                Player viewer = playerLookup.apply(participantId);
                if (viewer == null || !viewer.isOnline()) {
                    continue;
                }
                BossBar bar = activeBars.get(participantId);
                if (bar != null) {
                    bar.name(formatBossBarTitle(viewer, session, remainingSec));
                    bar.progress(progress);
                }
                ReadyCheckMenu menu = openMenus.get(participantId);
                if (menu != null) {
                    scheduler.runAtEntity(viewer, () -> {
                        if (viewer.isOnline() && isViewingMenu(viewer, menu)) {
                            menu.refresh(session, now);
                        }
                    });
                }
            }
        }
    }

    @Override
    public void onSessionStarted(ReadyCheckSession session) {
        trackedSessions.put(session.sessionId(), session);
        long now = clockMillis.getAsLong();
        long remainingSec = Math.max(0L, (long) Math.ceil(session.remainingMillis(now) / 1000.0));
        lastRenderedSecond.put(session.sessionId(), remainingSec);

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
                if (!member.isOnline()) {
                    return;
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

                BossBar oldBar = activeBars.remove(memberId);
                if (oldBar != null) {
                    member.hideBossBar(oldBar);
                }
                BossBar newBar = BossBar.bossBar(
                        formatBossBarTitle(member, session, remainingSec),
                        1.0f,
                        BossBar.Color.BLUE,
                        BossBar.Overlay.PROGRESS
                );
                activeBars.put(memberId, newBar);
                member.showBossBar(newBar);

                ReadyCheckMenu menu = new ReadyCheckMenu(
                        member, session, now, finderService, messageService, menuFactory, playerLookup);
                openMenus.put(memberId, menu);
                member.openInventory(menu.getInventory());
            });
        }
    }

    @Override
    public void onResponseUpdated(ReadyCheckSession session, UUID responderId, ReadyCheckResponse response) {
        trackedSessions.put(session.sessionId(), session);
        long now = clockMillis.getAsLong();
        long remainingSec = Math.max(0L, (long) Math.ceil(session.remainingMillis(now) / 1000.0));
        for (UUID memberId : session.participantIds()) {
            Player member = playerLookup.apply(memberId);
            if (member == null || !member.isOnline()) {
                continue;
            }
            BossBar bar = activeBars.get(memberId);
            if (bar != null) {
                bar.name(formatBossBarTitle(member, session, remainingSec));
            }
            ReadyCheckMenu menu = openMenus.get(memberId);
            if (menu != null) {
                scheduler.runAtEntity(member, () -> {
                    if (member.isOnline() && isViewingMenu(member, menu)) {
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
        for (UUID memberId : List.copyOf(activeBars.keySet())) {
            BossBar bar = activeBars.remove(memberId);
            Player member = playerLookup.apply(memberId);
            if (bar != null && member != null && member.isOnline()) {
                member.hideBossBar(bar);
            }
        }
        openMenus.clear();
        trackedSessions.clear();
        lastRenderedSecond.clear();
    }

    private void cleanupSessionUi(ReadyCheckSession session, boolean closeFinderMenu) {
        trackedSessions.remove(session.sessionId());
        lastRenderedSecond.remove(session.sessionId());
        for (UUID memberId : session.participantIds()) {
            BossBar bar = activeBars.remove(memberId);
            ReadyCheckMenu menu = openMenus.remove(memberId);
            Player member = playerLookup.apply(memberId);
            if (member != null && member.isOnline()) {
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
