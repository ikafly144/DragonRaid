package net.sabafly.dragonraid.reward;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.AchievementUnlockedEvent;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardTable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

/**
 * Caller-side bridge in {@code dragonraid-paper} listening to achievement unlocks and
 * encounter completions, building {@link RewardContext} snapshots from persistent state,
 * and invoking caller-specified reward tables via {@link RaidRewardService}.
 */
public final class RewardDispatchListener implements Listener {

    private static final String ENCOUNTER_SERVER_FIRST_PREFIX = "reward_clear_server_first:";

    private final RaidRewardService rewardService;
    private final ContentFinderService contentFinderService;
    private final @Nullable RaidAchievementService achievementService;
    private final @Nullable ContentUnlockService unlockService;
    private final @Nullable PlayerProgressionRepository progressionRepository;
    private final LongSupplier clockMillis;
    private final Logger logger;
    private final @Nullable Supplier<ContentPackCatalogSnapshot> catalogSnapshotSupplier;

    private final Set<String> claimedServerFirstInMemory = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<UUID>> inMemoryPendingServerFirst = new ConcurrentHashMap<>();

    public RewardDispatchListener(
            RaidRewardService rewardService,
            ContentFinderService contentFinderService,
            @Nullable RaidAchievementService achievementService,
            @Nullable ContentUnlockService unlockService,
            @Nullable PlayerProgressionRepository progressionRepository,
            LongSupplier clockMillis,
            Logger logger) {
        this(
                rewardService,
                contentFinderService,
                achievementService,
                unlockService,
                progressionRepository,
                clockMillis,
                logger,
                null
        );
    }

