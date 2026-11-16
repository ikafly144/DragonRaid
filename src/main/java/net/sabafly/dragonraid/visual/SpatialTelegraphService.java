package net.sabafly.dragonraid.visual;

import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Renders client-side spatial AoE telegraphs, stack/spread rings, crystal tethers,
 * and arena boundaries without creating heavy server-side entities.
 */
public interface SpatialTelegraphService {

    /**
     * Renders a horizontal ground circle telegraph at {@code center} with {@code radius}.
     */
    void renderCircleAoE(
            Location center,
            double radius,
            TelegraphStyle style,
            Collection<? extends Player> viewers);

    /**
     * Renders a donut AoE telegraph with a safe inner circle and outer danger boundary.
     */
    void renderDonutAoE(
            Location center,
            double innerRadius,
            double outerRadius,
            TelegraphStyle style,
            Collection<? extends Player> viewers);

    /**
     * Renders a rectangular/linear telegraph from {@code start} to {@code end} with {@code width}.
     */
    void renderLineAoE(
            Location start,
            Location end,
            double width,
            TelegraphStyle style,
            Collection<? extends Player> viewers);

    /**
     * Renders a stack convergence marker ring (or solo guard ring when {@code soloMode} is true).
     */
    void renderStackMarker(
            Location center,
            double radius,
            boolean soloMode,
            Collection<? extends Player> viewers);

    /**
     * Renders a personal spread danger ring around a targeted player location.
     */
    void renderSpreadMarker(
            Location center,
            double radius,
            Collection<? extends Player> viewers);

    /**
     * Renders a particle tether line connecting {@code from} and {@code to}.
     */
    void renderTetherLine(
            Location from,
            Location to,
            TelegraphStyle style,
            Collection<? extends Player> viewers);

    /**
     * Renders the shrinking safe arena perimeter during Soft Enrage.
     */
    void renderArenaBoundary(
            Location center,
            double safeRadius,
            Collection<? extends Player> viewers);
}
