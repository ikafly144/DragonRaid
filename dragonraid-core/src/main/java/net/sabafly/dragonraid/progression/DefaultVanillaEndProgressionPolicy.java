package net.sabafly.dragonraid.progression;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
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
import net.sabafly.dragonraid.api.script.ScriptHookContext;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
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
    private final Map<String, VanillaProgressionRule> packRules = new ConcurrentHashMap<>();
    private final Set<String> achievedServerMilestones = ConcurrentHashMap.newKeySet();
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
        for (VanillaProgressionRule rule : sortedRules()) {
            EndEntryGateSpec gate = rule.entryGate();
            if (gate == null) {
                continue;
            }
            if (!isGateSatisfied(playerId, rule, gate)) {
                String messageKey = !gate.denialMessageKey().isBlank()
                        ? gate.denialMessageKey()
                        : defaultDenialMessageKey(gate.mode());
                return VanillaEndAccessDecision.deny(
                        messageKey,
                        Map.of("target", gate.targetId(), "rule", rule.id()),
                        gate.allowBypassPermission(),
                        gate.pushBackOnDeny()
                );
            }
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
        executeMatchingClearHooks(encounterId.trim(), null, true, false, participantIds, null);
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
    public void registerRule(VanillaProgressionRule rule) {
        Objects.requireNonNull(rule, "rule");
        staticRules.put(rule.id(), rule);
    }

    @Override
    public void replacePackRules(Collection<VanillaProgressionRule> rules) {
        packRules.clear();
        if (rules != null) {
            for (VanillaProgressionRule rule : rules) {
                if (rule != null) {
                    packRules.put(rule.id(), rule);
                }
            }
        }
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
        achievedServerMilestones.add(milestoneKey);
        if (progressionRepository != null) {
            progressionRepository.tryClaimServerFirst(milestoneKey, participantIds, clockMillis.getAsLong())
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to persist server milestone " + milestoneKey, ex);
                        return false;
                    });
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
            @Nullable net.sabafly.dragonraid.api.script.EncounterDifficulty difficulty,
            boolean firstClear,
            boolean serverFirstHint,
            Collection<UUID> participantIds,
            @Nullable EncounterResultSnapshot snapshot) {
        long now = clockMillis.getAsLong();
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (VanillaProgressionRule rule : sortedRules()) {
            List<VanillaClearHookSpec> hooks = rule.clearHooks();
            for (int index = 0; index < hooks.size(); index++) {
                VanillaClearHookSpec hook = hooks.get(index);
                if (!matchesEncounter(hook.encounterId(), encounterId)) {
                    continue;
                }
                if (hook.difficulty() != null && difficulty != null && hook.difficulty() != difficulty) {
                    continue;
                }
                if (hook.firstClearOnly() && !firstClear) {
                    continue;
                }
                if (hook.serverFirstOnly()) {
                    String hookKey = HOOK_MILESTONE_PREFIX + rule.id() + ":" + index + ":" + encounterId;
                    CompletableFuture<Boolean> claimFuture = progressionRepository != null
                            ? progressionRepository.tryClaimServerFirst(hookKey, participantIds, now)
                            : CompletableFuture.completedFuture(achievedServerMilestones.add(hookKey));
                    futures.add(claimFuture.thenCompose(claimed -> {
                        if (!Boolean.TRUE.equals(claimed)) {
                            return CompletableFuture.completedFuture(null);
                        }
                        achievedServerMilestones.add(hookKey);
                        return runClearHook(hook, participantIds, snapshot);
                    }).exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to claim server-first clear hook " + hookKey, ex);
                        return null;
                    }));
                } else {
                    futures.add(runClearHook(hook, participantIds, snapshot));
                }
            }
        }
        return futures.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> runClearHook(
            VanillaClearHookSpec hook,
            Collection<UUID> participantIds,
            @Nullable EncounterResultSnapshot snapshot) {
        CompletableFuture<Void> progressionFuture = CompletableFuture.completedFuture(null);
        if (vanillaProgressionService != null) {
            if (hook.syncDragonBattleDefeated() || hook.generateExitPortal() || hook.removeLiveVanillaDragon()) {
                progressionFuture = vanillaProgressionService.synchronizeDragonDefeated(
                        hook.endWorldName(),
                        hook.generateExitPortal(),
                        hook.syncDragonBattleDefeated(),
                        hook.removeLiveVanillaDragon()
                ).thenCompose(ok -> {
                    if (hook.spawnGatewayCount() > 0) {
                        return vanillaProgressionService.expandEndGateways(
                                hook.endWorldName(),
                                hook.spawnGatewayCount(),
                                hook.maxGateways()
                        ).thenAccept(count -> {});
                    }
                    return CompletableFuture.completedFuture(null);
                }).exceptionally(ex -> {
                    logger.log(Level.WARNING, "Vanilla DragonBattle/gateway progression hook failed", ex);
                    return null;
                });
            } else if (hook.spawnGatewayCount() > 0) {
                progressionFuture = vanillaProgressionService.expandEndGateways(
                        hook.endWorldName(),
                        hook.spawnGatewayCount(),
                        hook.maxGateways()
                ).thenAccept(count -> {}).exceptionally(gwErr -> {
                    logger.log(Level.WARNING, "Failed to expand vanilla End gateways", gwErr);
                    return null;
                });
            }
        }

        if (!hook.scriptHookId().isBlank() && scriptHookRegistry != null) {
            ScriptHookContext ctx = new ScriptHookContext(
                    serviceRegistry,
                    null,
                    null,
                    participantIds,
                    snapshot,
                    hook.params(),
                    Map.of("encounterId", snapshot != null ? snapshot.encounterId() : hook.encounterId())
            );
            try {
                scriptHookRegistry.executeAction(hook.scriptHookId(), ctx);
            } catch (RuntimeException ex) {
                logger.log(Level.WARNING, "Clear progression script hook failed: " + hook.scriptHookId(), ex);
            }
        }
        return progressionFuture;
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
            case SCRIPT_HOOK -> scriptHookRegistry != null
                    && !gate.targetId().isBlank()
                    && scriptHookRegistry.evaluateCondition(
                            gate.targetId(),
                            ScriptHookContext.forPlayer(serviceRegistry, playerId, null, Map.of("ruleId", rule.id()), Map.of())
                    );
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
        java.util.Map<String, VanillaProgressionRule> merged = new java.util.LinkedHashMap<>(staticRules);
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
