package net.sabafly.dragonraid.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.enemy.ModelRendererType;
import net.sabafly.dragonraid.api.enemy.RaidEnemyDefinition;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.GimmickAction;
import net.sabafly.dragonraid.api.script.RaidScriptContext;
import net.sabafly.dragonraid.script.dsl.EncounterBlueprintBuilder;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class EncounterBlueprintDslTest {

    @Test
    void dslBuildsBlueprintAndRegistryManagesLifecycle() {
        EncounterBlueprint custom = new EncounterBlueprintBuilder("sample_encounter")
                .difficulty(EncounterDifficulty.NORMAL)
                .displayNameKey("encounter.sample.name")
                .primaryBoss("boss_alpha")
                .baseAttackDamage(10.0)
                .enemy("boss_alpha", e -> e
                        .role(EnemyRole.PRIMARY_BOSS)
                        .entityType(EntityType.ENDER_DRAGON)
                        .displayNameKey("enemy.boss_alpha")
                        .stats(500.0, 0.35, false, true, true)
                        .vanillaModel(1.0, Map.of(), Map.of()))
                .enemy("crystal_alpha", e -> e
                        .role(EnemyRole.OBJECTIVE_CRYSTAL)
                        .entityType(EntityType.END_CRYSTAL)
                        .displayNameKey("enemy.crystal_alpha")
                        .stats(80.0, 0.5, true, true, false)
                        .link("boss_alpha", 2.0, true, null, 5.0)
                        .vanillaModel(1.0, Map.of(), Map.of()))
                .mechanic("custom_beam", Map.of("name-key", "ability.custom_beam", "tag", "alpha"))
                .phase("phase_1", p -> p
                        .hpRange(1.0, 0.0)
                        .step(15.0, "CUSTOM_SCRIPT_BEAM", "custom_beam"))
                .build();

        DefaultEncounterBlueprintRegistry registry = new DefaultEncounterBlueprintRegistry();
        registry.register(custom);

        EncounterBlueprint loaded = registry.get("sample_encounter").orElseThrow();
        assertEquals(EncounterDifficulty.NORMAL, loaded.difficulty());
        assertEquals(1, loaded.phases().size());
        assertEquals(2, registry.get("sample_encounter").orElseThrow().enemies().size());

        RaidEnemyDefinition crystal = loaded.enemies().get("crystal_alpha");
        assertNotNull(crystal);
        assertEquals(EnemyRole.OBJECTIVE_CRYSTAL, crystal.role());
        assertTrue(crystal.statsSpec().oneHitShatter());
        assertNotNull(crystal.linkSpec());
        assertEquals("boss_alpha", crystal.linkSpec().linkedTargetEnemyId());
        assertTrue(crystal.linkSpec().staggerTargetOnAllDestroyed());
        assertEquals(ModelRendererType.VANILLA, crystal.modelSpec().rendererType());

        assertEquals(1, registry.all().size());
        assertTrue(registry.unregister("sample_encounter"));
        assertFalse(registry.unregister("sample_encounter"));
        assertTrue(registry.get("sample_encounter").isEmpty());
    }

    @Test
    void registryRegistersAndExecutesCustomGimmickActions() {
        DefaultGimmickActionRegistry registry = new DefaultGimmickActionRegistry();
        boolean[] executed = {false};
        registry.register(new GimmickAction() {
            @Override
            public String id() {
                return "CUSTOM_SCRIPT_BEAM";
            }

            @Override
            public void execute(RaidScriptContext context, Map<String, Object> params) {
                executed[0] = "ok".equals(params.get("tag"));
            }
        });

        GimmickAction loaded = registry.get("CUSTOM_SCRIPT_BEAM").orElseThrow();
        loaded.execute(mock(RaidScriptContext.class), Map.of("tag", "ok"));
        assertTrue(executed[0]);
    }
}
