package net.sabafly.dragonraid.boss;

/**
 * Controlled flight and ground postures for the DragonRaid EnderDragon boss.
 */
public enum DragonPosture {
    /** Smooth low-altitude circular orbit around the arena center. */
    LOW_ORBIT,
    /** Landed near the arena center pedestal for melee windows and tank busters. */
    CENTER_LANDED,
    /** High-altitude hover above the arena center during raid-wide casts. */
    HIGH_HOVER,
    /** Staggered on the ground after a successful Just Guard or mechanic interrupt. */
    STAGGERED
}
