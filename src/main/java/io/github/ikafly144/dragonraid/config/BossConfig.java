package io.github.ikafly144.dragonraid.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Strongly-typed immutable representation of {@code boss_normal.yml} and {@code boss_hard.yml}.
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

        public double castSeconds(double fallback) {
            return getDouble("cast-seconds", fallback);
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
        return phases.getLast();
    }
}
