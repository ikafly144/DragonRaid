package net.sabafly.dragonraid.api.service;

import java.util.Optional;

/**
 * Thread-safe SPI service registry decoupling {@code dragonraid-api} consumers and plugin modules.
 */
public interface RaidServiceRegistry {

    <T> void register(Class<T> serviceType, T implementation);

    <T> Optional<T> get(Class<T> serviceType);

    default <T> T require(Class<T> serviceType) {
        return get(serviceType).orElseThrow(() ->
                new IllegalStateException("Required DragonRaid service not registered: " + serviceType.getName()));
    }
}
