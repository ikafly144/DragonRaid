package net.sabafly.dragonraid.api.enemy;

import java.util.List;
import java.util.Objects;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * Data-driven definition unifying bosses, sub-bosses, destructible crystals, summoned adds,
 * and spatial gimmick actors.
 *
 * @param id unique enemy definition identifier within an encounter (e.g. {@code "ancient_dragon"})
 * @param displayNameKey MiniMessage translation key for UI display
 * @param role encounter role played by this enemy
 * @param baseEntityType underlying Bukkit {@link EntityType} (used as hitbox / AI / vanilla actor base)
 * @param modelSpec visual model and bone configuration ({@link ModelRendererType#BETTER_MODEL} or {@link ModelRendererType#VANILLA})
 * @param statsSpec health, scaling, shatter, and boss-bar properties
 * @param linkSpec optional link to another enemy definition
 * @param passiveMechanicIds passive mechanic IDs driven while instances of this enemy are alive
 */
public record RaidEnemyDefinition(
        String id,
        String displayNameKey,
        EnemyRole role,
        EntityType baseEntityType,
        ActorModelSpec modelSpec,
        EnemyStatsSpec statsSpec,
        @Nullable EnemyLinkSpec linkSpec,
        List<String> passiveMechanicIds
) {

    public RaidEnemyDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(displayNameKey, "displayNameKey");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(baseEntityType, "baseEntityType");
        Objects.requireNonNull(modelSpec, "modelSpec");
        Objects.requireNonNull(statsSpec, "statsSpec");
        passiveMechanicIds = passiveMechanicIds == null ? List.of() : List.copyOf(passiveMechanicIds);
    }
}
