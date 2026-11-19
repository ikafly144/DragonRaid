package net.sabafly.dragonraid.reward;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
import net.sabafly.dragonraid.api.reward.ContentUnlockGrant;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotification;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotifier;
import net.sabafly.dragonraid.api.reward.CurrencyGrant;
import net.sabafly.dragonraid.api.reward.CustomItemGrant;
import net.sabafly.dragonraid.api.reward.RewardApplier;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardCurrencySink;
import net.sabafly.dragonraid.api.reward.RewardGrant;
import net.sabafly.dragonraid.api.reward.RewardItemDelivery;
import net.sabafly.dragonraid.api.reward.ScriptHookGrant;
import net.sabafly.dragonraid.api.reward.VanillaItemGrant;
import net.sabafly.dragonraid.api.script.ScriptHookContext;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * Side-effect {@link RewardApplier} executing grants from an evaluated {@link RewardBundle}
 * with per-grant resumable {@code claimKey} persistence and accurate delivery verification.
 */
public final class DefaultRewardApplier implements RewardApplier {

    private final @Nullable ContentUnlockService unlockService;
    private final @Nullable ContentFinderService contentFinderService;
    private final @Nullable ContentUnlockNotifier unlockNotifier;
    private final @Nullable RewardCurrencySink currencySink;
    private final @Nullable RewardItemDelivery itemDelivery;
    private final @Nullable ScriptHookRegistry scriptHookRegistry;
    private final @Nullable PlayerProgressionRepository progressionRepository;
    private final @Nullable RaidServiceRegistry serviceRegistry;
    private final LongSupplier clockMillis;
    private final Logger logger;

    private final Map<UUID, Set<String>> claimedRewardsInMemory = new ConcurrentHashMap<>();
    private final Object queueLock = new Object();
    private final Map<UUID, CompletableFuture<Void>> playerQueues = new HashMap<>();

    public DefaultRewardApplier() {
        this(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                System::currentTimeMillis,
                Logger.getLogger(DefaultRewardApplier.class.getName())
        );
    }

    public DefaultRewardApplier(
            @Nullable ContentUnlockService unlockService,
            @Nullable ContentFinderService contentFinderService,
            @Nullable ContentUnlockNotifier unlockNotifier,
            @Nullable RewardCurrencySink currencySink,
            @Nullable RewardItemDelivery itemDelivery,
            @Nullable ScriptHookRegistry scriptHookRegistry,
            @Nullable PlayerProgressionRepository progressionRepository,
            @Nullable RaidServiceRegistry serviceRegistry,
            LongSupplier clockMillis,
            Logger logger) {
        this.unlockService = unlockService;
        this.contentFinderService = contentFinderService;
        this.unlockNotifier = unlockNotifier;
        this.currencySink = currencySink;
        this.itemDelivery = itemDelivery;
        this.scriptHookRegistry = scriptHookRegistry;
        this.progressionRepository = progressionRepository;
        this.serviceRegistry = serviceRegistry;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CompletableFuture<RewardApplyResult> apply(RewardBundle bundle, RewardContext context) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(context, "context");
        if (!Objects.equals(bundle.playerId(), context.playerId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "RewardBundle playerId (" + bundle.playerId() + ") does not match RewardContext playerId ("
                            + context.playerId() + ")"));
        }
        if (bundle.isEmpty()) {
            return CompletableFuture.completedFuture(RewardApplyResult.empty(bundle));
        }

