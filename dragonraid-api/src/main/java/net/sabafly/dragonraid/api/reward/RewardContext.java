package net.sabafly.dragonraid.api.reward;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.sabafly.dragonraid.api.achievement.AchievementUnlockedEvent;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable input snapshot passed to pure {@link RewardTable} evaluation and subsequent {@link RewardApplier} execution.
 */
public record RewardContext(
        UUID playerId,
        @Nullable String playerName,
        String sourceType,
        String sourceId,
        @Nullable EncounterResultSnapshot encounterSnapshot,
        @Nullable ParticipantCombatMetrics participantMetrics,
        @Nullable EncounterDifficulty difficulty,
        boolean firstClear,
        boolean serverFirst,
        boolean noDeath,
        int partySize,
        long durationMillis,
        double luck,
        Set<String> unlockedAchievements,
        Set<String> unlockedContents,
        Set<String> claimedKeys,
        Map<String, Object> attributes
) {

    public RewardContext {
        Objects.requireNonNull(playerId, "playerId");
        playerName = (playerName == null || playerName.isBlank()) ? null : playerName.trim();
        sourceType = sourceType == null ? "" : sourceType.trim();
        sourceId = sourceId == null ? "" : sourceId.trim();
        if (partySize < 0) {
            partySize = 0;
        }
        if (durationMillis < 0L) {
            durationMillis = 0L;
        }
        if (!Double.isFinite(luck)) {
            luck = 0.0;
        }
        unlockedAchievements = unlockedAchievements == null ? Set.of() : Set.copyOf(unlockedAchievements);
        unlockedContents = unlockedContents == null ? Set.of() : Set.copyOf(unlockedContents);
        claimedKeys = claimedKeys == null ? Set.of() : Set.copyOf(claimedKeys);
        attributes = immutableCopy(attributes);
    }

    public static Builder builder(UUID playerId) {
        return new Builder(playerId);
    }

    public static RewardContext forAchievement(
            AchievementUnlockedEvent event,
            @Nullable Set<String> unlockedAchievements,
            @Nullable Set<String> unlockedContents,
            @Nullable Set<String> claimedKeys) {
        Objects.requireNonNull(event, "event");
        Map<String, Object> attrs = new LinkedHashMap<>(event.metadata());
        attrs.put("achievementId", event.achievement().id());
        attrs.put("triggerType", event.triggerType().name());
        attrs.put("serverFirst", event.serverFirst());
        return builder(event.playerId())
                .playerName(event.playerName())
                .sourceType("ACHIEVEMENT_UNLOCKED")
                .sourceId(event.achievement().id())
                .serverFirst(event.serverFirst())
                .unlockedAchievements(unlockedAchievements)
                .unlockedContents(unlockedContents)
                .claimedKeys(claimedKeys)
                .attributes(attrs)
                .build();
    }

    public static RewardContext forEncounterParticipant(
            EncounterResultSnapshot snapshot,
            ParticipantCombatMetrics metrics,
            boolean firstClear,
            boolean serverFirst,
            @Nullable Set<String> unlockedAchievements,
            @Nullable Set<String> unlockedContents,
            @Nullable Set<String> claimedKeys) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(metrics, "metrics");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("encounterId", snapshot.encounterId());
        attrs.put("difficulty", snapshot.difficulty().name());
        attrs.put("firstClear", firstClear);
        attrs.put("serverFirst", serverFirst);
        return builder(metrics.playerId())
                .playerName(metrics.playerName())
                .sourceType("ENCOUNTER_CLEAR")
                .sourceId(snapshot.encounterId())
                .encounterSnapshot(snapshot)
                .participantMetrics(metrics)
                .difficulty(snapshot.difficulty())
                .firstClear(firstClear)
                .serverFirst(serverFirst)
                .noDeath(metrics.downedCount() == 0)
                .partySize(snapshot.partySize())
                .durationMillis(snapshot.durationMillis())
                .unlockedAchievements(unlockedAchievements)
                .unlockedContents(unlockedContents)
                .claimedKeys(claimedKeys)
                .attributes(attrs)
                .build();
    }

    private static Map<String, Object> immutableCopy(@Nullable Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    public static final class Builder {
        private final UUID playerId;
        private @Nullable String playerName;
        private String sourceType = "";
        private String sourceId = "";
        private @Nullable EncounterResultSnapshot encounterSnapshot;
        private @Nullable ParticipantCombatMetrics participantMetrics;
        private @Nullable EncounterDifficulty difficulty;
        private boolean firstClear;
        private boolean serverFirst;
        private boolean noDeath = true;
        private int partySize;
        private long durationMillis;
        private double luck;
        private Set<String> unlockedAchievements = Set.of();
        private Set<String> unlockedContents = Set.of();
        private Set<String> claimedKeys = Set.of();
        private Map<String, Object> attributes = Map.of();

        private Builder(UUID playerId) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
        }

        public Builder playerName(@Nullable String playerName) {
            this.playerName = playerName;
            return this;
        }

        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder encounterSnapshot(@Nullable EncounterResultSnapshot encounterSnapshot) {
            this.encounterSnapshot = encounterSnapshot;
            return this;
        }

        public Builder participantMetrics(@Nullable ParticipantCombatMetrics participantMetrics) {
            this.participantMetrics = participantMetrics;
            return this;
        }

        public Builder difficulty(@Nullable EncounterDifficulty difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        public Builder firstClear(boolean firstClear) {
            this.firstClear = firstClear;
            return this;
        }

        public Builder serverFirst(boolean serverFirst) {
            this.serverFirst = serverFirst;
            return this;
        }

        public Builder noDeath(boolean noDeath) {
            this.noDeath = noDeath;
            return this;
        }

        public Builder partySize(int partySize) {
            this.partySize = partySize;
            return this;
        }

        public Builder durationMillis(long durationMillis) {
            this.durationMillis = durationMillis;
            return this;
        }

        public Builder luck(double luck) {
            this.luck = luck;
            return this;
        }

        public Builder unlockedAchievements(@Nullable Set<String> unlockedAchievements) {
            this.unlockedAchievements = unlockedAchievements == null ? Set.of() : unlockedAchievements;
            return this;
        }

        public Builder unlockedContents(@Nullable Set<String> unlockedContents) {
            this.unlockedContents = unlockedContents == null ? Set.of() : unlockedContents;
            return this;
        }

        public Builder claimedKeys(@Nullable Set<String> claimedKeys) {
            this.claimedKeys = claimedKeys == null ? Set.of() : claimedKeys;
            return this;
        }

        public Builder attributes(@Nullable Map<String, Object> attributes) {
            this.attributes = attributes == null ? Map.of() : attributes;
            return this;
        }

        public RewardContext build() {
            return new RewardContext(
                    playerId,
                    playerName,
                    sourceType,
                    sourceId,
                    encounterSnapshot,
                    participantMetrics,
                    difficulty,
                    firstClear,
                    serverFirst,
                    noDeath,
                    partySize,
                    durationMillis,
                    luck,
                    unlockedAchievements,
                    unlockedContents,
                    claimedKeys,
                    attributes
            );
        }
    }
}
