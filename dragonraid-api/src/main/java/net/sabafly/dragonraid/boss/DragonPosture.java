package net.sabafly.dragonraid.boss;

/**
 * Controlled flight, dive, and ground postures for the DragonRaid EnderDragon boss.
 * Keeps dragon AI enabled (`setAI(true)`) so wing-flapping and neck/tail animations remain active.
 */
public enum DragonPosture {
    /** Natural wing-flapping low-altitude flight around the circular arena. */
    LOW_ORBIT,
    /** Landed near the arena center pedestal for melee windows and close-range bursts. */
    CENTER_LANDED,
    /** High-altitude wing-flapping hover above the arena for laser sweeps, storms, and barrages. */
    HIGH_HOVER,
    /** Ascending high above the arena before plunging in a high-speed dive. */
    ASCENDING_CHARGE,
    /** High-speed physical dive towards a locked-on player or arena impact point. */
    DIVING,
    /** Staggered on the ground after all crystals shatter or a shield parry succeeds. */
    STAGGERED
}
