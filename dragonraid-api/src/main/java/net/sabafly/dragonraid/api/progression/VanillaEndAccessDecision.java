package net.sabafly.dragonraid.api.progression;

import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Evaluation result returned by {@link VanillaEndProgressionPolicy#evaluateVanillaEndAccess(java.util.UUID)}.
 */
public record VanillaEndAccessDecision(
        boolean allowed,
        String denialMessageKey,
        Map<String, String> placeholders,
        boolean allowBypassPermission,
        boolean pushBackOnDeny
) {

    public VanillaEndAccessDecision {
        denialMessageKey = denialMessageKey == null ? "" : denialMessageKey.trim();
        placeholders = placeholders == null ? Map.of() : Map.copyOf(placeholders);
    }

    public static VanillaEndAccessDecision allow() {
        return new VanillaEndAccessDecision(true, "", Map.of(), true, false);
    }

    public static VanillaEndAccessDecision deny(
            String denialMessageKey,
            @Nullable Map<String, String> placeholders,
            boolean allowBypassPermission,
            boolean pushBackOnDeny) {
        Objects.requireNonNull(denialMessageKey, "denialMessageKey");
        return new VanillaEndAccessDecision(
                false,
                denialMessageKey,
                placeholders == null ? Map.of() : placeholders,
                allowBypassPermission,
                pushBackOnDeny
        );
    }
}
