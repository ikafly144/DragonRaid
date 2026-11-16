package net.sabafly.dragonraid.hud;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.encounter.ParticipantState;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Adventure-backed {@link RaidHudService} dispatching per-player localized BossBars,
 * ActionBars, phase Titles, and sounds safely on Folia entity threads.
 */
public final class AdventureRaidHudService implements RaidHudService {

    private final MessageService messageService;
    private final RaidScheduler scheduler;
    private final Map<UUID, ViewerHudState> viewerBars = new ConcurrentHashMap<>();

    public AdventureRaidHudService(MessageService messageService, RaidScheduler scheduler) {
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void updateMainBossBar(
            BossConfig bossConfig,
            BossConfig.PhaseConfig phase,
            double hpRatio,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(bossConfig, "bossConfig");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(viewers, "viewers");

        double clamped = Math.max(0.0, Math.min(1.0, hpRatio));
        int percent = (int) Math.round(clamped * 100.0);
        BossBar.Color color = phase.softEnrage()
                ? BossBar.Color.RED
                : (bossConfig.difficulty() == BossConfig.Difficulty.HARD ? BossBar.Color.RED : BossBar.Color.PURPLE);

        Set<UUID> activeViewerIds = new HashSet<>();
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            UUID id = viewer.getUniqueId();
            activeViewerIds.add(id);
            ViewerHudState hud = viewerBars.computeIfAbsent(id, ignored -> new ViewerHudState());
            hud.viewerRef = new WeakReference<>(viewer);

            Component bossName = messageService.render(viewer, bossConfig.displayNameKey());
            Component phaseName = messageService.render(viewer, phase.nameKey());
            Component title = messageService.render(
                    viewer,
                    "bossbar.main",
                    Placeholder.component("boss", bossName),
                    Placeholder.unparsed("difficulty", bossConfig.difficulty().name()),
                    Placeholder.component("phase", phaseName),
                    Placeholder.unparsed("hp_percent", String.valueOf(percent))
            );
            hud.mainBar.name(title);
            hud.mainBar.progress((float) clamped);
            hud.mainBar.color(color);

            if (!hud.mainVisible) {
                hud.mainVisible = true;
                scheduler.runAtEntity(viewer, () -> viewer.showBossBar(hud.mainBar));
            }
        }

        // Hide bars from players who left the viewer set (e.g. left the End world).
        for (UUID trackedId : Set.copyOf(viewerBars.keySet())) {
            if (!activeViewerIds.contains(trackedId)) {
                ViewerHudState removed = viewerBars.remove(trackedId);
                if (removed != null) {
                    hideTrackedBars(removed);
                }
            }
        }
    }

