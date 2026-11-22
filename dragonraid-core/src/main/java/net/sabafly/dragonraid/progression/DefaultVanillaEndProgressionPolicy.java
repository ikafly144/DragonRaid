package net.sabafly.dragonraid.progression;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.progression.EndEntryGateMode;
import net.sabafly.dragonraid.api.progression.EndEntryGateSpec;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
import net.sabafly.dragonraid.api.progression.VanillaClearHookSpec;
import net.sabafly.dragonraid.api.progression.VanillaEndAccessDecision;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.progression.VanillaProgressionService;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.ScriptHookContext;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.api.service.ReadOnlyRaidServiceRegistry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

/**
 * Data-driven and script-capable {@link VanillaEndProgressionPolicy} enforcing vanilla End portal
 * entry gates and executing post-clear {@link VanillaProgressionService} and script hooks.
 */
public final class DefaultVanillaEndProgressionPolicy implements VanillaEndProgressionPolicy, Listener {

    private static final String ENCOUNTER_MILESTONE_PREFIX = "encounter:";
    private static final String HOOK_MILESTONE_PREFIX = "progression_hook:";

    private final @Nullable PlayerProgressionRepository progressionRepository;
    private final @Nullable ContentUnlockService unlockService;
    private final @Nullable RaidAchievementService achievementService;
    private final @Nullable VanillaProgressionService vanillaProgressionService;
    private final @Nullable ScriptHookRegistry scriptHookRegistry;
    private final @Nullable RaidServiceRegistry serviceRegistry;
    private final LongSupplier clockMillis;
    private final Logger logger;

    private final Map<String, VanillaProgressionRule> staticRules = new ConcurrentHashMap<>();
    private volatile Map<String, VanillaProgressionRule> packRules = Map.of();
    private volatile boolean packLoadFailed;
    private final Set<String> achievedServerMilestones = ConcurrentHashMap.newKeySet();
    private final Set<String> executedStepsInMemory = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlightHooks = ConcurrentHashMap.newKeySet();
    private final Map<String, List<UUID>> inMemoryHookWinners = new ConcurrentHashMap<>();
    private final Map<String, String> inMemoryMilestoneStates = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> clearedEncountersCache = new ConcurrentHashMap<>();

    public DefaultVanillaEndProgressionPolicy(
            @Nullable PlayerProgressionRepository progressionRepository,
            @Nullable ContentUnlockService unlockService,
            @Nullable RaidAchievementService achievementService,
            @Nullable VanillaProgressionService vanillaProgressionService,
            @Nullable ScriptHookRegistry scriptHookRegistry,
            @Nullable RaidServiceRegistry serviceRegistry,
            Logger logger) {
        this(
                progressionRepository,
                unlockService,
                achievementService,
                vanillaProgressionService,
                scriptHookRegistry,
                serviceRegistry,
                () -> Instant.now().toEpochMilli(),
                logger
        );
    }

