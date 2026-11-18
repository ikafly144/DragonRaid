package net.sabafly.dragonraid.config;

/**
 * Thrown when a YAML configuration file is missing, malformed, or fails validation.
 */
public class ConfigException extends RuntimeException {

    private final String fileName;
    private final String path;

    public ConfigException(String fileName, String path, String message) {
        super(formatMessage(fileName, path, message));
        this.fileName = fileName;
        this.path = path;
    }

    public ConfigException(String fileName, String path, String message, Throwable cause) {
        super(formatMessage(fileName, path, message), cause);
        this.fileName = fileName;
        this.path = path;
    }

    public String fileName() {
        return fileName;
    }

    public String path() {
        return path;
    }

    private static String formatMessage(String fileName, String path, String message) {
        if (path == null || path.isBlank()) {
            return "[" + fileName + "] " + message;
        }
        return "[" + fileName + " @ " + path + "] " + message;
    }
}
