package net.sabafly.dragonraid.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;

class BuiltInEncountersTest {

    @Test
    void builtInNormalAndHardDefineDataDrivenEnemiesLinksAndPhases() {
        EncounterBlueprint normal = BuiltInEncounters.normalEncounter();
        assertEquals(EncounterDifficulty.NORMAL, normal.difficulty());
        assertEquals(3, normal.phases().size());

        RaidEnemyDefinition crystal = normal.enemies().get(BuiltInEncounters.ENEMY_OBSIDIAN_CRYSTAL);
        assertNotNull(crystal);
        assertEquals(EnemyRole.OBJECTIVE_CRYSTAL, crystal.role());
        assertTrue(crystal.statsSpec().oneHitShatter());
        assertNotNull(crystal.linkSpec());
        assertEquals(BuiltInEncounters.ENEMY_ANCIENT_DRAGON, crystal.linkSpec().linkedTargetEnemyId());
        assertTrue(crystal.linkSpec().staggerTargetOnAllDestroyed());
        assertEquals(ModelRendererType.VANILLA, crystal.modelSpec().rendererType());

        EncounterBlueprint hard = BuiltInEncounters.hardEncounter();
        assertEquals(EncounterDifficulty.HARD, hard.difficulty());
        assertEquals(4, hard.phases().size());
        assertTrue(hard.enemies().containsKey(BuiltInEncounters.ENEMY_MIRROR_DRAGON));
        assertEquals(EnemyRole.SUB_BOSS, hard.enemies().get(BuiltInEncounters.ENEMY_MIRROR_DRAGON).role());
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
