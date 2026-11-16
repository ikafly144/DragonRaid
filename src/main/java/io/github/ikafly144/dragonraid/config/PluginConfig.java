package io.github.ikafly144.dragonraid.config;

import java.util.List;

/**
 * Strongly-typed immutable representation of {@code config.yml}.
 */
public record PluginConfig(
        LanguageSettings language,
        DatabaseSettings database,
        WorldSettings world,
        RegionConfig regions,
        ScalingSettings scaling,
        EncounterSettings encounter,
        ReviveSettings revive,
        ProtectionSettings protection,
        VisualSettings visuals
) {

    public record LanguageSettings(
            String defaultLocale,
            boolean perPlayerLocale,
            List<String> supportedLocales
    ) {
    }

    public record DatabaseSettings(
            String type,
            String file,
            int poolSize
    ) {
    }

    public record WorldSettings(
            String endWorldName,
            boolean replaceFirstDragon,
            boolean preserveVanillaProgression
    ) {
    }

    public record GimmickScaling(
            int towerBase,
            double towerPerPlayers,
            int towerMin,
            int towerMax,
            double spreadRatio,
            int spreadMin,
            int spreadMax,
            int crystalBase,
            double crystalPerAdditionalPlayer,
            int crystalMax
    ) {
        /** Computes dynamic Meteor Tower count for party size {@code playerCount}. */
        public int towerCount(int playerCount) {
            int n = Math.max(1, playerCount);
            int raw = towerBase + (int) Math.floor(n * towerPerPlayers);
            return clamp(raw, towerMin, towerMax);
        }

        /** Computes dynamic Spread target count for party size {@code playerCount}. */
        public int spreadTargets(int playerCount) {
            int n = Math.max(1, playerCount);
            int raw = (int) Math.ceil(n * spreadRatio);
            return clamp(raw, spreadMin, Math.min(n, spreadMax));
        }

        /** Computes dynamic End Crystal count for party size {@code playerCount}. */
        public int crystalCount(int playerCount) {
            int n = Math.max(1, playerCount);
            int raw = crystalBase + (int) Math.floor((n - 1) * crystalPerAdditionalPlayer);
            return clamp(raw, 1, crystalMax);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    public record SoloFallbackSettings(
            boolean enabled,
            double stackDamageMultiplier,
            double simultaneousCrystalGraceSeconds,
            boolean allowVanillaTotem
    ) {
    }

    public record ScalingSettings(
            int minPlayers,
            int maxPlayers,
            boolean enforceMaxPlayers,
            double hpAlpha,
            double objectHpAlpha,
            GimmickScaling gimmick,
            SoloFallbackSettings soloFallback
    ) {
        /** Computes scaled boss HP: {@code baseHp * (1 + hpAlpha * (N - 1))}. */
        public double scaleBossHp(double baseHp, int playerCount) {
            int n = Math.max(1, playerCount);
            return baseHp * (1.0 + hpAlpha * (n - 1));
        }

        /** Computes scaled crystal/core HP: {@code baseHp * (1 + objectHpAlpha * (N - 1))}. */
        public double scaleObjectHp(double baseHp, int playerCount) {
            int n = Math.max(1, playerCount);
            return baseHp * (1.0 + objectHpAlpha * (n - 1));
        }
    }

    public record ItemCost(String material, int amount) {
    }

    public record AltarSettings(
            RegionConfig.Point3D location,
            double interactRadius,
            boolean unlockHardAfterNormalClear,
            List<ItemCost> normalCosts,
            List<ItemCost> hardCosts
    ) {
    }

    public record EncounterSettings(
            int pullCountdownSeconds,
            boolean autoStartOnArenaEnter,
            boolean altarGuiOnly,
            boolean lockLateJoinersToSafeZone,
            AltarSettings altar
    ) {
    }

    public record ReviveSettings(
            double channelSeconds,
            double interactRadius,
            boolean requireSneak,
            boolean allowRightClickHold,
            double invulnerabilitySeconds,
            double healPercent,
            double voidRescueYOffset,
            int wipeDelaySeconds,
            boolean wipeIfAllLeaveArena
    ) {
    }

    public record ProtectionSettings(
            boolean keepInventoryOnRaidDeath,
            boolean keepExperienceOnRaidDeath,
            boolean disableBedAndAnchorExplosions,
            boolean protectOriginalArenaTerrain,
            boolean allowTemporaryBlocks,
            boolean allowWaterBuckets,
            boolean allowEnderPearls,
            boolean allowWindCharges,
            int maxTemporaryBlocksPerPlayer,
            int maxTemporaryBlocksTotal,
            boolean rollbackTemporaryBlocksOnEnd
    ) {
    }

    public record VisualSettings(
            boolean usePacketEvents,
            int aoeUpdateIntervalTicks,
            boolean soulMarkerGlow,
            double tetherParticleStep,
            double maxRenderDistance
    ) {
    }
}
