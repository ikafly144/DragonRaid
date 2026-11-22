package net.sabafly.dragonraid.api.script;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.api.util.ImmutableSnapshots;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable context passed to scripted condition and action hooks during achievement,
 * reward, and vanilla progression evaluation.
 */
public record ScriptHookContext(
        @Nullable RaidServiceRegistry services,
        @Nullable UUID playerId,
        @Nullable String playerName,
        List<UUID> participantIds,
        @Nullable EncounterResultSnapshot encounterResult,
        Map<String, Object> params,
        Map<String, Object> attributes
) {

    public ScriptHookContext {
        participantIds = participantIds == null ? List.of() : List.copyOf(participantIds);
        params = ImmutableSnapshots.copyStringMap(params);
        attributes = ImmutableSnapshots.copyStringMap(attributes);
    }

    public ScriptHookContext(
            @Nullable RaidServiceRegistry services,
            @Nullable UUID playerId,
            @Nullable String playerName,
            @Nullable Collection<UUID> participantIds,
            @Nullable EncounterResultSnapshot encounterResult,
            @Nullable Map<String, Object> params,
            @Nullable Map<String, Object> attributes) {
        this(
                services,
                playerId,
                playerName,
                participantIds == null ? List.of() : List.copyOf(participantIds),
                encounterResult,
                params,
                attributes
        );
    }

    public Optional<Object> param(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(params.get(key));
    }

    public Optional<Object> attribute(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(attributes.get(key));
    }

    public static ScriptHookContext empty() {
        return new ScriptHookContext(null, null, null, List.of(), null, Map.of(), Map.of());
    }

    public static ScriptHookContext forPlayer(
            @Nullable RaidServiceRegistry services,
            UUID playerId,
            @Nullable String playerName,
            @Nullable Map<String, Object> params,
            @Nullable Map<String, Object> attributes) {
        Objects.requireNonNull(playerId, "playerId");
        return new ScriptHookContext(services, playerId, playerName, List.of(playerId), null, params, attributes);
    }
}
