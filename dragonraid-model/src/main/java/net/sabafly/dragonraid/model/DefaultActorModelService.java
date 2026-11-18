package net.sabafly.dragonraid.model;

import java.util.Objects;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import net.sabafly.dragonraid.api.enemy.ModelRendererType;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.api.model.ActorModelService;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Dispatches model binding/prop creation to either {@link BetterModelActorController}
 * ({@link ModelRendererType#BETTER_MODEL}) or {@link VanillaActorController}
 * ({@link ModelRendererType#VANILLA}) based on {@link ActorModelSpec#rendererType()}.
 */
public final class DefaultActorModelService implements ActorModelService {

    private final Logger logger;

    public DefaultActorModelService(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public ActorModelController bindEntity(Entity entity, ActorModelSpec spec) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(spec, "spec");
        return switch (spec.rendererType()) {
            case BETTER_MODEL -> new BetterModelActorController(entity, spec, logger);
            case VANILLA -> new VanillaActorController(entity, spec);
        };
    }

    @Override
    public ActorModelController createProp(Location location, ActorModelSpec spec) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(spec, "spec");
        return switch (spec.rendererType()) {
            case BETTER_MODEL -> new BetterModelActorController(location, spec, logger);
            case VANILLA -> new VanillaActorController(location, spec);
        };
    }
}
