package net.sabafly.dragonraid.visual;

import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Renders client-side spatial AoE telegraphs, Display Entity rings/arrows/beams/dice,
 * virtual scaled Enderman eruptions, lightning strikes, and dragon aura trails via PacketEvents.
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

    /**
     * Renders True Ending-inspired magenta-to-deep-blue transition aura and dragon breath trail
     * around the flying boss.
     */
    default void renderDragonAuraTrail(
            Location dragonLocation,
            Collection<? extends Player> viewers) {
    }

    /**
     * Renders an expanding 360-degree shockwave ring at {@code currentRadius} around {@code origin}.
     */
    default void renderExpandingShockwave(
            Location origin,
            double currentRadius,
            Collection<? extends Player> viewers) {
        renderCircleAoE(origin, currentRadius, TelegraphStyle.DANGER, viewers);
    }

    /**
     * Renders a high-density sweeping laser beam from {@code origin} to {@code impactPoint}.
     */
    default void renderLaserBeam(
            Location origin,
            Location impactPoint,
            Collection<? extends Player> viewers) {
        renderLineAoE(origin, impactPoint, 1.4, TelegraphStyle.DANGER, viewers);
    }

    /**
     * Renders the safe cone shadow behind a shielding player during a field-wide breath torrent.
     */
    default void renderShieldSafeCone(
            Location blockerLocation,
            double directionYawDegrees,
            double coneLength,
            double halfAngleDegrees,
            Collection<? extends Player> viewers) {
    }

    /**
     * Spawns a client-side lightning bolt packet and electric spark burst at {@code strikeLocation}.
     */
    default void renderLightningStrike(
            Location strikeLocation,
            Collection<? extends Player> viewers) {
    }

    /**
     * Spawns a virtual scaled Enderman (`generic.scale` attribute packet) that rises out of the
     * ground for bamboo-shoot (Takenoko) AoEs or rotating laser beams.
     *
     * @return virtual entity ID for subsequent rotation/teleport/destroy updates
     */
    default int spawnVirtualScaledEnderman(
            Location baseLocation,
            double scale,
            float yawDegrees,
            boolean screaming,
            Collection<? extends Player> viewers) {
        return -1;
    }

    /**
     * Updates the position and facing yaw of a previously spawned virtual Enderman.
     */
    default void moveAndRotateVirtualEnderman(
            int entityId,
            Location location,
            float yawDegrees,
            Collection<? extends Player> viewers) {
    }

    /**
     * Spawns or updates a 3D directional arrow indicator (`TEXT_DISPLAY` / `BLOCK_DISPLAY`)
     * for Exaflare lanes or Knockback radial waves.
     *
     * @return virtual display entity ID
     */
    default int spawnDirectionalArrowDisplay(
            Location location,
            float yawDegrees,
            TelegraphStyle style,
            Collection<? extends Player> viewers) {
        return -1;
    }

    /**
     * Spawns or updates an overhead 3D dice group marker (`•`, `••`, `•••`, `••••`) above a player
     * for the 2-group Dual Dive Dice mechanic.
     *
     * @return virtual display entity ID
     */
    default int spawnOverheadDiceDisplay(
            Location abovePlayer,
            int diceNumber,
            boolean oddGroup,
            Collection<? extends Player> viewers) {
        return -1;
    }

    /**
     * Destroys one or more virtual PacketEvents entities (Endermen, Display arrows, dice markers).
     */
    default void destroyVirtualEntities(
            Collection<? extends Player> viewers,
            int... entityIds) {
    }
}
