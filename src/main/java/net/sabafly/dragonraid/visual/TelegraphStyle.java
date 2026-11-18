package net.sabafly.dragonraid.visual;

/**
 * Visual color/particle styles for spatial raid telegraphs rendered via PacketEvents.
 */
public enum TelegraphStyle {
    /** High-danger AoE or unsoaked tower ring (red dust / flame accent). */
    DANGER,
    /** Safe zone, soaked tower, or safe inner ring (emerald/aqua dust). */
    SAFE,
    /** General incoming warning or breath line (amber/gold dust). */
    WARNING,
    /** Stack marker ring (golden yellow dust + end rod accent). */
    STACK,
    /** Spread personal ring (orange/violet dust). */
    SPREAD,
    /** Crystal-to-boss tether line (magenta/witch particles). */
    TETHER,
    /** Soft enrage void corruption perimeter (deep purple dust / witch accent). */
    VOID_CORRUPTION
}
