package net.sabafly.dragonraid.api.event;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a DragonRaid encounter completes (victory, wipe, or abort), carrying per-enemy
 * combat metrics for downstream reward and achievement modules.
 */
public final class RaidEncounterCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EncounterResultSnapshot snapshot;

    public RaidEncounterCompletedEvent(EncounterResultSnapshot snapshot) {
        super(Bukkit.getServer() != null && !Bukkit.isPrimaryThread());
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public EncounterResultSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
