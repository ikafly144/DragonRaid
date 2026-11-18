package net.sabafly.dragonraid.script;

import java.util.List;
import java.util.Map;
import net.sabafly.dragonraid.api.enemy.BoneOffset;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.EnrageRuleSpec;
import net.sabafly.dragonraid.api.script.ReviveRuleSpec;
import net.sabafly.dragonraid.script.dsl.EncounterBlueprintBuilder;
import org.bukkit.entity.EntityType;

/**
 * Pure DSL/Blueprint encounter and data-driven enemy definitions for Normal and Hard DragonRaid tiers.
 *
 * <p>Replaces legacy YAML boss timeline definitions with strongly-typed blueprints directly
 * aligned with the future {@code .raid.kts} scripting model.
 */
public final class BuiltInEncounters {

    public static final String ENEMY_ANCIENT_DRAGON = "ancient_dragon";
    public static final String ENEMY_MIRROR_DRAGON = "mirror_dragon";
    public static final String ENEMY_OBSIDIAN_CRYSTAL = "obsidian_crystal";
    public static final String ENEMY_VOID_ENDERMAN = "void_enderman";

    private BuiltInEncounters() {
    }

    public static EncounterBlueprint normalEncounter() {
        return new EncounterBlueprintBuilder("dragon_normal")
                .difficulty(EncounterDifficulty.NORMAL)
                .displayNameKey("boss.normal.name")
                .primaryBoss(ENEMY_ANCIENT_DRAGON)
                .baseAttackDamage(8.0)
                .revive(ReviveRuleSpec.normalDefault())
                .enrage(EnrageRuleSpec.normalDefault())
                .enemy(ENEMY_ANCIENT_DRAGON, e -> e
                        .displayNameKey("boss.normal.name")
                        .role(EnemyRole.PRIMARY_BOSS)
                        .entityType(EntityType.ENDER_DRAGON)
                        .vanillaModel(1.0, dragonBones(), dragonAnimations())
                        .stats(1200.0, 0.0, false, true, true))
                .enemy(ENEMY_OBSIDIAN_CRYSTAL, e -> e
                        .displayNameKey("ability.crystals.name")
                        .role(EnemyRole.OBJECTIVE_CRYSTAL)
                        .entityType(EntityType.END_CRYSTAL)
                        .vanillaModel(1.0, Map.of("core", new BoneOffset(0.0, 1.0, 0.0)), Map.of())
                        .stats(80.0, 0.0, true, true, false)
                        .link(ENEMY_ANCIENT_DRAGON, 4.0, true, "stagger_collapse", 6.0)
                        .passiveMechanic("PILLAR_LIGHTNING_DEFENSE")
                        .passiveMechanic("CRYSTAL_OUTER_BEAM"))
                .enemy(ENEMY_VOID_ENDERMAN, e -> e
                        .displayNameKey("ability.enderman_eruption.name")
                        .role(EnemyRole.GIMMICK_PROP)
                        .entityType(EntityType.ENDERMAN)
                        .vanillaModel(2.8, Map.of("head", new BoneOffset(0.0, 2.4, 0.0)), Map.of())
                        .stats(100.0, 0.0, false, true, false))
                // Additional reusable mechanic parameter presets available to custom scripts/rotations;
                // built-in Normal phases invoke the §4.1 True Ending mechanics below.
                .mechanic("tank_buster", Map.ofEntries(
                        Map.entry("name-key", "ability.tank_buster.name"),
                        Map.entry("cast-seconds", 3.0),
                        Map.entry("damage", 24.0),
                        Map.entry("shield-damage-multiplier", 0.25),
                        Map.entry("just-guard-window-ticks", 10),
                        Map.entry("just-guard-damage-multiplier", 0.10),
                        Map.entry("stagger-seconds", 3.0),
                        Map.entry("telegraph-radius", 3.5)
                ))
                .mechanic("stack", Map.of(
                        "name-key", "ability.stack.name",
                        "cast-seconds", 4.0,
                        "total-damage", 32.0,
                        "radius", 4.5
                ))
                .mechanic("ground_slam_dive", Map.of(
                        "name-key", "ability.ground_slam_dive.name",
                        "cast-seconds", 4.0,
                        "damage", 36.0,
                        "min-damage", 4.0,
                        "falloff-radius", 28.0
                ))
                .mechanic("lightning_storm", Map.of(
                        "name-key", "ability.lightning_storm.name",
                        "cast-seconds", 3.2,
                        "damage", 18.0,
                        "radius", 3.5,
                        "random-strikes", 6
                ))
                .mechanic("sweeping_laser", Map.of(
                        "name-key", "ability.sweeping_laser.name",
                        "cast-seconds", 5.0,
                        "damage", 24.0,
                        "beam-width", 2.6
                ))
                .mechanic("shield_wall_breath", Map.of(
                        "name-key", "ability.shield_wall_breath.name",
                        "cast-seconds", 4.5,
                        "damage", 34.0,
                        "shield-chip-multiplier", 0.20,
                        "safe-cone-half-angle", 32.0
                ))
                .mechanic("fireball_barrage", Map.of(
                        "name-key", "ability.fireball_barrage.name",
                        "cast-seconds", 3.6,
                        "damage", 16.0,
                        "radius", 3.2,
                        "impact-delay-seconds", 2.2,
                        "wave-interval-seconds", 1.1,
                        "orbs-per-wave", 3
                ))
                .mechanic("enderman_eruption", Map.of(
                        "name-key", "ability.enderman_eruption.name",
                        "cast-seconds", 3.5,
                        "damage", 20.0,
                        "radius", 3.4,
                        "pattern", "CHASING"
                ))
                .mechanic("exaflare_waves", Map.of(
                        "name-key", "ability.exaflare_waves.name",
                        "cast-seconds", 3.0,
                        "damage", 22.0
                ))
                .mechanic("knockback_combo", Map.of(
                        "name-key", "ability.knockback_combo.name",
                        "cast-seconds", 3.0,
                        "damage", 10.0,
                        "horizontal-impulse", 2.1,
                        "vertical-impulse", 0.55
                ))
                .mechanic("spread", Map.of(
                        "name-key", "ability.spread.name",
                        "cast-seconds", 4.0,
                        "damage", 14.0,
                        "radius", 4.5,
                        "overlap-penalty-multiplier", 2.0
                ))
                .mechanic("meteor_towers", Map.of(
                        "name-key", "ability.meteor_towers.name",
                        "cast-seconds", 6.0,
                        "tower-radius", 3.0,
                        "soak-damage", 8.0,
                        "unsoaked-raid-damage", 22.0
                ))
                .mechanic("los_breath", Map.of(
                        "name-key", "ability.los_breath.name",
                        "cast-seconds", 4.0,
                        "damage", 26.0,
                        "range", 96.0
                ))
                .mechanic("crystals", Map.of(
                        "name-key", "ability.crystals.name",
                        "base-hp", 80.0,
                        "tether-enabled", false,
                        "simultaneous-kill-window-seconds", 0.0,
                        "boss-regen-per-second", 4.0
                ))
                .phase("phase-1", p -> p
                        .displayNameKey("phase.p1.name")
                        .hpRange(1.00, 0.70)
                        .timelineSpeed(1.0)
                        .softEnrage(false)
                        .step(4.0, "SPAWN_CRYSTALS", "crystals")
                        .step(7.0, "FIREBALL_BARRAGE", "fireball_barrage")
                        .step(8.0, "DRAGON_DIVE_STACK", "stack")
                        .step(8.0, "LIGHTNING_STORM", "lightning_storm")
                        .step(8.0, "ENDERMAN_ERUPTION", "enderman_eruption")
                        .loop(10.0))
                .phase("phase-2", p -> p
                        .displayNameKey("phase.p2.name")
                        .hpRange(0.70, 0.40)
                        .timelineSpeed(1.0)
                        .softEnrage(false)
                        .step(4.0, "SPAWN_CRYSTALS", "crystals")
                        .step(7.0, "GROUND_SLAM_DIVE", "ground_slam_dive")
                        .step(9.0, "SWEEPING_LASER", "sweeping_laser")
                        .step(8.0, "SHIELD_WALL_BREATH", "shield_wall_breath")
                        .step(8.0, "KNOCKBACK_COMBO", "knockback_combo")
                        .loop(10.0))
                .phase("final", p -> p
                        .displayNameKey("phase.final.name")
                        .hpRange(0.40, 0.00)
                        .timelineSpeed(1.15)
                        .softEnrage(true)
                        .step(4.0, "SPAWN_CRYSTALS", "crystals")
                        .step(6.0, "EXAFLARE_WAVES", "exaflare_waves")
                        .step(7.0, "DRAGON_DIVE_STACK", "stack")
                        .step(8.0, "SWEEPING_LASER", "sweeping_laser")
                        .step(7.0, "SHIELD_WALL_BREATH", "shield_wall_breath")
                        .step(7.0, "GROUND_SLAM_DIVE", "ground_slam_dive")
                        .loop(9.0))
                .build();
    }

