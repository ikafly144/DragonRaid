package net.sabafly.dragonraid.achievement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.achievement.AchievementTriggerSpec;
import net.sabafly.dragonraid.api.achievement.AchievementTriggerType;
import net.sabafly.dragonraid.api.achievement.AchievementUnlockedEvent;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.event.RaidEncounterCompletedEvent;
import net.sabafly.dragonraid.api.event.RaidMechanicResolvedEvent;
import net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot;
import net.sabafly.dragonraid.api.progression.PlayerProgressionRepository;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.script.ScriptHookContext;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.api.service.ReadOnlyRaidServiceRegistry;
import net.sabafly.dragonraid.api.util.ImmutableSnapshots;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

/**
 * Data-driven {@link RaidAchievementService} evaluating portal touch, encounter clear,
 * server-first, no-death, mechanic mastery, and script-backed triggers with SQLite persistence.
 */
public final class DefaultRaidAchievementService implements RaidAchievementService, Listener {

    private static final String SERVER_FIRST_PREFIX = "achievement:";

    private final @Nullable PlayerProgressionRepository repository;
    private final @Nullable ScriptHookRegistry scriptHookRegistry;
    private final @Nullable RaidServiceRegistry serviceRegistry;
    private final Consumer<AchievementUnlockedEvent> eventPublisher;
    private final LongSupplier clockMillis;
    private final Logger logger;

    private final Map<String, AchievementDefinition> staticDefinitions = new ConcurrentHashMap<>();
    private volatile Map<String, AchievementDefinition> packDefinitions = Map.of();
    private volatile @Nullable Supplier<ContentPackCatalogSnapshot> catalogSnapshotSupplier;
    private final Map<UUID, Set<String>> unlockedByPlayer = new ConcurrentHashMap<>();
    private final Set<String> pendingGrants = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<UUID>> inMemoryPendingServerFirst = new ConcurrentHashMap<>();
    private final Set<String> claimedServerFirst = ConcurrentHashMap.newKeySet();

    public DefaultRaidAchievementService() {
        this(
                null,
                null,
                null,
                DefaultRaidAchievementService::publishBukkitEvent,
                System::currentTimeMillis,
                Logger.getLogger(DefaultRaidAchievementService.class.getName())
        );
    }

