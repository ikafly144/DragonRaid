package net.sabafly.dragonraid.api.reward;

import java.util.List;

/**
 * Cross-pack extension injecting additional {@link RewardPool} instances into an existing {@link RewardTable}.
 */
public record RewardTableExtension(
        String id,
        String packId,
        String targetTableId,
        int priority,
        boolean optional,
        List<RewardPool> appendPools
) {

    public RewardTableExtension {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("RewardTableExtension id must not be blank");
        }
        id = id.trim();
        packId = packId == null ? "" : packId.trim();
        if (targetTableId == null || targetTableId.isBlank()) {
            throw new IllegalArgumentException("targetTableId must not be blank for extension " + id);
        }
        targetTableId = targetTableId.trim();
        appendPools = appendPools == null ? List.of() : List.copyOf(appendPools);
        if (appendPools.isEmpty()) {
            throw new IllegalArgumentException("RewardTableExtension must define at least one pool: " + id);
        }
    }

    public RewardTableExtension(String id, String targetTableId, int priority, boolean optional, List<RewardPool> appendPools) {
        this(id, "", targetTableId, priority, optional, appendPools);
    }
}
