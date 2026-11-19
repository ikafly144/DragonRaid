package net.sabafly.dragonraid.api.reward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable pure output bundle produced by evaluating one or more {@link RewardTable} definitions.
 */
public record RewardBundle(
        String rootTableId,
        UUID playerId,
        List<RewardGrant> grants,
        Set<String> claimKeys
) {

    public RewardBundle {
        rootTableId = rootTableId == null ? "" : rootTableId.trim();
        Objects.requireNonNull(playerId, "playerId");
        grants = grants == null ? List.of() : List.copyOf(grants);
        claimKeys = claimKeys == null ? extractClaimKeys(grants) : Set.copyOf(claimKeys);
    }

    public RewardBundle(String rootTableId, UUID playerId, List<RewardGrant> grants) {
        this(rootTableId, playerId, grants, null);
    }

    public static RewardBundle empty(String rootTableId, UUID playerId) {
        return new RewardBundle(rootTableId, playerId, List.of(), Set.of());
    }

    public boolean isEmpty() {
        return grants.isEmpty();
    }

    public Map<String, Long> totalCurrencies() {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (RewardGrant grant : grants) {
            if (grant instanceof CurrencyGrant cg) {
                totals.merge(cg.currencyId(), cg.amount(), Long::sum);
            }
        }
        return Collections.unmodifiableMap(totals);
    }

    public List<CurrencyGrant> currencies() {
        List<CurrencyGrant> out = new ArrayList<>();
        for (RewardGrant grant : grants) {
            if (grant instanceof CurrencyGrant cg) {
                out.add(cg);
            }
        }
        return List.copyOf(out);
    }

    public List<VanillaItemGrant> vanillaItems() {
        List<VanillaItemGrant> out = new ArrayList<>();
        for (RewardGrant grant : grants) {
            if (grant instanceof VanillaItemGrant vg) {
                out.add(vg);
            }
        }
        return List.copyOf(out);
    }

    public List<CustomItemGrant> customItems() {
        List<CustomItemGrant> out = new ArrayList<>();
        for (RewardGrant grant : grants) {
            if (grant instanceof CustomItemGrant ig) {
                out.add(ig);
            }
        }
        return List.copyOf(out);
    }

    public List<ContentUnlockGrant> contentUnlocks() {
        List<ContentUnlockGrant> out = new ArrayList<>();
        for (RewardGrant grant : grants) {
            if (grant instanceof ContentUnlockGrant ug) {
                out.add(ug);
            }
        }
        return List.copyOf(out);
    }

    public List<ScriptHookGrant> scriptHooks() {
        List<ScriptHookGrant> out = new ArrayList<>();
        for (RewardGrant grant : grants) {
            if (grant instanceof ScriptHookGrant sg) {
                out.add(sg);
            }
        }
        return List.copyOf(out);
    }

    public RewardBundle merge(RewardBundle other) {
        Objects.requireNonNull(other, "other");
        if (!Objects.equals(playerId, other.playerId)) {
            throw new IllegalArgumentException("Cannot merge RewardBundles for different players");
        }
        List<RewardGrant> combinedGrants = new ArrayList<>(grants.size() + other.grants.size());
        combinedGrants.addAll(grants);
        combinedGrants.addAll(other.grants);
        Set<String> combinedClaims = new LinkedHashSet<>(claimKeys);
        combinedClaims.addAll(other.claimKeys);
        String combinedRoot = rootTableId.isEmpty() ? other.rootTableId
                : (other.rootTableId.isEmpty() ? rootTableId : rootTableId + "," + other.rootTableId);
        return new RewardBundle(combinedRoot, playerId, combinedGrants, combinedClaims);
    }

    private static Set<String> extractClaimKeys(List<RewardGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (RewardGrant grant : grants) {
            if (grant.claimKey() != null && !grant.claimKey().isBlank()) {
                keys.add(grant.claimKey());
            }
        }
        return Collections.unmodifiableSet(keys);
    }
}
