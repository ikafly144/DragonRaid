package net.sabafly.dragonraid.hud;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.encounter.ParticipantState;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Integrated HUD service managing localized main/cast BossBars, participant ActionBars,
 * phase-transition Titles, and synchronized sounds.
 */
public interface RaidHudService {

    /**
     * Updates or shows the main boss HUD bar (boss name, difficulty badge, current phase, HP%).
     */
    void updateMainBossBar(
            BossConfig bossConfig,
            BossConfig.PhaseConfig phase,
            double hpRatio,
            Collection<? extends Player> viewers);

    /**
     * Updates or hides the sub-BossBar displaying the active ability cast progress and remaining time.
     */
    void updateCastBossBar(
            @Nullable BossConfig.AbilitySettings ability,
            double remainingSeconds,
            double totalSeconds,
            Collection<? extends Player> viewers);

    /**
     * Updates participant ActionBars with current status, remaining revives, and mechanic assignment info.
     */
    void updateActionBars(
            Collection<? extends Player> participants,
            Function<UUID, ParticipantState> stateLookup,
            int remainingRevives,
            Function<UUID, @Nullable Component> extraStatusLookup,
            Predicate<UUID> suppressActionBar);

    /**
     * Announces a phase transition via localized Title/Subtitle and sound cue.
     */
    void announcePhaseTransition(
            BossConfig.PhaseConfig phase,
            Collection<? extends Player> viewers);

    /**
     * Hides all BossBars for tracked viewers and clears cached HUD state.
     */
    void clearAll(Collection<? extends Player> viewers);

    /**
     * Cleans up cached HUD bars for a disconnecting or departing viewer.
     */
    void removeViewer(Player viewer);
}
