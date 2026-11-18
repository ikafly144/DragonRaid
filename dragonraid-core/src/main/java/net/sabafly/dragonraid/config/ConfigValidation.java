package net.sabafly.dragonraid.config;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Shared validation helpers for reading Configurate {@link ConfigurationNode} trees into strongly-typed configs.
 */
final class ConfigValidation {

    private ConfigValidation() {
    }

    static ConfigurationNode requireNode(ConfigurationNode parent, String fileName, String key) {
        ConfigurationNode child = parent.node(key);
        if (child.virtual() || !child.isMap()) {
            throw new ConfigException(fileName, fullPath(child), "Missing or invalid configuration section");
        }
        return child;
    }

    static List<? extends ConfigurationNode> requireList(ConfigurationNode parent, String fileName, String key) {
        ConfigurationNode child = parent.node(key);
        if (child.virtual() || !child.isList()) {
            throw new ConfigException(fileName, fullPath(child), "Missing or invalid list");
        }
        return child.childrenList();
    }

    static String requireString(ConfigurationNode parent, String fileName, String key) {
        return requireString(parent.node(key), fileName);
    }

    static String requireString(ConfigurationNode node, String fileName) {
        Object raw = node.rawScalar();
        if (node.virtual() || node.isMap() || node.isList() || raw == null) {
            throw new ConfigException(fileName, fullPath(node), "Missing or blank string value");
        }
        String value = String.valueOf(raw);
        if (value.isBlank()) {
            throw new ConfigException(fileName, fullPath(node), "Missing or blank string value");
        }
        return value;
    }

    static List<String> requireStringList(ConfigurationNode parent, String fileName, String key) {
        List<? extends ConfigurationNode> nodes = requireList(parent, fileName, key);
        List<String> result = new ArrayList<>(nodes.size());
        for (ConfigurationNode item : nodes) {
            Object raw = item.rawScalar();
            if (raw == null || String.valueOf(raw).isBlank()) {
                throw new ConfigException(fileName, fullPath(item), "List entry must be a non-blank string");
            }
            result.add(String.valueOf(raw));
        }
        return List.copyOf(result);
    }

    static int requireInt(ConfigurationNode parent, String fileName, String key) {
        return requireInt(parent.node(key), fileName);
    }

    static int requireInt(ConfigurationNode node, String fileName) {
        Object raw = node.rawScalar();
        if (node.virtual() || !(raw instanceof Number number)
                || raw instanceof Float || raw instanceof Double || raw instanceof BigDecimal) {
            throw new ConfigException(fileName, fullPath(node), "Missing integer value");
        }
        if (raw instanceof BigInteger bi) {
            try {
                return bi.intValueExact();
            } catch (ArithmeticException ex) {
                throw new ConfigException(fileName, fullPath(node), "Integer value out of range: " + bi, ex);
            }
        }
        long value = number.longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ConfigException(fileName, fullPath(node), "Integer value out of range: " + value);
        }
        return (int) value;
    }

    static double requireDouble(ConfigurationNode parent, String fileName, String key) {
        return requireDouble(parent.node(key), fileName);
    }

    static double requireDouble(ConfigurationNode node, String fileName) {
        Object raw = node.rawScalar();
        if (node.virtual() || !(raw instanceof Number number)) {
            throw new ConfigException(fileName, fullPath(node), "Missing numeric value");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            throw new ConfigException(fileName, fullPath(node), "Numeric value must be finite");
        }
        return value;
    }

    static boolean requireBoolean(ConfigurationNode parent, String fileName, String key) {
        return requireBoolean(parent.node(key), fileName);
    }

    static boolean requireBoolean(ConfigurationNode node, String fileName) {
        Object raw = node.rawScalar();
        if (node.virtual() || !(raw instanceof Boolean bool)) {
            throw new ConfigException(fileName, fullPath(node), "Missing boolean value");
        }
        return bool;
    }

    static int requirePositive(int value, String fileName, String path) {
        if (value <= 0) {
            throw new ConfigException(fileName, path, "Value must be > 0 (got " + value + ")");
        }
        return value;
    }

    static double requirePositive(double value, String fileName, String path) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new ConfigException(fileName, path, "Value must be > 0 (got " + value + ")");
        }
        return value;
    }

    static int requireNonNegative(int value, String fileName, String path) {
        if (value < 0) {
            throw new ConfigException(fileName, path, "Value must be >= 0 (got " + value + ")");
        }
        return value;
    }

    static double requireNonNegative(double value, String fileName, String path) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new ConfigException(fileName, path, "Value must be >= 0 (got " + value + ")");
        }
        return value;
    }

    static double requireUnitRatio(double value, String fileName, String path) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new ConfigException(fileName, path, "Ratio must be within [0.0, 1.0] (got " + value + ")");
        }
        return value;
    }

    static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, String fileName, String path) {
        Objects.requireNonNull(enumType, "enumType");
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException ex) {
            throw new ConfigException(
                    fileName,
                    path,
                    "Unsupported " + enumType.getSimpleName() + " value: '" + raw + "'",
                    ex);
        }
    }

    static String fullPath(ConfigurationNode node) {
        StringBuilder sb = new StringBuilder();
        for (Object segment : node.path()) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    static String fullPath(String prefix, String child) {
        return prefix == null || prefix.isEmpty() ? child : prefix + "." + child;
    }
}
