package net.sabafly.dragonraid.achievement;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.api.event.RaidMechanicResolvedEvent;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Evaluates raid clear, flawless play, and mechanic mastery achievements from DragonRaid events.
 */
public final class DefaultRaidAchievementService implements RaidAchievementService, Listener {

    public static final String ACH_FIRST_SLAYER = "first_dragon_slayer";
    public static final String ACH_DEATHLESS = "deathless_clear";
    public static final String ACH_HARD_CONQUEROR = "hard_mode_conqueror";
    public static final String ACH_CRYSTAL_BREAKER = "crystal_breaker";
    public static final String ACH_FLAWLESS_MECHANIC = "flawless_mechanic_master";

    private static final List<AchievementDefinition> DEFINITIONS = List.of(
            new AchievementDefinition(ACH_FIRST_SLAYER, "ach.first_slayer.title", "ach.first_slayer.desc"),
            new AchievementDefinition(ACH_DEATHLESS, "ach.deathless.title", "ach.deathless.desc"),
            new AchievementDefinition(ACH_HARD_CONQUEROR, "ach.hard_conqueror.title", "ach.hard_conqueror.desc"),
            new AchievementDefinition(ACH_CRYSTAL_BREAKER, "ach.crystal_breaker.title", "ach.crystal_breaker.desc"),
            new AchievementDefinition(ACH_FLAWLESS_MECHANIC, "ach.flawless_mechanic.title", "ach.flawless_mechanic.desc")
    );

    private final Map<UUID, Set<String>> unlockedByPlayer = new ConcurrentHashMap<>();

    @EventHandler
    public void onEncounterCompleted(RaidEncounterCompletedEvent event) {
        Objects.requireNonNull(event, "event");
        EncounterResultSnapshot snap = event.snapshot();
        if (snap.outcome() != EncounterOutcome.VICTORY) {
            return;
        }
        for (ParticipantCombatMetrics metrics : snap.participantMetrics().values()) {
            Set<String> unlocked = unlockedByPlayer.computeIfAbsent(metrics.playerId(), id -> ConcurrentHashMap.newKeySet());
            unlocked.add(ACH_FIRST_SLAYER);
            if (metrics.downedCount() == 0) {
                unlocked.add(ACH_DEATHLESS);
            }
            if (snap.difficulty() == EncounterDifficulty.HARD) {
                unlocked.add(ACH_HARD_CONQUEROR);
            }
            if (metrics.totalCrystalsDestroyed() >= 3) {
                unlocked.add(ACH_CRYSTAL_BREAKER);
            }
        }
    }

    @EventHandler
    public void onMechanicResolved(RaidMechanicResolvedEvent event) {
        Objects.requireNonNull(event, "event");
        if (!event.perfectResolve()) {
            return;
        }
        Object eligible = event.tags().get("eligiblePlayers");
        if (eligible instanceof Collection<?> col) {
            for (Object item : col) {
                if (item instanceof UUID id) {
                    unlockedByPlayer.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet())
                            .add(ACH_FLAWLESS_MECHANIC);
                }
            }
        }
    }

    @Override
    public Collection<AchievementDefinition> definitions() {
        return DEFINITIONS;
    }

    @Override
    public Set<String> unlockedFor(UUID playerId) {
        if (playerId == null) {
            return Set.of();
        }
        Set<String> set = unlockedByPlayer.get(playerId);
        return set == null ? Set.of() : Set.copyOf(set);
    }
}