    public DefaultRaidAchievementService(
            @Nullable PlayerProgressionRepository repository,
            @Nullable ScriptHookRegistry scriptHookRegistry,
            @Nullable RaidServiceRegistry serviceRegistry,
            Consumer<AchievementUnlockedEvent> eventPublisher,
            LongSupplier clockMillis,
            Logger logger) {
        this.repository = repository;
        this.scriptHookRegistry = scriptHookRegistry;
        this.serviceRegistry = serviceRegistry;
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private static void publishBukkitEvent(AchievementUnlockedEvent event) {
        if (Bukkit.getServer() != null) {
            Bukkit.getPluginManager().callEvent(event);
        }
    }

    @Override
    public void bindCatalogSnapshotSupplier(@Nullable Supplier<ContentPackCatalogSnapshot> catalogSnapshotSupplier) {
        setCatalogSnapshotSupplier(catalogSnapshotSupplier);
    }

    public void setCatalogSnapshotSupplier(@Nullable Supplier<ContentPackCatalogSnapshot> catalogSnapshotSupplier) {
        this.catalogSnapshotSupplier = catalogSnapshotSupplier;
    }

    @Override
    public void registerDefinition(AchievementDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        staticDefinitions.put(definition.id(), definition);
    }

    @Override
    public void replacePackDefinitions(Collection<AchievementDefinition> definitions) {
        Map<String, AchievementDefinition> next = new LinkedHashMap<>();
        if (definitions != null) {
            for (AchievementDefinition def : definitions) {
                if (def != null) {
                    next.put(def.id(), def);
                }
            }
        }
        this.packDefinitions = Collections.unmodifiableMap(next);
    }

    @Override
    public Optional<AchievementDefinition> getDefinition(String achievementId) {
        if (achievementId == null || achievementId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(captureCatalogState().definitions().get(achievementId));
    }

    @Override
    public Collection<AchievementDefinition> definitions() {
        return List.copyOf(captureCatalogState().definitions().values());
    }

    private record CatalogState(
            Map<String, AchievementDefinition> definitions,
            @Nullable Map<String, RewardTable> rewardTables
    ) {}

    private CatalogState captureCatalogState() {
        Supplier<ContentPackCatalogSnapshot> supplier = this.catalogSnapshotSupplier;
        ContentPackCatalogSnapshot catalog = supplier != null ? supplier.get() : null;
        if (catalog != null && catalog.generation() > 0L) {
            Map<String, AchievementDefinition> merged = new LinkedHashMap<>(staticDefinitions);
            merged.putAll(catalog.achievements());
            return new CatalogState(Collections.unmodifiableMap(merged), catalog.rewardTables());
        }
        Map<String, AchievementDefinition> merged = new LinkedHashMap<>(staticDefinitions);
        merged.putAll(packDefinitions);
        return new CatalogState(Collections.unmodifiableMap(merged), null);
    }

    @Override
    public Set<String> unlockedFor(UUID playerId) {
        if (playerId == null) {
            return Set.of();
        }
        Set<String> unlocked = unlockedByPlayer.get(playerId);
        return unlocked == null ? Set.of() : Set.copyOf(unlocked);
    }

    @Override
    public CompletableFuture<Set<String>> loadPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (repository == null) {
            return CompletableFuture.completedFuture(unlockedFor(playerId));
        }
        return repository.loadAchievements(playerId).thenApply(loaded -> {
            Set<String> cached = unlockedByPlayer.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet());
            if (loaded != null) {
                cached.addAll(loaded);
            }
            return Set.copyOf(cached);
        });
    }

    @Override
    public void evictPlayer(UUID playerId) {
        if (playerId != null) {
            unlockedByPlayer.remove(playerId);
        }
    }

    @Override
    public CompletableFuture<List<AchievementUnlockedEvent>> evaluateEndPortalTouch(
            UUID playerId,
            @Nullable String playerName,
            @Nullable String worldName) {
        Objects.requireNonNull(playerId, "playerId");
        String normalizedWorld = worldName == null ? "" : worldName.trim();
        Map<String, Object> attrs = normalizedWorld.isEmpty()
                ? Map.of("event", "END_PORTAL_TOUCH")
                : Map.of("event", "END_PORTAL_TOUCH", "world", normalizedWorld);

        CatalogState catalog = captureCatalogState();
        List<CompletableFuture<Optional<AchievementUnlockedEvent>>> futures = new ArrayList<>();
        for (AchievementDefinition def : catalog.definitions().values()) {
            if (hasUnlocked(playerId, def.id()) || !prerequisitesSatisfied(playerId, def)) {
                continue;
            }
            for (AchievementTriggerSpec trigger : def.triggers()) {
                if (trigger.type() == AchievementTriggerType.END_PORTAL_TOUCH) {
                    if (!trigger.worldName().isEmpty() && !trigger.worldName().equalsIgnoreCase(normalizedWorld)) {
                        continue;
                    }
                    if (!evaluateConditionHook(trigger, playerId, playerName, List.of(playerId), null, attrs)) {
                        continue;
                    }
                    futures.add(grantAchievementInternal(
                            playerId,
                            playerName,
                            def,
                            AchievementTriggerType.END_PORTAL_TOUCH,
                            false,
                            attrs,
                            catalog.rewardTables()
                    ).thenApply(GrantOutcome::event));
                    break;
                }
                if (trigger.type() == AchievementTriggerType.CUSTOM_SCRIPT
                        && ("END_PORTAL_TOUCH".equalsIgnoreCase(trigger.customEventKey()))) {
                    if (!evaluateConditionHook(trigger, playerId, playerName, List.of(playerId), null, attrs)) {
                        continue;
                    }
                    futures.add(grantAchievementInternal(
                            playerId,
                            playerName,
                            def,
                            AchievementTriggerType.CUSTOM_SCRIPT,
                            false,
                            attrs,
                            catalog.rewardTables()
                    ).thenApply(GrantOutcome::event));
                    break;
                }
            }
        }
        return collectUnlockedEvents(futures);
    }

