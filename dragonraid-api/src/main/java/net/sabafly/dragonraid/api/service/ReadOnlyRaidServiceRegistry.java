package net.sabafly.dragonraid.api.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.achievement.AchievementTriggerType;
import net.sabafly.dragonraid.api.achievement.AchievementUnlockedEvent;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.economy.CurrencyDefinition;
import net.sabafly.dragonraid.api.economy.RaidEconomyService;
import net.sabafly.dragonraid.api.finder.ContentEligibilityResult;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.finder.ReadyCheckListener;
import net.sabafly.dragonraid.api.finder.ReadyCheckSession;
import net.sabafly.dragonraid.api.item.CustomItemDefinition;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot;
import net.sabafly.dragonraid.api.party.PartyChangeListener;
import net.sabafly.dragonraid.api.party.PartyChatChannel;
import net.sabafly.dragonraid.api.party.PartyInvite;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.api.progression.VanillaEndAccessDecision;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.RewardTableExtension;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.stat.RaidStatService;
import net.sabafly.dragonraid.api.stat.StatSnapshot;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only {@link RaidServiceRegistry} facade exposed to pure condition hooks (such as reward
 * {@code SIMULATE} and portal gate checks) so script predicates cannot mutate persistent or
 * runtime state during evaluation.
 */
public final class ReadOnlyRaidServiceRegistry implements RaidServiceRegistry {

    private final RaidServiceRegistry delegate;

