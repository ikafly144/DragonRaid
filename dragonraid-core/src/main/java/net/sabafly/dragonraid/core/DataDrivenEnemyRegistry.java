package net.sabafly.dragonraid.core;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.enemy.RaidEnemyDefinition;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;

/**
 * Runtime registry holding the active encounter's data-driven {@link RaidEnemyDefinition}s.
 */
public final class DataDrivenEnemyRegistry {

    private final Map<String, RaidEnemyDefinition> definitions = new ConcurrentHashMap<>();
    private volatile String primaryBossEnemyId = "";

    public void loadFromBlueprint(EncounterBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        definitions.clear();
        definitions.putAll(blueprint.enemies());
        this.primaryBossEnemyId = blueprint.primaryBossEnemyId();
    }

    public void register(RaidEnemyDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        definitions.put(definition.id(), definition);
        if (definition.role() == EnemyRole.PRIMARY_BOSS && primaryBossEnemyId.isBlank()) {
            primaryBossEnemyId = definition.id();
        }
    }

    public Optional<RaidEnemyDefinition> find(String enemyId) {
        if (enemyId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(enemyId));
    }

    public Optional<RaidEnemyDefinition> primaryBoss() {
        return find(primaryBossEnemyId);
    }

    public Optional<RaidEnemyDefinition> firstByRole(EnemyRole role) {
        Objects.requireNonNull(role, "role");
        return definitions.values().stream().filter(d -> d.role() == role).findFirst();
    }

    public Collection<RaidEnemyDefinition> all() {
        return java.util.List.copyOf(definitions.values());
    }

    public void clear() {
        definitions.clear();
        primaryBossEnemyId = "";
    }
}
