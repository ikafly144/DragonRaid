package net.sabafly.dragonraid.api.progression;

/**
 * Gating strategies controlling direct player entry through a vanilla End portal.
 */
public enum EndEntryGateMode {
    ALLOW,
    REQUIRE_SERVER_FIRST,
    REQUIRE_PLAYER_UNLOCK,
    REQUIRE_PLAYER_ACHIEVEMENT,
    REQUIRE_PLAYER_CLEAR,
    SCRIPT_HOOK,
    DENY
}
