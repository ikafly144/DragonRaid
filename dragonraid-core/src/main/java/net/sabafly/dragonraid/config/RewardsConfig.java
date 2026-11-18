package net.sabafly.dragonraid.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Strongly-typed immutable representation of {@code rewards.yml}.
 */
@ConfigSerializable
public record RewardsConfig(
        @Setting("currencies") Map<String, CurrencyDefinition> currencies,
        @Setting("clear-rewards") Map<BossConfig.Difficulty, DifficultyClearReward> clearRewards,
        @Setting("custom-gear") Map<String, CustomGearDefinition> customGear,
        @Setting("custom-enchants") Map<String, CustomEnchantDefinition> customEnchants,
        @Setting("shop") ShopSettings shop
) {

    @ConfigSerializable
    public record CurrencyDefinition(
            String id,
            @Setting("name-key") String nameKey,
            @Setting("icon") String icon,
            @Setting("max-balance") int maxBalance
    ) {
    }

    @ConfigSerializable
    public record DropEntry(
            @Setting("type") String type,
            @Setting("id") String id,
            @Setting("level") int level,
            @Setting("chance") double chance,
            @Setting("min-amount") int minAmount,
            @Setting("max-amount") int maxAmount,
            @Setting("per-player") boolean perPlayer
    ) {
    }

    @ConfigSerializable
    public record DifficultyClearReward(
            @Setting("points") Map<String, Integer> points,
            @Setting("first-clear-bonus") Map<String, Integer> firstClearBonus,
            @Setting("drops") List<DropEntry> drops
    ) {
    }

    @ConfigSerializable
    public record CustomGearDefinition(
            String id,
            @Setting("name-key") String nameKey,
            @Setting("lore-key") String loreKey,
            @Setting("material") String material,
            @Setting("custom-model-data") int customModelData,
            @Setting("stats") Map<String, Double> stats
    ) {
        public double stat(String key, double fallback) {
            return stats.getOrDefault(key, fallback);
        }
    }

    @ConfigSerializable
    public record CustomEnchantDefinition(
            String id,
            @Setting("name-key") String nameKey,
            @Setting("lore-key") String loreKey,
            @Setting("max-level") int maxLevel,
            Map<String, Double> stats
    ) {
        public double stat(String key, double fallback) {
            return stats.getOrDefault(key, fallback);
        }
    }

    @ConfigSerializable
    public record ShopItemDefinition(
            @Setting("id") String id,
            @Setting("slot") int slot,
            @Setting("type") String type,
            @Setting("target") String target,
            @Setting("level") int level,
            @Setting("amount") int amount,
            @Setting("requires-hard-clear") boolean requiresHardClear,
            @Setting("costs") Map<String, Integer> costs
    ) {
    }

    @ConfigSerializable
    public record ShopSettings(
            @Setting("title-key") String titleKey,
            @Setting("size") int size,
            @Setting("items") List<ShopItemDefinition> items
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
