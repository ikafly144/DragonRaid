package io.github.ikafly144.dragonraid.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Shared validation and extraction helpers for YAML configuration loading.
 */
final class ConfigValidation {

    private ConfigValidation() {
    }

    static ConfigurationSection requireSection(ConfigurationSection root, String path, String fileName) {
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null) {
            throw new ConfigException(fileName, fullPath(root, path), "Missing required section");
        }
        return section;
    }

    static String requireString(ConfigurationSection root, String path, String fileName) {
        String value = root.getString(path);
        if (value == null || value.isBlank()) {
            throw new ConfigException(fileName, fullPath(root, path), "Missing or blank required string");
        }
        return value;
    }

    static List<String> requireStringList(ConfigurationSection root, String path, String fileName) {
        List<String> list = root.getStringList(path);
        if (list.isEmpty()) {
            throw new ConfigException(fileName, fullPath(root, path), "Missing or empty required string list");
        }
        return List.copyOf(list);
    }

    static List<Map<?, ?>> requireMapList(ConfigurationSection root, String path, String fileName) {
        List<Map<?, ?>> list = root.getMapList(path);
        if (list.isEmpty()) {
            throw new ConfigException(fileName, fullPath(root, path), "Missing or empty required list");
        }
        return list;
    }

    static int requireInt(ConfigurationSection root, String path, String fileName) {
        if (!root.isInt(path) && !root.isLong(path)) {
            throw new ConfigException(fileName, fullPath(root, path), "Expected integer value");
        }
        return root.getInt(path);
    }

    static double requireDouble(ConfigurationSection root, String path, String fileName) {
        if (!root.isDouble(path) && !root.isInt(path) && !root.isLong(path)) {
            throw new ConfigException(fileName, fullPath(root, path), "Expected numeric value");
        }
        return root.getDouble(path);
    }

    static boolean requireBoolean(ConfigurationSection root, String path, String fileName) {
        if (!root.isBoolean(path)) {
            throw new ConfigException(fileName, fullPath(root, path), "Expected boolean value");
        }
        return root.getBoolean(path);
    }

    static int requirePositive(int value, String path, String fileName) {
        if (value <= 0) {
            throw new ConfigException(fileName, path, "Value must be > 0, got " + value);
        }
        return value;
    }

    static int requireNonNegative(int value, String path, String fileName) {
        if (value < 0) {
            throw new ConfigException(fileName, path, "Value must be >= 0, got " + value);
        }
        return value;
    }

    static double requirePositive(double value, String path, String fileName) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new ConfigException(fileName, path, "Value must be finite and > 0, got " + value);
        }
        return value;
    }

    static double requireNonNegative(double value, String path, String fileName) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new ConfigException(fileName, path, "Value must be finite and >= 0, got " + value);
        }
        return value;
    }

    static double requireUnitRatio(double value, String path, String fileName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new ConfigException(fileName, path, "Ratio must be in [0.0, 1.0], got " + value);
        }
        return value;
    }

    static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, String path, String fileName) {
        if (raw == null || raw.isBlank()) {
            throw new ConfigException(fileName, path, "Missing enum value for " + enumType.getSimpleName());
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ConfigException(fileName, path,
                    "Invalid " + enumType.getSimpleName() + " value: '" + raw + "'", ex);
        }
    }

    static String mapString(Map<?, ?> map, String key, String path, String fileName) {
        Object val = map.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new ConfigException(fileName, path + "." + key, "Missing or blank string");
        }
        return val.toString();
    }

    static int mapInt(Map<?, ?> map, String key, String path, String fileName) {
        Object val = map.get(key);
        if (!(val instanceof Number num)) {
            throw new ConfigException(fileName, path + "." + key, "Expected integer value");
        }
        return num.intValue();
    }

    static double mapDouble(Map<?, ?> map, String key, String path, String fileName) {
        Object val = map.get(key);
        if (!(val instanceof Number num)) {
            throw new ConfigException(fileName, path + "." + key, "Expected numeric value");
        }
        return num.doubleValue();
    }

    static String fullPath(ConfigurationSection section, String child) {
        String current = section.getCurrentPath();
        if (current == null || current.isEmpty()) {
            return child;
        }
        return current + "." + child;
    }
}
