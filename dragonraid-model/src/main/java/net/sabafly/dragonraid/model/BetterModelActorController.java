package net.sabafly.dragonraid.model;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.ModelScaler;
import kr.toxicity.model.api.tracker.Tracker;
import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import net.sabafly.dragonraid.api.enemy.BoneOffset;
import net.sabafly.dragonraid.api.enemy.ModelRendererType;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.boss.DragonPosture;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * First-class {@link ModelRendererType#BETTER_MODEL} actor controller backed by BetterModel's
 * {@link ModelRenderer} and {@link Tracker} APIs.
 */
public final class BetterModelActorController implements ActorModelController {

    interface TrackerHandle {
        boolean animate(String clipName, float speed, boolean loop);

        boolean stopAnimation(String clipName);

        @Nullable Vector3f boneWorldPosition(String boneName);

        void setScale(float scale);

        void close();
    }

    private final @Nullable Entity entity;
    private final @Nullable Location propAnchor;
    private final ActorModelSpec spec;
    private final @Nullable TrackerHandle tracker;
    private volatile double currentScale;

    public BetterModelActorController(Entity entity, ActorModelSpec spec, Logger logger) {
        this(
                entity,
                null,
                spec,
                modelId -> bindEntityTracker(entity, modelId),
                logger
        );
    }

    public BetterModelActorController(Location propAnchor, ActorModelSpec spec, Logger logger) {
        this(
                null,
                propAnchor,
                spec,
                modelId -> createPropTracker(propAnchor, modelId),
                logger
        );
    }

    BetterModelActorController(
            @Nullable Entity entity,
            @Nullable Location propAnchor,
            ActorModelSpec spec,
            Function<String, Optional<TrackerHandle>> trackerFactory,
            Logger logger) {
        this.entity = entity;
        this.propAnchor = propAnchor != null ? propAnchor.clone() : null;
        this.spec = Objects.requireNonNull(spec, "spec");
        this.currentScale = spec.scale();

        TrackerHandle bound = null;
        String modelId = spec.betterModelId();
        if (modelId != null && !modelId.isBlank()) {
            try {
                Optional<TrackerHandle> created = trackerFactory.apply(modelId);
                if (created != null && created.isPresent()) {
                    bound = created.get();
                    if (Math.abs(currentScale - 1.0) > 1e-6) {
                        bound.setScale((float) currentScale);
                    }
                } else {
                    logger.warning("BetterModel asset '" + modelId + "' not found; actor stays active using entity anchor.");
                }
            } catch (RuntimeException | LinkageError ex) {
                logger.warning("Failed to initialize BetterModel '" + modelId + "': " + ex.getMessage());
            }
        }
        this.tracker = bound;
    }

    private static Optional<TrackerHandle> bindEntityTracker(Entity entity, String modelId) {
        return BetterModel.model(modelId).map(renderer -> {
            Tracker bound = renderer.getOrCreate(BukkitAdapter.adapt(entity));
            return wrapTracker(bound);
        });
    }

    private static Optional<TrackerHandle> createPropTracker(Location location, String modelId) {
        return BetterModel.model(modelId).map(renderer -> {
            Tracker bound = renderer.create(BukkitAdapter.adapt(location));
            return wrapTracker(bound);
        });
    }

    private static TrackerHandle wrapTracker(Tracker tracker) {
        return new TrackerHandle() {
            @Override
            public boolean animate(String clipName, float speed, boolean loop) {
                AnimationIterator.Type type = loop
                        ? AnimationIterator.Type.LOOP
                        : AnimationIterator.Type.PLAY_ONCE;
                return tracker.animate(clipName, new AnimationModifier(0, 0, type, () -> speed));
            }

            @Override
            public boolean stopAnimation(String clipName) {
                return tracker.stopAnimation(clipName);
            }

            @Override
            public @Nullable Vector3f boneWorldPosition(String boneName) {
                RenderedBone bone = tracker.bone(boneName);
                return bone != null ? bone.worldPosition() : null;
            }

            @Override
            public void setScale(float scale) {
                tracker.scaler(ModelScaler.value(scale));
            }

            @Override
            public void close() {
                tracker.close();
            }
        };
    }

    @Override
    public ModelRendererType rendererType() {
        return ModelRendererType.BETTER_MODEL;
    }

    @Override
    public boolean playAnimation(String animationKey, double speed, boolean loop) {
        Objects.requireNonNull(animationKey, "animationKey");
        if (tracker == null) {
            return false;
        }
        String clip = spec.resolveAnimationClip(animationKey);
        return tracker.animate(clip, (float) Math.max(0.05, speed), loop);
    }

    @Override
    public boolean stopAnimation(String animationKey) {
        Objects.requireNonNull(animationKey, "animationKey");
        if (tracker == null) {
            return false;
        }
        String clip = spec.resolveAnimationClip(animationKey);
        return tracker.stopAnimation(clip) || tracker.stopAnimation(animationKey);
    }

    @Override
    public @Nullable Location boneLocation(String boneName) {
        Objects.requireNonNull(boneName, "boneName");
        Location origin = entity != null ? entity.getLocation() : propAnchor;
        if (origin == null) {
            return null;
        }
        if (tracker != null) {
            // RenderedBone.worldPosition() returns an origin-relative offset in world orientation.
            Vector3f relativePos = tracker.boneWorldPosition(boneName);
            if (relativePos != null) {
                return origin.clone().add(relativePos.x(), relativePos.y(), relativePos.z());
            }
        }
        BoneOffset offset = spec.boneOffsets().getOrDefault(boneName, BoneOffset.ZERO);
        return BoneMath.resolveWorldOffset(origin, offset, currentScale);
    }

    @Override
    public void setPosture(DragonPosture posture) {
        Objects.requireNonNull(posture, "posture");
        String clipKey = switch (posture) {
            case LOW_ORBIT -> "orbit";
            case CENTER_LANDED -> "landed_idle";
            case HIGH_HOVER -> "hover";
            case ASCENDING_CHARGE -> "ascend_charge";
            case DIVING -> "dive_attack";
            case STAGGERED -> "stagger_collapse";
        };
        playAnimation(clipKey, 1.0, posture != DragonPosture.ASCENDING_CHARGE);
    }

    @Override
    public void setScale(double scale) {
        if (!Double.isFinite(scale) || scale <= 0.0) {
            return;
        }
        this.currentScale = scale;
        if (tracker != null) {
            tracker.setScale((float) scale);
        }
    }

    @Override
    public void close() {
        if (tracker != null) {
            tracker.close();
        }
    }
}
