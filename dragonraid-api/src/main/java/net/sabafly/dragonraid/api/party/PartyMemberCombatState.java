package net.sabafly.dragonraid.api.party;

/**
 * High-level combat/connection state shown in the party list HUD.
 */
public enum PartyMemberCombatState {
    OUT_OF_COMBAT,
    ALIVE,
    DOWNED,
    DEAD_SPECTATOR,
    OFFLINE
}
