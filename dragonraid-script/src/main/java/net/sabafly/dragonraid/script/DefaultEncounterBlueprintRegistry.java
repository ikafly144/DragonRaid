package net.sabafly.dragonraid.script;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterBlueprintRegistry;

/**
 * Thread-safe registry storing {@link EncounterBlueprint} definitions loaded from Content Packs or scripts.
 */
public final class DefaultEncounterBlueprintRegistry implements EncounterBlueprintRegistry {

    private final Map<String, EncounterBlueprint> blueprints = new ConcurrentHashMap<>();

    @Override
    public void register(EncounterBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        String id = blueprint.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("EncounterBlueprint.id() must not be blank");
        }
        blueprints.put(id, blueprint);
    }

    @Override
    public Optional<EncounterBlueprint> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(blueprints.get(id));
    }

    @Override
    public Collection<EncounterBlueprint> all() {
        return List.copyOf(blueprints.values());
    }

    @Override
    public boolean unregister(String id) {
        if (id == null) {
            return false;
        }
        return blueprints.remove(id) != null;
    }
}
