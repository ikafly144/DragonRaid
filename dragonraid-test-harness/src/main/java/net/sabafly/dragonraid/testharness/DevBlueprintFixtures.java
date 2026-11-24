package net.sabafly.dragonraid.testharness;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.sabafly.dragonraid.api.economy.CurrencyDefinition;
import net.sabafly.dragonraid.api.economy.RaidEconomyService;
import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import net.sabafly.dragonraid.api.enemy.BoneOffset;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.enemy.EnemyStatsSpec;
import net.sabafly.dragonraid.api.enemy.RaidEnemyDefinition;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterBlueprintRegistry;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.EncounterPhaseSpec;
import net.sabafly.dragonraid.api.script.EnrageRuleSpec;
import net.sabafly.dragonraid.api.script.GimmickAction;
import net.sabafly.dragonraid.api.script.GimmickActionRegistry;
import net.sabafly.dragonraid.api.script.RaidScriptContext;
import net.sabafly.dragonraid.api.script.ReviveRuleSpec;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import org.bukkit.entity.EntityType;

/**
 * Registers verification {@link EncounterBlueprint}s, {@link GimmickAction}s, {@link ScriptHookRegistry}
 * callbacks, and {@link CurrencyDefinition}s through DragonRaid's public {@link RaidServiceRegistry}.
 */
final class DevBlueprintFixtures {

    static final String BLUEPRINT_PRACTICE = "dragon_practice";
    static final String BLUEPRINT_NORMAL = "dragon_normal";
    static final String BLUEPRINT_HARD = "dragon_hard";
    static final String GIMMICK_DEV_PULSE = "DEV_ARENA_PULSE";
    static final String HOOK_ON_CLEAR = "dev_ender_dragon:on_clear";
    static final String CURRENCY_DRAGON_TOKEN = "dragon_token";
    static final String CURRENCY_ABYSSAL_SCALE = "abyssal_scale";

    private final AtomicInteger clearHookInvocations = new AtomicInteger(0);
    private final AtomicInteger gimmickInvocations = new AtomicInteger(0);
    private volatile String lastHookThreadName = "";

    void registerAll(RaidServiceRegistry registry) {
        Objects.requireNonNull(registry, "registry");

        RaidEconomyService economy = registry.require(RaidEconomyService.class);
        economy.registerCurrency(new CurrencyDefinition(
                CURRENCY_DRAGON_TOKEN,
                "currency.dragon_token",
                2000L,
                450L
        ));
        economy.registerCurrency(new CurrencyDefinition(
                CURRENCY_ABYSSAL_SCALE,
                "currency.abyssal_scale",
                500L,
                100L
        ));

        ScriptHookRegistry scriptHooks = registry.require(ScriptHookRegistry.class);
        scriptHooks.registerAction(HOOK_ON_CLEAR, context -> {
            lastHookThreadName = Thread.currentThread().getName();
            clearHookInvocations.incrementAndGet();
        });

        GimmickActionRegistry gimmicks = registry.require(GimmickActionRegistry.class);
        gimmicks.register(new GimmickAction() {
            @Override
            public String id() {
                return GIMMICK_DEV_PULSE;
            }

            @Override
            public void execute(RaidScriptContext context, Map<String, Object> params) {
                gimmickInvocations.incrementAndGet();
            }
        });

        EncounterBlueprintRegistry blueprints = registry.require(EncounterBlueprintRegistry.class);
        blueprints.register(buildDragonBlueprint(BLUEPRINT_PRACTICE, EncounterDifficulty.NORMAL, 300.0));
        blueprints.register(buildDragonBlueprint(BLUEPRINT_NORMAL, EncounterDifficulty.NORMAL, 600.0));
        blueprints.register(buildDragonBlueprint(BLUEPRINT_HARD, EncounterDifficulty.HARD, 1200.0));
    }

    int clearHookInvocations() {
        return clearHookInvocations.get();
    }

    String lastHookThreadName() {
        return lastHookThreadName;
    }

    private static EncounterBlueprint buildDragonBlueprint(
            String blueprintId,
            EncounterDifficulty difficulty,
            double baseHp) {
        ActorModelSpec modelSpec = ActorModelSpec.vanilla(
                1.0,
                Map.of(
                        "head", new BoneOffset(0.0, 2.5, 6.0),
                        "mouth", new BoneOffset(0.0, 2.0, 7.0),
                        "chest_core", new BoneOffset(0.0, 1.5, 0.0)
                )
        );
        RaidEnemyDefinition primaryBoss = new RaidEnemyDefinition(
                "ender_dragon_boss",
                "boss.ender_dragon.name",
                EnemyRole.PRIMARY_BOSS,
                EntityType.ENDER_DRAGON,
                modelSpec,
                new EnemyStatsSpec(baseHp, 0.5, false, true, true),
                null,
                List.of()
        );

        List<EncounterPhaseSpec> phases = List.of(
                new EncounterPhaseSpec(
                        "opening",
                        "phase.opening",
                        1.0,
                        0.5,
                        1.0,
                        false,
                        12.0,
                        List.of(GIMMICK_DEV_PULSE),
                        List.of(),
                        List.of()
                ),
                new EncounterPhaseSpec(
                        "final",
                        "phase.final",
                        0.5,
                        0.0,
                        1.15,
                        true,
                        10.0,
                        List.of(GIMMICK_DEV_PULSE),
                        List.of(),
                        List.of()
                )
        );

        return new EncounterBlueprint(
                blueprintId,
                difficulty,
                "blueprint." + blueprintId + ".name",
                primaryBoss.id(),
                difficulty == EncounterDifficulty.HARD ? 10.0 : 6.0,
                difficulty == EncounterDifficulty.HARD ? ReviveRuleSpec.hardDefault() : ReviveRuleSpec.normalDefault(),
                difficulty == EncounterDifficulty.HARD ? EnrageRuleSpec.hardDefault() : EnrageRuleSpec.normalDefault(),
                Map.of(primaryBoss.id(), primaryBoss),
                phases,
                Map.of(GIMMICK_DEV_PULSE, Map.of("radius", 8.0))
        );
    }
}
