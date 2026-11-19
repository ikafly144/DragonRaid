package net.sabafly.dragonraid.api.reward;

import java.util.Map;
import net.sabafly.dragonraid.api.util.ImmutableSnapshots;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable script action hook output from {@link RewardTable} evaluation.
 */
public record ScriptHookGrant(
        String hookId,
        Map<String, Object> params,
        @Nullable String claimKey
) implements RewardGrant {

    public ScriptHookGrant {
        if (hookId == null || hookId.isBlank()) {
            throw new IllegalArgumentException("hookId must not be blank");
        }
        hookId = hookId.trim();
        params = ImmutableSnapshots.copyStringMap(params);
        claimKey = (claimKey == null || claimKey.isBlank()) ? null : claimKey.trim();
    }
}
