package net.sabafly.dragonraid.api.pack;

import org.jetbrains.annotations.Nullable;

/**
 * Metadata loaded from {@code plugins/DragonRaid/contents/<pack-id>/pack.yml}.
 */
public record ContentPackManifest(
        String id,
        String name,
        String version,
        boolean enabled,
        int priority,
        String description
) {

    public ContentPackManifest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("pack id must not be blank");
        }
        id = id.trim();
        name = (name == null || name.isBlank()) ? id : name.trim();
        version = (version == null || version.isBlank()) ? "1.0.0" : version.trim();
        description = normalize(description);
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
