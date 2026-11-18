package net.sabafly.dragonraid.api.enemy;

import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Model, scale, bone, and animation configuration for a raid enemy or mechanic prop.
 *
 * @param rendererType peer renderer backend ({@link ModelRendererType#BETTER_MODEL} or {@link ModelRendererType#VANILLA})
 * @param betterModelId BetterModel model key when {@code rendererType == BETTER_MODEL} (nullable for {@link ModelRendererType#VANILLA})
 * @param scale uniform visual model/entity scale multiplier (must be {@code > 0})
 * @param boneOffsets local bone offsets keyed by bone name (e.g. {@code "mouth"}, {@code "head"}, {@code "chest_core"})
 * @param animationAliases mapping from logical DragonRaid animation keys to renderer-specific clip names
 * @param yawOffsetDegrees optional model-specific yaw offset added on top of renderer defaults
 */
public record ActorModelSpec(
        ModelRendererType rendererType,
        @Nullable String betterModelId,
        double scale,
        Map<String, BoneOffset> boneOffsets,
        Map<String, String> animationAliases,
        double yawOffsetDegrees
) {

    public ActorModelSpec {
        Objects.requireNonNull(rendererType, "rendererType");
        if (rendererType == ModelRendererType.BETTER_MODEL
                && (betterModelId == null || betterModelId.isBlank())) {
            throw new IllegalArgumentException("betterModelId must not be blank when rendererType is BETTER_MODEL");
        }
        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("scale must be positive and finite: " + scale);
        }
        if (!Double.isFinite(yawOffsetDegrees)) {
            throw new IllegalArgumentException("yawOffsetDegrees must be finite: " + yawOffsetDegrees);
        }
        boneOffsets = boneOffsets == null ? Map.of() : Map.copyOf(boneOffsets);
        animationAliases = animationAliases == null ? Map.of() : Map.copyOf(animationAliases);
    }

    public ActorModelSpec(
            ModelRendererType rendererType,
            @Nullable String betterModelId,
            double scale,
            Map<String, BoneOffset> boneOffsets,
            Map<String, String> animationAliases) {
        this(rendererType, betterModelId, scale, boneOffsets, animationAliases, 0.0);
    }

    public static ActorModelSpec vanilla(double scale, Map<String, BoneOffset> boneOffsets) {
        return new ActorModelSpec(ModelRendererType.VANILLA, null, scale, boneOffsets, Map.of());
    }

    public static ActorModelSpec betterModel(String modelId, double scale) {
        return new ActorModelSpec(ModelRendererType.BETTER_MODEL, modelId, scale, Map.of(), Map.of());
    }

    /**
     * Resolves the animation clip name for {@code logicalKey}, returning {@code logicalKey} if unaliased.
     */
    public String resolveAnimationClip(String logicalKey) {
        Objects.requireNonNull(logicalKey, "logicalKey");
        return animationAliases.getOrDefault(logicalKey, logicalKey);
    }
}
