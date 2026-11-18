package net.sabafly.dragonraid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import net.sabafly.dragonraid.api.enemy.BoneOffset;
import net.sabafly.dragonraid.api.enemy.ModelRendererType;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.boss.DragonPosture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DefaultActorModelServiceTest {

    @Test
    void vanillaRendererComputesScaledBoneLocationAndManagesAnimations() {
        World world = mock(World.class);
        Entity entity = mock(Entity.class);
        // yaw = 0 faces +Z, right is -X
        when(entity.getLocation()).thenReturn(new Location(world, 10.0, 64.0, 20.0, 0.0f, 0.0f));

        ActorModelSpec spec = ActorModelSpec.vanilla(
                2.0,
                Map.of("mouth", new BoneOffset(1.0, 2.0, 3.0))
        );
        DefaultActorModelService service = new DefaultActorModelService(Logger.getLogger("ModelTest"));
        ActorModelController controller = service.bindEntity(entity, spec);

        assertEquals(ModelRendererType.VANILLA, controller.rendererType());
        Location mouth = controller.boneLocation("mouth");
        assertNotNull(mouth);
        assertEquals(8.0, mouth.getX(), 1e-6);
        assertEquals(68.0, mouth.getY(), 1e-6);
        assertEquals(26.0, mouth.getZ(), 1e-6);

        assertTrue(controller.playAnimation("sweeping_laser", 1.2, false));
        assertTrue(controller.stopAnimation("sweeping_laser"));
        assertFalse(controller.stopAnimation("sweeping_laser"));
        controller.close();
    }

    @Test
    void betterModelRendererDrivesTrackerAnimationsBonesScaleAndPosture() {
        World world = mock(World.class);
        Entity entity = mock(Entity.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(new Location(world, 0.0, 70.0, 0.0));

        BetterModelActorController.TrackerHandle handle = mock(BetterModelActorController.TrackerHandle.class);
        when(handle.boneWorldPosition("mouth")).thenReturn(new Vector3f(4.5f, 75.0f, -2.5f));
        when(handle.animate("clip_breath", 1.5f, false)).thenReturn(true);
        when(handle.animate("dive_attack", 1.0f, true)).thenReturn(true);
        when(handle.stopAnimation("clip_breath")).thenReturn(true);

        ActorModelSpec spec = new ActorModelSpec(
                ModelRendererType.BETTER_MODEL,
                "ancient_dragon_bb",
                1.5,
                Map.of("mouth", BoneOffset.ZERO),
                Map.of("breath", "clip_breath")
        );

        BetterModelActorController controller = new BetterModelActorController(
                entity,
                null,
                spec,
                id -> "ancient_dragon_bb".equals(id) ? Optional.of(handle) : Optional.empty(),
                Logger.getLogger("BetterModelTest")
        );

        assertEquals(ModelRendererType.BETTER_MODEL, controller.rendererType());
        verify(handle).setScale(1.5f);

        Location mouth = controller.boneLocation("mouth");
        assertNotNull(mouth);
        assertEquals(4.5, mouth.getX(), 1e-4);
        assertEquals(75.0, mouth.getY(), 1e-4);
        assertEquals(-2.5, mouth.getZ(), 1e-4);

        assertTrue(controller.playAnimation("breath", 1.5, false));
        verify(handle).animate("clip_breath", 1.5f, false);
        assertTrue(controller.stopAnimation("breath"));
        controller.setPosture(DragonPosture.DIVING);
        verify(handle).animate("dive_attack", 1.0f, true);

        controller.setScale(2.2);
        verify(handle).setScale(2.2f);
        controller.close();
        verify(handle).close();
    }

    @Test
    void betterModelDegradesSafelyWithoutBootedPlatformUsingBoneOffset() {
        World world = mock(World.class);
        Location propAnchor = new Location(world, 5.0, 65.0, 5.0, 0.0f, 0.0f);
        ActorModelSpec spec = new ActorModelSpec(
                ModelRendererType.BETTER_MODEL,
                "unloaded_prop",
                1.0,
                Map.of("core", new BoneOffset(0.0, 2.0, 0.0)),
                Map.of()
        );
        DefaultActorModelService service = new DefaultActorModelService(Logger.getLogger("ModelFallbackTest"));
        ActorModelController controller = service.createProp(propAnchor, spec);

        assertEquals(ModelRendererType.BETTER_MODEL, controller.rendererType());
        Location core = controller.boneLocation("core");
        assertNotNull(core);
        assertEquals(67.0, core.getY(), 1e-6);
        assertFalse(controller.playAnimation("idle", 1.0, true));
        controller.close();
    }
}
