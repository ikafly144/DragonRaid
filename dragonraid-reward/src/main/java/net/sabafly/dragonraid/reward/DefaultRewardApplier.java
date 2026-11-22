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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
import net.sabafly.dragonraid.api.reward.ItemDeliveryResult;
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
import net.sabafly.dragonraid.reward.RewardPlanJsonCodec.DecodeResult;
import net.sabafly.dragonraid.reward.RewardPlanJsonCodec.PlanStep;
import net.sabafly.dragonraid.reward.RewardPlanJsonCodec.RewardPlan;
import org.jetbrains.annotations.Nullable;

/**
 * Side-effect {@link RewardApplier} executing grants from an evaluated {@link RewardBundle}
 * with durable {@code player_reward_plans} step persistence, partial item remainder resume,
 * atomic idempotent currency deposits, and player-serialized execution.
 */
public final class DefaultRewardApplier implements RewardApplier {

    private static final String CURRENCY_IDEMPOTENCY_PREFIX = "__sys:cur:";

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
    private final @Nullable Executor scriptExecutor;

    private final Map<UUID, Set<String>> claimedRewardsInMemory = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> inMemoryRewardPlans = new ConcurrentHashMap<>();
    private final Set<String> loggedPlanFailureWarnings = ConcurrentHashMap.newKeySet();
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
                Logger.getLogger(DefaultRewardApplier.class.getName()),
                null
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
        this(
                unlockService,
                contentFinderService,
                unlockNotifier,
                currencySink,
                itemDelivery,
                scriptHookRegistry,
                progressionRepository,
                serviceRegistry,
                clockMillis,
                logger,
                null
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
            Logger logger,
            @Nullable Executor scriptExecutor) {
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
        this.scriptExecutor = scriptExecutor;
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
        if (bundle.isEmpty() && bundle.claimKeys().isEmpty()) {
            return CompletableFuture.completedFuture(RewardApplyResult.empty(bundle));
        }

        UUID playerId = context.playerId();
        return enqueueForPlayer(playerId, () -> applySerialized(bundle, context));
    }

    /**
     * Retries each unfinished reward plan persisted for {@code playerId} once per invocation,
     * keeping permanently unavailable grants (e.g., M2 {@code CUSTOM_ITEM}) queued for future upgrades.
     */
    public CompletableFuture<List<RewardApplyResult>> resumePendingPlans(
            UUID playerId,
            @Nullable String playerName) {
        Objects.requireNonNull(playerId, "playerId");
        return enqueueForPlayer(playerId, () -> resumePendingPlansSerialized(playerId, playerName));
    }