    private ReadOnlyRaidServiceRegistry(RaidServiceRegistry delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static @Nullable RaidServiceRegistry wrap(@Nullable RaidServiceRegistry delegate) {
        if (delegate == null) {
            return null;
        }
        if (delegate instanceof ReadOnlyRaidServiceRegistry) {
            return delegate;
        }
        return new ReadOnlyRaidServiceRegistry(delegate);
    }

    @Override
    public <T> void register(Class<T> serviceType, T implementation) {
        throw new UnsupportedOperationException("Cannot register services on read-only condition registry");
    }

    @Override
    public <T> Optional<T> get(Class<T> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType");
        if (serviceType == ContentUnlockService.class) {
            return delegate.get(ContentUnlockService.class)
                    .map(s -> serviceType.cast(new ReadOnlyContentUnlockService(s)));
        }
        if (serviceType == RaidEconomyService.class) {
            return delegate.get(RaidEconomyService.class)
                    .map(s -> serviceType.cast(new ReadOnlyRaidEconomyService(s)));
        }
        if (serviceType == RaidAchievementService.class) {
            return delegate.get(RaidAchievementService.class)
                    .map(s -> serviceType.cast(new ReadOnlyRaidAchievementService(s)));
        }
        if (serviceType == ContentFinderService.class) {
            return delegate.get(ContentFinderService.class)
                    .map(s -> serviceType.cast(new ReadOnlyContentFinderService(s)));
        }
        if (serviceType == RaidRewardService.class) {
            return delegate.get(RaidRewardService.class)
                    .map(s -> serviceType.cast(new ReadOnlyRaidRewardService(s)));
        }
        if (serviceType == VanillaEndProgressionPolicy.class) {
            return delegate.get(VanillaEndProgressionPolicy.class)
                    .map(s -> serviceType.cast(new ReadOnlyVanillaEndProgressionPolicy(s)));
        }
        if (serviceType == RaidPartyService.class) {
            return delegate.get(RaidPartyService.class)
                    .map(s -> serviceType.cast(new ReadOnlyRaidPartyService(s)));
        }
        if (serviceType == RaidItemService.class) {
            return delegate.get(RaidItemService.class)
                    .map(s -> serviceType.cast(new ReadOnlyRaidItemService(s)));
        }
        if (serviceType == RaidStatService.class) {
            return delegate.get(RaidStatService.class)
                    .map(s -> serviceType.cast(new ReadOnlyRaidStatService(s)));
        }
        return Optional.empty();
    }

    private static UnsupportedOperationException readOnlyError(String op) {
        return new UnsupportedOperationException(
                "Mutating operation '" + op + "' is forbidden in read-only condition evaluation");
    }

    private record ReadOnlyContentUnlockService(ContentUnlockService delegate) implements ContentUnlockService {
        @Override
        public boolean isUnlocked(UUID playerId, String contentId) {
            return delegate.isUnlocked(playerId, contentId);
        }

        @Override
        public boolean unlock(UUID playerId, String contentId) {
            throw readOnlyError("ContentUnlockService.unlock");
        }

        @Override
        public boolean unlock(UUID playerId, @Nullable String playerName, String contentId) {
            throw readOnlyError("ContentUnlockService.unlock");
        }

        @Override
        public boolean revoke(UUID playerId, String contentId) {
            throw readOnlyError("ContentUnlockService.revoke");
        }

        @Override
        public CompletableFuture<Boolean> unlockAsync(UUID playerId, @Nullable String playerName, String contentId) {
            throw readOnlyError("ContentUnlockService.unlockAsync");
        }

        @Override
        public CompletableFuture<Boolean> revokeAsync(UUID playerId, String contentId) {
            throw readOnlyError("ContentUnlockService.revokeAsync");
        }

        @Override
        public Set<String> unlockedContents(UUID playerId) {
            return delegate.unlockedContents(playerId);
        }

        @Override
        public CompletableFuture<Set<String>> loadPlayer(UUID playerId) {
            throw readOnlyError("ContentUnlockService.loadPlayer");
        }

        @Override
        public void evictPlayer(UUID playerId) {
            throw readOnlyError("ContentUnlockService.evictPlayer");
        }
    }

    private record ReadOnlyRaidEconomyService(RaidEconomyService delegate) implements RaidEconomyService {
        @Override
        public void registerCurrency(CurrencyDefinition definition) {
            throw readOnlyError("RaidEconomyService.registerCurrency");
        }

        @Override
        public Optional<CurrencyDefinition> getCurrency(String currencyId) {
            return delegate.getCurrency(currencyId);
        }

        @Override
        public Collection<CurrencyDefinition> currencies() {
            return delegate.currencies();
        }

        @Override
        public long balance(UUID playerId, String currencyId) {
            return delegate.balance(playerId, currencyId);
        }

        @Override
        public CompletableFuture<Long> balanceAsync(UUID playerId, String currencyId) {
            return delegate.balanceAsync(playerId, currencyId);
        }

        @Override
        public boolean deposit(UUID playerId, String currencyId, long amount) {
            throw readOnlyError("RaidEconomyService.deposit");
        }

        @Override
        public CompletableFuture<Boolean> depositAsync(
                UUID playerId,
                @Nullable String playerName,
                String currencyId,
                long amount) {
            throw readOnlyError("RaidEconomyService.depositAsync");
        }

        @Override
        public CompletableFuture<Boolean> depositIdempotentAsync(
                UUID playerId,
                @Nullable String playerName,
                String currencyId,
                long amount,
                @Nullable String idempotencyKey,
                long claimedAtEpochMillis) {
            throw readOnlyError("RaidEconomyService.depositIdempotentAsync");
        }

        @Override
        public boolean withdraw(UUID playerId, String currencyId, long amount) {
            throw readOnlyError("RaidEconomyService.withdraw");
        }

        @Override
        public CompletableFuture<Boolean> withdrawAsync(UUID playerId, String currencyId, long amount) {
            throw readOnlyError("RaidEconomyService.withdrawAsync");
        }
    }

    private record ReadOnlyRaidAchievementService(RaidAchievementService delegate) implements RaidAchievementService {
        @Override
        public void bindCatalogSnapshotSupplier(@Nullable Supplier<ContentPackCatalogSnapshot> supplier) {
            throw readOnlyError("RaidAchievementService.bindCatalogSnapshotSupplier");
        }

        @Override
        public void registerDefinition(AchievementDefinition definition) {
            throw readOnlyError("RaidAchievementService.registerDefinition");
        }

        @Override
        public void replacePackDefinitions(Collection<AchievementDefinition> packDefinitions) {
            throw readOnlyError("RaidAchievementService.replacePackDefinitions");
        }

        @Override
        public Optional<AchievementDefinition> getDefinition(String achievementId) {
            return delegate.getDefinition(achievementId);
        }

        @Override
        public Collection<AchievementDefinition> definitions() {
            return delegate.definitions();
        }

        @Override
        public Set<String> unlockedFor(UUID playerId) {
            return delegate.unlockedFor(playerId);
        }

        @Override
        public CompletableFuture<Set<String>> loadPlayer(UUID playerId) {
            throw readOnlyError("RaidAchievementService.loadPlayer");
        }

        @Override
        public void evictPlayer(UUID playerId) {
            throw readOnlyError("RaidAchievementService.evictPlayer");
        }

        @Override
        public CompletableFuture<List<AchievementUnlockedEvent>> evaluateEndPortalTouch(
                UUID playerId, @Nullable String playerName, @Nullable String worldName) {
            throw readOnlyError("RaidAchievementService.evaluateEndPortalTouch");
        }

        @Override
        public CompletableFuture<List<AchievementUnlockedEvent>> evaluateCustomTrigger(
                UUID playerId, @Nullable String playerName, String customEventKey, Map<String, Object> attributes) {
            throw readOnlyError("RaidAchievementService.evaluateCustomTrigger");
        }

        @Override
        public CompletableFuture<Optional<AchievementUnlockedEvent>> grantAchievement(
                UUID playerId,
                @Nullable String playerName,
                String achievementId,
                AchievementTriggerType triggerType,
                boolean serverFirst,
                Map<String, Object> metadata) {
            throw readOnlyError("RaidAchievementService.grantAchievement");
        }

        @Override
        public CompletableFuture<List<AchievementUnlockedEvent>> resumePendingServerFirst(
                UUID playerId, @Nullable String playerName) {
            throw readOnlyError("RaidAchievementService.resumePendingServerFirst");
        }
    }

    private record ReadOnlyContentFinderService(ContentFinderService delegate) implements ContentFinderService {
        @Override
        public void registerContent(ContentEntrySpec entry) {
            throw readOnlyError("ContentFinderService.registerContent");
        }

        @Override
        public Optional<ContentEntrySpec> getContent(String contentId) {
            return delegate.getContent(contentId);
        }

        @Override
        public Collection<ContentEntrySpec> allContents() {
            return delegate.allContents();
        }

        @Override
        public boolean requestEntry(UUID initiatorId, String contentId) {
            throw readOnlyError("ContentFinderService.requestEntry");
        }

        @Override
        public ContentEligibilityResult evaluateEligibility(UUID initiatorId, String contentId) {
            return delegate.evaluateEligibility(initiatorId, contentId);
        }

        @Override
        public Optional<ReadyCheckSession> startReadyCheck(UUID initiatorId, String contentId) {
            throw readOnlyError("ContentFinderService.startReadyCheck");
        }

        @Override
        public boolean respondReadyCheck(UUID playerId, boolean accepted) {
            throw readOnlyError("ContentFinderService.respondReadyCheck");
        }

        @Override
        public boolean respondReadyCheck(UUID playerId, UUID expectedSessionId, boolean accepted) {
            throw readOnlyError("ContentFinderService.respondReadyCheck");
        }

        @Override
        public boolean cancelReadyCheck(UUID actorId) {
            throw readOnlyError("ContentFinderService.cancelReadyCheck");
        }

        @Override
        public Optional<ReadyCheckSession> activeReadyCheck(UUID playerId) {
            return delegate.activeReadyCheck(playerId);
        }

        @Override
        public void tickReadyChecks() {
            throw readOnlyError("ContentFinderService.tickReadyChecks");
        }

        @Override
        public void addReadyCheckListener(ReadyCheckListener listener) {
            throw readOnlyError("ContentFinderService.addReadyCheckListener");
        }

        @Override
        public void removeReadyCheckListener(ReadyCheckListener listener) {
            throw readOnlyError("ContentFinderService.removeReadyCheckListener");
        }

        @Override
        public boolean unregisterContent(String contentId) {
            throw readOnlyError("ContentFinderService.unregisterContent");
        }

        @Override
        public void replacePackContents(Collection<ContentEntrySpec> packEntries) {
            throw readOnlyError("ContentFinderService.replacePackContents");
        }
    }

    private record ReadOnlyRaidRewardService(RaidRewardService delegate) implements RaidRewardService {
        @Override
        public void registerTable(RewardTable table) {
            throw readOnlyError("RaidRewardService.registerTable");
        }

        @Override
        public void registerExtension(RewardTableExtension extension) {
            throw readOnlyError("RaidRewardService.registerExtension");
        }

        @Override
        public void replacePackTables(Collection<RewardTable> packTables, Collection<RewardTableExtension> packExtensions) {
            throw readOnlyError("RaidRewardService.replacePackTables");
        }

        @Override
        public Optional<RewardTable> getTable(String tableId) {
            return delegate.getTable(tableId);
        }

        @Override
        public Collection<RewardTable> tables() {
            return delegate.tables();
        }

        @Override
        public Collection<RewardTableExtension> extensions() {
            return delegate.extensions();
        }

        @Override
        public Set<String> staticTableIds() {
            return delegate.staticTableIds();
        }

        @Override
        public Collection<RewardTable> staticTables() {
            return delegate.staticTables();
        }

        @Override
        public Collection<RewardTableExtension> staticExtensions() {
            return delegate.staticExtensions();
        }

        @Override
        public Map<String, RewardTable> effectiveTablesSnapshot() {
            return delegate.effectiveTablesSnapshot();
        }

        @Override
        public RewardBundle evaluate(String tableId, RewardContext context, RandomGenerator random) {
            return delegate.evaluate(tableId, context, random);
        }

        @Override
        public RewardBundle evaluate(
                Map<String, RewardTable> tableSnapshot,
                String tableId,
                RewardContext context,
                RandomGenerator random) {
            return delegate.evaluate(tableSnapshot, tableId, context, random);
        }

        @Override
        public RewardBundle evaluateAll(Collection<String> tableIds, RewardContext context, RandomGenerator random) {
            return delegate.evaluateAll(tableIds, context, random);
        }

        @Override
        public RewardBundle evaluateAll(
                Map<String, RewardTable> tableSnapshot,
                Collection<String> tableIds,
                RewardContext context,
                RandomGenerator random) {
            return delegate.evaluateAll(tableSnapshot, tableIds, context, random);
        }

        @Override
        public CompletableFuture<RewardApplyResult> apply(RewardBundle bundle, RewardContext context) {
            throw readOnlyError("RaidRewardService.apply");
        }

        @Override
        public CompletableFuture<RewardApplyResult> evaluateAndApply(
                String tableId, RewardContext context, RandomGenerator random) {
            throw readOnlyError("RaidRewardService.evaluateAndApply");
        }

        @Override
        public CompletableFuture<RewardApplyResult> evaluateAndApplyAll(
                Collection<String> tableIds, RewardContext context, RandomGenerator random) {
            throw readOnlyError("RaidRewardService.evaluateAndApplyAll");
        }

        @Override
        public CompletableFuture<RewardApplyResult> evaluateAndApplyAll(
                Map<String, RewardTable> tableSnapshot,
                Collection<String> tableIds,
                RewardContext context,
                RandomGenerator random) {
            throw readOnlyError("RaidRewardService.evaluateAndApplyAll");
        }

        @Override
        public CompletableFuture<List<RewardApplyResult>> resumePendingPlans(
                UUID playerId, @Nullable String playerName) {
            throw readOnlyError("RaidRewardService.resumePendingPlans");
        }
    }

    private record ReadOnlyVanillaEndProgressionPolicy(VanillaEndProgressionPolicy delegate)
            implements VanillaEndProgressionPolicy {
        @Override
        public boolean canEnterVanillaEnd(UUID playerId) {
            return delegate.canEnterVanillaEnd(playerId);
        }

        @Override
        public VanillaEndAccessDecision evaluateVanillaEndAccess(UUID playerId) {
            return delegate.evaluateVanillaEndAccess(playerId);
        }

        @Override
        public void onEncounterCleared(String encounterId, Collection<UUID> participants) {
            throw readOnlyError("VanillaEndProgressionPolicy.onEncounterCleared");
        }

        @Override
        public boolean hasMatchingClearHook(String encounterId, @Nullable EncounterDifficulty difficulty) {
            return delegate.hasMatchingClearHook(encounterId, difficulty);
        }

        @Override
        public boolean hasApplicableWorldProgressionHook(
                String encounterId, @Nullable EncounterDifficulty difficulty, boolean firstClear) {
            return delegate.hasApplicableWorldProgressionHook(encounterId, difficulty, firstClear);
        }

        @Override
        public void markPackLoadFailed(@Nullable String reason) {
            throw readOnlyError("VanillaEndProgressionPolicy.markPackLoadFailed");
        }

        @Override
        public void markPackLoadSucceeded() {
            throw readOnlyError("VanillaEndProgressionPolicy.markPackLoadSucceeded");
        }

        @Override
        public boolean isPackLoadFailed() {
            return delegate.isPackLoadFailed();
        }

        @Override
        public Collection<VanillaProgressionRule> rules() {
            return delegate.rules();
        }

        @Override
        public boolean isServerMilestoneAchieved(String milestoneKey) {
            return delegate.isServerMilestoneAchieved(milestoneKey);
        }

        @Override
        public CompletableFuture<Void> loadServerMilestones() {
            throw readOnlyError("VanillaEndProgressionPolicy.loadServerMilestones");
        }
    }

    private record ReadOnlyRaidPartyService(RaidPartyService delegate) implements RaidPartyService {
        @Override
        public RaidParty createParty(UUID leaderId) {
            throw readOnlyError("RaidPartyService.createParty");
        }

        @Override
        public Optional<RaidParty> partyOf(UUID playerId) {
            return delegate.partyOf(playerId);
        }

        @Override
        public boolean invite(UUID leaderId, UUID targetId) {
            throw readOnlyError("RaidPartyService.invite");
        }

        @Override
        public boolean acceptInvite(UUID playerId, UUID partyId) {
            throw readOnlyError("RaidPartyService.acceptInvite");
        }

        @Override
        public boolean leave(UUID playerId) {
            throw readOnlyError("RaidPartyService.leave");
        }

        @Override
        public boolean kick(UUID leaderId, UUID targetId) {
            throw readOnlyError("RaidPartyService.kick");
        }

        @Override
        public boolean transferLeadership(UUID currentLeaderId, UUID newLeaderId) {
            throw readOnlyError("RaidPartyService.transferLeadership");
        }

        @Override
        public boolean disband(UUID leaderId) {
            throw readOnlyError("RaidPartyService.disband");
        }

        @Override
        public Optional<RaidParty> partyById(UUID partyId) {
            return delegate.partyById(partyId);
        }

        @Override
        public int maxPartySize() {
            return delegate.maxPartySize();
        }

        @Override
        public boolean declineInvite(UUID playerId, UUID partyId) {
            throw readOnlyError("RaidPartyService.declineInvite");
        }

        @Override
        public List<PartyInvite> pendingInvites(UUID targetId) {
            return delegate.pendingInvites(targetId);
        }

        @Override
        public int expireInvites() {
            throw readOnlyError("RaidPartyService.expireInvites");
        }

        @Override
        public boolean markDisconnected(UUID playerId) {
            throw readOnlyError("RaidPartyService.markDisconnected");
        }

        @Override
        public boolean reconnect(UUID playerId) {
            throw readOnlyError("RaidPartyService.reconnect");
        }

        @Override
        public int expireDisconnectedSeats() {
            throw readOnlyError("RaidPartyService.expireDisconnectedSeats");
        }

        @Override
        public boolean isDisconnected(UUID playerId) {
            return delegate.isDisconnected(playerId);
        }

        @Override
        public Set<UUID> disconnectedMemberIds(UUID partyId) {
            return delegate.disconnectedMemberIds(partyId);
        }

        @Override
        public boolean isMutationLocked(UUID playerId) {
            return delegate.isMutationLocked(playerId);
        }

        @Override
        public PartyChatChannel chatChannel(UUID playerId) {
            return delegate.chatChannel(playerId);
        }

        @Override
        public void setChatChannel(UUID playerId, PartyChatChannel channel) {
            throw readOnlyError("RaidPartyService.setChatChannel");
        }

        @Override
        public PartyChatChannel toggleChatChannel(UUID playerId) {
            throw readOnlyError("RaidPartyService.toggleChatChannel");
        }

        @Override
        public void addListener(PartyChangeListener listener) {
            throw readOnlyError("RaidPartyService.addListener");
        }

        @Override
        public void removeListener(PartyChangeListener listener) {
            throw readOnlyError("RaidPartyService.removeListener");
        }
    }

    private record ReadOnlyRaidItemService(RaidItemService delegate) implements RaidItemService {
        @Override
        public void registerItem(CustomItemDefinition item) {
            throw readOnlyError("RaidItemService.registerItem");
        }

        @Override
        public Optional<CustomItemDefinition> getItem(String itemId) {
            return delegate.getItem(itemId);
        }

        @Override
        public Collection<CustomItemDefinition> allItems() {
            return delegate.allItems();
        }

        @Override
        public int averageItemLevel(UUID playerId) {
            return delegate.averageItemLevel(playerId);
        }
    }

    private record ReadOnlyRaidStatService(RaidStatService delegate) implements RaidStatService {
        @Override
        public void setTemporaryModifiers(UUID entityId, Map<String, Double> modifiers) {
            throw readOnlyError("RaidStatService.setTemporaryModifiers");
        }

        @Override
        public void clearTemporaryModifiers(UUID entityId) {
            throw readOnlyError("RaidStatService.clearTemporaryModifiers");
        }

        @Override
        public StatSnapshot snapshotFor(UUID entityId) {
            return delegate.snapshotFor(entityId);
        }
    }
}
