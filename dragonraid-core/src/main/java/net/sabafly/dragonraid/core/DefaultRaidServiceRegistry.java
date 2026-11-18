package net.sabafly.dragonraid.core;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;

/**
 * Thread-safe {@link RaidServiceRegistry} implementation sharing module SPIs across DragonRaid.
 */
public final class DefaultRaidServiceRegistry implements RaidServiceRegistry {

    private final ConcurrentHashMap<Class<?>, Object> services = new ConcurrentHashMap<>();

    @Override
    public <T> void register(Class<T> serviceType, T implementation) {
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(implementation, "implementation");
        services.put(serviceType, implementation);
    }

    @Override
    public <T> Optional<T> get(Class<T> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType");
        Object impl = services.get(serviceType);
        return serviceType.isInstance(impl) ? Optional.of(serviceType.cast(impl)) : Optional.empty();
    }
}
