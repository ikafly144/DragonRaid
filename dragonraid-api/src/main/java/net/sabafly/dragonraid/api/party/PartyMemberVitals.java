package net.sabafly.dragonraid.api.party;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a party member's health, shield guard posture, combat status, and shared revives.
 */
public record PartyMemberVitals(
        UUID playerId,
        String displayName,
        boolean online,
        double health,
        double maxHealth,
        boolean blocking,
        PartyMemberCombatState combatState,
        int remainingRevives
) {

    public PartyMemberVitals {
        Objects.requireNonNull(playerId, "playerId");
        displayName = (displayName == null || displayName.isBlank()) ? playerId.toString().substring(0, 8) : displayName;
        maxHealth = Double.isFinite(maxHealth) && maxHealth > 0.0 ? maxHealth : 20.0;
        health = Double.isFinite(health) ? Math.max(0.0, Math.min(maxHealth, health)) : 0.0;
        Objects.requireNonNull(combatState, "combatState");
        remainingRevives = Math.max(0, remainingRevives);
    }
}
