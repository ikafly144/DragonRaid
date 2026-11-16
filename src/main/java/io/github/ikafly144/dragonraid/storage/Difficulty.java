package io.github.ikafly144.dragonraid.storage;

import java.util.Locale;
import java.util.Objects;

/**
 * Raid encounter difficulty levels tracked in player statistics.
 */
public enum Difficulty {
    NORMAL("normal"),
    HARD("hard");

    private final String key;

    Difficulty(String key) {
        this.key = key;
    }

    /**
     * Returns the lowercase configuration key for this difficulty.
     */
    public String key() {
        return key;
    }

    /**
     * Parses a difficulty name case-insensitively (for example, {@code "normal"} or {@code "HARD"}).
     */
    public static Difficulty fromKey(String raw) {
        Objects.requireNonNull(raw, "raw difficulty must not be null");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (Difficulty value : values()) {
            if (value.key.equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown difficulty: " + raw);
    }
}
