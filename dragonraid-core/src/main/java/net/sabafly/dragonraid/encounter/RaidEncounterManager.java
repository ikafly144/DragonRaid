package net.sabafly.dragonraid.encounter;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.scaling.ScaledEncounterParameters;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Coordinates the DragonRaid encounter lifecycle: pull countdowns, late-joiner locks,
 * participant states ({@code ALIVE}, {@code DOWNED}, {@code DEAD_SPECTATOR}), revives, wipes,
 * and end-of-attempt cleanup.
 */
public interface RaidEncounterManager {

    RaidState state();

    boolean isProtectionActive();

    /**
     * Returns {@code true} if no raid is active/pending and no temporary blocks are awaiting rollback.
     */
    boolean canModifyArenaRegion();

    /**
     * Reserves an exclusive arena-region modification slot if allowed, returning {@code false} if blocked.
     */
    boolean tryBeginArenaRegionUpdate();

    /**
     * Releases a previously acquired arena-region modification slot.
     */
    void endArenaRegionUpdate();

    Optional<ScaledEncounterParameters> activeScaling();

    int remainingRevives();

    boolean isParticipant(UUID playerId);

    Optional<ParticipantState> participantState(UUID playerId);

    boolean isDowned(UUID playerId);

    boolean isDeadSpectator(UUID playerId);

    /**
     * Initiates a data-driven encounter directly from the given {@link EncounterBlueprint}
     * for an explicit party of online players.
     */
    default boolean startEncounter(EncounterBlueprint blueprint, Collection<? extends Player> players) {
        return false;
    }

    void adminStart(CommandSender sender, BossConfig.Difficulty difficulty);

    void adminReset(CommandSender sender);

    void onPlayerMove(PlayerMoveEvent event);

    void onPlayerJoin(Player player);

    void onPlayerQuit(Player player);

    void onPlayerChangedWorld(Player player);

    /**
     * Evaluates incoming player damage during an active raid, intercepting lethal hits into {@code DOWNED}
     * or cancelling damage for already {@code DOWNED} / {@code DEAD_SPECTATOR} participants.
     */
    void onPlayerDamage(Player player, EntityDamageEvent event);

    /**
     * Applies item/XP retention rules if a participant death event fires during an encounter.
     */
    void onParticipantDeath(Player player, PlayerDeathEvent event);

    /**
     * Records a right-click interaction pulse to support hold-to-revive with empty/non-usable hands.
     */
    void recordRightClickPulse(Player player);

    /**
     * Records participant damage dealt to a raid boss entity for combat telemetry.
     */
    default void onBossDamage(EnderDragon dragon, EntityDamageEvent event) {
    }

    /**
     * Cancels vanilla explosion side effects for raid-controlled entities.
     */
    default void onEntityExplode(Entity entity, Cancellable event) {
    }

    /**
     * Handles EnderDragon defeat during an active encounter, performing cleanup and rollback.
     */
    void onDragonDefeated(EnderDragon dragon);

    /**
     * Restores all modified player states and cancels active encounter tasks on plugin shutdown.
     */
    void shutdown();
}
