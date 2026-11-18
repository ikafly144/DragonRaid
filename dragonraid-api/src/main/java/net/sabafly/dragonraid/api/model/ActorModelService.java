package net.sabafly.dragonraid.api.model;

import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Factory service creating {@link ActorModelController} instances for bound Bukkit entities
 * and standalone spatial props according to {@link ActorModelSpec#rendererType()}.
 */
public interface ActorModelService {

    ActorModelController bindEntity(Entity entity, ActorModelSpec spec);

    ActorModelController createProp(Location location, ActorModelSpec spec);
}
