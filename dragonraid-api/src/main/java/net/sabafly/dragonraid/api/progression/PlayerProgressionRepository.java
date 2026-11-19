package net.sabafly.dragonraid.api.progression;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Asynchronous persistence SPI for player achievements, content unlocks, claimed one-shot rewards,
 * per-encounter clears, and server-first milestones.
 */
public interface PlayerProgressionRepository {

    CompletableFuture<Set<String>> loadAchievements(UUID uuid);

    CompletableFuture<Boolean> grantAchievement(
            UUID uuid,
            @Nullable String lastKnownName,
            String achievementId,
            long unlockedAtEpochMillis
    );

    CompletableFuture<Boolean> hasAchievement(UUID uuid, String achievementId);

    CompletableFuture<Set<String>> loadUnlockedContents(UUID uuid);

    CompletableFuture<Boolean> grantContentUnlock(
            UUID uuid,
            @Nullable String lastKnownName,
            String contentId,
            long unlockedAtEpochMillis
    );

    CompletableFuture<Boolean> revokeContentUnlock(UUID uuid, String contentId);

    CompletableFuture<Boolean> hasContentUnlock(UUID uuid, String contentId);

    CompletableFuture<Boolean> tryClaimReward(
            UUID uuid,
            @Nullable String lastKnownName,
            String rewardId,
            long claimedAtEpochMillis
    );

    CompletableFuture<Boolean> hasClaimedReward(UUID uuid, String rewardId);

    CompletableFuture<Set<String>> loadClaimedRewards(UUID uuid);

    CompletableFuture<Boolean> tryClaimServerFirst(
            String milestoneKey,
            Collection<UUID> participantIds,
            long achievedAtEpochMillis
    );

    /**
     * Atomically claims {@code milestoneKey} along with pending per-participant work items, or
     * returns any remaining uncompleted participants in {@code participantIds} when resuming after
     * a partial failure.
     */
    default CompletableFuture<Set<UUID>> claimOrResumeServerFirstRecipients(
            String milestoneKey,
            Collection<UUID> participantIds,
            long achievedAtEpochMillis) {
        return tryClaimServerFirst(milestoneKey, participantIds, achievedAtEpochMillis)
                .thenApply(claimed -> Boolean.TRUE.equals(claimed) && participantIds != null
                        ? Set.copyOf(participantIds)
                        : Set.of());
    }

    /**
     * Marks a participant's server-first work item complete for {@code milestoneKey}.
     */
    default CompletableFuture<Void> completeServerFirstRecipient(String milestoneKey, UUID participantId) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Returns the persisted participant UUIDs recorded when {@code milestoneKey} was originally claimed.
     */
    default CompletableFuture<List<UUID>> loadServerMilestoneParticipants(String milestoneKey) {
        return CompletableFuture.completedFuture(List.of());
    }

    CompletableFuture<Boolean> isServerFirstAchieved(String milestoneKey);

    CompletableFuture<Set<String>> loadAllServerMilestones();

    /**
     * Records an encounter clear and returns {@code true} if this was the player's first clear
     * of {@code encounterId}.
     */
    CompletableFuture<Boolean> recordEncounterClear(
            UUID uuid,
            @Nullable String lastKnownName,
            String encounterId,
            long clearTimeMs,
            long clearedAtEpochMillis
    );

    CompletableFuture<Boolean> hasClearedEncounter(UUID uuid, String encounterId);

    CompletableFuture<Set<String>> loadClearedEncounters(UUID uuid);
}
