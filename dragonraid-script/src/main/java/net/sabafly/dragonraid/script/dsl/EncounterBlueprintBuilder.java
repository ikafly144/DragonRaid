package net.sabafly.dragonraid.script.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.sabafly.dragonraid.api.enemy.RaidEnemyDefinition;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.EncounterPhaseSpec;
import net.sabafly.dragonraid.api.script.EnrageRuleSpec;
import net.sabafly.dragonraid.api.script.ReviveRuleSpec;

/**
 * Kotlin-script-ready fluent DSL builder constructing immutable {@link EncounterBlueprint}s.
 */
public final class EncounterBlueprintBuilder {

    private final String id;
    private EncounterDifficulty difficulty = EncounterDifficulty.NORMAL;
    private String displayNameKey = "";
    private String primaryBossEnemyId = "";
    private double baseAttackDamage = 8.0;
    private ReviveRuleSpec reviveRule = ReviveRuleSpec.normalDefault();
    private EnrageRuleSpec enrageRule = EnrageRuleSpec.normalDefault();
    private final Map<String, RaidEnemyDefinition> enemies = new LinkedHashMap<>();
    private final List<EncounterPhaseSpec> phases = new ArrayList<>();
    private final Map<String, Map<String, Object>> mechanicParams = new LinkedHashMap<>();

    public EncounterBlueprintBuilder(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public EncounterBlueprintBuilder difficulty(EncounterDifficulty difficulty) {
        this.difficulty = Objects.requireNonNull(difficulty, "difficulty");
        return this;
    }

    public EncounterBlueprintBuilder displayNameKey(String key) {
        this.displayNameKey = Objects.requireNonNull(key, "key");
        return this;
    }

    public EncounterBlueprintBuilder primaryBoss(String enemyId) {
        this.primaryBossEnemyId = Objects.requireNonNull(enemyId, "enemyId");
        return this;
    }

    public EncounterBlueprintBuilder baseAttackDamage(double damage) {
        this.baseAttackDamage = damage;
        return this;
    }

    public EncounterBlueprintBuilder revive(ReviveRuleSpec reviveRule) {
        this.reviveRule = Objects.requireNonNull(reviveRule, "reviveRule");
        return this;
    }

    public EncounterBlueprintBuilder enrage(EnrageRuleSpec enrageRule) {
        this.enrageRule = Objects.requireNonNull(enrageRule, "enrageRule");
        return this;
    }

    public EncounterBlueprintBuilder enemy(String enemyId, Consumer<EnemyDefinitionBuilder> block) {
        EnemyDefinitionBuilder builder = new EnemyDefinitionBuilder(enemyId);
        block.accept(builder);
        RaidEnemyDefinition def = builder.build();
        enemies.put(def.id(), def);
        return this;
    }

    public EncounterBlueprintBuilder phase(String phaseId, Consumer<PhaseSpecBuilder> block) {
        PhaseSpecBuilder builder = new PhaseSpecBuilder(phaseId);
        block.accept(builder);
        phases.add(builder.build());
        return this;
    }

    public EncounterBlueprintBuilder mechanic(String abilityKey, Map<String, Object> params) {
        Objects.requireNonNull(abilityKey, "abilityKey");
        mechanicParams.put(abilityKey, params == null ? Map.of() : new LinkedHashMap<>(params));
        return this;
    }

    public EncounterBlueprint build() {
        return new EncounterBlueprint(
                id,
                difficulty,
                displayNameKey,
                primaryBossEnemyId,
                baseAttackDamage,
                reviveRule,
                enrageRule,
                enemies,
                phases,
                mechanicParams
        );
    }
}
