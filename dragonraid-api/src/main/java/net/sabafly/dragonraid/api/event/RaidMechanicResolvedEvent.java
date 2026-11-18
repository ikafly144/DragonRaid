package net.sabafly.dragonraid.api.event;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a scripted or built-in raid mechanic completes resolution, enabling event-driven
 * achievements and custom hooks without coupling core encounter code.
 */
public final class RaidMechanicResolvedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String mechanicId;
    private final @Nullable String sourceEnemyId;
    private final Set<UUID> hitPlayerIds;
    private final boolean perfectResolve;
    private final Map<String, Object> tags;

    public RaidMechanicResolvedEvent(
            String mechanicId,
            @Nullable String sourceEnemyId,
            Set<UUID> hitPlayerIds,
            boolean perfectResolve,
            Map<String, Object> tags) {
        super(Bukkit.getServer() != null && !Bukkit.isPrimaryThread());
        this.mechanicId = Objects.requireNonNull(mechanicId, "mechanicId");
        this.sourceEnemyId = sourceEnemyId;
        this.hitPlayerIds = hitPlayerIds == null ? Set.of() : Set.copyOf(hitPlayerIds);
        this.perfectResolve = perfectResolve;
        this.tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public String mechanicId() {
        return mechanicId;
    }

    public @Nullable String sourceEnemyId() {
        return sourceEnemyId;
    }

    public Set<UUID> hitPlayerIds() {
        return hitPlayerIds;
    }

    public boolean perfectResolve() {
        return perfectResolve;
    }

    public Map<String, Object> tags() {
        return tags;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