    @Override
    public void updateCastBossBar(
            @Nullable BossConfig.AbilitySettings ability,
            double remainingSeconds,
            double totalSeconds,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(viewers, "viewers");
        if (ability == null || totalSeconds <= 0.0) {
            for (Player viewer : viewers) {
                if (viewer == null || !viewer.isOnline()) {
                    continue;
                }
                ViewerHudState hud = viewerBars.get(viewer.getUniqueId());
                if (hud != null && hud.castVisible) {
                    hud.castVisible = false;
                    scheduler.runAtEntity(viewer, () -> viewer.hideBossBar(hud.castBar));
                }
            }
            return;
        }

        float progress = (float) Math.max(0.0, Math.min(1.0, (totalSeconds - remainingSeconds) / totalSeconds));
        String formattedSeconds = String.format(Locale.ROOT, "%.1f", Math.max(0.0, remainingSeconds));

        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            ViewerHudState hud = viewerBars.computeIfAbsent(viewer.getUniqueId(), ignored -> new ViewerHudState());
            Component abilityName = messageService.render(viewer, ability.nameKey());
            Component title = messageService.render(
                    viewer,
                    "bossbar.cast",
                    Placeholder.component("ability", abilityName),
                    Placeholder.unparsed("seconds", formattedSeconds)
            );
            hud.castBar.name(title);
            hud.castBar.progress(progress);
            if (!hud.castVisible) {
                hud.castVisible = true;
                scheduler.runAtEntity(viewer, () -> viewer.showBossBar(hud.castBar));
            }
        }
    }

    @Override
    public void updateActionBars(
            Collection<? extends Player> participants,
            Function<UUID, ParticipantState> stateLookup,
            int remainingRevives,
            Function<UUID, @Nullable Component> extraStatusLookup,
            Predicate<UUID> suppressActionBar) {
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(stateLookup, "stateLookup");
        Objects.requireNonNull(extraStatusLookup, "extraStatusLookup");
        Objects.requireNonNull(suppressActionBar, "suppressActionBar");

        for (Player player : participants) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            UUID id = player.getUniqueId();
            if (suppressActionBar.test(id)) {
                continue;
            }
            ParticipantState state = stateLookup.apply(id);
            if (state == null) {
                continue;
            }
            String stateKey = switch (state) {
                case ALIVE -> "actionbar.state-alive";
                case DOWNED -> "actionbar.state-downed";
                case DEAD_SPECTATOR -> "actionbar.state-spectator";
            };
            Component stateComp = messageService.render(player, stateKey);
            Component extra = extraStatusLookup.apply(id);
            Component extraComp = extra != null ? extra : Component.empty();
            Component bar = messageService.render(
                    player,
                    "actionbar.status",
                    Placeholder.component("state", stateComp),
                    Placeholder.unparsed("revives", String.valueOf(Math.max(0, remainingRevives))),
                    Placeholder.component("extra", extraComp)
            );
            scheduler.runAtEntity(player, () -> player.sendActionBar(bar));
        }
    }

    @Override
    public void announcePhaseTransition(
            BossConfig.PhaseConfig phase,
            Collection<? extends Player> viewers) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(viewers, "viewers");

        String announceKey = derivePhaseAnnounceKey(phase);
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            scheduler.runAtEntity(viewer, () -> {
                Component title = messageService.render(viewer, phase.nameKey());
                Component subtitle = messageService.render(viewer, announceKey);
                viewer.showTitle(Title.title(title, subtitle));
                viewer.playSound(Sound.sound(
                        Key.key("minecraft:entity.ender_dragon.growl"),
                        Sound.Source.HOSTILE,
                        0.75f,
                        phase.softEnrage() ? 1.25f : 1.0f
                ));
            });
        }
    }

    @Override
    public void clearAll(Collection<? extends Player> viewers) {
        Objects.requireNonNull(viewers, "viewers");
        for (Player viewer : viewers) {
            if (viewer != null) {
                removeViewer(viewer);
            }
        }
        for (UUID trackedId : Set.copyOf(viewerBars.keySet())) {
            ViewerHudState remaining = viewerBars.remove(trackedId);
            if (remaining != null) {
                hideTrackedBars(remaining);
            }
        }
        viewerBars.clear();
    }

    @Override
    public void removeViewer(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        ViewerHudState hud = viewerBars.remove(viewer.getUniqueId());
        if (hud == null) {
            return;
        }
        boolean hideMain = hud.mainVisible;
        boolean hideCast = hud.castVisible;
        hud.mainVisible = false;
        hud.castVisible = false;
        if ((hideMain || hideCast) && viewer.isOnline()) {
            scheduler.runAtEntity(viewer, () -> {
                if (hideMain) {
                    viewer.hideBossBar(hud.mainBar);
                }
                if (hideCast) {
                    viewer.hideBossBar(hud.castBar);
                }
            });
        }
    }

    private void hideTrackedBars(ViewerHudState hud) {
        boolean hideMain = hud.mainVisible;
        boolean hideCast = hud.castVisible;
        hud.mainVisible = false;
        hud.castVisible = false;
        Player viewer = hud.viewerRef != null ? hud.viewerRef.get() : null;
        if (viewer != null && viewer.isOnline() && (hideMain || hideCast)) {
            scheduler.runAtEntity(viewer, () -> {
                if (hideMain) {
                    viewer.hideBossBar(hud.mainBar);
                }
                if (hideCast) {
                    viewer.hideBossBar(hud.castBar);
                }
            });
        }
    }

    private static String derivePhaseAnnounceKey(BossConfig.PhaseConfig phase) {
        String nameKey = phase.nameKey();
        if (nameKey.endsWith(".name")) {
            return nameKey.substring(0, nameKey.length() - ".name".length()) + ".announce";
        }
        return nameKey;
    }

    private static final class ViewerHudState {
        final BossBar mainBar = BossBar.bossBar(
                Component.empty(),
                1.0f,
                BossBar.Color.PURPLE,
                BossBar.Overlay.PROGRESS
        );
        final BossBar castBar = BossBar.bossBar(
                Component.empty(),
                0.0f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.NOTCHED_10
        );
        volatile @Nullable WeakReference<Player> viewerRef;
        volatile boolean mainVisible;
        volatile boolean castVisible;
    }
}
