package net.sabafly.dragonraid.progression;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.reward.RewardDispatchListener;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Preloads player progression caches (achievements, content unlocks, encounter clears) on join,
 * resumes pending Server-First milestones and unfinished reward plans, and evicts transient caches on disconnect.
 */
public final class PlayerProgressionJoinListener implements Listener {

    private final @Nullable PlayerDataRepository playerDataRepository;
    private final @Nullable RaidAchievementService achievementService;
    private final @Nullable ContentUnlockService unlockService;
    private final @Nullable DefaultVanillaEndProgressionPolicy progressionPolicy;
    private final @Nullable VanillaEndPortalListener portalListener;
    private final @Nullable RaidRewardService rewardService;
    private final @Nullable RewardDispatchListener rewardDispatchListener;
    private final Logger logger;

    public PlayerProgressionJoinListener(
            @Nullable PlayerDataRepository playerDataRepository,
            @Nullable RaidAchievementService achievementService,
            @Nullable ContentUnlockService unlockService,
            @Nullable DefaultVanillaEndProgressionPolicy progressionPolicy,
            @Nullable VanillaEndPortalListener portalListener,
            Logger logger) {
        this(
                playerDataRepository,
                achievementService,
                unlockService,
                progressionPolicy,
                portalListener,
                null,
                null,
                logger
        );
    }

    public PlayerProgressionJoinListener(
            @Nullable PlayerDataRepository playerDataRepository,
            @Nullable RaidAchievementService achievementService,
            @Nullable ContentUnlockService unlockService,
            @Nullable DefaultVanillaEndProgressionPolicy progressionPolicy,
            @Nullable VanillaEndPortalListener portalListener,
            @Nullable RaidRewardService rewardService,
            @Nullable RewardDispatchListener rewardDispatchListener,
            Logger logger) {
        this.playerDataRepository = playerDataRepository;
        this.achievementService = achievementService;
        this.unlockService = unlockService;
        this.progressionPolicy = progressionPolicy;
        this.portalListener = portalListener;
        this.rewardService = rewardService;
        this.rewardDispatchListener = rewardDispatchListener;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        List<CompletableFuture<?>> warmups = new ArrayList<>(4);
        if (playerDataRepository != null) {
            warmups.add(playerDataRepository.updateLastKnownName(uuid, name)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to update player name in progression DB for " + name, ex);
                        return null;
                    }));
        }
        if (achievementService != null) {
            warmups.add(achievementService.loadPlayer(uuid)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to load achievements for " + name, ex);
                        return java.util.Set.of();
                    }));
        }
        if (unlockService != null) {
            warmups.add(unlockService.loadPlayer(uuid)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to load unlocked contents for " + name, ex);
                        return java.util.Set.of();
                    }));
        }
        if (progressionPolicy != null) {
            warmups.add(progressionPolicy.loadPlayerClears(uuid)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to load cleared encounters for " + name, ex);
                        return java.util.Set.of();
                    }));
        }

        CompletableFuture<Void> resumeChain = warmups.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(warmups.toArray(CompletableFuture[]::new));

        if (achievementService != null) {
            resumeChain = resumeChain.thenCompose(ignored -> achievementService.resumePendingServerFirst(uuid, name)
                    .handle((res, ex) -> {
                        if (ex != null) {
                            logger.log(Level.WARNING,
                                    "Failed to resume pending server-first achievements for " + name, ex);
                        }
                        return (Void) null;
                    }));
        }
        if (rewardService != null) {
            resumeChain = resumeChain.thenCompose(ignored -> rewardService.resumePendingPlans(uuid, name)
                    .handle((res, ex) -> {
                        if (ex != null) {
                            logger.log(Level.WARNING,
                                    "Failed to resume pending reward plans for " + name, ex);
                        }
                        return (Void) null;
                    }));
        }
        if (rewardDispatchListener != null) {
            resumeChain = resumeChain.thenCompose(ignored -> rewardDispatchListener.resumePendingServerFirst(uuid, name)
                    .handle((res, ex) -> {
                        if (ex != null) {
                            logger.log(Level.WARNING,
                                    "Failed to resume pending encounter server-first rewards for " + name, ex);
                        }
                        return (Void) null;
                    }));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (achievementService != null) {
            achievementService.evictPlayer(uuid);
        }
        if (unlockService != null) {
            unlockService.evictPlayer(uuid);
        }
        if (progressionPolicy != null) {
            progressionPolicy.evictPlayer(uuid);
        }
        if (portalListener != null) {
            portalListener.clearPlayerState(uuid);
        }
    }
}
