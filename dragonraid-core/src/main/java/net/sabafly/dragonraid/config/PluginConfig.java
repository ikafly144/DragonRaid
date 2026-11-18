package net.sabafly.dragonraid.config;

import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Strongly-typed immutable representation of {@code config.yml}.
 */
@ConfigSerializable
public record PluginConfig(
        @Setting("language") LanguageSettings language,
        @Setting("database") DatabaseSettings database,
        @Setting("world") WorldSettings world,
        @Setting("regions") RegionConfig regions,
        @Setting("scaling") ScalingSettings scaling,
        @Setting("encounter") EncounterSettings encounter,
        @Setting("revive") ReviveSettings revive,
        @Setting("protection") ProtectionSettings protection,
        @Setting("visuals") VisualSettings visuals
) {

    @ConfigSerializable
    public record LanguageSettings(
            @Setting("default") String defaultLocale,
            @Setting("per-player-locale") boolean perPlayerLocale,
            @Setting("supported") List<String> supportedLocales
    ) {
    }

    @ConfigSerializable
    public record DatabaseSettings(
            @Setting("type") String type,
            @Setting("file") String file,
            @Setting("pool-size") int poolSize
    ) {
    }

    @ConfigSerializable
    public record WorldSettings(
            @Setting("end-world-name") String endWorldName,
            @Setting("replace-first-dragon") boolean replaceFirstDragon,
            @Setting("preserve-vanilla-progression") boolean preserveVanillaProgression
    ) {
    }

    @ConfigSerializable
    public record GimmickScaling(
            @Setting("tower-base") int towerBase,
            @Setting("tower-per-players") double towerPerPlayers,
            @Setting("tower-min") int towerMin,
            @Setting("tower-max") int towerMax,
            @Setting("spread-ratio") double spreadRatio,
            @Setting("spread-min") int spreadMin,
            @Setting("spread-max") int spreadMax,
            @Setting("crystal-base") int crystalBase,
            @Setting("crystal-per-additional-player") double crystalPerAdditionalPlayer,
            @Setting("crystal-max") int crystalMax
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
            int upper = Math.min(n, spreadMax);
            int lower = Math.min(spreadMin, upper);
            int raw = (int) Math.ceil(n * spreadRatio);
            return clamp(raw, lower, upper);
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

    @ConfigSerializable
    public record SoloFallbackSettings(
            @Setting("enabled") boolean enabled,
            @Setting("stack-damage-multiplier") double stackDamageMultiplier,
            @Setting("simultaneous-crystal-grace-seconds") double simultaneousCrystalGraceSeconds,
            @Setting("allow-vanilla-totem") boolean allowVanillaTotem
    ) {
    }

    @ConfigSerializable
    public record ScalingSettings(
            @Setting("min-players") int minPlayers,
            @Setting("max-players") int maxPlayers,
            @Setting("enforce-max-players") boolean enforceMaxPlayers,
            @Setting("hp-alpha") double hpAlpha,
            @Setting("object-hp-alpha") double objectHpAlpha,
            @Setting("gimmick") GimmickScaling gimmick,
            @Setting("solo-fallback") SoloFallbackSettings soloFallback
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

    @ConfigSerializable
    public record ItemCost(
            @Setting("material") String material,
            @Setting("amount") int amount
    ) {
    }

    @ConfigSerializable
    public record AltarSettings(
            @Setting("location") RegionConfig.Point3D location,
            @Setting("interact-radius") double interactRadius,
            @Setting("unlock-hard-after-normal-clear") boolean unlockHardAfterNormalClear,
            List<ItemCost> normalCosts,
            List<ItemCost> hardCosts
    ) {
    }

    @ConfigSerializable
    public record EncounterSettings(
            @Setting("pull-countdown-seconds") int pullCountdownSeconds,
            @Setting("auto-start-on-arena-enter") boolean autoStartOnArenaEnter,
            @Setting("altar-gui-only") boolean altarGuiOnly,
            @Setting("lock-late-joiners-to-safe-zone") boolean lockLateJoinersToSafeZone,
            @Setting("altar") AltarSettings altar
    ) {
    }

    @ConfigSerializable
    public record ReviveSettings(
            @Setting("channel-seconds") double channelSeconds,
            @Setting("interact-radius") double interactRadius,
            @Setting("require-sneak") boolean requireSneak,
            @Setting("allow-right-click-hold") boolean allowRightClickHold,
            @Setting("invulnerability-seconds") double invulnerabilitySeconds,
            @Setting("heal-percent") double healPercent,
            @Setting("void-rescue-y-offset") double voidRescueYOffset,
            @Setting("wipe-delay-seconds") int wipeDelaySeconds,
            @Setting("wipe-if-all-leave-arena") boolean wipeIfAllLeaveArena,
            @Setting("down-on-disconnect") boolean downOnDisconnect
    ) {
    }

    @ConfigSerializable
    public record ProtectionSettings(
            @Setting("keep-inventory-on-raid-death") boolean keepInventoryOnRaidDeath,
            @Setting("keep-experience-on-raid-death") boolean keepExperienceOnRaidDeath,
            @Setting("disable-bed-and-anchor-explosions") boolean disableBedAndAnchorExplosions,
            @Setting("protect-original-arena-terrain") boolean protectOriginalArenaTerrain,
            @Setting("allow-temporary-blocks") boolean allowTemporaryBlocks,
            @Setting("allow-water-buckets") boolean allowWaterBuckets,
            @Setting("allow-ender-pearls") boolean allowEnderPearls,
            @Setting("allow-wind-charges") boolean allowWindCharges,
            @Setting("max-temporary-blocks-per-player") int maxTemporaryBlocksPerPlayer,
            @Setting("max-temporary-blocks-total") int maxTemporaryBlocksTotal,
            @Setting("rollback-temporary-blocks-on-end") boolean rollbackTemporaryBlocksOnEnd
    ) {
    }

    @ConfigSerializable
    public record VisualSettings(
            @Setting("use-packet-events") boolean usePacketEvents,
            @Setting("aoe-update-interval-ticks") int aoeUpdateIntervalTicks,
            @Setting("soul-marker-glow") boolean soulMarkerGlow,
            @Setting("tether-particle-step") double tetherParticleStep,
            @Setting("max-render-distance") double maxRenderDistance
    ) {
    }
}
