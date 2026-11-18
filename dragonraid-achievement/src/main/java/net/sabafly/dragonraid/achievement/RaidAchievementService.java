package net.sabafly.dragonraid.achievement;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Event-driven achievement evaluation and tracking service decoupled from core raid state.
 */
public interface RaidAchievementService {

    Collection<AchievementDefinition> definitions();

    Set<String> unlockedFor(UUID playerId);
}