    public DefaultVanillaEndProgressionPolicy(
            @Nullable PlayerProgressionRepository progressionRepository,
            @Nullable ContentUnlockService unlockService,
            @Nullable RaidAchievementService achievementService,
            @Nullable VanillaProgressionService vanillaProgressionService,
            @Nullable ScriptHookRegistry scriptHookRegistry,
            @Nullable RaidServiceRegistry serviceRegistry,
            LongSupplier clockMillis,
            Logger logger) {
        this.progressionRepository = progressionRepository;
        this.unlockService = unlockService;
        this.achievementService = achievementService;
        this.vanillaProgressionService = vanillaProgressionService;
        this.scriptHookRegistry = scriptHookRegistry;
        this.serviceRegistry = serviceRegistry;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public boolean canEnterVanillaEnd(UUID playerId) {
        return evaluateVanillaEndAccess(playerId).allowed();
    }

    @Override
    public VanillaEndAccessDecision evaluateVanillaEndAccess(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (packLoadFailed) {
            return VanillaEndAccessDecision.deny(
                    "progression.end-locked-pack-error",
                    Map.of(),
                    false,
                    true
            );
        }
        VanillaProgressionRule firstDenied = null;
        VanillaProgressionRule firstNonBypassable = null;
        boolean allBypassable = true;

        for (VanillaProgressionRule rule : sortedRules()) {
            EndEntryGateSpec gate = rule.entryGate();
            if (gate == null) {
                continue;
            }
            if (!isGateSatisfied(playerId, rule, gate)) {
                if (firstDenied == null) {
                    firstDenied = rule;
                }
                if (!gate.allowBypassPermission()) {
                    allBypassable = false;
                    if (firstNonBypassable == null) {
                        firstNonBypassable = rule;
                    }
                }
            }
        }
        if (firstDenied != null) {
            VanillaProgressionRule primaryRule = firstNonBypassable != null ? firstNonBypassable : firstDenied;
            EndEntryGateSpec primaryGate = primaryRule.entryGate();
            String messageKey = !primaryGate.denialMessageKey().isBlank()
                    ? primaryGate.denialMessageKey()
                    : defaultDenialMessageKey(primaryGate.mode());
            return VanillaEndAccessDecision.deny(
                    messageKey,
                    Map.of("target", primaryGate.targetId(), "rule", primaryRule.id()),
                    allBypassable,
                    primaryGate.pushBackOnDeny()
            );
        }
        return VanillaEndAccessDecision.allow();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEncounterCompleted(RaidEncounterCompletedEvent event) {
        Objects.requireNonNull(event, "event");
        EncounterResultSnapshot snapshot = event.snapshot();
        if (snapshot.outcome() == EncounterOutcome.VICTORY) {
            onEncounterCleared(snapshot, false);
        }
    }

    @Override
    public void onEncounterCleared(String encounterId, Collection<UUID> participants) {
        if (encounterId == null || encounterId.isBlank()) {
            return;
        }
        Collection<UUID> participantIds = participants == null ? List.of() : List.copyOf(participants);
        recordClearMilestones(encounterId.trim(), participantIds);
        // Unknown difficulty / firstClear in legacy string overload: only run unconditional hooks.
        executeMatchingClearHooks(encounterId.trim(), null, false, false, participantIds, null);
    }

    @Override
    public void onEncounterCleared(EncounterResultSnapshot snapshot, boolean serverFirst) {
        onEncounterClearedAsync(snapshot, serverFirst);
    }

    public CompletableFuture<Void> onEncounterClearedAsync(EncounterResultSnapshot snapshot, boolean serverFirst) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.outcome() != EncounterOutcome.VICTORY) {
            return CompletableFuture.completedFuture(null);
        }
        Collection<UUID> participantIds = List.copyOf(snapshot.participantMetrics().keySet());
        recordClearMilestones(snapshot.encounterId(), participantIds);
        return executeMatchingClearHooks(
                snapshot.encounterId(),
                snapshot.difficulty(),
                snapshot.firstClear(),
                serverFirst,
                participantIds,
                snapshot
        );
    }

