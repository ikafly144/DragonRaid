package net.sabafly.dragonraid.api.script;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.sabafly.dragonraid.api.enemy.RaidEnemyDefinition;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe execution façade exposed to scripted and modular {@link GimmickAction}s.
 *
 * <p>Metadata reads return thread-safe snapshots, while any access to a {@link Player}'s live
 * entity state (e.g. location) or mutation must dispatch via {@link #damagePlayer},
 * {@link #knockbackPlayer}, or {@link #scheduler()}'s {@code runAtEntity}.
 */
public interface RaidScriptContext {

    World world();

    /**
     * Returns an immutable list copy of online alive participant handles at context creation.
     * Mutating or reading live entity state on these handles must be dispatched onto each player's
     * owning Folia entity thread via {@link #scheduler()}.
     */
    List<Player> aliveParticipants();

    @Nullable Location bossLocation();

    Location arenaCenter();

    boolean isShieldBlocking(UUID playerId);

    Optional<RaidEnemyDefinition> enemyDefinition(String enemyId);

    @Nullable ActorModelController primaryBossModel();

    RaidScheduler scheduler();

    void damagePlayer(Player player, double amount, String mechanicId);

    void knockbackPlayer(Player player, Vector velocity);
}
