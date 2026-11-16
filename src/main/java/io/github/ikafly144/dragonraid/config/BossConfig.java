package io.github.ikafly144.dragonraid.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Strongly-typed immutable representation of {@code boss_normal.yml} and {@code boss_hard.yml}.
 */
public record BossConfig(
        Difficulty difficulty,
        String displayNameKey,
        double baseHp,
        double baseAttackDamage,
        BossReviveSettings revive,
        EnrageSettings enrage,
        Map<String, AbilitySettings> abilities,
        List<PhaseConfig> phases
) {

    public enum Difficulty {
        NORMAL,
        HARD
    }

    public enum ReviveFormula {
        PER_PLAYER,
        HALF_CEIL
    }

    public record BossReviveSettings(
            ReviveFormula formula,
            double multiplier,
            int downedTimeoutSeconds
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

    public record SoftEnrageSettings(
            boolean enabled,
            String startPhase,
            double voidCorruptionShrinkPerSecond,
            double voidDamagePerSecond
    ) {
    }

    public record HardEnrageSettings(
            boolean enabled,
            int timeLimitSeconds,
            String abilityNameKey,
            double castSeconds
    ) {
    }

    public record EnrageSettings(
            SoftEnrageSettings softEnrage,
            HardEnrageSettings hardEnrage
    ) {
    }

    /**
     * Generic ability definition backed by immutable parameter map so each mechanic can expose
     * standard and mechanic-specific parameters uniformly.
     */
    public record AbilitySettings(
            String id,
            String nameKey,
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

    public record TimelineStep(
            double delaySeconds,
            String action,
            String ability
    ) {
    }

    public record PhaseConfig(
            String id,
            String nameKey,
            double hpThresholdMax,
            double hpThresholdMin,
            double timelineSpeed,
            boolean softEnrage,
            List<TimelineStep> timeline
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