    @Override
    public CompletableFuture<List<AchievementUnlockedEvent>> evaluateCustomTrigger(
            UUID playerId,
            @Nullable String playerName,
            String customEventKey,
            Map<String, Object> attributes) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(customEventKey, "customEventKey");
        Map<String, Object> safeAttrs = ImmutableSnapshots.copyStringMap(attributes);
        CatalogState catalog = captureCatalogState();
        List<CompletableFuture<Optional<AchievementUnlockedEvent>>> futures = new ArrayList<>();
        for (AchievementDefinition def : catalog.definitions().values()) {
            if (hasUnlocked(playerId, def.id()) || !prerequisitesSatisfied(playerId, def)) {
                continue;
            }
            for (AchievementTriggerSpec trigger : def.triggers()) {
                if (trigger.type() != AchievementTriggerType.CUSTOM_SCRIPT) {
                    continue;
                }
                if (!trigger.customEventKey().isEmpty()
                        && !trigger.customEventKey().equalsIgnoreCase(customEventKey.trim())) {
                    continue;
                }
                if (!evaluateConditionHook(trigger, playerId, playerName, List.of(playerId), null, safeAttrs)) {
                    continue;
                }
                futures.add(grantAchievementInternal(
                        playerId,
                        playerName,
                        def,
                        AchievementTriggerType.CUSTOM_SCRIPT,
                        false,
                        safeAttrs,
                        catalog.rewardTables()
                ).thenApply(GrantOutcome::event));
                break;
            }
        }
        return collectUnlockedEvents(futures);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEncounterCompleted(RaidEncounterCompletedEvent event) {
        Objects.requireNonNull(event, "event");
        evaluateEncounterCompleted(event.snapshot());
    }

