package net.sabafly.dragonraid.script.pack;

import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

/**
 * Exception thrown when loading or validating a Content Pack fails.
 */
public final class ContentPackException extends RuntimeException {

    private final String packId;
    private final String filePath;
    private final String nodePath;

    public ContentPackException(
            @Nullable String packId,
            @Nullable Path filePath,
            @Nullable String nodePath,
            String message) {
        this(packId, filePath != null ? filePath.toString() : "", nodePath, message, null);
    }

    public ContentPackException(
            @Nullable String packId,
            @Nullable Path filePath,
            @Nullable String nodePath,
            String message,
            @Nullable Throwable cause) {
        this(packId, filePath != null ? filePath.toString() : "", nodePath, message, cause);
    }

    public ContentPackException(
            @Nullable String packId,
            @Nullable String filePath,
            @Nullable String nodePath,
            String message,
            @Nullable Throwable cause) {
        super(formatMessage(packId, filePath, nodePath, message), cause);
        this.packId = packId == null ? "" : packId;
        this.filePath = filePath == null ? "" : filePath;
        this.nodePath = nodePath == null ? "" : nodePath;
    }

    public String packId() {
        return packId;
    }

    public String filePath() {
        return filePath;
    }

    public String nodePath() {
        return nodePath;
    }

    private static String formatMessage(
            @Nullable String packId,
            @Nullable String filePath,
            @Nullable String nodePath,
            String message) {
        StringBuilder sb = new StringBuilder();
        if (packId != null && !packId.isBlank()) {
            sb.append("[pack=").append(packId).append("] ");
        }
        if (filePath != null && !filePath.isBlank()) {
            sb.append(filePath);
            if (nodePath != null && !nodePath.isBlank()) {
                sb.append(" (").append(nodePath).append(')');
            }
            sb.append(": ");
        } else if (nodePath != null && !nodePath.isBlank()) {
            sb.append('(').append(nodePath).append("): ");
        }
        sb.append(message);
        return sb.toString();
    }
}
