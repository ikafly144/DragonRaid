package net.sabafly.dragonraid.encounter;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/**
 * Mutable per-attempt state record for a locked-in raid participant.
 */
public final class RaidParticipant {

    private final UUID playerId;
    private volatile String name;
    private volatile ParticipantState state = ParticipantState.ALIVE;
    private volatile boolean online = true;
    private volatile boolean rescueInProgress;
    private volatile boolean initialSnapshotReady;

    // Captured original player flags to restore cleanly on revive, end, reset, or rejoin.
    private volatile @Nullable GameMode previousGameMode;
    private volatile boolean previousInvisible;
    private volatile boolean previousCollidable = true;
    private volatile boolean previousInvulnerable;
    private volatile boolean stateCaptured;

    private volatile @Nullable Location downedLocation;
    private volatile long downedAtMillis;
    private volatile double reviveProgressSeconds;
    private volatile @Nullable UUID currentReviverId;

    public RaidParticipant(UUID playerId, String name) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.name = Objects.requireNonNull(name, "name");
    }

    public UUID playerId() {
        return playerId;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public ParticipantState state() {
        return state;
    }

    public void setState(ParticipantState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public boolean isRescueInProgress() {
        return rescueInProgress;
    }

    public void setRescueInProgress(boolean rescueInProgress) {
        this.rescueInProgress = rescueInProgress;
    }

    public boolean isInitialSnapshotReady() {
        return initialSnapshotReady;
    }

    public void markInitialSnapshotReady() {
        this.initialSnapshotReady = true;
    }

    public boolean isStateCaptured() {
        return stateCaptured;
    }

    public @Nullable GameMode previousGameMode() {
        return previousGameMode;
    }

    public boolean previousInvisible() {
        return previousInvisible;
    }

    public boolean previousCollidable() {
        return previousCollidable;
    }

    public boolean previousInvulnerable() {
        return previousInvulnerable;
    }

    public void captureOriginalState(
            GameMode gameMode,
            boolean invisible,
            boolean collidable,
            boolean invulnerable) {
        if (stateCaptured) {
            return;
        }
        this.previousGameMode = gameMode;
        this.previousInvisible = invisible;
        this.previousCollidable = collidable;
        this.previousInvulnerable = invulnerable;
        this.stateCaptured = true;
    }

    public void clearCapturedState() {
        this.stateCaptured = false;
        this.previousGameMode = null;
    }

    public @Nullable Location downedLocation() {
        return downedLocation;
    }

    public void setDownedLocation(@Nullable Location downedLocation) {
        this.downedLocation = downedLocation == null ? null : downedLocation.clone();
    }

    public long downedAtMillis() {
        return downedAtMillis;
    }

    public void setDownedAtMillis(long downedAtMillis) {
        this.downedAtMillis = downedAtMillis;
    }

    public double reviveProgressSeconds() {
        return reviveProgressSeconds;
    }

    public void setReviveProgressSeconds(double reviveProgressSeconds) {
        this.reviveProgressSeconds = reviveProgressSeconds;
    }

    public @Nullable UUID currentReviverId() {
        return currentReviverId;
    }

    public void setCurrentReviverId(@Nullable UUID currentReviverId) {
        this.currentReviverId = currentReviverId;
    }
}
