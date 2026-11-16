package net.sabafly.dragonraid.encounter;

/**
 * Lifecycle states of a DragonRaid encounter attempt.
 */
public enum RaidState {
    /** No encounter active; players may prepare or trigger a pull. */
    IDLE,
    /** Pull countdown is ticking down before participant lock-in. */
    COUNTDOWN,
    /** Combat is active; protection, scaling, downed/revive, and wipe detection are enforced. */
    ACTIVE,
    /** Wipe sequence is running before resetting the arena and returning players to safety. */
    WIPING
}
