package net.sabafly.dragonraid.script;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.script.GimmickAction;
import net.sabafly.dragonraid.api.script.GimmickActionRegistry;

/**
 * Thread-safe registry storing built-in and script-provided {@link GimmickAction}s.
 */
public final class DefaultGimmickActionRegistry implements GimmickActionRegistry {

    private final Map<String, GimmickAction> actions = new ConcurrentHashMap<>();

    @Override
    public void register(GimmickAction action) {
        Objects.requireNonNull(action, "action");
        String id = action.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("GimmickAction.id() must not be blank");
        }
        actions.put(id, action);
    }

    @Override
    public Optional<GimmickAction> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(actions.get(id));
    }

    @Override
    public Collection<GimmickAction> all() {
        return List.copyOf(actions.values());
    }
}
