package net.sabafly.dragonraid.api.reward;

import java.util.List;

/**
 * Pure Loot-Table-style reward definition evaluated with a {@link RewardContext}.
 */
public record RewardTable(
        String id,
        String packId,
        List<RewardPool> pools
) {

    public RewardTable {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("RewardTable id must not be blank");
        }
        id = id.trim();
        packId = packId == null ? "" : packId.trim();
        pools = pools == null ? List.of() : List.copyOf(pools);
        if (pools.isEmpty()) {
            throw new IllegalArgumentException("RewardTable must define at least one pool: " + id);
        }
    }

    public RewardTable(String id, List<RewardPool> pools) {
        this(id, "", pools);
    }
}
