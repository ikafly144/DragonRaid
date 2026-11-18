package net.sabafly.dragonraid.api.script;

import java.util.Collection;
import java.util.Optional;

/**
 * Thread-safe registry of active {@link EncounterBlueprint} definitions loaded from Content Packs or scripts.
 */
public interface EncounterBlueprintRegistry {

    void register(EncounterBlueprint blueprint);

    Optional<EncounterBlueprint> get(String id);

    Collection<EncounterBlueprint> all();

    boolean unregister(String id);
}
