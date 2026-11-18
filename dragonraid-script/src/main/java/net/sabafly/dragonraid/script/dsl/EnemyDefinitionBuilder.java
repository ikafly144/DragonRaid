package net.sabafly.dragonraid.script.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import net.sabafly.dragonraid.api.enemy.BoneOffset;
import net.sabafly.dragonraid.api.enemy.EnemyLinkSpec;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.enemy.EnemyStatsSpec;
import net.sabafly.dragonraid.api.enemy.ModelRendererType;
import net.sabafly.dragonraid.api.enemy.RaidEnemyDefinition;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * Fluent DSL builder for data-driven {@link RaidEnemyDefinition}s.
 */
public final class EnemyDefinitionBuilder {

    private final String id;
    private String displayNameKey = "";
    private EnemyRole role = EnemyRole.PRIMARY_BOSS;
    private EntityType baseEntityType = EntityType.ENDER_DRAGON;
    private ActorModelSpec modelSpec = ActorModelSpec.vanilla(1.0, Map.of());
    private EnemyStatsSpec statsSpec = new EnemyStatsSpec(1000.0, 0.0, false, true, false);
    private @Nullable EnemyLinkSpec linkSpec;
    private final List<String> passiveMechanics = new ArrayList<>();

    public EnemyDefinitionBuilder(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public EnemyDefinitionBuilder displayNameKey(String key) {
        this.displayNameKey = Objects.requireNonNull(key, "key");
        return this;
    }

    public EnemyDefinitionBuilder role(EnemyRole role) {
        this.role = Objects.requireNonNull(role, "role");
        return this;
    }

    public EnemyDefinitionBuilder entityType(EntityType type) {
        this.baseEntityType = Objects.requireNonNull(type, "type");
        return this;
    }

    public EnemyDefinitionBuilder vanillaModel(
            double scale,
            Map<String, BoneOffset> boneOffsets,
            Map<String, String> animationAliases) {
        this.modelSpec = new ActorModelSpec(
                ModelRendererType.VANILLA,
                null,
                scale,
                boneOffsets == null ? Map.of() : new LinkedHashMap<>(boneOffsets),
                animationAliases == null ? Map.of() : new LinkedHashMap<>(animationAliases)
        );
        return this;
    }

    public EnemyDefinitionBuilder betterModel(
            String betterModelId,
            double scale,
            Map<String, String> animationAliases) {
        this.modelSpec = new ActorModelSpec(
                ModelRendererType.BETTER_MODEL,
                betterModelId,
                scale,
                Map.of(),
                animationAliases == null ? Map.of() : new LinkedHashMap<>(animationAliases)
        );
        return this;
    }

    public EnemyDefinitionBuilder stats(
            double baseMaxHp,
            double hpPerExtraPlayer,
            boolean oneHitShatter,
            boolean suppressExplosion,
            boolean showBossBar) {
        this.statsSpec = new EnemyStatsSpec(baseMaxHp, hpPerExtraPlayer, oneHitShatter, suppressExplosion, showBossBar);
        return this;
    }

    public EnemyDefinitionBuilder link(
            String targetEnemyId,
            double healTargetPerSecond,
            boolean staggerTargetOnAllDestroyed,
            @Nullable String staggerAnimationKey,
            double staggerDurationSeconds) {
        this.linkSpec = new EnemyLinkSpec(
                targetEnemyId,
                healTargetPerSecond,
                staggerTargetOnAllDestroyed,
                staggerAnimationKey,
                staggerDurationSeconds
        );
        return this;
    }

    public EnemyDefinitionBuilder passiveMechanic(String mechanicId) {
        this.passiveMechanics.add(Objects.requireNonNull(mechanicId, "mechanicId"));
        return this;
    }

    public RaidEnemyDefinition build() {
        return new RaidEnemyDefinition(
                id,
                displayNameKey,
                role,
                baseEntityType,
                modelSpec,
                statsSpec,
                linkSpec,
                passiveMechanics
        );
    }
}
