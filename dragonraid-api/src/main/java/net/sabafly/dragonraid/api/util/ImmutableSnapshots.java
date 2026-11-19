package net.sabafly.dragonraid.api.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Deeply copies parameter and attribute maps/collections into unmodifiable snapshots
 * so callers cannot mutate records after construction.
 */
public final class ImmutableSnapshots {

    private ImmutableSnapshots() {
    }

    /**
     * Returns an unmodifiable deep snapshot of {@code source}, skipping {@code null} keys and values.
     */
    public static Map<String, Object> copyStringMap(@Nullable Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
        visiting.put(source, Boolean.TRUE);
        try {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), freezeValue(entry.getValue(), visiting));
                }
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            visiting.remove(source);
        }
    }

    /**
     * Recursively freezes nested collections, maps, and arrays into unmodifiable containers.
     */
    public static Object freezeValue(Object value) {
        return freezeValue(value, new IdentityHashMap<>());
    }

    private static Object freezeValue(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null || isKnownImmutableScalar(value)) {
            return value;
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Circular reference detected in snapshot value");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        Object frozenKey = freezeValue(entry.getKey(), visiting);
                        Object frozenVal = freezeValue(entry.getValue(), visiting);
                        copy.put(frozenKey, frozenVal);
                    }
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof Set<?> set) {
                Set<Object> copy = new LinkedHashSet<>();
                for (Object item : set) {
                    if (item != null) {
                        copy.add(freezeValue(item, visiting));
                    }
                }
                return Collections.unmodifiableSet(copy);
            }
            if (value instanceof Collection<?> collection) {
                List<Object> copy = new ArrayList<>(collection.size());
                for (Object item : collection) {
                    if (item != null) {
                        copy.add(freezeValue(item, visiting));
                    }
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Object[] array) {
                List<Object> copy = new ArrayList<>(array.length);
                for (Object item : array) {
                    if (item != null) {
                        copy.add(freezeValue(item, visiting));
                    }
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof int[] array) {
                List<Integer> copy = new ArrayList<>(array.length);
                for (int item : array) {
                    copy.add(item);
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof long[] array) {
                List<Long> copy = new ArrayList<>(array.length);
                for (long item : array) {
                    copy.add(item);
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof double[] array) {
                List<Double> copy = new ArrayList<>(array.length);
                for (double item : array) {
                    copy.add(item);
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof float[] array) {
                List<Float> copy = new ArrayList<>(array.length);
                for (float item : array) {
                    copy.add(item);
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof boolean[] array) {
                List<Boolean> copy = new ArrayList<>(array.length);
                for (boolean item : array) {
                    copy.add(item);
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof byte[] array) {
                List<Byte> copy = new ArrayList<>(array.length);
                for (byte item : array) {
                    copy.add(item);
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof short[] array) {
                List<Short> copy = new ArrayList<>(array.length);
                for (short item : array) {
                    copy.add(item);
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof char[] array) {
                List<Character> copy = new ArrayList<>(array.length);
                for (char item : array) {
                    copy.add(item);
                }
                return Collections.unmodifiableList(copy);
            }
            return value;
        } finally {
            visiting.remove(value);
        }
    }

    private static boolean isKnownImmutableScalar(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Enum<?>
                || value instanceof UUID
                || value instanceof Instant
                || value instanceof Duration
                || value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof LocalDateTime
                || value instanceof ZonedDateTime
                || value instanceof OffsetDateTime;
    }
}
