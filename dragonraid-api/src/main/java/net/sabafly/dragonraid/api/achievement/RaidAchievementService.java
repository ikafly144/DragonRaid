package net.sabafly.dragonraid.api.achievement;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot;
import org.jetbrains.annotations.Nullable;

/**
 * Data-driven achievement trigger evaluation and tracking SPI.
 */
public interface RaidAchievementService {

    default void bindCatalogSnapshotSupplier(@Nullable Supplier<ContentPackCatalogSnapshot> supplier) {
    }

    void registerDefinition(AchievementDefinition definition);

    void replacePackDefinitions(Collection<AchievementDefinition> packDefinitions);

    Optional<AchievementDefinition> getDefinition(String achievementId);

    Collection<AchievementDefinition> definitions();

    Set<String> unlockedFor(UUID playerId);

    default boolean hasUnlocked(UUID playerId, String achievementId) {
        if (playerId == null || achievementId == null || achievementId.isBlank()) {
            return false;
        }
        return unlockedFor(playerId).contains(achievementId);
    }

    CompletableFuture<Set<String>> loadPlayer(UUID playerId);

    void evictPlayer(UUID playerId);

    CompletableFuture<List<AchievementUnlockedEvent>> evaluateEndPortalTouch(
            UUID playerId,
            @Nullable String playerName,
            @Nullable String worldName
    );

    CompletableFuture<List<AchievementUnlockedEvent>> evaluateCustomTrigger(
            UUID playerId,
            @Nullable String playerName,
            String customEventKey,
            Map<String, Object> attributes
    );

    CompletableFuture<Optional<AchievementUnlockedEvent>> grantAchievement(
            UUID playerId,
            @Nullable String playerName,
            String achievementId,
            AchievementTriggerType triggerType,
            boolean serverFirst,
            Map<String, Object> metadata
    );
}
