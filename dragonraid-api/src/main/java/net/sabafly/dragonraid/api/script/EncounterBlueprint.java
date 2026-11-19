package net.sabafly.dragonraid.api.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.sabafly.dragonraid.api.enemy.RaidEnemyDefinition;

/**
 * Complete data-driven encounter blueprint unifying enemy definitions, phases, timelines,
 * mechanic parameters, and revive/enrage rules.
 */
public record EncounterBlueprint(
        String id,
        EncounterDifficulty difficulty,
        String displayNameKey,
        String primaryBossEnemyId,
        double baseAttackDamage,
        ReviveRuleSpec reviveRule,
        EnrageRuleSpec enrageRule,
        Map<String, RaidEnemyDefinition> enemies,
        List<EncounterPhaseSpec> phases,
        Map<String, Map<String, Object>> mechanicParams
) {

    public EncounterBlueprint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(displayNameKey, "displayNameKey");
        if (primaryBossEnemyId == null || primaryBossEnemyId.isBlank()) {
            throw new IllegalArgumentException("primaryBossEnemyId must not be blank");
        }
        Objects.requireNonNull(reviveRule, "reviveRule");
        Objects.requireNonNull(enrageRule, "enrageRule");
        enemies = enemies == null ? Map.of() : Map.copyOf(enemies);
        if (!enemies.containsKey(primaryBossEnemyId)) {
            throw new IllegalArgumentException("primaryBossEnemyId not found in enemies map: " + primaryBossEnemyId);
        }
        phases = phases == null ? List.of() : List.copyOf(phases);
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("EncounterBlueprint must define at least one phase");
        }
        Map<String, Map<String, Object>> frozenParams = new LinkedHashMap<>();
        if (mechanicParams != null) {
            for (Map.Entry<String, Map<String, Object>> entry : mechanicParams.entrySet()) {
                frozenParams.put(
                        entry.getKey(),
                        net.sabafly.dragonraid.api.util.ImmutableSnapshots.copyStringMap(entry.getValue())
                );
            }
        }
        mechanicParams = Collections.unmodifiableMap(frozenParams);
    }

    public EncounterBlueprint(
            String id,
            EncounterDifficulty difficulty,
            String displayNameKey,
            String primaryBossEnemyId,
            Map<String, RaidEnemyDefinition> enemies,
            List<EncounterPhaseSpec> phases,
            Map<String, Map<String, Object>> mechanicParams) {
        this(
                id,
                difficulty,
                displayNameKey,
                primaryBossEnemyId,
                difficulty == EncounterDifficulty.HARD ? 12.0 : 8.0,
                difficulty == EncounterDifficulty.HARD ? ReviveRuleSpec.hardDefault() : ReviveRuleSpec.normalDefault(),
                difficulty == EncounterDifficulty.HARD ? EnrageRuleSpec.hardDefault() : EnrageRuleSpec.normalDefault(),
                enemies,
                phases,
                mechanicParams
        );
    }

    public RaidEnemyDefinition primaryBossDefinition() {
        return enemies.get(primaryBossEnemyId);
    }
}
