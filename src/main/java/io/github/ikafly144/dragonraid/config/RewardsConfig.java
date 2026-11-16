package io.github.ikafly144.dragonraid.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Strongly-typed immutable representation of {@code rewards.yml}.
 */
public record RewardsConfig(
        Map<String, CurrencyDefinition> currencies,
        Map<BossConfig.Difficulty, DifficultyClearReward> clearRewards,
        Map<String, CustomGearDefinition> customGear,
        Map<String, CustomEnchantDefinition> customEnchants,
        ShopSettings shop
) {

    public record CurrencyDefinition(
            String id,
            String nameKey,
            String icon,
            int maxBalance
    ) {
    }

    public record DropEntry(
            String type,
            String id,
            int level,
            double chance,
            int minAmount,
            int maxAmount,
            boolean perPlayer
    ) {
    }

    public record DifficultyClearReward(
            Map<String, Integer> points,
            Map<String, Integer> firstClearBonus,
            List<DropEntry> drops
    ) {
    }

    public record CustomGearDefinition(
            String id,
            String nameKey,
            String loreKey,
            String material,
            int customModelData,
            Map<String, Double> stats
    ) {
        public double stat(String key, double fallback) {
            return stats.getOrDefault(key, fallback);
        }
    }

    public record CustomEnchantDefinition(
            String id,
            String nameKey,
            String loreKey,
            int maxLevel,
            Map<String, Double> stats
    ) {
        public double stat(String key, double fallback) {
            return stats.getOrDefault(key, fallback);
        }
    }

    public record ShopItemDefinition(
            String id,
            int slot,
            String type,
            String target,
            int level,
            int amount,
            boolean requiresHardClear,
            Map<String, Integer> costs
    ) {
    }

    public record ShopSettings(
            String titleKey,
            int size,
            List<ShopItemDefinition> items
    ) {
    }

    public Optional<CurrencyDefinition> currency(String id) {
        return Optional.ofNullable(currencies.get(id));
    }

    public Optional<DifficultyClearReward> clearReward(BossConfig.Difficulty difficulty) {
        return Optional.ofNullable(clearRewards.get(difficulty));
    }

    public Optional<CustomGearDefinition> gear(String id) {
        return Optional.ofNullable(customGear.get(id));
    }

    public Optional<CustomEnchantDefinition> enchant(String id) {
        return Optional.ofNullable(customEnchants.get(id));
    }
}
