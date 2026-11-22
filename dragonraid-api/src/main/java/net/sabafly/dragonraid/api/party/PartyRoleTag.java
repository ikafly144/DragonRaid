package net.sabafly.dragonraid.api.party;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * Optional decorative or script-assigned role badge rendered alongside a party member in HUDs.
 */
public record PartyRoleTag(
        String roleId,
        Component badge
) {

    public PartyRoleTag {
        if (roleId == null || roleId.isBlank()) {
            throw new IllegalArgumentException("roleId must not be blank");
        }
        Objects.requireNonNull(badge, "badge");
    }
}