    private CompletableFuture<List<RewardApplyResult>> resumePendingPlansSerialized(
            UUID playerId,
            @Nullable String playerName) {
        long now = clockMillis.getAsLong();
        return loadClaimedSet(playerId).thenCompose(existingClaimed ->
                loadAllPlanJsons(playerId).thenCompose(rawPlans -> {
                    if (rawPlans.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<RewardApplyResult>of());
                    }
                    CompletableFuture<List<RewardApplyResult>> chain =
                            CompletableFuture.completedFuture(new ArrayList<>(rawPlans.size()));
                    for (Map.Entry<String, String> entry : rawPlans.entrySet()) {
                        String planKey = entry.getKey();
                        String planJson = entry.getValue();
                        chain = chain.thenCompose(results -> resumeSingleStoredPlan(
                                playerId, playerName, planKey, planJson, existingClaimed, now)
                                .thenApply(optResult -> {
                                    optResult.ifPresent(results::add);
                                    return results;
                                }));
                    }
                    return chain.thenApply(List::copyOf);
                }));
    }

    private CompletableFuture<Optional<RewardApplyResult>> resumeSingleStoredPlan(
            UUID playerId,
            @Nullable String playerName,
            String planKey,
            String planJson,
            Set<String> existingClaimed,
            long now) {
        if (existingClaimed.contains(planKey)) {
            return deletePlanJson(playerId, planKey)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to clean up already-claimed reward plan '" + planKey + "'", ex);
                        return null;
                    })
                    .thenApply(ignored -> Optional.empty());
        }
        DecodeResult decoded = RewardPlanJsonCodec.decode(planJson);
        switch (decoded) {
            case DecodeResult.UnsupportedVersion uv -> {
                warnPlanFailureOnce(
                        playerId, planKey,
                        "Ignoring reward plan '" + planKey + "' for " + playerId
                                + " with unsupported schema version v=" + uv.version(),
                        null);
                return CompletableFuture.completedFuture(Optional.empty());
            }
            case DecodeResult.Malformed mf -> {
                warnPlanFailureOnce(
                        playerId, planKey,
                        "Ignoring malformed reward plan '" + planKey + "' for " + playerId + ": " + mf.reason(),
                        null);
                return CompletableFuture.completedFuture(Optional.empty());
            }
            case DecodeResult.Parsed parsed -> {
                RewardPlan plan = parsed.plan();
                RewardContext resumeCtx = plan.toContext(playerName);
                RewardBundle bundle = plan.toBundle();
                List<RewardGrant> applied = new ArrayList<>();
                List<RewardGrant> failed = new ArrayList<>();
                Set<String> newlyClaimed = new LinkedHashSet<>();
                Set<String> alreadyClaimed = new LinkedHashSet<>();
                Set<String> failedClaimKeys = new LinkedHashSet<>();
                return executeLoadedPlan(
                        plan, resumeCtx, now, applied, failed, newlyClaimed, alreadyClaimed, failedClaimKeys)
                        .thenApply(v -> Optional.of(new RewardApplyResult(
                                bundle,
                                List.copyOf(applied),
                                filterPublicClaimKeys(newlyClaimed),
                                filterPublicClaimKeys(alreadyClaimed),
                                List.copyOf(failed),
                                Set.copyOf(failedClaimKeys)
                        )));
            }
        }
    }

    private CompletableFuture<RewardApplyResult> applySerialized(RewardBundle bundle, RewardContext context) {
        UUID playerId = context.playerId();
        @Nullable String playerName = context.playerName();
        long now = clockMillis.getAsLong();

        return loadClaimedSet(playerId).thenCompose(existingClaimed -> {
            Set<String> initialSkipped = new LinkedHashSet<>();
            List<RewardGrant> repeatableGrants = new ArrayList<>();
            Map<String, List<RewardGrant>> grantsByClaimKey = new LinkedHashMap<>();

            for (String claimKey : bundle.claimKeys()) {
                if (existingClaimed.contains(claimKey)) {
                    initialSkipped.add(claimKey);
                } else {
                    grantsByClaimKey.putIfAbsent(claimKey, new ArrayList<>());
                }
            }
            for (RewardGrant grant : bundle.grants()) {
                String claimKey = grant.claimKey();
                if (claimKey == null) {
                    repeatableGrants.add(grant);
                } else if (existingClaimed.contains(claimKey)) {
                    initialSkipped.add(claimKey);
                } else {
                    grantsByClaimKey.computeIfAbsent(claimKey, k -> new ArrayList<>()).add(grant);
                }
            }

            List<RewardGrant> appliedGrants = Collections.synchronizedList(new ArrayList<>());
            List<RewardGrant> failedGrants = Collections.synchronizedList(new ArrayList<>());
            Set<String> newlyClaimedCollector = ConcurrentHashMap.newKeySet();
            Set<String> alreadyClaimedCollector = ConcurrentHashMap.newKeySet();
            Set<String> failedClaimKeys = ConcurrentHashMap.newKeySet();

            // Clean up any stale plan records for already-claimed keys without mutating initialSkipped.
            List<CompletableFuture<Void>> cleanupOps = new ArrayList<>();
            for (String skippedKey : initialSkipped) {
                cleanupOps.add(deletePlanJson(playerId, skippedKey)
                        .exceptionally(ex -> {
                            logger.log(Level.WARNING,
                                    "Failed to remove stale reward plan '" + skippedKey + "' for " + playerId, ex);
                            return null;
                        }));
            }

            // Repeatable grants (claimKey == null) run without durable plan tracking.
            List<CompletableFuture<Void>> repeatableOps = new ArrayList<>(repeatableGrants.size());
            for (RewardGrant grant : repeatableGrants) {
                CompletableFuture<Void> op = executeRepeatableGrant(grant, context, now, appliedGrants, failedGrants);
                repeatableOps.add(op);
            }

            // Durable claim-keyed plans execute sequentially per claimKey in arrival order.
            CompletableFuture<Void> plansChain = CompletableFuture.completedFuture(null);
            for (Map.Entry<String, List<RewardGrant>> planEntry : grantsByClaimKey.entrySet()) {
                String claimKey = planEntry.getKey();
                List<RewardGrant> planGrants = planEntry.getValue();
                plansChain = plansChain.thenCompose(ignored -> processClaimKeyPlan(
                        playerId, playerName, claimKey, bundle.rootTableId(), context, planGrants, now,
                        appliedGrants, failedGrants, newlyClaimedCollector, alreadyClaimedCollector, failedClaimKeys));
            }

            List<CompletableFuture<?>> allBranches = new ArrayList<>();
            allBranches.addAll(cleanupOps);
            allBranches.addAll(repeatableOps);
            allBranches.add(plansChain);

            return CompletableFuture.allOf(allBranches.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        Set<String> combinedSkipped = new LinkedHashSet<>(initialSkipped);
                        combinedSkipped.addAll(alreadyClaimedCollector);
                        return new RewardApplyResult(
                                bundle,
                                List.copyOf(appliedGrants),
                                filterPublicClaimKeys(newlyClaimedCollector),
                                filterPublicClaimKeys(combinedSkipped),
                                List.copyOf(failedGrants),
                                Set.copyOf(failedClaimKeys)
                        );
                    });
        });
    }

    private CompletableFuture<Void> executeRepeatableGrant(
            RewardGrant grant,
            RewardContext context,
            long now,
            List<RewardGrant> appliedGrants,
            List<RewardGrant> failedGrants) {
        return executeGrantWithDetail(grant, context, null, now)
                .handle((outcome, ex) -> {
                    if (ex != null) {
                        logger.log(Level.WARNING, "Failed to apply grant " + describeGrant(grant), ex);
                        failedGrants.add(grant);
                        return (Void) null;
                    }
                    if (outcome.appliedGrant() != null) {
                        appliedGrants.add(outcome.appliedGrant());
                    }
                    if (outcome.failedGrant() != null) {
                        failedGrants.add(outcome.failedGrant());
                    }
                    return (Void) null;
                });
    }

    private CompletableFuture<Void> processClaimKeyPlan(
            UUID playerId,
            @Nullable String playerName,
            String claimKey,
            String rootTableId,
            RewardContext context,
            List<RewardGrant> freshGrants,
            long now,
            List<RewardGrant> appliedGrants,
            List<RewardGrant> failedGrants,
            Set<String> newlyClaimedCollector,
            Set<String> alreadyClaimedCollector,
            Set<String> failedClaimKeys) {
        if (freshGrants.isEmpty()) {
            return finalizeClaimKeyOnly(
                    playerId, playerName, claimKey, now,
                    newlyClaimedCollector, alreadyClaimedCollector, failedClaimKeys);
        }

        return loadPlanJson(playerId, claimKey).handle((optJson, loadErr) -> {
            if (loadErr != null) {
                warnPlanFailureOnce(playerId, claimKey,
                        "Failed to load reward plan '" + claimKey + "' for " + playerId, loadErr);
                failedClaimKeys.add(claimKey);
                return Optional.<RewardPlan>empty();
            }
            if (optJson != null && optJson.isPresent()) {
                DecodeResult decoded = RewardPlanJsonCodec.decode(optJson.get());
                return switch (decoded) {
                    case DecodeResult.Parsed parsed -> Optional.of(parsed.plan());
                    case DecodeResult.UnsupportedVersion uv -> {
                        warnPlanFailureOnce(playerId, claimKey,
                                "Ignoring reward plan '" + claimKey + "' for " + playerId
                                        + " with unsupported schema version v=" + uv.version(),
                                null);
                        yield Optional.<RewardPlan>empty();
                    }
                    case DecodeResult.Malformed mf -> {
                        warnPlanFailureOnce(playerId, claimKey,
                                "Ignoring malformed reward plan '" + claimKey + "' for " + playerId + ": " + mf.reason(),
                                null);
                        failedClaimKeys.add(claimKey);
                        yield Optional.<RewardPlan>empty();
                    }
                };
            }
            return Optional.<RewardPlan>empty();
        }).thenCompose(existingOpt -> {
            if (failedClaimKeys.contains(claimKey)) {
                return CompletableFuture.completedFuture((Void) null);
            }
            if (existingOpt.isPresent()) {
                return executeLoadedPlan(
                        existingOpt.get(), context, now,
                        appliedGrants, failedGrants, newlyClaimedCollector, alreadyClaimedCollector, failedClaimKeys);
            }
            // Check again whether an unsupported-version plan already occupies this key (avoid overwriting future version).
            return loadPlanJson(playerId, claimKey).thenCompose(recheckOpt -> {
                if (recheckOpt.isPresent()) {
                    return CompletableFuture.completedFuture((Void) null);
                }
                RewardPlan freshPlan = RewardPlan.fromGrants(claimKey, rootTableId, context, freshGrants);
                String encoded = RewardPlanJsonCodec.encode(freshPlan);
                return savePlanJsonIfAbsent(playerId, playerName, claimKey, encoded, now)
                        .handle((inserted, saveErr) -> {
                            if (saveErr != null) {
                                warnPlanFailureOnce(playerId, claimKey,
                                        "Failed to persist reward plan '" + claimKey + "' for " + playerId, saveErr);
                                failedClaimKeys.add(claimKey);
                                return Boolean.FALSE;
                            }
                            return Boolean.TRUE.equals(inserted);
                        })
                        .thenCompose(inserted -> {
                            if (failedClaimKeys.contains(claimKey)) {
                                return CompletableFuture.completedFuture((Void) null);
                            }
                            if (Boolean.TRUE.equals(inserted)) {
                                return executeLoadedPlan(
                                        freshPlan, context, now,
                                        appliedGrants, failedGrants, newlyClaimedCollector, alreadyClaimedCollector, failedClaimKeys);
                            }
                            // Concurrent insert won; reload stored plan.
                            return loadPlanJson(playerId, claimKey).thenCompose(loadedOpt -> {
                                if (loadedOpt.isEmpty()) {
                                    failedClaimKeys.add(claimKey);
                                    return CompletableFuture.completedFuture((Void) null);
                                }
                                if (RewardPlanJsonCodec.decode(loadedOpt.get()) instanceof DecodeResult.Parsed parsed) {
                                    return executeLoadedPlan(
                                            parsed.plan(), context, now,
                                            appliedGrants, failedGrants, newlyClaimedCollector, alreadyClaimedCollector, failedClaimKeys);
                                }
                                return CompletableFuture.completedFuture((Void) null);
                            });
                        });
            });
        });
    }

    private CompletableFuture<Void> executeLoadedPlan(
            RewardPlan plan,
            RewardContext context,
            long now,
            List<RewardGrant> appliedGrants,
            List<RewardGrant> failedGrants,
            Set<String> newlyClaimedCollector,
            Set<String> alreadyClaimedCollector,
            Set<String> failedClaimKeys) {
        UUID playerId = plan.playerId;
        @Nullable String playerName = context.playerName() != null ? context.playerName() : plan.playerName;
        String planKey = plan.planKey;

        final boolean[] progressed = {false};
        CompletableFuture<Void> stepChain = CompletableFuture.completedFuture(null);
        for (PlanStep step : plan.steps) {
            if (step.completed) {
                continue;
            }
            stepChain = stepChain.thenCompose(ignored -> {
                RewardGrant pendingGrant = step.toPendingGrant(planKey);
                String currencyIdemKey = "CURRENCY".equals(step.type) ? CURRENCY_IDEMPOTENCY_PREFIX + step.id : null;
                return executeGrantWithDetail(pendingGrant, context, currencyIdemKey, now)
                        .handle((outcome, ex) -> {
                            if (ex != null) {
                                warnPlanFailureOnce(playerId, planKey,
                                        "Failed to apply planned grant " + describeGrant(pendingGrant) + " in '" + planKey + "'",
                                        ex);
                                failedGrants.add(pendingGrant);
                                return (Void) null;
                            }
                            if (outcome.appliedGrant() != null) {
                                appliedGrants.add(outcome.appliedGrant());
                            }
                            if (outcome.failedGrant() != null) {
                                failedGrants.add(outcome.failedGrant());
                                if (outcome.deliveredItemCount() == 0) {
                                    warnPlanFailureOnce(playerId, planKey,
                                            "Pending grant failed in plan '" + planKey + "' for " + playerId
                                                    + ": " + describeGrant(pendingGrant),
                                            null);
                                }
                            }
                            if (outcome.stepCompleted()) {
                                step.completed = true;
                                if ("VANILLA_ITEM".equals(step.type) || "CUSTOM_ITEM".equals(step.type)) {
                                    step.remainingAmount = 0;
                                }
                                progressed[0] = true;
                            } else if (outcome.deliveredItemCount() > 0
                                    && ("VANILLA_ITEM".equals(step.type) || "CUSTOM_ITEM".equals(step.type))) {
                                step.remainingAmount = Math.max(0, step.remainingAmount - outcome.deliveredItemCount());
                                progressed[0] = true;
                            }
                            return (Void) null;
                        });
            });
        }

        return stepChain.thenCompose(ignored -> {
            CompletableFuture<Boolean> progressFuture;
            if (progressed[0]) {
                String updatedJson = RewardPlanJsonCodec.encode(plan);
                progressFuture = updatePlanJson(playerId, planKey, updatedJson, now)
                        .handle((v, err) -> {
                            if (err != null) {
                                warnPlanFailureOnce(playerId, planKey,
                                        "Failed to persist reward plan progress '" + planKey + "' for " + playerId, err);
                                failedClaimKeys.add(planKey);
                                return false;
                            }
                            return true;
                        });
            } else {
                progressFuture = CompletableFuture.completedFuture(true);
            }

            return progressFuture.thenCompose(progressSaved -> {
                if (!progressSaved || !plan.allCompleted()) {
                    return CompletableFuture.completedFuture((Void) null);
                }
                return finalizeClaimKeyOnly(
                        playerId, playerName, planKey, now,
                        newlyClaimedCollector, alreadyClaimedCollector, failedClaimKeys)
                        .thenCompose(ignored2 -> {
                            if (failedClaimKeys.contains(planKey)) {
                                return CompletableFuture.completedFuture((Void) null);
                            }
                            loggedPlanFailureWarnings.remove(playerId + ":" + planKey);
                            return deletePlanJson(playerId, planKey)
                                    .exceptionally(err -> {
                                        logger.log(Level.WARNING,
                                                "Failed to delete completed reward plan '" + planKey + "' for " + playerId,
                                                err);
                                        return null;
                                    });
                        });
            });
        });
    }

    private CompletableFuture<Void> finalizeClaimKeyOnly(
            UUID playerId,
            @Nullable String playerName,
            String claimKey,
            long now,
            Set<String> newlyClaimedCollector,
            Set<String> alreadyClaimedCollector,
            Set<String> failedClaimKeys) {
        return persistClaimKey(playerId, playerName, claimKey, now)
                .handle((claimed, err) -> {
                    if (err != null) {
                        warnPlanFailureOnce(playerId, claimKey,
                                "Failed to finalize reward claim key '" + claimKey + "' for " + playerId, err);
                        failedClaimKeys.add(claimKey);
                        return (Void) null;
                    }
                    if (Boolean.TRUE.equals(claimed)) {
                        newlyClaimedCollector.add(claimKey);
                    } else {
                        alreadyClaimedCollector.add(claimKey);
                    }
                    return (Void) null;
                });
    }

    private record GrantExecutionOutcome(
            boolean stepCompleted,
            int deliveredItemCount,
            @Nullable RewardGrant appliedGrant,
            @Nullable RewardGrant failedGrant
    ) {
        static GrantExecutionOutcome success(RewardGrant grant) {
            return new GrantExecutionOutcome(true, 0, grant, null);
        }

        static GrantExecutionOutcome failure(RewardGrant grant) {
            return new GrantExecutionOutcome(false, 0, null, grant);
        }
    }

    private CompletableFuture<GrantExecutionOutcome> executeGrantWithDetail(
            RewardGrant grant,
            RewardContext context,
            @Nullable String currencyIdempotencyKey,
            long now) {
        UUID playerId = context.playerId();
        @Nullable String playerName = context.playerName();

        return switch (grant) {
            case VanillaItemGrant vg -> executeVanillaItemGrant(vg, playerId);
            case CustomItemGrant ig -> executeCustomItemGrant(ig, playerId);
            default -> executeGrantBoolean(grant, context, currencyIdempotencyKey, now)
                    .thenApply(ok -> Boolean.TRUE.equals(ok)
                            ? GrantExecutionOutcome.success(grant)
                            : GrantExecutionOutcome.failure(grant));
        };
    }

    private CompletableFuture<GrantExecutionOutcome> executeVanillaItemGrant(
            VanillaItemGrant vg,
            UUID playerId) {
        if (itemDelivery == null) {
            return CompletableFuture.completedFuture(GrantExecutionOutcome.failure(vg));
        }
        return safeCallFuture(() -> {
            CompletableFuture<ItemDeliveryResult> detailed =
                    itemDelivery.deliverVanillaItemDetailed(playerId, vg.materialName(), vg.amount());
            if (detailed != null) {
                return detailed;
            }
            CompletableFuture<Boolean> legacy =
                    itemDelivery.deliverVanillaItem(playerId, vg.materialName(), vg.amount());
            if (legacy == null) {
                return CompletableFuture.completedFuture(ItemDeliveryResult.failed(vg.amount()));
            }
            return legacy.thenApply(ok -> Boolean.TRUE.equals(ok)
                    ? ItemDeliveryResult.full(vg.amount())
                    : ItemDeliveryResult.failed(vg.amount()));
        }).thenApply(res -> {
            if (res == null || (res.deliveredAmount() == 0 && !res.completed())) {
                return GrantExecutionOutcome.failure(vg);
            }
            if (res.completed()) {
                return new GrantExecutionOutcome(
                        true, res.deliveredAmount(),
                        new VanillaItemGrant(vg.materialName(), res.deliveredAmount(), vg.claimKey()),
                        null);
            }
            int remaining = Math.max(1, res.remainingAmount());
            return new GrantExecutionOutcome(
                    false, res.deliveredAmount(),
                    new VanillaItemGrant(vg.materialName(), res.deliveredAmount(), vg.claimKey()),
                    new VanillaItemGrant(vg.materialName(), remaining, vg.claimKey()));
        });
    }

    private CompletableFuture<GrantExecutionOutcome> executeCustomItemGrant(
            CustomItemGrant ig,
            UUID playerId) {
        if (itemDelivery == null) {
            return CompletableFuture.completedFuture(GrantExecutionOutcome.failure(ig));
        }
        return safeCallFuture(() -> {
            CompletableFuture<ItemDeliveryResult> detailed =
                    itemDelivery.deliverCustomItemDetailed(playerId, ig.customItemId(), ig.amount());
            if (detailed != null) {
                return detailed;
            }
            CompletableFuture<Boolean> legacy =
                    itemDelivery.deliverCustomItem(playerId, ig.customItemId(), ig.amount());
            if (legacy == null) {
                return CompletableFuture.completedFuture(ItemDeliveryResult.failed(ig.amount()));
            }
            return legacy.thenApply(ok -> Boolean.TRUE.equals(ok)
                    ? ItemDeliveryResult.full(ig.amount())
                    : ItemDeliveryResult.failed(ig.amount()));
        }).thenApply(res -> {
            if (res == null || (res.deliveredAmount() == 0 && !res.completed())) {
                return GrantExecutionOutcome.failure(ig);
            }
            if (res.completed()) {
                return new GrantExecutionOutcome(
                        true, res.deliveredAmount(),
                        new CustomItemGrant(ig.customItemId(), res.deliveredAmount(), ig.claimKey()),
                        null);
            }
            int remaining = Math.max(1, res.remainingAmount());
            return new GrantExecutionOutcome(
                    false, res.deliveredAmount(),
                    new CustomItemGrant(ig.customItemId(), res.deliveredAmount(), ig.claimKey()),
                    new CustomItemGrant(ig.customItemId(), remaining, ig.claimKey()));
        });
    }

    private CompletableFuture<Boolean> executeGrantBoolean(
            RewardGrant grant,
            RewardContext context,
            @Nullable String currencyIdempotencyKey,
            long now) {
        UUID playerId = context.playerId();
        @Nullable String playerName = context.playerName();

        return switch (grant) {
            case ContentUnlockGrant ug -> {
                if (unlockService == null) {
                    yield CompletableFuture.completedFuture(false);
                }
                yield safeCallFuture(() -> {
                    CompletableFuture<Boolean> unlockFuture = unlockService.unlockAsync(playerId, playerName, ug.contentId());
                    if (unlockFuture == null) {
                        boolean fallback = unlockService.unlock(playerId, playerName, ug.contentId());
                        if (fallback) {
                            notifyUnlockSafely(playerId, ug);
                        }
                        return CompletableFuture.completedFuture(true);
                    }
                    return unlockFuture.thenApply(newlyUnlocked -> {
                        if (Boolean.TRUE.equals(newlyUnlocked)) {
                            notifyUnlockSafely(playerId, ug);
                        }
                        return true;
                    });
                });
            }
            case CurrencyGrant cg -> {
                if (currencySink == null) {
                    yield CompletableFuture.completedFuture(false);
                }
                yield safeCallFuture(() -> {
                    CompletableFuture<Boolean> f = currencySink.grantCurrencyIdempotent(
                            playerId, playerName, cg.currencyId(), cg.amount(), currencyIdempotencyKey, now);
                    if (f == null) {
                        f = currencySink.grantCurrency(playerId, playerName, cg.currencyId(), cg.amount());
                    }
                    return f != null ? f : CompletableFuture.completedFuture(false);
                });
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
                if (scriptExecutor != null) {
                    try {
                        yield CompletableFuture.supplyAsync(() -> {
                            try {
                                return scriptHookRegistry.executeAction(sg.hookId(), hookCtx);
                            } catch (RuntimeException ex) {
                                logger.log(Level.WARNING, "Reward script hook failed: " + sg.hookId(), ex);
                                return false;
                            }
                        }, scriptExecutor);
                    } catch (Throwable ex) {
                        logger.log(Level.WARNING, "Failed to dispatch script hook '" + sg.hookId() + "'", ex);
                        yield CompletableFuture.completedFuture(false);
                    }
                }
                try {
                    yield CompletableFuture.completedFuture(scriptHookRegistry.executeAction(sg.hookId(), hookCtx));
                } catch (RuntimeException ex) {
                    logger.log(Level.WARNING, "Reward script hook failed: " + sg.hookId(), ex);
                    yield CompletableFuture.completedFuture(false);
                }
            }
            case VanillaItemGrant vg -> executeVanillaItemGrant(vg, playerId)
                    .thenApply(o -> o.stepCompleted());
            case CustomItemGrant ig -> executeCustomItemGrant(ig, playerId)
                    .thenApply(o -> o.stepCompleted());
        };
    }

    private void notifyUnlockSafely(UUID playerId, ContentUnlockGrant ug) {
        if (!ug.notifyPlayer() || unlockNotifier == null) {
            return;
        }
        try {
            unlockNotifier.notifyContentUnlocked(playerId, buildUnlockNotification(ug));
        } catch (Throwable ex) {
            logger.log(Level.WARNING,
                    "Failed to notify player " + playerId + " of unlocked content '" + ug.contentId() + "'",
                    ex);
        }
    }

    private static <T> CompletableFuture<T> safeCallFuture(Supplier<CompletableFuture<T>> call) {
        try {
            CompletableFuture<T> f = call.get();
            return f != null ? f : CompletableFuture.completedFuture(null);
        } catch (Throwable ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private CompletableFuture<Set<String>> loadClaimedSet(UUID playerId) {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(Set.copyOf(claimedRewardsInMemory.getOrDefault(playerId, Set.of())));
        }
        return safeCallFuture(() -> {
            CompletableFuture<Set<String>> repoFuture = progressionRepository.loadClaimedRewards(playerId);
            if (repoFuture == null) {
                return CompletableFuture.completedFuture(Set.copyOf(claimedRewardsInMemory.getOrDefault(playerId, Set.of())));
            }
            return repoFuture.thenApply(loaded -> loaded == null ? Set.of() : Set.copyOf(loaded));
        });
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
        return safeCallFuture(() -> {
            CompletableFuture<Boolean> future = progressionRepository.tryClaimReward(playerId, playerName, key, now);
            if (future == null) {
                return CompletableFuture.completedFuture(
                        claimedRewardsInMemory.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(key));
            }
            return future.thenApply(claimed -> {
                claimedRewardsInMemory.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(key);
                return Boolean.TRUE.equals(claimed);
            });
        });
    }

    private CompletableFuture<Optional<String>> loadPlanJson(UUID playerId, String planKey) {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(Optional.ofNullable(inMemoryPlan(playerId, planKey)));
        }
        return safeCallFuture(() -> {
            CompletableFuture<Optional<String>> future = progressionRepository.loadRewardPlan(playerId, planKey);
            if (future == null) {
                return CompletableFuture.completedFuture(Optional.ofNullable(inMemoryPlan(playerId, planKey)));
            }
            return future.thenApply(opt -> opt == null ? Optional.empty() : opt);
        });
    }

    private CompletableFuture<Boolean> savePlanJsonIfAbsent(
            UUID playerId,
            @Nullable String playerName,
            String planKey,
            String planJson,
            long now) {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(inMemorySaveIfAbsent(playerId, planKey, planJson));
        }
        return safeCallFuture(() -> {
            CompletableFuture<Boolean> future = progressionRepository.saveRewardPlanIfAbsent(
                    playerId, playerName, planKey, planJson, now);
            if (future == null) {
                return CompletableFuture.completedFuture(inMemorySaveIfAbsent(playerId, planKey, planJson));
            }
            return future.thenApply(Boolean.TRUE::equals);
        });
    }

    private CompletableFuture<Void> updatePlanJson(
            UUID playerId,
            String planKey,
            String planJson,
            long now) {
        if (progressionRepository == null) {
            inMemoryUpdate(playerId, planKey, planJson);
            return CompletableFuture.completedFuture(null);
        }
        return safeCallFuture(() -> {
            CompletableFuture<Void> future = progressionRepository.updateRewardPlanProgress(playerId, planKey, planJson, now);
            if (future == null) {
                inMemoryUpdate(playerId, planKey, planJson);
                return CompletableFuture.completedFuture(null);
            }
            return future;
        });
    }

    private CompletableFuture<Void> deletePlanJson(UUID playerId, String planKey) {
        inMemoryDelete(playerId, planKey);
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(null);
        }
        return safeCallFuture(() -> {
            CompletableFuture<Void> future = progressionRepository.deleteRewardPlan(playerId, planKey);
            return future == null ? CompletableFuture.completedFuture(null) : future;
        });
    }

    private CompletableFuture<Map<String, String>> loadAllPlanJsons(UUID playerId) {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(copyInMemoryPlans(playerId));
        }
        return safeCallFuture(() -> {
            CompletableFuture<Map<String, String>> future = progressionRepository.loadAllRewardPlans(playerId);
            if (future == null) {
                return CompletableFuture.completedFuture(copyInMemoryPlans(playerId));
            }
            return future.thenApply(map -> map == null ? Map.of() : Map.copyOf(map));
        });
    }

    private @Nullable String inMemoryPlan(UUID playerId, String planKey) {
        Map<String, String> map = inMemoryRewardPlans.get(playerId);
        return map == null ? null : map.get(planKey);
    }

    private boolean inMemorySaveIfAbsent(UUID playerId, String planKey, String planJson) {
        return inMemoryRewardPlans
                .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .putIfAbsent(planKey, planJson) == null;
    }

    private void inMemoryUpdate(UUID playerId, String planKey, String planJson) {
        inMemoryRewardPlans
                .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(planKey, planJson);
    }

    private void inMemoryDelete(UUID playerId, String planKey) {
        Map<String, String> map = inMemoryRewardPlans.get(playerId);
        if (map != null) {
            map.remove(planKey);
        }
    }

    private Map<String, String> copyInMemoryPlans(UUID playerId) {
        Map<String, String> map = inMemoryRewardPlans.get(playerId);
        return (map == null || map.isEmpty()) ? Map.of() : Map.copyOf(map);
    }

    private static Set<String> filterPublicClaimKeys(Set<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        Set<String> filtered = new LinkedHashSet<>(keys.size());
        for (String key : keys) {
            if (!key.startsWith(PureRewardTableEvaluator.INTERNAL_SF_CLAIM_PREFIX)) {
                filtered.add(key);
            }
        }
        return Set.copyOf(filtered);
    }

    private void warnPlanFailureOnce(
            UUID playerId,
            String planKey,
            String message,
            @Nullable Throwable ex) {
        String key = playerId + ":" + planKey;
        Level level = loggedPlanFailureWarnings.add(key) ? Level.WARNING : Level.FINE;
        if (ex != null) {
            logger.log(level, message, ex);
        } else {
            logger.log(level, message);
        }
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
