package net.sabafly.dragonraid.api.progression;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Combined vanilla End entry gate and encounter-clear progression rule loaded from Content Packs.
 */
public record VanillaProgressionRule(
        String id,
        String packId,
        int priority,
        @Nullable EndEntryGateSpec entryGate,
        List<VanillaClearHookSpec> clearHooks
) {

    public VanillaProgressionRule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        id = id.trim();
        packId = packId == null ? "" : packId.trim();
        clearHooks = clearHooks == null ? List.of() : List.copyOf(clearHooks);
    }

    public VanillaProgressionRule(
            String id,
            int priority,
            @Nullable EndEntryGateSpec entryGate,
            @Nullable List<VanillaClearHookSpec> clearHooks) {
        this(id, "", priority, entryGate, clearHooks);
    }
}
