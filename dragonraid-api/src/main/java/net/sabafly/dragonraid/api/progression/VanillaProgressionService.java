package net.sabafly.dragonraid.api.progression;

import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe service synchronizing vanilla End {@code DragonBattle} defeat status,
 * exit portal generation, and End Gateway expansion.
 */
public interface VanillaProgressionService {

    record GatewayExpansionResult(int spawned, int finalGatewayCount, int effectiveMax) {
    }

    CompletableFuture<Boolean> synchronizeDragonDefeated(
            @Nullable String endWorldName,
            boolean generateExitPortal,
            boolean markPreviouslyKilled,
            boolean removeLiveVanillaDragon
    );

    CompletableFuture<Integer> expandEndGateways(
            @Nullable String endWorldName,
            int count,
            int maxGateways
    );

    default CompletableFuture<GatewayExpansionResult> expandEndGatewaysToTarget(
            @Nullable String endWorldName,
            int targetCount,
            int maxGateways) {
        return getEndGatewayCount(endWorldName).thenCompose(current -> {
            int effectiveMax = Math.min(maxGateways, VanillaClearHookSpec.MAX_VANILLA_GATEWAYS);
            int effectiveTarget = Math.min(Math.max(0, targetCount), effectiveMax);
            int cur = current == null ? 0 : current;
            int needed = Math.max(0, effectiveTarget - cur);
            if (needed == 0) {
                return CompletableFuture.completedFuture(new GatewayExpansionResult(0, cur, effectiveMax));
            }
            return expandEndGateways(endWorldName, needed, maxGateways).thenApply(spawned -> {
                int s = spawned == null ? 0 : spawned;
                return new GatewayExpansionResult(s, cur + s, effectiveMax);
            });
        });
    }

    CompletableFuture<Boolean> isVanillaDragonPreviouslyKilled(@Nullable String endWorldName);

    CompletableFuture<Integer> getEndGatewayCount(@Nullable String endWorldName);
}