    public RewardDispatchListener(
            RaidRewardService rewardService,
            ContentFinderService contentFinderService,
            @Nullable RaidAchievementService achievementService,
            @Nullable ContentUnlockService unlockService,
            @Nullable PlayerProgressionRepository progressionRepository,
            LongSupplier clockMillis,
            Logger logger,
            @Nullable Supplier<ContentPackCatalogSnapshot> catalogSnapshotSupplier) {
        this.rewardService = Objects.requireNonNull(rewardService, "rewardService");
        this.contentFinderService = Objects.requireNonNull(contentFinderService, "contentFinderService");
        this.achievementService = achievementService;
        this.unlockService = unlockService;
        this.progressionRepository = progressionRepository;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.catalogSnapshotSupplier = catalogSnapshotSupplier;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAchievementUnlocked(AchievementUnlockedEvent event) {
        Objects.requireNonNull(event, "event");
        dispatchAchievementRewards(event);
    }

    public CompletableFuture<RewardApplyResult> dispatchAchievementRewards(AchievementUnlockedEvent event) {
        Objects.requireNonNull(event, "event");
        List<String> tableIds = event.achievement().rewards();
        UUID playerId = event.playerId();
        if (tableIds.isEmpty()) {
            return CompletableFuture.completedFuture(
                    RewardApplyResult.empty(RewardBundle.empty("", playerId)));
        }

        @Nullable Map<String, RewardTable> tableSnapshot = event.rewardTableSnapshot();
        CompletableFuture<Set<String>> claimedFuture = loadClaimedKeys(playerId);
        return claimedFuture.thenCompose(claimedKeys -> {
            Set<String> unlockedAchievements = new LinkedHashSet<>();
            if (achievementService != null) {
                unlockedAchievements.addAll(achievementService.unlockedFor(playerId));
            }
            unlockedAchievements.add(event.achievement().id());
            Set<String> unlockedContents = unlockService != null
                    ? unlockService.unlockedContents(playerId)
                    : Set.of();

            RewardContext context = RewardContext.forAchievement(
                    event,
                    unlockedAchievements,
                    unlockedContents,
                    claimedKeys
            );
            return tableSnapshot != null
                    ? rewardService.evaluateAndApplyAll(tableSnapshot, tableIds, context, ThreadLocalRandom.current())
                    : rewardService.evaluateAndApplyAll(tableIds, context, ThreadLocalRandom.current());
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to dispatch achievement rewards for " + event.achievement().id(), ex);
            return RewardApplyResult.empty(RewardBundle.empty("", playerId));
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEncounterCompleted(RaidEncounterCompletedEvent event) {
        Objects.requireNonNull(event, "event");
        dispatchEncounterRewards(event.snapshot());
    }

    public CompletableFuture<List<RewardApplyResult>> dispatchEncounterRewards(EncounterResultSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.outcome() != EncounterOutcome.VICTORY || snapshot.participantMetrics().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        ContentPackCatalogSnapshot catalog = catalogSnapshotSupplier != null ? catalogSnapshotSupplier.get() : null;
        Optional<ContentEntrySpec> contentOpt;
        @Nullable Map<String, RewardTable> tableSnapshot;
        if (catalog != null && catalog.generation() > 0L) {
            contentOpt = catalog.resolveContent(snapshot.encounterId());
            if (contentOpt.isEmpty()) {
                contentOpt = resolveContent(snapshot.encounterId());
            }
            tableSnapshot = catalog.rewardTables();
        } else {
            contentOpt = resolveContent(snapshot.encounterId());
            tableSnapshot = rewardService.effectiveTablesSnapshot();
        }

        if (contentOpt.isEmpty() || contentOpt.get().clearRewards().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<String> tableIds = contentOpt.get().clearRewards();
        Collection<UUID> participantIds = List.copyOf(snapshot.participantMetrics().keySet());
        String sfKey = ENCOUNTER_SERVER_FIRST_PREFIX + snapshot.encounterId();

        CompletableFuture<Set<UUID>> sfRecipientsFuture = progressionRepository != null
                ? progressionRepository.claimOrResumeServerFirstRecipients(sfKey, participantIds, clockMillis.getAsLong())
                        .exceptionally(ex -> {
                            logger.log(Level.WARNING, "Failed to check encounter reward server-first " + sfKey, ex);
                            return Set.of();
                        })
                : CompletableFuture.completedFuture(claimOrResumeInMemoryServerFirst(sfKey, participantIds));

        Map<String, RewardTable> effectiveSnapshot = tableSnapshot;
        return sfRecipientsFuture.thenCompose(sfRecipients -> {
            Set<UUID> safeSfRecipients = sfRecipients == null ? Set.of() : sfRecipients;
            List<CompletableFuture<RewardApplyResult>> participantFutures = new ArrayList<>();
            for (ParticipantCombatMetrics metrics : snapshot.participantMetrics().values()) {
                UUID playerId = metrics.playerId();
                boolean playerFirstClear = snapshot.isFirstClearFor(playerId);
                boolean playerServerFirst = safeSfRecipients.contains(playerId);

                CompletableFuture<RewardApplyResult> future = loadClaimedKeys(playerId).thenCompose(claimedKeys -> {
                    Set<String> unlockedAchievements = achievementService != null
                            ? achievementService.unlockedFor(playerId)
                            : Set.of();
                    Set<String> unlockedContents = unlockService != null
                            ? unlockService.unlockedContents(playerId)
                            : Set.of();
                    RewardContext ctx = RewardContext.forEncounterParticipant(
                            snapshot,
                            metrics,
                            playerFirstClear,
                            playerServerFirst,
                            unlockedAchievements,
                            unlockedContents,
                            claimedKeys
                    );
                    return effectiveSnapshot != null
                            ? rewardService.evaluateAndApplyAll(effectiveSnapshot, tableIds, ctx, ThreadLocalRandom.current())
                            : rewardService.evaluateAndApplyAll(tableIds, ctx, ThreadLocalRandom.current());
                }).thenCompose(applyResult -> {
                    if (playerServerFirst && applyResult.failedGrants().isEmpty()) {
                        return markServerFirstRecipientCompleted(sfKey, playerId).thenApply(v -> applyResult);
                    }
                    return CompletableFuture.completedFuture(applyResult);
                }).exceptionally(ex -> {
                    logger.log(Level.WARNING, "Failed to dispatch encounter rewards for " + playerId, ex);
                    return RewardApplyResult.empty(RewardBundle.empty("", playerId));
                });
                participantFutures.add(future);
            }
            return CompletableFuture.allOf(participantFutures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> participantFutures.stream().map(CompletableFuture::join).toList());
        });
    }

    private Set<UUID> claimOrResumeInMemoryServerFirst(String key, Collection<UUID> participantIds) {
        if (claimedServerFirstInMemory.add(key)) {
            Set<UUID> pending = ConcurrentHashMap.newKeySet();
            for (UUID id : participantIds) {
                if (id != null) {
                    pending.add(id);
                }
            }
            inMemoryPendingServerFirst.put(key, pending);
            return Set.copyOf(pending);
        }
        Set<UUID> pending = inMemoryPendingServerFirst.get(key);
        if (pending == null || pending.isEmpty()) {
            return Set.of();
        }
        Set<UUID> resume = new LinkedHashSet<>();
        for (UUID id : participantIds) {
            if (pending.contains(id)) {
                resume.add(id);
            }
        }
        return Set.copyOf(resume);
    }

    private CompletableFuture<Void> markServerFirstRecipientCompleted(String key, UUID playerId) {
        if (progressionRepository != null) {
            return progressionRepository.completeServerFirstRecipient(key, playerId)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING,
                                "Failed to mark server-first reward dispatch completed for " + playerId + " on " + key, ex);
                        return null;
                    });
        }
        Set<UUID> pending = inMemoryPendingServerFirst.get(key);
        if (pending != null) {
            pending.remove(playerId);
        }
        return CompletableFuture.completedFuture(null);
    }

    private Optional<ContentEntrySpec> resolveContent(String encounterId) {
        Optional<ContentEntrySpec> direct = contentFinderService.getContent(encounterId);
        if (direct.isPresent()) {
            return direct;
        }
        for (ContentEntrySpec spec : contentFinderService.allContents()) {
            if (encounterId.equals(spec.blueprintId())) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    private CompletableFuture<Set<String>> loadClaimedKeys(UUID playerId) {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(Set.of());
        }
        return progressionRepository.loadClaimedRewards(playerId)
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Failed to load claimed rewards for " + playerId, ex);
                    return Set.of();
                });
    }
}