    public CompletableFuture<List<AchievementUnlockedEvent>> evaluateEncounterCompleted(EncounterResultSnapshot snap) {
        Objects.requireNonNull(snap, "snap");
        if (snap.outcome() != EncounterOutcome.VICTORY || snap.participantMetrics().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        CatalogState catalog = captureCatalogState();
        Collection<UUID> allParticipants = List.copyOf(snap.participantMetrics().keySet());
        List<CompletableFuture<List<AchievementUnlockedEvent>>> allFutures = new ArrayList<>();

        for (AchievementDefinition def : catalog.definitions().values()) {
            boolean hasServerFirst = def.triggers().stream()
                    .anyMatch(t -> t.type() == AchievementTriggerType.SERVER_FIRST);
            if (hasServerFirst) {
                allFutures.add(evaluateServerFirstTrigger(def, snap, allParticipants, catalog.rewardTables()));
            }

            List<CompletableFuture<Optional<AchievementUnlockedEvent>>> participantFutures = new ArrayList<>();
            for (ParticipantCombatMetrics metrics : snap.participantMetrics().values()) {
                UUID playerId = metrics.playerId();
                if (hasUnlocked(playerId, def.id()) || !prerequisitesSatisfied(playerId, def)) {
                    continue;
                }
                for (AchievementTriggerSpec trigger : def.triggers()) {
                    if (trigger.type() == AchievementTriggerType.SERVER_FIRST) {
                        continue;
                    }
                    if (!matchesEncounterTrigger(trigger, snap, metrics, allParticipants)) {
                        continue;
                    }
                    participantFutures.add(grantAchievementInternal(
                            playerId,
                            metrics.playerName(),
                            def,
                            trigger.type(),
                            false,
                            Map.of("encounterId", snap.encounterId(), "difficulty", snap.difficulty().name()),
                            catalog.rewardTables()
                    ).thenApply(GrantOutcome::event));
                    break;
                }
            }
            if (!participantFutures.isEmpty()) {
                allFutures.add(collectUnlockedEvents(participantFutures));
            }
        }
        return flattenUnlockedLists(allFutures);
    }

    private CompletableFuture<List<AchievementUnlockedEvent>> evaluateServerFirstTrigger(
            AchievementDefinition def,
            EncounterResultSnapshot snap,
            Collection<UUID> allParticipants,
            @Nullable Map<String, RewardTable> rewardTables) {
        List<ParticipantCombatMetrics> eligible = new ArrayList<>();
        for (AchievementTriggerSpec trigger : def.triggers()) {
            if (trigger.type() != AchievementTriggerType.SERVER_FIRST) {
                continue;
            }
            for (ParticipantCombatMetrics metrics : snap.participantMetrics().values()) {
                if (!prerequisitesSatisfied(metrics.playerId(), def)) {
                    continue;
                }
                if (matchesEncounterFilter(trigger, snap, metrics, allParticipants)) {
                    eligible.add(metrics);
                }
            }
            if (!eligible.isEmpty()) {
                break;
            }
        }
        if (eligible.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        String milestoneKey = SERVER_FIRST_PREFIX + def.id();
        List<UUID> eligibleIds = eligible.stream().map(ParticipantCombatMetrics::playerId).toList();
        CompletableFuture<Set<UUID>> recipientsFuture = repository != null
                ? repository.claimOrResumeServerFirstRecipients(milestoneKey, eligibleIds, clockMillis.getAsLong())
                : CompletableFuture.completedFuture(claimOrResumeInMemoryServerFirst(milestoneKey, eligibleIds));

        return recipientsFuture.thenCompose(recipients -> {
            if (recipients == null || recipients.isEmpty()) {
                return CompletableFuture.completedFuture(List.<AchievementUnlockedEvent>of());
            }
            claimedServerFirst.add(milestoneKey);
            List<CompletableFuture<Optional<AchievementUnlockedEvent>>> grantFutures = new ArrayList<>();
            for (ParticipantCombatMetrics winner : eligible) {
                if (!recipients.contains(winner.playerId())) {
                    continue;
                }
                CompletableFuture<Optional<AchievementUnlockedEvent>> single = grantAchievementInternal(
                        winner.playerId(),
                        winner.playerName(),
                        def,
                        AchievementTriggerType.SERVER_FIRST,
                        true,
                        Map.of(
                                "encounterId", snap.encounterId(),
                                "difficulty", snap.difficulty().name(),
                                "serverFirst", true
                        ),
                        rewardTables
                ).thenCompose(outcome -> {
                    if (outcome.status() == GrantStatus.FAILED) {
                        return CompletableFuture.completedFuture(Optional.<AchievementUnlockedEvent>empty());
                    }
                    if (repository != null) {
                        return repository.completeServerFirstRecipient(milestoneKey, winner.playerId())
                                .exceptionally(err -> {
                                    logger.log(Level.WARNING,
                                            "Failed to mark server-first recipient completed for " + def.id() + ": " + winner.playerId(),
                                            err);
                                    return null;
                                })
                                .thenApply(v -> outcome.event());
                    }
                    Set<UUID> inMem = inMemoryPendingServerFirst.get(milestoneKey);
                    if (inMem != null) {
                        inMem.remove(winner.playerId());
                    }
                    return CompletableFuture.completedFuture(outcome.event());
                });
                grantFutures.add(single);
            }
            return collectUnlockedEvents(grantFutures);
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to claim server-first achievement " + def.id(), ex);
            return List.of();
        });
    }

    private Set<UUID> claimOrResumeInMemoryServerFirst(String milestoneKey, List<UUID> eligibleIds) {
        if (claimedServerFirst.add(milestoneKey)) {
            Set<UUID> pending = ConcurrentHashMap.newKeySet();
            pending.addAll(eligibleIds);
            inMemoryPendingServerFirst.put(milestoneKey, pending);
            return Set.copyOf(pending);
        }
        Set<UUID> pending = inMemoryPendingServerFirst.get(milestoneKey);
        if (pending == null || pending.isEmpty()) {
            return Set.of();
        }
        Set<UUID> resume = new LinkedHashSet<>();
        for (UUID id : eligibleIds) {
            if (pending.contains(id)) {
                resume.add(id);
            }
        }
        return Set.copyOf(resume);
    }

    private static CompletableFuture<List<AchievementUnlockedEvent>> flattenUnlockedLists(
            List<CompletableFuture<List<AchievementUnlockedEvent>>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<AchievementUnlockedEvent> combined = new ArrayList<>();
                    for (CompletableFuture<List<AchievementUnlockedEvent>> f : futures) {
                        combined.addAll(f.join());
                    }
                    return List.copyOf(combined);
                });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMechanicResolved(RaidMechanicResolvedEvent event) {
        Objects.requireNonNull(event, "event");
        if (!event.perfectResolve()) {
            return;
        }
        Object rawEligible = event.tags().get("eligiblePlayers");
        if (!(rawEligible instanceof Collection<?> col) || col.isEmpty()) {
            return;
        }
        List<UUID> eligiblePlayers = new ArrayList<>();
        for (Object item : col) {
            if (item instanceof UUID id) {
                eligiblePlayers.add(id);
            }
        }
        if (eligiblePlayers.isEmpty()) {
            return;
        }

        Map<String, Object> attrs = new LinkedHashMap<>(event.tags());
        attrs.put("mechanicId", event.mechanicId());
        if (event.sourceEnemyId() != null) {
            attrs.put("sourceEnemyId", event.sourceEnemyId());
        }

        CatalogState catalog = captureCatalogState();
        for (AchievementDefinition def : catalog.definitions().values()) {
            for (UUID playerId : eligiblePlayers) {
                if (hasUnlocked(playerId, def.id()) || !prerequisitesSatisfied(playerId, def)) {
                    continue;
                }
                for (AchievementTriggerSpec trigger : def.triggers()) {
                    if (trigger.type() == AchievementTriggerType.MECHANIC_PERFECT) {
                        if (!trigger.mechanicId().isEmpty()
                                && !trigger.mechanicId().equalsIgnoreCase(event.mechanicId())) {
                            continue;
                        }
                        if (!trigger.sourceEnemyId().isEmpty()
                                && !trigger.sourceEnemyId().equalsIgnoreCase(Objects.toString(event.sourceEnemyId(), ""))) {
                            continue;
                        }
                        if (!evaluateConditionHook(trigger, playerId, null, eligiblePlayers, null, attrs)) {
                            continue;
                        }
                        grantAchievementInternal(
                                playerId,
                                null,
                                def,
                                AchievementTriggerType.MECHANIC_PERFECT,
                                false,
                                attrs,
                                catalog.rewardTables()
                        );
                        break;
                    }
                    if (trigger.type() == AchievementTriggerType.CUSTOM_SCRIPT
                            && "MECHANIC_PERFECT".equalsIgnoreCase(trigger.customEventKey())) {
                        if (!evaluateConditionHook(trigger, playerId, null, eligiblePlayers, null, attrs)) {
                            continue;
                        }
                        grantAchievementInternal(
                                playerId,
                                null,
                                def,
                                AchievementTriggerType.CUSTOM_SCRIPT,
                                false,
                                attrs,
                                catalog.rewardTables()
                        );
                        break;
                    }
                }
            }
        }
    }

    @Override
    public CompletableFuture<Optional<AchievementUnlockedEvent>> grantAchievement(
            UUID playerId,
            @Nullable String playerName,
            String achievementId,
            AchievementTriggerType triggerType,
            boolean serverFirst,
            Map<String, Object> metadata) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(achievementId, "achievementId");
        Objects.requireNonNull(triggerType, "triggerType");

        CatalogState catalog = captureCatalogState();
        AchievementDefinition def = catalog.definitions().get(achievementId);
        if (def == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return grantAchievementInternal(playerId, playerName, def, triggerType, serverFirst, metadata, catalog.rewardTables())
                .thenApply(GrantOutcome::event);
    }

    private enum GrantStatus {
        GRANTED,
        ALREADY_UNLOCKED,
        FAILED
    }

    private record GrantOutcome(GrantStatus status, Optional<AchievementUnlockedEvent> event) {
    }

    private CompletableFuture<GrantOutcome> grantAchievementInternal(
            UUID playerId,
            @Nullable String playerName,
            AchievementDefinition def,
            AchievementTriggerType triggerType,
            boolean serverFirst,
            @Nullable Map<String, Object> metadata,
            @Nullable Map<String, RewardTable> rewardTables) {
        if (hasUnlocked(playerId, def.id())) {
            return CompletableFuture.completedFuture(new GrantOutcome(GrantStatus.ALREADY_UNLOCKED, Optional.empty()));
        }

        // Validate/freeze metadata and clock BEFORE reserving pendingKey.
        Map<String, Object> safeMeta = ImmutableSnapshots.copyStringMap(metadata);
        long nowMillis = clockMillis.getAsLong();

        String pendingKey = playerId + ":" + def.id();
        if (!pendingGrants.add(pendingKey)) {
            return CompletableFuture.completedFuture(new GrantOutcome(GrantStatus.FAILED, Optional.empty()));
        }

        try {
            if (repository == null) {
                try {
                    boolean added = unlockedByPlayer
                            .computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet())
                            .add(def.id());
                    if (!added) {
                        return CompletableFuture.completedFuture(new GrantOutcome(GrantStatus.ALREADY_UNLOCKED, Optional.empty()));
                    }
                    AchievementUnlockedEvent unlockedEvent = new AchievementUnlockedEvent(
                            playerId,
                            playerName,
                            def,
                            triggerType,
                            serverFirst,
                            safeMeta,
                            Instant.ofEpochMilli(nowMillis),
                            rewardTables
                    );
                    publishEventSafely(unlockedEvent);
                    return CompletableFuture.completedFuture(new GrantOutcome(GrantStatus.GRANTED, Optional.of(unlockedEvent)));
                } finally {
                    pendingGrants.remove(pendingKey);
                }
            }

            CompletableFuture<Boolean> persistFuture = repository.grantAchievement(playerId, playerName, def.id(), nowMillis);
            if (persistFuture == null) {
                pendingGrants.remove(pendingKey);
                return CompletableFuture.completedFuture(new GrantOutcome(GrantStatus.FAILED, Optional.empty()));
            }
            return persistFuture.handle((inserted, err) -> {
                try {
                    if (err != null) {
                        logger.log(Level.WARNING, "Failed to persist achievement " + def.id() + " for " + playerId, err);
                        return new GrantOutcome(GrantStatus.FAILED, Optional.empty());
                    }
                    unlockedByPlayer
                            .computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet())
                            .add(def.id());
                    if (!Boolean.TRUE.equals(inserted)) {
                        return new GrantOutcome(GrantStatus.ALREADY_UNLOCKED, Optional.empty());
                    }
                    AchievementUnlockedEvent unlockedEvent = new AchievementUnlockedEvent(
                            playerId,
                            playerName,
                            def,
                            triggerType,
                            serverFirst,
                            safeMeta,
                            Instant.ofEpochMilli(nowMillis),
                            rewardTables
                    );
                    publishEventSafely(unlockedEvent);
                    return new GrantOutcome(GrantStatus.GRANTED, Optional.of(unlockedEvent));
                } finally {
                    pendingGrants.remove(pendingKey);
                }
            });
        } catch (Throwable ex) {
            pendingGrants.remove(pendingKey);
            logger.log(Level.WARNING, "Failed to submit achievement grant " + def.id() + " for " + playerId, ex);
            return CompletableFuture.completedFuture(new GrantOutcome(GrantStatus.FAILED, Optional.empty()));
        }
    }

    private void publishEventSafely(AchievementUnlockedEvent unlockedEvent) {
        try {
            eventPublisher.accept(unlockedEvent);
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Failed to publish AchievementUnlockedEvent for " + unlockedEvent.achievement().id(), ex);
        }
    }

    private boolean prerequisitesSatisfied(UUID playerId, AchievementDefinition def) {
        if (def.prerequisites().isEmpty()) {
            return true;
        }
        return unlockedFor(playerId).containsAll(def.prerequisites());
    }

    private boolean matchesEncounterTrigger(
            AchievementTriggerSpec trigger,
            EncounterResultSnapshot snap,
            ParticipantCombatMetrics metrics,
            Collection<UUID> allParticipants) {
        return switch (trigger.type()) {
            case ENCOUNTER_CLEAR -> matchesEncounterFilter(trigger, snap, metrics, allParticipants);
            case NO_DEATH -> metrics.downedCount() == 0
                    && matchesEncounterFilter(trigger, snap, metrics, allParticipants);
            case CUSTOM_SCRIPT -> (trigger.customEventKey().isEmpty()
                    || "ENCOUNTER_CLEAR".equalsIgnoreCase(trigger.customEventKey()))
                    && matchesEncounterFilter(trigger, snap, metrics, allParticipants);
            case END_PORTAL_TOUCH, SERVER_FIRST, MECHANIC_PERFECT -> false;
        };
    }

    private boolean matchesEncounterFilter(
            AchievementTriggerSpec trigger,
            EncounterResultSnapshot snap,
            ParticipantCombatMetrics metrics,
            Collection<UUID> allParticipants) {
        if (!trigger.encounterId().isEmpty()
                && !"*".equals(trigger.encounterId())
                && !trigger.encounterId().equals(snap.encounterId())) {
            return false;
        }
        if (trigger.difficulty() != null && trigger.difficulty() != snap.difficulty()) {
            return false;
        }
        if (trigger.maxDurationMillis() > 0L && snap.durationMillis() > trigger.maxDurationMillis()) {
            return false;
        }
        if (trigger.minPartySize() > 0 && snap.partySize() < trigger.minPartySize()) {
            return false;
        }
        if (trigger.maxPartySize() > 0 && snap.partySize() > trigger.maxPartySize()) {
            return false;
        }
        if (trigger.minBossDamage() > 0.0 && metrics.totalBossDamage() < trigger.minBossDamage()) {
            return false;
        }
        if (trigger.minCrystalsDestroyed() > 0 && metrics.totalCrystalsDestroyed() < trigger.minCrystalsDestroyed()) {
            return false;
        }
        if (trigger.minRevivesCompleted() > 0 && metrics.revivesCompleted() < trigger.minRevivesCompleted()) {
            return false;
        }
        return evaluateConditionHook(
                trigger,
                metrics.playerId(),
                metrics.playerName(),
                allParticipants,
                snap,
                Map.of("encounterId", snap.encounterId())
        );
    }

    private boolean evaluateConditionHook(
            AchievementTriggerSpec trigger,
            UUID playerId,
            @Nullable String playerName,
            Collection<UUID> participantIds,
            @Nullable EncounterResultSnapshot encounterResult,
            Map<String, Object> attributes) {
        if (trigger.conditionHook().isEmpty()) {
            return true;
        }
        if (scriptHookRegistry == null) {
            return false;
        }
        ScriptHookContext context = new ScriptHookContext(
                ReadOnlyRaidServiceRegistry.wrap(serviceRegistry),
                playerId,
                playerName,
                participantIds,
                encounterResult,
                trigger.params(),
                attributes
        );
        try {
            return scriptHookRegistry.evaluateCondition(trigger.conditionHook(), context);
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Achievement condition hook threw exception: " + trigger.conditionHook(), ex);
            return false;
        }
    }

    private static CompletableFuture<List<AchievementUnlockedEvent>> collectUnlockedEvents(
            List<CompletableFuture<Optional<AchievementUnlockedEvent>>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<AchievementUnlockedEvent> unlocked = new ArrayList<>();
                    for (CompletableFuture<Optional<AchievementUnlockedEvent>> future : futures) {
                        future.join().ifPresent(unlocked::add);
                    }
                    return List.copyOf(unlocked);
                });
    }
}
