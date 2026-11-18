package net.sabafly.dragonraid.api.script;

import java.util.Objects;

/**
 * Single timeline step inside a phase script/blueprint.
 *
 * @param delaySeconds delay relative to the previous step (in baseline timeline seconds)
 * @param actionId registered {@link GimmickAction#id()} or timeline directive (e.g. {@code "LOOP"}, {@code "SPAWN_CRYSTALS"})
 * @param abilityId parameter block key inside {@link EncounterBlueprint#mechanicParams()}
 */
public record TimelineEntrySpec(
        double delaySeconds,
        String actionId,
        String abilityId
) {

    public TimelineEntrySpec {
        if (!Double.isFinite(delaySeconds) || delaySeconds < 0.0) {
            throw new IllegalArgumentException("delaySeconds must be >= 0: " + delaySeconds);
        }
        Objects.requireNonNull(actionId, "actionId");
        abilityId = abilityId == null ? "" : abilityId;
    }
}