    @Override
    public boolean hasMatchingClearHook(String encounterId, @Nullable EncounterDifficulty difficulty) {
        if (encounterId == null || encounterId.isBlank()) {
            return false;
        }
        String normalizedId = encounterId.trim();
        for (VanillaProgressionRule rule : sortedRules()) {
            for (VanillaClearHookSpec hook : rule.clearHooks()) {
                if (!matchesEncounter(hook.encounterId(), normalizedId)) {
                    continue;
                }
                if (hook.difficulty() != null && hook.difficulty() != difficulty) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasApplicableWorldProgressionHook(
            String encounterId, @Nullable EncounterDifficulty difficulty, boolean firstClear) {
        if (encounterId == null || encounterId.isBlank()) {
            return false;
        }
        String normalizedId = encounterId.trim();
        for (VanillaProgressionRule rule : sortedRules()) {
            List<VanillaClearHookSpec> hooks = rule.clearHooks();
            for (int index = 0; index < hooks.size(); index++) {
                VanillaClearHookSpec hook = hooks.get(index);
                if (!matchesEncounter(hook.encounterId(), normalizedId)) {
                    continue;
                }
                if (hook.difficulty() != null && hook.difficulty() != difficulty) {
                    continue;
                }
                if (!wantsWorldProgression(hook)) {
                    continue;
                }
                if (!hook.serverFirstOnly()) {
                    if (!hook.firstClearOnly() || firstClear) {
                        return true;
                    }
                    continue;
                }
                if (!hook.firstClearOnly() || firstClear
                        || hasCompletedOrClaimedServerFirstHook(rule.id(), index, hook, normalizedId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean wantsWorldProgression(VanillaClearHookSpec hook) {
        return hook.syncDragonBattleDefeated()
                || hook.generateExitPortal()
                || hook.removeLiveVanillaDragon()
                || hook.spawnGatewayCount() > 0;
    }

    private boolean hasCompletedOrClaimedServerFirstHook(
            String ruleId,
            int index,
            VanillaClearHookSpec hook,
            String encounterId) {
        String hookKey = HOOK_MILESTONE_PREFIX + ruleId + ":id:" + hook.stableKey() + ":" + encounterId;
        String doneKey = hookKey + ":done";
        String claimKey = hookKey + ":claimed";
        String legacyKey = HOOK_MILESTONE_PREFIX + ruleId + ":" + index + ":" + encounterId;
        if (achievedServerMilestones.contains(doneKey)
                || achievedServerMilestones.contains(claimKey)
                || achievedServerMilestones.contains(legacyKey)) {
            return true;
        }
        if (progressionRepository == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(checkMilestoneRecorded(doneKey).get(2, TimeUnit.SECONDS))
                    || Boolean.TRUE.equals(checkMilestoneRecorded(claimKey).get(2, TimeUnit.SECONDS))
                    || Boolean.TRUE.equals(checkMilestoneRecorded(legacyKey).get(2, TimeUnit.SECONDS));
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to query server-first hook milestone state for fallback check", ex);
            return false;
        }
    }

    @Override
    public void markPackLoadFailed(@Nullable String reason) {
        this.packLoadFailed = true;
    }

    @Override
    public void markPackLoadSucceeded() {
        this.packLoadFailed = false;
    }

    @Override
    public boolean isPackLoadFailed() {
        return packLoadFailed;
    }

    @Override
    public void registerRule(VanillaProgressionRule rule) {
        Objects.requireNonNull(rule, "rule");
        staticRules.put(rule.id(), rule);
    }

    @Override
    public void replacePackRules(Collection<VanillaProgressionRule> rules) {
        Map<String, VanillaProgressionRule> next = new LinkedHashMap<>();
        if (rules != null) {
            for (VanillaProgressionRule rule : rules) {
                if (rule != null) {
                    next.put(rule.id(), rule);
                }
            }
        }
        this.packRules = Collections.unmodifiableMap(next);
    }

    @Override
    public Collection<VanillaProgressionRule> rules() {
        return sortedRules();
    }

    @Override
    public boolean isServerMilestoneAchieved(String milestoneKey) {
        if (milestoneKey == null || milestoneKey.isBlank()) {
            return false;
        }
        String trimmed = milestoneKey.trim();
        return achievedServerMilestones.contains(trimmed)
                || achievedServerMilestones.contains(ENCOUNTER_MILESTONE_PREFIX + trimmed);
    }

    @Override
    public CompletableFuture<Void> loadServerMilestones() {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(null);
        }
        return progressionRepository.loadAllServerMilestones()
                .thenAccept(milestones -> {
                    if (milestones != null) {
                        achievedServerMilestones.addAll(milestones);
                    }
                });
    }

    public CompletableFuture<Set<String>> loadPlayerClears(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(
                    Set.copyOf(clearedEncountersCache.getOrDefault(playerId, Set.of()))
            );
        }
        return progressionRepository.loadClearedEncounters(playerId)
                .thenApply(loaded -> {
                    Set<String> set = clearedEncountersCache.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
                    if (loaded != null) {
                        set.addAll(loaded);
                    }
                    return Set.copyOf(set);
                });
    }

    public void evictPlayer(UUID playerId) {
        if (playerId != null) {
            clearedEncountersCache.remove(playerId);
        }
    }

    private void recordClearMilestones(String encounterId, Collection<UUID> participantIds) {
        String milestoneKey = ENCOUNTER_MILESTONE_PREFIX + encounterId;
        if (progressionRepository != null) {
            progressionRepository.tryClaimServerFirst(milestoneKey, participantIds, clockMillis.getAsLong())
                    .thenAccept(ignored -> achievedServerMilestones.add(milestoneKey))
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to persist server milestone " + milestoneKey, ex);
                        return null;
                    });
        } else {
            achievedServerMilestones.add(milestoneKey);
        }
        for (UUID participantId : participantIds) {
            if (participantId != null) {
                clearedEncountersCache.computeIfAbsent(participantId, k -> ConcurrentHashMap.newKeySet())
                        .add(encounterId);
            }
        }
    }

    private CompletableFuture<Void> executeMatchingClearHooks(
            String encounterId,
            @Nullable EncounterDifficulty difficulty,
            boolean firstClear,
            boolean serverFirstHint,
            Collection<UUID> participantIds,
            @Nullable EncounterResultSnapshot snapshot) {
        long now = clockMillis.getAsLong();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (VanillaProgressionRule rule : sortedRules()) {
            List<VanillaClearHookSpec> hooks = rule.clearHooks();
            for (int index = 0; index < hooks.size(); index++) {
                VanillaClearHookSpec hook = hooks.get(index);
                if (!matchesEncounter(hook.encounterId(), encounterId)) {
                    continue;
                }
                if (hook.difficulty() != null && hook.difficulty() != difficulty) {
                    continue;
                }
                boolean allowNewExecution = !hook.firstClearOnly() || firstClear;
                if (hook.serverFirstOnly()) {
                    String hookKey = HOOK_MILESTONE_PREFIX + rule.id() + ":id:" + hook.stableKey() + ":" + encounterId;
                    String legacyKey = HOOK_MILESTONE_PREFIX + rule.id() + ":" + index + ":" + encounterId;
                    futures.add(executeResumableServerFirstHook(
                            hookKey,
                            legacyKey,
                            allowNewExecution,
                            hook,
                            participantIds,
                            snapshot,
                            now
                    ));
                } else if (allowNewExecution) {
                    futures.add(runClearHookStages(hook, participantIds, snapshot, null, now));
                }
            }
        }
        return futures.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> executeResumableServerFirstHook(
            String hookKey,
            String legacyKey,
            boolean allowNewClaim,
            VanillaClearHookSpec hook,
            Collection<UUID> participantIds,
            @Nullable EncounterResultSnapshot snapshot,
            long now) {
        String doneKey = hookKey + ":done";
        String claimKey = hookKey + ":claimed";

        return checkMilestoneRecorded(doneKey).thenCompose(done -> {
            if (Boolean.TRUE.equals(done)) {
                return CompletableFuture.completedFuture(null);
            }
            return checkMilestoneRecorded(claimKey).thenCompose(alreadyClaimed -> {
                if (Boolean.TRUE.equals(alreadyClaimed)) {
                    if (!inFlightHooks.add(hookKey)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return loadExistingHookWinners(claimKey, participantIds)
                            .thenCompose(winners -> runClearHookStages(hook, winners, snapshot, hookKey, now)
                                    .thenCompose(v -> persistMilestoneStep(doneKey, winners, now)))
                            .whenComplete((v, err) -> {
                                inFlightHooks.remove(hookKey);
                                if (err != null) {
                                    logger.log(Level.WARNING, "Server-first clear hook partially failed and remains resumable: " + hookKey, err);
                                }
                            })
                            .exceptionally(ex -> null);
                }
                return checkMilestoneRecorded(legacyKey).thenCompose(legacyDone -> {
                    if (Boolean.TRUE.equals(legacyDone)) {
                        // Migrate pre-PR legacy positional completion to stable hook ID per hook without re-executing.
                        return persistMilestoneStep(doneKey, participantIds, now);
                    }
                    if (!allowNewClaim) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (!inFlightHooks.add(hookKey)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return claimOrLoadHookWinners(claimKey, participantIds, now)
                            .thenCompose(winners -> runClearHookStages(hook, winners, snapshot, hookKey, now)
                                    .thenCompose(v -> persistMilestoneStep(doneKey, winners, now)))
                            .whenComplete((v, err) -> {
                                inFlightHooks.remove(hookKey);
                                if (err != null) {
                                    logger.log(Level.WARNING, "Server-first clear hook partially failed and remains resumable: " + hookKey, err);
                                }
                            })
                            .exceptionally(ex -> null);
                });
            });
        });
    }

    private CompletableFuture<Collection<UUID>> loadExistingHookWinners(
            String claimKey,
            Collection<UUID> fallbackParticipants) {
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(
                    inMemoryHookWinners.getOrDefault(claimKey,
                            fallbackParticipants == null ? List.of() : List.copyOf(fallbackParticipants)));
        }
        return progressionRepository.loadServerMilestoneParticipants(claimKey)
                .thenApply(loaded -> {
                    if (loaded != null && !loaded.isEmpty()) {
                        return (Collection<UUID>) loaded;
                    }
                    return fallbackParticipants == null ? List.of() : List.copyOf(fallbackParticipants);
                });
    }

    private CompletableFuture<Collection<UUID>> claimOrLoadHookWinners(
            String claimKey,
            Collection<UUID> candidateParticipants,
            long now) {
        if (progressionRepository == null) {
            boolean fresh = achievedServerMilestones.add(claimKey);
            if (fresh) {
                List<UUID> snapshot = candidateParticipants == null ? List.of() : List.copyOf(candidateParticipants);
                inMemoryHookWinners.put(claimKey, snapshot);
                return CompletableFuture.completedFuture(snapshot);
            }
            return CompletableFuture.completedFuture(inMemoryHookWinners.getOrDefault(claimKey, List.of()));
        }
        return progressionRepository.tryClaimServerFirst(claimKey, candidateParticipants, now)
                .thenCompose(fresh -> {
                    achievedServerMilestones.add(claimKey);
                    if (Boolean.TRUE.equals(fresh)) {
                        return CompletableFuture.completedFuture(
                                candidateParticipants == null ? List.of() : List.copyOf(candidateParticipants));
                    }
                    return loadExistingHookWinners(claimKey, candidateParticipants);
                });
    }

    private CompletableFuture<Void> runClearHookStages(
            VanillaClearHookSpec hook,
            Collection<UUID> participantIds,
            @Nullable EncounterResultSnapshot snapshot,
            @Nullable String hookKeyPrefix,
            long now) {
        boolean wantsSync = hook.syncDragonBattleDefeated() || hook.generateExitPortal() || hook.removeLiveVanillaDragon();
        boolean wantsGateways = hook.spawnGatewayCount() > 0;
        boolean wantsScript = !hook.scriptHookId().isBlank();

        if ((wantsSync || wantsGateways) && vanillaProgressionService == null) {
            CompletableFuture<Void> failed = CompletableFuture.failedFuture(
                    new IllegalStateException("VanillaProgressionService unavailable for clear hook on " + hook.encounterId()));
            return hookKeyPrefix == null ? swallowNonServerFirstFailure(failed) : failed;
        }
        if (wantsScript && scriptHookRegistry == null) {
            CompletableFuture<Void> failed = CompletableFuture.failedFuture(
                    new IllegalStateException("ScriptHookRegistry unavailable for clear hook script: " + hook.scriptHookId()));
            return hookKeyPrefix == null ? swallowNonServerFirstFailure(failed) : failed;
        }

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        if (wantsSync) {
            String syncStepKey = hookKeyPrefix != null ? hookKeyPrefix + ":step:sync" : null;
            chain = chain.thenCompose(v -> checkMilestoneRecorded(syncStepKey).thenCompose(alreadyDone -> {
                if (Boolean.TRUE.equals(alreadyDone)) {
                    return CompletableFuture.completedFuture(null);
                }
                if (syncStepKey != null && executedStepsInMemory.contains(syncStepKey)) {
                    return persistMilestoneStep(syncStepKey, participantIds, now);
                }
                return vanillaProgressionService.synchronizeDragonDefeated(
                        hook.endWorldName(),
                        hook.generateExitPortal(),
                        hook.syncDragonBattleDefeated(),
                        hook.removeLiveVanillaDragon()
                ).thenCompose(ok -> {
                    if (!Boolean.TRUE.equals(ok)) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("DragonBattle synchronization returned false for " + hook.endWorldName()));
                    }
                    if (syncStepKey != null) {
                        executedStepsInMemory.add(syncStepKey);
                    }
                    return persistMilestoneStep(syncStepKey, participantIds, now);
                });
            }));
        }

        if (wantsGateways) {
            String gatewayStepKey = hookKeyPrefix != null ? hookKeyPrefix + ":step:gateways" : null;
            chain = chain.thenCompose(v -> checkMilestoneRecorded(gatewayStepKey).thenCompose(alreadyDone -> {
                if (Boolean.TRUE.equals(alreadyDone)) {
                    return CompletableFuture.completedFuture(null);
                }
                if (gatewayStepKey != null && executedStepsInMemory.contains(gatewayStepKey)) {
                    return persistMilestoneStep(gatewayStepKey, participantIds, now);
                }
                if (hookKeyPrefix == null) {
                    return vanillaProgressionService.expandEndGateways(
                            hook.endWorldName(),
                            hook.spawnGatewayCount(),
                            hook.maxGateways()
                    ).thenAccept(spawned -> {});
                }
                String gatewayTargetKey = hookKeyPrefix + ":gateway_target";
                return resolveOrPersistGatewayTarget(gatewayTargetKey, hook, now)
                        .thenCompose(targetCount -> vanillaProgressionService
                                .expandEndGatewaysToTarget(hook.endWorldName(), targetCount, hook.maxGateways())
                                .thenCompose(result -> {
                                    if (result == null || result.finalGatewayCount() < targetCount) {
                                        int actual = result == null ? -1 : result.finalGatewayCount();
                                        return CompletableFuture.failedFuture(new IllegalStateException(
                                                "Gateway expansion did not reach target count " + targetCount
                                                        + " (actual=" + actual + ") for " + hook.endWorldName()));
                                    }
                                    executedStepsInMemory.add(gatewayStepKey);
                                    return persistMilestoneStep(gatewayStepKey, participantIds, now);
                                }));
            }));
        }

        if (wantsScript) {
            String scriptStepKey = hookKeyPrefix != null ? hookKeyPrefix + ":step:script" : null;
            chain = chain.thenCompose(v -> checkMilestoneRecorded(scriptStepKey).thenCompose(alreadyDone -> {
                if (Boolean.TRUE.equals(alreadyDone)) {
                    return CompletableFuture.completedFuture(null);
                }
                if (scriptStepKey != null && executedStepsInMemory.contains(scriptStepKey)) {
                    return persistMilestoneStep(scriptStepKey, participantIds, now);
                }
                ScriptHookContext ctx = new ScriptHookContext(
                        serviceRegistry,
                        null,
                        null,
                        participantIds,
                        snapshot,
                        hook.params(),
                        Map.of("encounterId", snapshot != null ? snapshot.encounterId() : hook.encounterId())
                );
                boolean executed = scriptHookRegistry.executeAction(hook.scriptHookId(), ctx);
                if (!executed && hookKeyPrefix != null) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Unregistered progression script hook: " + hook.scriptHookId()));
                }
                if (executed && scriptStepKey != null) {
                    executedStepsInMemory.add(scriptStepKey);
                }
                return persistMilestoneStep(scriptStepKey, participantIds, now);
            }));
        }

        if (hookKeyPrefix == null) {
            return swallowNonServerFirstFailure(chain);
        }
        return chain;
    }

    private CompletableFuture<Void> swallowNonServerFirstFailure(CompletableFuture<Void> future) {
        return future.exceptionally(ex -> {
            logger.log(Level.WARNING, "Clear progression hook failed", ex);
            return null;
        });
    }

    private CompletableFuture<Integer> resolveOrPersistGatewayTarget(
            String gatewayTargetKey,
            VanillaClearHookSpec hook,
            long now) {
        String inMem = inMemoryMilestoneStates.get(gatewayTargetKey);
        if (inMem != null) {
            try {
                return CompletableFuture.completedFuture(Integer.parseInt(inMem));
            } catch (NumberFormatException ignored) {
                inMemoryMilestoneStates.remove(gatewayTargetKey);
            }
        }
        CompletableFuture<Optional<String>> loadedFuture = progressionRepository != null
                ? progressionRepository.loadServerMilestoneState(gatewayTargetKey)
                : CompletableFuture.completedFuture(Optional.empty());
        return loadedFuture.thenCompose(loadedOpt -> {
            if (loadedOpt != null && loadedOpt.isPresent()) {
                try {
                    int parsed = Integer.parseInt(loadedOpt.get().trim());
                    inMemoryMilestoneStates.put(gatewayTargetKey, Integer.toString(parsed));
                    return CompletableFuture.completedFuture(parsed);
                } catch (NumberFormatException ignored) {
                    // Fall through to recompute cleanly.
                }
            }
            return vanillaProgressionService.getEndGatewayCount(hook.endWorldName()).thenCompose(current -> {
                int effectiveMax = Math.min(hook.maxGateways(), VanillaClearHookSpec.MAX_VANILLA_GATEWAYS);
                int cur = current == null ? 0 : current;
                int target = cur >= effectiveMax
                        ? cur
                        : Math.min(cur + hook.spawnGatewayCount(), effectiveMax);
                String encoded = Integer.toString(target);
                if (progressionRepository != null) {
                    return progressionRepository.saveServerMilestoneState(gatewayTargetKey, encoded, now)
                            .thenApply(ignored -> {
                                inMemoryMilestoneStates.put(gatewayTargetKey, encoded);
                                return target;
                            });
                }
                inMemoryMilestoneStates.put(gatewayTargetKey, encoded);
                return CompletableFuture.completedFuture(target);
            });
        });
    }

    private CompletableFuture<Boolean> checkMilestoneRecorded(@Nullable String key) {
        if (key == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (achievedServerMilestones.contains(key)) {
            return CompletableFuture.completedFuture(true);
        }
        if (progressionRepository == null) {
            return CompletableFuture.completedFuture(false);
        }
        return progressionRepository.isServerFirstAchieved(key).thenApply(found -> {
            if (Boolean.TRUE.equals(found)) {
                achievedServerMilestones.add(key);
                return true;
            }
            return false;
        });
    }

    private CompletableFuture<Void> persistMilestoneStep(
            @Nullable String stepKey,
            Collection<UUID> participantIds,
            long now) {
        if (stepKey == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (progressionRepository == null) {
            achievedServerMilestones.add(stepKey);
            return CompletableFuture.completedFuture(null);
        }
        return progressionRepository.tryClaimServerFirst(stepKey, participantIds, now)
                .thenApply(ignored -> {
                    achievedServerMilestones.add(stepKey);
                    return null;
                });
    }

    private boolean isGateSatisfied(UUID playerId, VanillaProgressionRule rule, EndEntryGateSpec gate) {
        return switch (gate.mode()) {
            case ALLOW -> true;
            case DENY -> false;
            case REQUIRE_SERVER_FIRST -> isServerMilestoneSatisfied(gate.targetId());
            case REQUIRE_PLAYER_UNLOCK -> unlockService != null
                    && !gate.targetId().isBlank()
                    && unlockService.isUnlocked(playerId, gate.targetId());
            case REQUIRE_PLAYER_ACHIEVEMENT -> achievementService != null
                    && !gate.targetId().isBlank()
                    && achievementService.hasUnlocked(playerId, gate.targetId());
            case REQUIRE_PLAYER_CLEAR -> hasPlayerCleared(playerId, gate.targetId());
            case SCRIPT_HOOK -> {
                if (scriptHookRegistry == null || gate.targetId().isBlank()) {
                    yield false;
                }
                try {
                    yield scriptHookRegistry.evaluateCondition(
                            gate.targetId(),
                            ScriptHookContext.forPlayer(
                                    ReadOnlyRaidServiceRegistry.wrap(serviceRegistry),
                                    playerId,
                                    null,
                                    Map.of("ruleId", rule.id()),
                                    Map.of()
                            )
                    );
                } catch (RuntimeException ex) {
                    logger.log(Level.WARNING, "End entry gate script hook threw exception: " + gate.targetId(), ex);
                    yield false;
                }
            }
        };
    }

    private boolean isServerMilestoneSatisfied(String targetId) {
        if (targetId.isBlank()) {
            return false;
        }
        return achievedServerMilestones.contains(targetId)
                || achievedServerMilestones.contains(ENCOUNTER_MILESTONE_PREFIX + targetId);
    }

    private boolean hasPlayerCleared(UUID playerId, String encounterId) {
        if (encounterId.isBlank()) {
            return false;
        }
        Set<String> cached = clearedEncountersCache.get(playerId);
        return cached != null && cached.contains(encounterId);
    }

    private List<VanillaProgressionRule> sortedRules() {
        Map<String, VanillaProgressionRule> merged = new LinkedHashMap<>(staticRules);
        merged.putAll(packRules);
        return merged.values().stream()
                .sorted(Comparator.comparingInt(VanillaProgressionRule::priority).thenComparing(VanillaProgressionRule::id))
                .toList();
    }

    private static boolean matchesEncounter(String ruleEncounterId, String actualEncounterId) {
        return "*".equals(ruleEncounterId) || ruleEncounterId.equals(actualEncounterId);
    }

    private static String defaultDenialMessageKey(EndEntryGateMode mode) {
        return switch (mode) {
            case REQUIRE_SERVER_FIRST -> "progression.end-locked-server-first";
            case REQUIRE_PLAYER_UNLOCK -> "progression.end-locked-unlock-required";
            case REQUIRE_PLAYER_ACHIEVEMENT -> "progression.end-locked-achievement-required";
            case REQUIRE_PLAYER_CLEAR -> "progression.end-locked-clear-required";
            case ALLOW, SCRIPT_HOOK, DENY -> "progression.end-locked-generic";
        };
    }
}
