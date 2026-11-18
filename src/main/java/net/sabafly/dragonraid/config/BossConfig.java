package net.sabafly.dragonraid.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.EncounterPhaseSpec;
import net.sabafly.dragonraid.api.script.EnrageRuleSpec;
import net.sabafly.dragonraid.api.script.ReviveRuleSpec;
import net.sabafly.dragonraid.api.script.TimelineEntrySpec;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Strongly-typed immutable boss encounter settings built from {@link EncounterBlueprint}
 * definitions in {@code dragonraid-script}.
 */
@ConfigSerializable
public record BossConfig(
        @Setting("difficulty") Difficulty difficulty,
        @Setting("display-name-key") String displayNameKey,
        @Setting("base-hp") double baseHp,
        @Setting("base-attack-damage") double baseAttackDamage,
        @Setting("revive") BossReviveSettings revive,
        @Setting("enrage") EnrageSettings enrage,
        @Setting("abilities") Map<String, AbilitySettings> abilities,
        @Setting("phases") List<PhaseConfig> phases
) {

    public enum Difficulty {
        NORMAL,
        HARD
    }

    public enum ReviveFormula {
        PER_PLAYER,
        HALF_CEIL
    }

    @ConfigSerializable
    public record BossReviveSettings(
            @Setting("formula") ReviveFormula formula,
            @Setting("multiplier") double multiplier,
            @Setting("downed-timeout-seconds") int downedTimeoutSeconds
    ) {
        /**
         * Computes maximum revives allowed for the encounter given {@code playerCount}.
         */
        public int computeMaxRevives(int playerCount) {
            int n = Math.max(1, playerCount);
            return switch (formula) {
                case PER_PLAYER -> Math.max(0, (int) Math.floor(n * multiplier));
                case HALF_CEIL -> Math.max(0, (int) Math.ceil(n * multiplier));
            };
        }

        public boolean hasDownedTimeout() {
            return downedTimeoutSeconds > 0;
        }
    }

    @ConfigSerializable
    public record SoftEnrageSettings(
            @Setting("enabled") boolean enabled,
            @Setting("start-phase") String startPhase,
            @Setting("void-corruption-shrink-per-second") double voidCorruptionShrinkPerSecond,
            @Setting("void-damage-per-second") double voidDamagePerSecond
    ) {
    }

    @ConfigSerializable
    public record HardEnrageSettings(
            @Setting("enabled") boolean enabled,
            @Setting("time-limit-seconds") int timeLimitSeconds,
            @Setting("ability-name-key") String abilityNameKey,
            @Setting("cast-seconds") double castSeconds
    ) {
    }

    @ConfigSerializable
    public record EnrageSettings(
            @Setting("soft-enrage") SoftEnrageSettings softEnrage,
            @Setting("hard-enrage") HardEnrageSettings hardEnrage
    ) {
    }

    /**
     * Generic ability definition backed by immutable parameter map so each mechanic can expose
     * standard and mechanic-specific parameters uniformly.
     */
    @ConfigSerializable
    public record AbilitySettings(
            String id,
            @Setting("name-key") String nameKey,
            Map<String, Object> parameters
    ) {
        public AbilitySettings {
            parameters = Map.copyOf(parameters);
        }

        public double castSeconds() {
            return getDouble("cast-seconds", 3.5);
        }

        public double castSeconds(double fallback) {
            return getDouble("cast-seconds", fallback);
        }

        public String getString(String key, String fallback) {
            Object value = parameters.get(key);
            return value != null ? String.valueOf(value) : fallback;
        }

        public double damage(double fallback) {
            return getDouble("damage", fallback);
        }

        public double radius(double fallback) {
            return getDouble("radius", fallback);
        }

        public double getDouble(String key, double fallback) {
            Object value = parameters.get(key);
            return value instanceof Number num ? num.doubleValue() : fallback;
        }

        public int getInt(String key, int fallback) {
            Object value = parameters.get(key);
            return value instanceof Number num ? num.intValue() : fallback;
        }

        public boolean getBoolean(String key, boolean fallback) {
            Object value = parameters.get(key);
            return value instanceof Boolean bool ? bool : fallback;
        }
    }

    @ConfigSerializable
    public record TimelineStep(
            @Setting("delay-seconds") double delaySeconds,
            @Setting("action") String action,
            @Setting("ability") String ability
    ) {
    }

    @ConfigSerializable
    public record PhaseConfig(
            @Setting("id") String id,
            @Setting("name-key") String nameKey,
            @Setting("hp-threshold-max") double hpThresholdMax,
            @Setting("hp-threshold-min") double hpThresholdMin,
            @Setting("timeline-speed") double timelineSpeed,
            @Setting("soft-enrage") boolean softEnrage,
            @Setting("timeline") List<TimelineStep> timeline
    ) {
        public boolean matchesHpRatio(double hpRatio) {
            return hpRatio <= hpThresholdMax && hpRatio >= hpThresholdMin;
        }
    }

    /**
     * Converts a data-driven {@link EncounterBlueprint} into {@link BossConfig} for encounter execution.
     */
    public static BossConfig fromBlueprint(EncounterBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        Difficulty difficulty = blueprint.difficulty() == EncounterDifficulty.HARD
                ? Difficulty.HARD
                : Difficulty.NORMAL;
        double baseHp = blueprint.primaryBossDefinition().statsSpec().baseMaxHp();

        ReviveRuleSpec rev = blueprint.reviveRule();
        ReviveFormula formula = rev.formula() == ReviveRuleSpec.Formula.HALF_CEIL
                ? ReviveFormula.HALF_CEIL
                : ReviveFormula.PER_PLAYER;
        BossReviveSettings reviveSettings = new BossReviveSettings(
                formula, rev.multiplier(), rev.downedTimeoutSeconds());

        EnrageRuleSpec enr = blueprint.enrageRule();
        EnrageSettings enrageSettings = new EnrageSettings(
                new SoftEnrageSettings(
                        enr.softEnabled(),
                        enr.softStartPhase(),
                        enr.voidShrinkPerSecond(),
                        enr.voidDamagePerSecond()),
                new HardEnrageSettings(
                        enr.hardEnabled(),
                        enr.hardTimeLimitSeconds(),
                        enr.hardAbilityNameKey(),
                        enr.hardCastSeconds()));

        Map<String, AbilitySettings> abilities = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : blueprint.mechanicParams().entrySet()) {
            String abilityId = entry.getKey();
            Map<String, Object> raw = entry.getValue();
            String nameKey = raw.containsKey("name-key")
                    ? String.valueOf(raw.get("name-key"))
                    : "ability." + abilityId + ".name";
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<String, Object> param : raw.entrySet()) {
                if (!"name-key".equals(param.getKey()) && param.getValue() != null) {
                    params.put(param.getKey(), param.getValue());
                }
            }
            abilities.put(abilityId, new AbilitySettings(abilityId, nameKey, params));
        }

        List<PhaseConfig> phases = new ArrayList<>(blueprint.phases().size());
        for (EncounterPhaseSpec spec : blueprint.phases()) {
            List<TimelineStep> steps = new ArrayList<>(spec.timeline().size());
            for (TimelineEntrySpec stepSpec : spec.timeline()) {
                String abilityId = stepSpec.abilityId().isBlank() ? null : stepSpec.abilityId();
                if (abilityId != null && !abilities.containsKey(abilityId)) {
                    throw new ConfigException(
                            "blueprint:" + blueprint.id(),
                            "phases." + spec.id() + ".timeline",
                            "Unknown ability referenced in blueprint timeline: " + abilityId);
                }
                steps.add(new TimelineStep(stepSpec.delaySeconds(), stepSpec.actionId(), abilityId));
            }
            phases.add(new PhaseConfig(
                    spec.id(),
                    spec.displayNameKey(),
                    spec.hpThresholdMax(),
                    spec.hpThresholdMin(),
                    spec.timelineSpeed(),
                    spec.softEnrage(),
                    List.copyOf(steps)));
        }

        return new BossConfig(
                difficulty,
                blueprint.displayNameKey(),
                baseHp,
                blueprint.baseAttackDamage(),
                reviveSettings,
                enrageSettings,
                Collections.unmodifiableMap(abilities),
                List.copyOf(phases));
    }

    public Optional<AbilitySettings> ability(String id) {
        return Optional.ofNullable(abilities.get(id));
    }

    /**
     * Resolves the active phase for a boss HP ratio in {@code [0.0, 1.0]}.
     */
    public PhaseConfig phaseForHpRatio(double hpRatio) {
        double clamped = Math.max(0.0, Math.min(1.0, hpRatio));
        for (PhaseConfig phase : phases) {
            if (clamped > phase.hpThresholdMin() && clamped <= phase.hpThresholdMax()) {
                return phase;
            }
        }
        // Fallback includes lower-bound equality (most notably hpRatio == 0.0) regardless of phase ordering.
        for (PhaseConfig phase : phases) {
            if (phase.matchesHpRatio(clamped)) {
                return phase;
            }
        }
        PhaseConfig lowest = phases.getFirst();
        for (PhaseConfig phase : phases) {
            if (phase.hpThresholdMin() < lowest.hpThresholdMin()) {
                lowest = phase;
            }
        }
        return lowest;
    }
}
