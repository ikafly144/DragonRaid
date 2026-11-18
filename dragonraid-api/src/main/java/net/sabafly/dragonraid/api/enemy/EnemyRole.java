package net.sabafly.dragonraid.api.enemy;

/**
 * Functional role an enemy or objective plays within a data-driven DragonRaid encounter.
 */
public enum EnemyRole {
    /** Main boss controlling boss bar HP and primary clear condition. */
    PRIMARY_BOSS,
    /** Secondary or phantom boss sharing or complementing encounter phases. */
    SUB_BOSS,
    /** Destructible encounter objective (e.g. obsidian pillar crystal, arcane pylon). */
    OBJECTIVE_CRYSTAL,
    /** Summoned combat minion or add. */
    SUMMONED_ADD,
    /** Spatial mechanic actor (e.g. erupting giant enderman, rotating beam turret). */
    GIMMICK_PROP
}