        UUID playerId = context.playerId();
        return enqueueForPlayer(playerId, () -> applySerialized(bundle, context));
    }

    private CompletableFuture<RewardApplyResult> applySerialized(RewardBundle bundle, RewardContext context) {
        UUID playerId = context.playerId();
        @Nullable String playerName = context.playerName();
        long now = clockMillis.getAsLong();

        return loadClaimedSet(playerId).thenCompose(existingClaimed -> {
            Set<String> skippedClaims = new LinkedHashSet<>();
            for (String claimKey : bundle.claimKeys()) {
                if (existingClaimed.contains(claimKey)) {
                    skippedClaims.add(claimKey);
                }
            }

            List<RewardGrant> appliedGrants = Collections.synchronizedList(new ArrayList<>());
            List<RewardGrant> failedGrants = Collections.synchronizedList(new ArrayList<>());
            Map<String, Integer> perKeyCounters = new LinkedHashMap<>();
            Set<String> keysWithFailures = ConcurrentHashMap.newKeySet();
            List<CompletableFuture<Void>> grantOps = new ArrayList<>();

            for (RewardGrant grant : bundle.grants()) {
                String claimKey = grant.claimKey();
                if (claimKey != null && skippedClaims.contains(claimKey)) {
                    continue;
                }
                String subKey = null;
                if (claimKey != null) {
                    int ordinal = perKeyCounters.merge(claimKey, 1, Integer::sum) - 1;
                    subKey = buildGrantSubKey(claimKey, ordinal, grant);
                    if (existingClaimed.contains(subKey)) {
                        continue;
                    }
                }

                String finalSubKey = subKey;
                CompletableFuture<Void> op = executeGrant(grant, context)
                        .handle((succeeded, ex) -> {
                            if (ex != null) {
                                logger.log(Level.WARNING, "Failed to apply grant " + describeGrant(grant), ex);
                                return false;
                            }
                            return Boolean.TRUE.equals(succeeded);
                        })
                        .thenCompose(succeeded -> {
                            if (!succeeded) {
                                failedGrants.add(grant);
                                if (claimKey != null) {
                                    keysWithFailures.add(claimKey);
                                }
                                return CompletableFuture.completedFuture((Void) null);
                            }
                            appliedGrants.add(grant);
                            if (finalSubKey == null) {
                                return CompletableFuture.completedFuture((Void) null);
                            }
                            return persistClaimKey(playerId, playerName, finalSubKey, now)
                                    .handle((claimed, err) -> {
                                        if (err != null) {
                                            logger.log(Level.WARNING,
                                                    "Failed to persist completed grant marker '" + finalSubKey + "'", err);
                                            if (claimKey != null) {
                                                keysWithFailures.add(claimKey);
                                            }
                                        }
                                        return (Void) null;
                                    });
                        });
                grantOps.add(op);
            }

            CompletableFuture<Void> grantsDone = grantOps.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(grantOps.toArray(CompletableFuture[]::new));

            return grantsDone.thenCompose(ignored -> {
                Set<String> newlyClaimed = new LinkedHashSet<>();
                List<CompletableFuture<Void>> finalizeClaimOps = new ArrayList<>();
                for (String claimKey : bundle.claimKeys()) {
                    if (skippedClaims.contains(claimKey) || keysWithFailures.contains(claimKey)) {
                        continue;
                    }
                    finalizeClaimOps.add(persistClaimKey(playerId, playerName, claimKey, now)
                            .handle((claimed, err) -> {
                                if (err != null) {
                                    logger.log(Level.WARNING,
                                            "Failed to finalize reward claim key '" + claimKey + "' for " + playerId, err);
                                    return (Void) null;
                                }
                                synchronized (newlyClaimed) {
                                    if (Boolean.TRUE.equals(claimed)) {
                                        newlyClaimed.add(claimKey);
                                    } else {
                                        skippedClaims.add(claimKey);
                                    }
                                }
                                return (Void) null;
                            }));
                }
                CompletableFuture<Void> claimsDone = finalizeClaimOps.isEmpty()
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.allOf(finalizeClaimOps.toArray(CompletableFuture[]::new));
                return claimsDone.thenApply(v -> new RewardApplyResult(
                        bundle,
                        List.copyOf(appliedGrants),
                        Set.copyOf(newlyClaimed),
                        Set.copyOf(skippedClaims),
                        List.copyOf(failedGrants)
                ));
            });
        });
    }

    private CompletableFuture<Set<String>> loadClaimedSet(UUID playerId) {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(Set.copyOf(claimedRewardsInMemory.getOrDefault(playerId, Set.of())));
        }
        CompletableFuture<Set<String>> repoFuture = progressionRepository.loadClaimedRewards(playerId);
        if (repoFuture == null) {
            // Mocked repositories that only stub tryClaimReward return null here; fall back to memory cache.
            return CompletableFuture.completedFuture(Set.copyOf(claimedRewardsInMemory.getOrDefault(playerId, Set.of())));
        }
        return repoFuture.thenApply(loaded -> loaded == null ? Set.of() : Set.copyOf(loaded));
    }

    private CompletableFuture<Boolean> persistClaimKey(
            UUID playerId,
            @Nullable String playerName,
            String key,
            long now) {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(
                    claimedRewardsInMemory.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(key));
        }
        CompletableFuture<Boolean> future = progressionRepository.tryClaimReward(playerId, playerName, key, now);
        if (future == null) {
            return CompletableFuture.completedFuture(
                    claimedRewardsInMemory.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(key));
        }
        return future.thenApply(claimed -> {
            if (Boolean.TRUE.equals(claimed)) {
                claimedRewardsInMemory.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(key);
                return true;
            }
            claimedRewardsInMemory.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(key);
            return false;
        });
    }

    private CompletableFuture<Boolean> executeGrant(RewardGrant grant, RewardContext context) {
        UUID playerId = context.playerId();
        @Nullable String playerName = context.playerName();

        return switch (grant) {
            case ContentUnlockGrant ug -> {
                if (unlockService == null) {
                    yield CompletableFuture.completedFuture(false);
                }
                CompletableFuture<Boolean> unlockFuture = unlockService.unlockAsync(playerId, playerName, ug.contentId());
                if (unlockFuture == null) {
                    boolean fallback = unlockService.unlock(playerId, playerName, ug.contentId());
                    if (fallback && ug.notifyPlayer() && unlockNotifier != null) {
                        unlockNotifier.notifyContentUnlocked(playerId, buildUnlockNotification(ug));
                    }
                    yield CompletableFuture.completedFuture(true);
                }
                yield unlockFuture.thenApply(newlyUnlocked -> {
                    if (Boolean.TRUE.equals(newlyUnlocked) && ug.notifyPlayer() && unlockNotifier != null) {
                        unlockNotifier.notifyContentUnlocked(playerId, buildUnlockNotification(ug));
                    }
                    return true;
                });
            }
            case CurrencyGrant cg -> {
                if (currencySink == null) {
                    yield CompletableFuture.completedFuture(false);
                }
                CompletableFuture<Boolean> f = currencySink.grantCurrency(playerId, playerName, cg.currencyId(), cg.amount());
                yield f != null ? f : CompletableFuture.completedFuture(false);
            }
            case VanillaItemGrant vg -> {
                if (itemDelivery == null) {
                    yield CompletableFuture.completedFuture(false);
                }
                CompletableFuture<Boolean> f = itemDelivery.deliverVanillaItem(playerId, vg.materialName(), vg.amount());
                yield f != null ? f : CompletableFuture.completedFuture(false);
            }
            case CustomItemGrant ig -> {
                if (itemDelivery == null) {
                    yield CompletableFuture.completedFuture(false);
                }
                CompletableFuture<Boolean> f = itemDelivery.deliverCustomItem(playerId, ig.customItemId(), ig.amount());
                yield f != null ? f : CompletableFuture.completedFuture(false);
            }
            case ScriptHookGrant sg -> {
                if (scriptHookRegistry == null) {
                    yield CompletableFuture.completedFuture(false);
                }
                Collection<UUID> participantIds = context.encounterSnapshot() != null
                        ? List.copyOf(context.encounterSnapshot().participantMetrics().keySet())
                        : List.of(playerId);
                ScriptHookContext hookCtx = new ScriptHookContext(
                        serviceRegistry,
                        playerId,
                        playerName,
                        participantIds,
                        context.encounterSnapshot(),
                        sg.params(),
                        context.attributes()
                );
                try {
                    yield CompletableFuture.completedFuture(scriptHookRegistry.executeAction(sg.hookId(), hookCtx));
                } catch (RuntimeException ex) {
                    logger.log(Level.WARNING, "Reward script hook failed: " + sg.hookId(), ex);
                    yield CompletableFuture.completedFuture(false);
                }
            }
        };
    }

    private <T> CompletableFuture<T> enqueueForPlayer(UUID playerId, Supplier<CompletableFuture<T>> op) {
        CompletableFuture<Void> prev;
        CompletableFuture<Void> gate = new CompletableFuture<>();
        synchronized (queueLock) {
            prev = playerQueues.get(playerId);
            playerQueues.put(playerId, gate);
        }
        CompletableFuture<T> out = new CompletableFuture<>();
        Runnable start = () -> {
            try {
                CompletableFuture<T> inner = op.get();
                if (inner == null) {
                    finishPlayerOp(playerId, gate, out, null, null);
                } else {
                    inner.whenComplete((res, err) -> finishPlayerOp(playerId, gate, out, res, err));
                }
            } catch (Throwable ex) {
                finishPlayerOp(playerId, gate, out, null, ex);
            }
        };
        if (prev == null || prev.isDone()) {
            start.run();
        } else {
            prev.whenComplete((ignored, prevErr) -> start.run());
        }
        return out;
    }

    private <T> void finishPlayerOp(
            UUID playerId,
            CompletableFuture<Void> gate,
            CompletableFuture<T> out,
            @Nullable T res,
            @Nullable Throwable err) {
        synchronized (queueLock) {
            if (playerQueues.get(playerId) == gate) {
                playerQueues.remove(playerId);
            }
        }
        gate.complete(null);
        if (err != null) {
            out.completeExceptionally(err);
        } else {
            out.complete(res);
        }
    }

    private static String buildGrantSubKey(String claimKey, int ordinal, RewardGrant grant) {
        return claimKey + "#g" + ordinal + ":" + describeGrant(grant);
    }

    private static String describeGrant(RewardGrant grant) {
        return switch (grant) {
            case CurrencyGrant cg -> "CURRENCY:" + cg.currencyId();
            case VanillaItemGrant vg -> "VANILLA_ITEM:" + vg.materialName();
            case CustomItemGrant ig -> "CUSTOM_ITEM:" + ig.customItemId();
            case ContentUnlockGrant ug -> "UNLOCK_CONTENT:" + ug.contentId();
            case ScriptHookGrant sg -> "SCRIPT_HOOK:" + sg.hookId();
        };
    }

    private ContentUnlockNotification buildUnlockNotification(ContentUnlockGrant grant) {
        String contentId = grant.contentId();
        String displayNameKey = contentFinderService != null
                ? contentFinderService.getContent(contentId).map(ContentEntrySpec::displayNameKey).orElse(contentId)
                : contentId;
        return new ContentUnlockNotification(
                contentId,
                displayNameKey,
                grant.titleKey().isEmpty() ? ContentUnlockNotification.DEFAULT_TITLE_KEY : grant.titleKey(),
                grant.subtitleKey().isEmpty() ? ContentUnlockNotification.DEFAULT_SUBTITLE_KEY : grant.subtitleKey(),
                grant.toastTitleKey().isEmpty() ? ContentUnlockNotification.DEFAULT_TOAST_TITLE_KEY : grant.toastTitleKey(),
                grant.toastDescriptionKey().isEmpty() ? ContentUnlockNotification.DEFAULT_TOAST_DESC_KEY : grant.toastDescriptionKey(),
                grant.iconMaterial().isEmpty() ? ContentUnlockNotification.DEFAULT_ICON_MATERIAL : grant.iconMaterial(),
                grant.soundKey().isEmpty() ? ContentUnlockNotification.DEFAULT_SOUND_KEY : grant.soundKey(),
                1.0f,
                1.0f
        );
    }
}