    public static EncounterBlueprint hardEncounter() {
        return new EncounterBlueprintBuilder("dragon_hard")
                .difficulty(EncounterDifficulty.HARD)
                .displayNameKey("boss.hard.name")
                .primaryBoss(ENEMY_ANCIENT_DRAGON)
                .baseAttackDamage(12.0)
                .revive(ReviveRuleSpec.hardDefault())
                .enrage(EnrageRuleSpec.hardDefault())
                .enemy(ENEMY_ANCIENT_DRAGON, e -> e
                        .displayNameKey("boss.hard.name")
                        .role(EnemyRole.PRIMARY_BOSS)
                        .entityType(EntityType.ENDER_DRAGON)
                        .vanillaModel(1.0, dragonBones(), dragonAnimations())
                        .stats(2400.0, 0.0, false, true, true))
                .enemy(ENEMY_MIRROR_DRAGON, e -> e
                        .displayNameKey("boss.hard.name")
                        .role(EnemyRole.SUB_BOSS)
                        .entityType(EntityType.ENDER_DRAGON)
                        .vanillaModel(1.0, dragonBones(), dragonAnimations())
                        .stats(2400.0, 0.0, false, true, false))
                .enemy(ENEMY_OBSIDIAN_CRYSTAL, e -> e
                        .displayNameKey("ability.crystals.name")
                        .role(EnemyRole.OBJECTIVE_CRYSTAL)
                        .entityType(EntityType.END_CRYSTAL)
                        .vanillaModel(1.0, Map.of("core", new BoneOffset(0.0, 1.0, 0.0)), Map.of())
                        .stats(120.0, 0.0, true, true, false)
                        .link(ENEMY_ANCIENT_DRAGON, 6.0, true, "stagger_collapse", 5.0)
                        .passiveMechanic("PILLAR_LIGHTNING_DEFENSE")
                        .passiveMechanic("CRYSTAL_OUTER_BEAM"))
                .enemy(ENEMY_VOID_ENDERMAN, e -> e
                        .displayNameKey("ability.enderman_eruption.name")
                        .role(EnemyRole.GIMMICK_PROP)
                        .entityType(EntityType.ENDERMAN)
                        .vanillaModel(3.2, Map.of("head", new BoneOffset(0.0, 2.4, 0.0)), Map.of())
                        .stats(140.0, 0.0, false, true, false))
                // Additional reusable mechanic parameter presets available to custom scripts/rotations;
                // built-in Normal phases invoke the §4.1 True Ending mechanics below.
                .mechanic("tank_buster", Map.ofEntries(
                        Map.entry("name-key", "ability.tank_buster.name"),
                        Map.entry("cast-seconds", 2.5),
                        Map.entry("damage", 34.0),
                        Map.entry("shield-damage-multiplier", 0.25),
                        Map.entry("just-guard-window-ticks", 8),
                        Map.entry("just-guard-damage-multiplier", 0.10),
                        Map.entry("stagger-seconds", 3.0),
                        Map.entry("telegraph-radius", 4.0)
                ))
                .mechanic("stack", Map.of(
                        "name-key", "ability.stack.name",
                        "cast-seconds", 3.8,
                        "total-damage", 44.0,
                        "radius", 4.5
                ))
                .mechanic("ground_slam_dive", Map.of(
                        "name-key", "ability.ground_slam_dive.name",
                        "cast-seconds", 3.5,
                        "damage", 46.0,
                        "min-damage", 6.0,
                        "falloff-radius", 30.0
                ))
                .mechanic("lightning_storm", Map.of(
                        "name-key", "ability.lightning_storm.name",
                        "cast-seconds", 2.8,
                        "damage", 24.0,
                        "radius", 3.8,
                        "random-strikes", 10
                ))
                .mechanic("sweeping_laser", Map.of(
                        "name-key", "ability.sweeping_laser.name",
                        "cast-seconds", 4.5,
                        "damage", 32.0,
                        "beam-width", 3.0
                ))
                .mechanic("shield_wall_breath", Map.of(
                        "name-key", "ability.shield_wall_breath.name",
                        "cast-seconds", 4.0,
                        "damage", 44.0,
                        "shield-chip-multiplier", 0.20,
                        "safe-cone-half-angle", 28.0
                ))
                .mechanic("fireball_barrage", Map.of(
                        "name-key", "ability.fireball_barrage.name",
                        "cast-seconds", 3.2,
                        "damage", 22.0,
                        "radius", 3.2,
                        "impact-delay-seconds", 2.2,
                        "wave-interval-seconds", 1.05,
                        "orbs-per-wave", 5
                ))
                .mechanic("enderman_eruption", Map.of(
                        "name-key", "ability.enderman_eruption.name",
                        "cast-seconds", 3.0,
                        "damage", 26.0,
                        "radius", 3.6,
                        "pattern", "RING_CASCADE"
                ))
                .mechanic("exaflare_waves", Map.of(
                        "name-key", "ability.exaflare_waves.name",
                        "cast-seconds", 2.6,
                        "damage", 28.0
                ))
                .mechanic("knockback_combo", Map.of(
                        "name-key", "ability.knockback_combo.name",
                        "cast-seconds", 2.8,
                        "damage", 14.0,
                        "horizontal-impulse", 2.3,
                        "vertical-impulse", 0.65
                ))
                .mechanic("enderman_rotating_beam", Map.of(
                        "name-key", "ability.enderman_rotating_beam.name",
                        "cast-seconds", 3.5,
                        "damage", 28.0,
                        "sweep-degrees", 120.0
                ))
                .mechanic("dual_dive_dice", Map.of(
                        "name-key", "ability.dual_dive_dice.name",
                        "cast-seconds", 4.2,
                        "total-damage", 44.0,
                        "radius", 4.5
                ))
                .mechanic("tether_dive_relay", Map.of(
                        "name-key", "ability.tether_dive_relay.name",
                        "cast-seconds", 5.4,
                        "damage", 20.0,
                        "relay-hits", 3,
                        "vulnerability-multiplier", 3.0
                ))
                .mechanic("spread", Map.of(
                        "name-key", "ability.spread.name",
                        "cast-seconds", 3.5,
                        "damage", 18.0,
                        "radius", 5.0,
                        "overlap-penalty-multiplier", 2.5
                ))
                .mechanic("meteor_towers", Map.of(
                        "name-key", "ability.meteor_towers.name",
                        "cast-seconds", 5.5,
                        "tower-radius", 3.0,
                        "soak-damage", 10.0,
                        "unsoaked-raid-damage", 30.0
                ))
                .mechanic("los_breath", Map.of(
                        "name-key", "ability.los_breath.name",
                        "cast-seconds", 3.5,
                        "damage", 36.0,
                        "range", 112.0
                ))
                .mechanic("crystals", Map.of(
                        "name-key", "ability.crystals.name",
                        "base-hp", 120.0,
                        "tether-enabled", true,
                        "simultaneous-kill-window-seconds", 6.0,
                        "boss-regen-per-second", 6.0
                ))
                .mechanic("donut_outer_aoe", Map.of(
                        "name-key", "ability.donut_outer_aoe.name",
                        "cast-seconds", 3.0,
                        "inner-safe-radius", 10.0,
                        "outer-radius", 38.0,
                        "damage", 22.0,
                        "follow-up-delay-seconds", 2.0
                ))
                .mechanic("knockback_roar", Map.of(
                        "name-key", "ability.knockback_roar.name",
                        "cast-seconds", 2.8,
                        "horizontal-impulse", 2.2,
                        "vertical-impulse", 0.65,
                        "damage", 10.0
                ))
                .mechanic("updraft_wind_field", Map.of(
                        "name-key", "ability.updraft_wind_field.name",
                        "field-count", 6,
                        "radius", 4.5,
                        "upward-velocity", 1.35,
                        "duration-seconds", 25.0
                ))
                .mechanic("celestial_crystals", Map.of(
                        "name-key", "ability.celestial_crystals.name",
                        "base-hp", 150.0,
                        "altitude-offset", 28.0,
                        "mace-smash-bonus-multiplier", 2.5,
                        "ranged-bow-multiplier", 0.65,
                        "stagger-seconds", 4.0,
                        "raid-cast-seconds", 30.0
                ))
                .phase("phase-1", p -> p
                        .displayNameKey("phase.p1.name")
                        .hpRange(1.00, 0.70)
                        .timelineSpeed(1.0)
                        .softEnrage(false)
                        .step(4.0, "SPAWN_TETHERED_CRYSTALS", "crystals")
                        .step(6.5, "DUAL_DIVE_DICE", "dual_dive_dice")
                        .step(7.5, "GROUND_SLAM_DIVE", "ground_slam_dive")
                        .step(7.5, "ENDERMAN_ROTATING_BEAM", "enderman_rotating_beam")
                        .step(8.0, "FIREBALL_BARRAGE", "fireball_barrage")
                        .loop(9.0))
                .phase("phase-2", p -> p
                        .displayNameKey("phase.p2.name")
                        .hpRange(0.70, 0.40)
                        .timelineSpeed(1.05)
                        .softEnrage(false)
                        .step(4.0, "SPAWN_TETHERED_CRYSTALS", "crystals")
                        .step(6.5, "TETHER_DIVE_RELAY", "tether_dive_relay")
                        .step(8.0, "SWEEPING_LASER", "sweeping_laser")
                        .step(7.5, "SHIELD_WALL_BREATH", "shield_wall_breath")
                        .step(7.0, "KNOCKBACK_COMBO", "knockback_combo")
                        .loop(9.0))
                .phase("phase-3", p -> p
                        .displayNameKey("phase.p3.name")
                        .hpRange(0.40, 0.25)
                        .timelineSpeed(1.1)
                        .softEnrage(false)
                        .step(3.0, "EXAFLARE_WAVES", "exaflare_waves")
                        .step(7.0, "ENDERMAN_ROTATING_BEAM", "enderman_rotating_beam")
                        .step(7.5, "DUAL_DIVE_DICE", "dual_dive_dice")
                        .step(7.5, "ENDERMAN_ERUPTION", "enderman_eruption")
                        .loop(9.0))
                .phase("final", p -> p
                        .displayNameKey("phase.final.name")
                        .hpRange(0.25, 0.00)
                        .timelineSpeed(1.25)
                        .softEnrage(true)
                        .step(3.0, "SPAWN_TETHERED_CRYSTALS", "crystals")
                        .step(5.5, "EXAFLARE_WAVES", "exaflare_waves")
                        .step(6.5, "TETHER_DIVE_RELAY", "tether_dive_relay")
                        .step(6.5, "SWEEPING_LASER", "sweeping_laser")
                        .step(6.0, "SHIELD_WALL_BREATH", "shield_wall_breath")
                        .step(6.0, "DUAL_DIVE_DICE", "dual_dive_dice")
                        .loop(7.0))
                .build();
    }

    public static List<EncounterBlueprint> allBuiltIn() {
        return List.of(normalEncounter(), hardEncounter());
    }

    private static Map<String, BoneOffset> dragonBones() {
        return Map.of(
                "mouth", new BoneOffset(0.0, 0.8, 3.6),
                "head", new BoneOffset(0.0, 1.2, 3.0),
                "chest_core", new BoneOffset(0.0, 1.4, 0.0)
        );
    }

    private static Map<String, String> dragonAnimations() {
        return Map.of(
                "orbit", "orbit",
                "hover", "hover",
                "ascend_charge", "ascend_charge",
                "dive_attack", "dive_attack",
                "stagger_collapse", "stagger_collapse"
        );
    }
}
