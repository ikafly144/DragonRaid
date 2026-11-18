package net.sabafly.dragonraid.model;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import net.sabafly.dragonraid.api.enemy.BoneOffset;
import net.sabafly.dragonraid.api.enemy.ModelRendererType;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.boss.DragonPosture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * First-class {@link ModelRendererType#VANILLA} actor model controller supporting vanilla entities
 * and PacketEvents virtual props with local bone offsets and scale control.
 */
public final class VanillaActorController implements ActorModelController {

    private final @Nullable Entity entity;
    private final @Nullable Location propAnchor;
    private final ActorModelSpec spec;
    private final Set<String> activeAnimations = ConcurrentHashMap.newKeySet();
    private volatile DragonPosture posture = DragonPosture.LOW_ORBIT;
    private volatile double currentScale;

    public VanillaActorController(Entity entity, ActorModelSpec spec) {
        this.entity = Objects.requireNonNull(entity, "entity");
        this.propAnchor = null;
        this.spec = Objects.requireNonNull(spec, "spec");
        this.currentScale = spec.scale();
        applyEntityScale(currentScale);
    }

    public VanillaActorController(Location propAnchor, ActorModelSpec spec) {
        this.entity = null;
        this.propAnchor = Objects.requireNonNull(propAnchor, "propAnchor").clone();
        this.spec = Objects.requireNonNull(spec, "spec");
        this.currentScale = spec.scale();
    }

    @Override
    public ModelRendererType rendererType() {
        return ModelRendererType.VANILLA;
    }

    @Override
    public boolean playAnimation(String animationKey, double speed, boolean loop) {
        Objects.requireNonNull(animationKey, "animationKey");
        String clip = spec.resolveAnimationClip(animationKey);
        activeAnimations.add(clip);
        return true;
    }

    @Override
    public boolean stopAnimation(String animationKey) {
        Objects.requireNonNull(animationKey, "animationKey");
        String clip = spec.resolveAnimationClip(animationKey);
        return activeAnimations.remove(clip) | activeAnimations.remove(animationKey);
    }

    @Override
    public @Nullable Location boneLocation(String boneName) {
        Objects.requireNonNull(boneName, "boneName");
        Location origin = entity != null ? entity.getLocation() : propAnchor;
        BoneOffset offset = spec.boneOffsets().getOrDefault(boneName, BoneOffset.ZERO);
        return BoneMath.resolveWorldOffset(origin, offset, currentScale);
    }

    @Override
    public void setPosture(DragonPosture posture) {
        this.posture = Objects.requireNonNull(posture, "posture");
    }

    public DragonPosture posture() {
        return posture;
    }

    @Override
    public void setScale(double scale) {
        if (!Double.isFinite(scale) || scale <= 0.0) {
            return;
        }
        this.currentScale = scale;
        applyEntityScale(scale);
    }

    public double currentScale() {
        return currentScale;
    }

    private void applyEntityScale(double scale) {
        if (!(entity instanceof LivingEntity living) || Bukkit.getServer() == null) {
            return;
        }
        try {
            AttributeInstance attr = living.getAttribute(Attribute.SCALE);
            if (attr != null) {
                attr.setBaseValue(scale);
            }
        } catch (RuntimeException ignored) {
            // Entities without scale attributes safely ignore scale application.
        }
    }

    @Override
    public void close() {
        activeAnimations.clear();
    }
}
