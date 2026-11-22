package net.sabafly.dragonraid.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.progression.VanillaEndAccessDecision;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.permission.RaidPermissions;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaEndPortalListenerTest {

    @TempDir
    Path tempDir;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        ConfigManager configManager = new ConfigManager(
                tempDir,
                name -> VanillaEndPortalListenerTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        messageService = new MessageService(configManager);
        messageService.reload();
    }

    @Test
    void triggersPortalTouchCancelsBlockedOverworldEntryAndAppliesThrottledFeedbackAndPushBack() {
        RaidAchievementService achievementService = mock(RaidAchievementService.class);
        when(achievementService.evaluateEndPortalTouch(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        VanillaEndProgressionPolicy policy = mock(VanillaEndProgressionPolicy.class);
        RaidInstanceManager instanceManager = mock(RaidInstanceManager.class);
        AtomicLong clock = new AtomicLong(10_000L);

        VanillaEndPortalListener listener = new VanillaEndPortalListener(
                achievementService, policy, instanceManager, messageService, clock::get, Logger.getLogger("test"));

        World overworld = mock(World.class);
        when(overworld.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(overworld.getName()).thenReturn("world");

        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Steve");
        when(player.getWorld()).thenReturn(overworld);
        when(player.locale()).thenReturn(Locale.US);
        when(player.getLocation()).thenReturn(new Location(overworld, 10, 64, 10, 0f, 0f));

        when(policy.evaluateVanillaEndAccess(playerId)).thenReturn(VanillaEndAccessDecision.deny(
                "progression.end-locked-server-first",
                Map.of("target", "dragon_normal"),
                true,
                true
        ));

        // 1st portal contact at t=10s -> triggers achievement, cancels portal, sends feedback + pushback
        EntityPortalEnterEvent enter1 = new EntityPortalEnterEvent(player, new Location(overworld, 10, 64, 10), PortalType.ENDER);
        listener.onEntityPortalEnter(enter1);
        assertTrue(enter1.isCancelled());
        verify(achievementService, times(1)).evaluateEndPortalTouch(playerId, "Steve", "world");
        verify(player, times(1)).sendMessage(any(Component.class));
        verify(player, times(1)).playSound(any(Sound.class));
        verify(player, times(1)).setVelocity(any(Vector.class));

        // 2nd contact 200ms later -> still cancelled, feedback & touch throttled
        clock.addAndGet(200L);
        EntityPortalReadyEvent ready2 = new EntityPortalReadyEvent(player, null, PortalType.ENDER);
        listener.onEntityPortalReady(ready2);
        assertTrue(ready2.isCancelled());
        verify(achievementService, times(1)).evaluateEndPortalTouch(playerId, "Steve", "world");
        verify(player, times(1)).sendMessage(any(Component.class));

        // Bypass permission allows entry through PlayerPortalEvent
        when(player.hasPermission(RaidPermissions.BYPASS_END_GATE)).thenReturn(true);
        PlayerPortalEvent portalBypass = new PlayerPortalEvent(
                player,
                new Location(overworld, 10, 64, 10),
                new Location(overworld, 100, 50, 0),
                TeleportCause.END_PORTAL
        );
        listener.onPlayerPortal(portalBypass);
        assertFalse(portalBypass.isCancelled());

        // Returning from THE_END is never cancelled and does not fire END_PORTAL_TOUCH
        clock.addAndGet(5000L);
        World endWorld = mock(World.class);
        when(endWorld.getEnvironment()).thenReturn(World.Environment.THE_END);
        when(endWorld.getName()).thenReturn("world_the_end");
        when(player.getWorld()).thenReturn(endWorld);
        when(player.hasPermission(RaidPermissions.BYPASS_END_GATE)).thenReturn(false);

        PlayerPortalEvent returnFromEnd = new PlayerPortalEvent(
                player,
                new Location(endWorld, 0, 64, 0),
                new Location(overworld, 0, 70, 0),
                TeleportCause.END_PORTAL
        );
        listener.onPlayerPortal(returnFromEnd);
        assertFalse(returnFromEnd.isCancelled());
        verify(achievementService, times(1)).evaluateEndPortalTouch(eq(playerId), any(), any());
    }

    @Test
    void allowsEntryWhenPolicyPermitsAndIgnoresManagedInstances() {
        RaidAchievementService achievementService = mock(RaidAchievementService.class);
        when(achievementService.evaluateEndPortalTouch(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        VanillaEndProgressionPolicy policy = mock(VanillaEndProgressionPolicy.class);
        RaidInstanceManager instanceManager = mock(RaidInstanceManager.class);

        VanillaEndPortalListener listener = new VanillaEndPortalListener(
                achievementService, policy, instanceManager, messageService, () -> 10_000L, Logger.getLogger("test"));

        World instanceWorld = mock(World.class);
        when(instanceWorld.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(instanceWorld.getName()).thenReturn("dragonraid_instance_1");

        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alex");
        when(player.getWorld()).thenReturn(instanceWorld);

        EntityPortalEnterEvent instancePortal = new EntityPortalEnterEvent(player, new Location(instanceWorld, 0, 64, 0), PortalType.ENDER);
        listener.onEntityPortalEnter(instancePortal);
        assertFalse(instancePortal.isCancelled());
        verify(achievementService, never()).evaluateEndPortalTouch(any(), any(), any());

        World overworld = mock(World.class);
        when(overworld.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(overworld.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(overworld);
        when(policy.evaluateVanillaEndAccess(playerId)).thenReturn(VanillaEndAccessDecision.allow());

        PlayerPortalEvent allowedPortal = new PlayerPortalEvent(
                player,
                new Location(overworld, 0, 64, 0),
                new Location(overworld, 100, 50, 0),
                TeleportCause.END_PORTAL
        );
        listener.onPlayerPortal(allowedPortal);
        assertFalse(allowedPortal.isCancelled());
        verify(achievementService, times(1)).evaluateEndPortalTouch(playerId, "Alex", "world");
    }
}
