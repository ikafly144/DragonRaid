package net.sabafly.dragonraid.reward;

import java.util.ArrayList;
import java.util.Collection;
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
 * with atomic {@code claimKey} persistence against concurrent completions.
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
        if (bundle.isEmpty()) {
            return CompletableFuture.completedFuture(RewardApplyResult.empty(bundle));
        }

        UUID playerId = context.playerId();
        @Nullable String playerName = context.playerName();
        long now = clockMillis.getAsLong();

        Map<String, CompletableFuture<Boolean>> claimFutures = new LinkedHashMap<>();
        for (String claimKey : bundle.claimKeys()) {
            CompletableFuture<Boolean> future = progressionRepository != null
                    ? progressionRepository.tryClaimReward(playerId, playerName, claimKey, now)
                            .exceptionally(ex -> {
                                logger.log(Level.WARNING, "Failed to claim reward key '" + claimKey + "' for " + playerId, ex);
                                return false;
                            })
                    : CompletableFuture.completedFuture(
                            claimedRewardsInMemory.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(claimKey));
            claimFutures.put(claimKey, future);
        }

        CompletableFuture<Void> claimsReady = claimFutures.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(claimFutures.values().toArray(CompletableFuture[]::new));

        return claimsReady.thenCompose(ignored -> {
            Set<String> newlyClaimed = new LinkedHashSet<>();
            Set<String> skippedClaims = new LinkedHashSet<>();
            for (Map.Entry<String, CompletableFuture<Boolean>> entry : claimFutures.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().join())) {
                    newlyClaimed.add(entry.getKey());
                } else {
                    skippedClaims.add(entry.getKey());
                }
            }

            List<RewardGrant> appliedGrants = new ArrayList<>();
            List<CompletableFuture<Void>> asyncOps = new ArrayList<>();

            for (RewardGrant grant : bundle.grants()) {
                String claimKey = grant.claimKey();
                if (claimKey != null && !newlyClaimed.contains(claimKey)) {
                    continue;
                }
                executeGrant(grant, context, appliedGrants, asyncOps);
            }

            RewardApplyResult result = new RewardApplyResult(bundle, appliedGrants, newlyClaimed, skippedClaims);
            if (asyncOps.isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            return CompletableFuture.allOf(asyncOps.toArray(CompletableFuture[]::new))
                    .thenApply(v -> result);
        });
    }

    private void executeGrant(
            RewardGrant grant,
            RewardContext context,
            List<RewardGrant> appliedGrants,
            List<CompletableFuture<Void>> asyncOps) {
        UUID playerId = context.playerId();
        @Nullable String playerName = context.playerName();

        switch (grant) {
            case ContentUnlockGrant ug -> {
                if (unlockService != null) {
                    boolean newlyUnlocked = unlockService.unlock(playerId, playerName, ug.contentId());
                    if (newlyUnlocked && ug.notifyPlayer() && unlockNotifier != null) {
                        unlockNotifier.notifyContentUnlocked(playerId, buildUnlockNotification(ug));
                    }
                    appliedGrants.add(ug);
                }
            }
            case CurrencyGrant cg -> {
                if (currencySink != null) {
                    asyncOps.add(currencySink.grantCurrency(playerId, playerName, cg.currencyId(), cg.amount())
                            .thenAccept(ok -> {})
                            .exceptionally(ex -> {
                                logger.log(Level.WARNING, "Failed to grant reward currency " + cg.currencyId(), ex);
                                return null;
                            }));
                    appliedGrants.add(cg);
                }
            }
            case VanillaItemGrant vg -> {
                if (itemDelivery != null) {
                    itemDelivery.deliverVanillaItem(playerId, vg.materialName(), vg.amount());
                    appliedGrants.add(vg);
                }
            }
            case CustomItemGrant ig -> {
                if (itemDelivery != null) {
                    itemDelivery.deliverCustomItem(playerId, ig.customItemId(), ig.amount());
                    appliedGrants.add(ig);
                }
            }
            case ScriptHookGrant sg -> {
                if (scriptHookRegistry != null) {
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
                        scriptHookRegistry.executeAction(sg.hookId(), hookCtx);
                        appliedGrants.add(sg);
                    } catch (RuntimeException ex) {
                        logger.log(Level.WARNING, "Reward script hook failed: " + sg.hookId(), ex);
                    }
                }
            }
        }
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
