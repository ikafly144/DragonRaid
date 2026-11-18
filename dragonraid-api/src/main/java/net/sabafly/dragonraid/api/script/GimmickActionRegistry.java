package net.sabafly.dragonraid.api.script;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry of built-in and script-defined {@link GimmickAction} implementations.
 */
public interface GimmickActionRegistry {

    void register(GimmickAction action);

    Optional<GimmickAction> get(String id);

    Collection<GimmickAction> all();
}
