package net.sabafly.dragonraid.api.achievement;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.util.ImmutableSnapshots;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a player newly unlocks a data-driven DragonRaid achievement.
 */
public final class AchievementUnlockedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final @Nullable String playerName;
    private final AchievementDefinition achievement;
    private final AchievementTriggerType triggerType;
    private final boolean serverFirst;
    private final Map<String, Object> metadata;
    private final Instant unlockedAt;
    private final @Nullable Map<String, RewardTable> rewardTableSnapshot;

    public AchievementUnlockedEvent(
            UUID playerId,
            @Nullable String playerName,
            AchievementDefinition achievement,
            AchievementTriggerType triggerType,
            boolean serverFirst,
            @Nullable Map<String, Object> metadata,
            @Nullable Instant unlockedAt) {
        this(playerId, playerName, achievement, triggerType, serverFirst, metadata, unlockedAt, null);
    }

    public AchievementUnlockedEvent(
            UUID playerId,
            @Nullable String playerName,
            AchievementDefinition achievement,
            AchievementTriggerType triggerType,
            boolean serverFirst,
            @Nullable Map<String, Object> metadata,
            @Nullable Instant unlockedAt,
            @Nullable Map<String, RewardTable> rewardTableSnapshot) {
        super(Bukkit.getServer() != null && !Bukkit.isPrimaryThread());
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = playerName;
        this.achievement = Objects.requireNonNull(achievement, "achievement");
        this.triggerType = Objects.requireNonNull(triggerType, "triggerType");
        this.serverFirst = serverFirst;
        this.metadata = ImmutableSnapshots.copyStringMap(metadata);
        this.unlockedAt = unlockedAt == null ? Instant.now() : unlockedAt;
        this.rewardTableSnapshot = rewardTableSnapshot == null ? null : Map.copyOf(rewardTableSnapshot);
    }

    public UUID playerId() {
        return playerId;
    }

    public @Nullable String playerName() {
        return playerName;
    }

    public AchievementDefinition achievement() {
        return achievement;
    }

    public AchievementTriggerType triggerType() {
        return triggerType;
    }

    public boolean serverFirst() {
        return serverFirst;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public Instant unlockedAt() {
        return unlockedAt;
    }

    public @Nullable Map<String, RewardTable> rewardTableSnapshot() {
        return rewardTableSnapshot;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
