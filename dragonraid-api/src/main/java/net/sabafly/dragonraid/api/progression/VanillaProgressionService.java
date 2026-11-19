package net.sabafly.dragonraid.api.progression;

import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Folia-safe service synchronizing vanilla End {@code DragonBattle} defeat status,
 * exit portal generation, and End Gateway expansion.
 */
public interface VanillaProgressionService {

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

    CompletableFuture<Boolean> isVanillaDragonPreviouslyKilled(@Nullable String endWorldName);

    CompletableFuture<Integer> getEndGatewayCount(@Nullable String endWorldName);
}
