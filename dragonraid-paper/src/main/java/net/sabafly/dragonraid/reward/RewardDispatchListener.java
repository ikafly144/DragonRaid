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
import net.sabafly.dragonraid.api.reward.RewardGrant;
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

    private final Object serverFirstLock = new Object();
    private final Set<String> claimedServerFirstInMemory = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<UUID>> inMemoryPendingServerFirst = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> inFlightServerFirst = new ConcurrentHashMap<>();

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
        dispatchEncounterRewards(event.snapshot())
                .exceptionally(ex -> {
                    logger.log(Level.WARNING,
                            "Async encounter reward dispatch failed for " + event.snapshot().encounterId(), ex);
                    return List.of();
                });
    }

    public CompletableFuture<List<RewardApplyResult>> dispatchEncounterRewards(EncounterResultSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.outcome() != EncounterOutcome.VICTORY || snapshot.participantMetrics().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        ResolvedEncounterContent resolved = resolveContentAndTables(snapshot.encounterId());
        if (resolved == null || resolved.content().clearRewards().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<String> tableIds = resolved.content().clearRewards();
        Map<String, RewardTable> effectiveSnapshot = resolved.tables();
        Collection<UUID> participantIds = List.copyOf(snapshot.participantMetrics().keySet());
        String sfKey = ENCOUNTER_SERVER_FIRST_PREFIX + snapshot.encounterId();

        CompletableFuture<Set<UUID>> sfRecipientsFuture;
        if (progressionRepository != null) {
            try {
                CompletableFuture<Set<UUID>> claimedFuture = progressionRepository
                        .claimOrResumeServerFirstRecipients(sfKey, participantIds, clockMillis.getAsLong());
                sfRecipientsFuture = claimedFuture == null
                        ? CompletableFuture.completedFuture(Set.<UUID>of())
                        : claimedFuture
                                .exceptionally(ex -> {
                                    logger.log(Level.WARNING, "Failed to check encounter reward server-first " + sfKey, ex);
                                    return Set.of();
                                })
                                .thenApply(claimed -> acquireRepoInFlightSet(sfKey, claimed));
            } catch (Throwable ex) {
                logger.log(Level.WARNING, "Failed to check encounter reward server-first " + sfKey, ex);
                sfRecipientsFuture = CompletableFuture.completedFuture(Set.of());
            }
        } else {
            sfRecipientsFuture = CompletableFuture.completedFuture(
                    claimOrAcquireInMemoryServerFirst(sfKey, participantIds));
        }

        return sfRecipientsFuture.thenCompose(safeSfRecipients -> {
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
                    if (!playerServerFirst) {
                        return CompletableFuture.completedFuture(applyResult);
                    }
                    if (applyResult.hasFailures()) {
                        releaseServerFirstInFlight(sfKey, playerId, true);
                        return CompletableFuture.completedFuture(applyResult);
                    }
                    return markServerFirstRecipientCompleted(sfKey, playerId)
                            .handle((ignored, markErr) -> {
                                if (markErr != null) {
                                    logger.log(Level.WARNING,
                                            "Failed to mark server-first reward dispatch completed for "
                                                    + playerId + " on " + sfKey,
                                            markErr);
                                    releaseServerFirstInFlight(sfKey, playerId, true);
                                    throw markErr instanceof RuntimeException rt
                                            ? rt
                                            : new RuntimeException(markErr);
                                }
                                releaseServerFirstInFlight(sfKey, playerId, false);
                                return applyResult;
                            });
                }).whenComplete((res, err) -> {
                    if (err != null && playerServerFirst) {
                        releaseServerFirstInFlight(sfKey, playerId, true);
                    }
                });
                participantFutures.add(future);
            }
            return CompletableFuture.allOf(participantFutures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> participantFutures.stream().map(CompletableFuture::join).toList());
        });
    }

    /**
     * Resumes pending encounter Server-First milestones for {@code playerId} (typically on join),
     * filtering evaluated bundles to claim-keyed grants so repeatable encounter drops are not duplicated.
     */
    public CompletableFuture<List<RewardApplyResult>> resumePendingServerFirst(
            UUID playerId,
            @Nullable String playerName) {
        Objects.requireNonNull(playerId, "playerId");
        CompletableFuture<Set<String>> repoPendingFuture;
        if (progressionRepository != null) {
            try {
                CompletableFuture<Set<String>> f = progressionRepository.loadPendingServerFirstMilestones(playerId);
                repoPendingFuture = f == null
                        ? CompletableFuture.completedFuture(Set.of())
                        : f.exceptionally(ex -> {
                            logger.log(Level.WARNING,
                                    "Failed to load pending server-first reward milestones for " + playerId, ex);
                            return Set.of();
                        });
            } catch (Throwable ex) {
                logger.log(Level.WARNING, "Failed to load pending server-first reward milestones for " + playerId, ex);
                repoPendingFuture = CompletableFuture.completedFuture(Set.of());
            }
        } else {
            repoPendingFuture = CompletableFuture.completedFuture(Set.of());
        }

        return repoPendingFuture.thenCompose(repoMilestones -> {
            Set<String> targetKeys = new LinkedHashSet<>();
            if (repoMilestones != null) {
                for (String key : repoMilestones) {
                    if (key != null && key.startsWith(ENCOUNTER_SERVER_FIRST_PREFIX) && tryAcquireInFlight(key, playerId)) {
                        targetKeys.add(key);
                    }
                }
            }
            if (progressionRepository == null) {
                for (String key : acquireInMemoryPendingForPlayer(playerId)) {
                    if (key.startsWith(ENCOUNTER_SERVER_FIRST_PREFIX)) {
                        targetKeys.add(key);
                    }
                }
            }
            if (targetKeys.isEmpty()) {
                return CompletableFuture.completedFuture(List.<RewardApplyResult>of());
            }

            List<CompletableFuture<RewardApplyResult>> ops = new ArrayList<>(targetKeys.size());
            for (String sfKey : targetKeys) {
                ops.add(resumeSingleEncounterServerFirst(sfKey, playerId, playerName));
            }
            return CompletableFuture.allOf(ops.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> ops.stream().map(CompletableFuture::join).toList());
        });
    }

    private CompletableFuture<RewardApplyResult> resumeSingleEncounterServerFirst(
            String sfKey,
            UUID playerId,
            @Nullable String playerName) {
        String encounterId = sfKey.substring(ENCOUNTER_SERVER_FIRST_PREFIX.length());
        ResolvedEncounterContent resolved = resolveContentAndTables(encounterId);
        if (resolved == null || resolved.content().clearRewards().isEmpty()) {
            releaseServerFirstInFlight(sfKey, playerId, true);
            return CompletableFuture.completedFuture(RewardApplyResult.empty(RewardBundle.empty("", playerId)));
        }
        List<String> tableIds = resolved.content().clearRewards();
        Map<String, RewardTable> effectiveSnapshot = resolved.tables();

        return loadClaimedKeys(playerId).thenCompose(claimedKeys -> {
            Set<String> unlockedAchievements = achievementService != null
                    ? achievementService.unlockedFor(playerId)
                    : Set.of();
            Set<String> unlockedContents = unlockService != null
                    ? unlockService.unlockedContents(playerId)
                    : Set.of();
            RewardContext ctx = RewardContext.builder(playerId)
                    .playerName(playerName)
                    .sourceType("ENCOUNTER_CLEAR")
                    .sourceId(encounterId)
                    .firstClear(false)
                    .serverFirst(true)
                    .unlockedAchievements(unlockedAchievements)
                    .unlockedContents(unlockedContents)
                    .claimedKeys(claimedKeys)
                    .build();

            RewardBundle fullBundle = effectiveSnapshot != null
                    ? rewardService.evaluateAll(effectiveSnapshot, tableIds, ctx, ThreadLocalRandom.current())
                    : rewardService.evaluateAll(tableIds, ctx, ThreadLocalRandom.current());
            RewardBundle claimKeyedOnly = filterClaimKeyedOnly(fullBundle);
            if (claimKeyedOnly.isEmpty() && claimKeyedOnly.claimKeys().isEmpty()) {
                return CompletableFuture.completedFuture(RewardApplyResult.empty(claimKeyedOnly));
            }
            return rewardService.apply(claimKeyedOnly, ctx);
        }).thenCompose(applyResult -> {
            if (applyResult.hasFailures()) {
                releaseServerFirstInFlight(sfKey, playerId, true);
                return CompletableFuture.completedFuture(applyResult);
            }
            return markServerFirstRecipientCompleted(sfKey, playerId)
                    .handle((ignored, markErr) -> {
                        if (markErr != null) {
                            logger.log(Level.WARNING,
                                    "Failed to mark resumed server-first reward dispatch completed for "
                                            + playerId + " on " + sfKey,
                                    markErr);
                            releaseServerFirstInFlight(sfKey, playerId, true);
                            throw markErr instanceof RuntimeException rt
                                    ? rt
                                    : new RuntimeException(markErr);
                        }
                        releaseServerFirstInFlight(sfKey, playerId, false);
                        return applyResult;
                    });
        }).whenComplete((res, err) -> {
            if (err != null) {
                releaseServerFirstInFlight(sfKey, playerId, true);
            }
        });
    }

    private static RewardBundle filterClaimKeyedOnly(RewardBundle bundle) {
        List<RewardGrant> filtered = new ArrayList<>();
        for (RewardGrant grant : bundle.grants()) {
            if (grant.claimKey() != null && !grant.claimKey().isBlank()) {
                filtered.add(grant);
            }
        }
        return new RewardBundle(bundle.rootTableId(), bundle.playerId(), filtered, bundle.claimKeys());
    }

    private Set<UUID> acquireRepoInFlightSet(String sfKey, @Nullable Set<UUID> claimed) {
        if (claimed == null || claimed.isEmpty()) {
            return Set.of();
        }
        Set<UUID> acquired = new LinkedHashSet<>();
        synchronized (serverFirstLock) {
            Set<UUID> inFlight = inFlightServerFirst.computeIfAbsent(sfKey, k -> new LinkedHashSet<>());
            for (UUID id : claimed) {
                if (id != null && inFlight.add(id)) {
                    acquired.add(id);
                }
            }
        }
        return Set.copyOf(acquired);
    }

    private boolean tryAcquireInFlight(String sfKey, UUID playerId) {
        synchronized (serverFirstLock) {
            return inFlightServerFirst.computeIfAbsent(sfKey, k -> new LinkedHashSet<>()).add(playerId);
        }
    }

    private Set<UUID> claimOrAcquireInMemoryServerFirst(String key, Collection<UUID> participantIds) {
        synchronized (serverFirstLock) {
            Set<UUID> inFlight = inFlightServerFirst.computeIfAbsent(key, k -> new LinkedHashSet<>());
            if (claimedServerFirstInMemory.add(key)) {
                Set<UUID> acquired = new LinkedHashSet<>();
                for (UUID id : participantIds) {
                    if (id != null && inFlight.add(id)) {
                        acquired.add(id);
                    }
                }
                return Set.copyOf(acquired);
            }
            Set<UUID> pending = inMemoryPendingServerFirst.get(key);
            if (pending == null || pending.isEmpty()) {
                return Set.of();
            }
            Set<UUID> acquired = new LinkedHashSet<>();
            for (UUID id : participantIds) {
                if (id != null && pending.contains(id) && inFlight.add(id)) {
                    pending.remove(id);
                    acquired.add(id);
                }
            }
            return Set.copyOf(acquired);
        }
    }

    private Set<String> acquireInMemoryPendingForPlayer(UUID playerId) {
        Set<String> acquiredKeys = new LinkedHashSet<>();
        synchronized (serverFirstLock) {
            for (Map.Entry<String, Set<UUID>> entry : inMemoryPendingServerFirst.entrySet()) {
                String key = entry.getKey();
                Set<UUID> pending = entry.getValue();
                if (pending != null && pending.contains(playerId)) {
                    Set<UUID> inFlight = inFlightServerFirst.computeIfAbsent(key, k -> new LinkedHashSet<>());
                    if (inFlight.add(playerId)) {
                        pending.remove(playerId);
                        acquiredKeys.add(key);
                    }
                }
            }
        }
        return Set.copyOf(acquiredKeys);
    }

    private void releaseServerFirstInFlight(String key, UUID playerId, boolean restorePendingOnInMemory) {
        synchronized (serverFirstLock) {
            Set<UUID> inFlight = inFlightServerFirst.get(key);
            if (inFlight != null) {
                inFlight.remove(playerId);
                if (inFlight.isEmpty()) {
                    inFlightServerFirst.remove(key);
                }
            }
            if (progressionRepository == null && restorePendingOnInMemory) {
                inMemoryPendingServerFirst.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(playerId);
            }
        }
    }

    private CompletableFuture<Void> markServerFirstRecipientCompleted(String key, UUID playerId) {
        if (progressionRepository != null) {
            try {
                CompletableFuture<Void> future = progressionRepository.completeServerFirstRecipient(key, playerId);
                return future != null ? future : CompletableFuture.completedFuture(null);
            } catch (Throwable ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private record ResolvedEncounterContent(ContentEntrySpec content, @Nullable Map<String, RewardTable> tables) {
    }

    private @Nullable ResolvedEncounterContent resolveContentAndTables(String encounterId) {
        if (catalogSnapshotSupplier == null) {
            return resolveStaticContent(encounterId)
                    .map(content -> new ResolvedEncounterContent(content, rewardService.effectiveTablesSnapshot()))
                    .orElse(null);
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            ContentPackCatalogSnapshot catalog = catalogSnapshotSupplier.get();
            long genBefore = catalog != null ? catalog.generation() : 0L;
            if (catalog != null && genBefore > 0L) {
                Optional<ContentEntrySpec> catalogContent = catalog.resolveContent(encounterId);
                if (catalogContent.isPresent()) {
                    return new ResolvedEncounterContent(catalogContent.get(), catalog.rewardTables());
                }
                Optional<ContentEntrySpec> staticContent = resolveStaticContent(encounterId);
                Map<String, RewardTable> fallbackTables = rewardService.effectiveTablesSnapshot();
                ContentPackCatalogSnapshot after = catalogSnapshotSupplier.get();
                long genAfter = after != null ? after.generation() : 0L;
                if (genAfter == genBefore) {
                    return staticContent
                            .map(c -> new ResolvedEncounterContent(c, fallbackTables))
                            .orElse(null);
                }
                continue;
            }
            Optional<ContentEntrySpec> staticContent = resolveStaticContent(encounterId);
            Map<String, RewardTable> fallbackTables = rewardService.effectiveTablesSnapshot();
            ContentPackCatalogSnapshot after = catalogSnapshotSupplier.get();
            if (after == null || after.generation() <= 0L) {
                return staticContent
                        .map(c -> new ResolvedEncounterContent(c, fallbackTables))
                        .orElse(null);
            }
        }
        logger.warning("Unable to resolve generation-consistent content and reward tables for encounter " + encounterId);
        return null;
    }

    private Optional<ContentEntrySpec> resolveStaticContent(String encounterId) {
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
        try {
            CompletableFuture<Set<String>> f = progressionRepository.loadClaimedRewards(playerId);
            if (f == null) {
                return CompletableFuture.completedFuture(Set.of());
            }
            return f.exceptionally(ex -> {
                logger.log(Level.WARNING, "Failed to load claimed rewards for " + playerId, ex);
                return Set.of();
            });
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "Failed to load claimed rewards for " + playerId, ex);
            return CompletableFuture.completedFuture(Set.of());
        }
    }
}
