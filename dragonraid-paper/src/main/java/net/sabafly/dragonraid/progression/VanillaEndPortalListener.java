package net.sabafly.dragonraid.progression;

import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.progression.VanillaEndAccessDecision;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.permission.RaidPermissions;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * Intercepts vanilla End portal contact and dimension transitions to evaluate
 * {@code END_PORTAL_TOUCH} achievement triggers and enforce {@link VanillaEndProgressionPolicy}.
 */
public final class VanillaEndPortalListener implements Listener {

    private static final long PORTAL_TOUCH_THROTTLE_MS = 1000L;
    private static final long DENIAL_FEEDBACK_THROTTLE_MS = 3000L;
    private static final long PUSH_BACK_THROTTLE_MS = 500L;

    private final RaidAchievementService achievementService;
    private final VanillaEndProgressionPolicy progressionPolicy;
    private final @Nullable RaidInstanceManager instanceManager;
    private final MessageService messageService;
    private final LongSupplier clockMillis;
    private final Logger logger;

    private final Map<UUID, Long> portalTouchCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> denialFeedbackCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pushBackCooldowns = new ConcurrentHashMap<>();

    public VanillaEndPortalListener(
            RaidAchievementService achievementService,
            VanillaEndProgressionPolicy progressionPolicy,
            @Nullable RaidInstanceManager instanceManager,
            MessageService messageService,
            Logger logger) {
        this(
                achievementService,
                progressionPolicy,
                instanceManager,
                messageService,
                System::currentTimeMillis,
                logger
        );
    }

    public VanillaEndPortalListener(
            RaidAchievementService achievementService,
            VanillaEndProgressionPolicy progressionPolicy,
            @Nullable RaidInstanceManager instanceManager,
            MessageService messageService,
            LongSupplier clockMillis,
            Logger logger) {
        this.achievementService = Objects.requireNonNull(achievementService, "achievementService");
        this.progressionPolicy = Objects.requireNonNull(progressionPolicy, "progressionPolicy");
        this.instanceManager = instanceManager;
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getPortalType() != PortalType.ENDER) {
            return;
        }
        handleEndPortalAttempt(player, null, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortalReady(EntityPortalReadyEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getPortalType() != PortalType.ENDER) {
            return;
        }
        handleEndPortalAttempt(player, event.getTargetWorld(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() != TeleportCause.END_PORTAL) {
            return;
        }
        World targetWorld = event.getTo() != null ? event.getTo().getWorld() : null;
        handleEndPortalAttempt(event.getPlayer(), targetWorld, event);
    }

    public void clearPlayerState(UUID playerId) {
        if (playerId != null) {
            portalTouchCooldowns.remove(playerId);
            denialFeedbackCooldowns.remove(playerId);
            pushBackCooldowns.remove(playerId);
        }
    }

    private void handleEndPortalAttempt(Player player, @Nullable World targetWorld, Cancellable event) {
        World currentWorld = player.getWorld();
        if (currentWorld != null && currentWorld.getEnvironment() == World.Environment.THE_END) {
            // Never interfere with the vanilla End return portal back to the Overworld.
            return;
        }
        if (currentWorld != null && (RaidInstanceManager.isManagedInstanceWorldName(currentWorld.getName())
                || (instanceManager != null && instanceManager.isInstanceWorld(currentWorld)))) {
            return;
        }
        if (targetWorld != null && (RaidInstanceManager.isManagedInstanceWorldName(targetWorld.getName())
                || (instanceManager != null && instanceManager.isInstanceWorld(targetWorld)))) {
            return;
        }

        UUID playerId = player.getUniqueId();
        long now = clockMillis.getAsLong();

        if (tryAcquireThrottle(portalTouchCooldowns, playerId, now, PORTAL_TOUCH_THROTTLE_MS)) {
            String worldName = currentWorld != null ? currentWorld.getName() : "";
            achievementService.evaluateEndPortalTouch(playerId, player.getName(), worldName)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to evaluate END_PORTAL_TOUCH for " + player.getName(), ex);
                        return java.util.List.of();
                    });
        }

        VanillaEndAccessDecision decision = progressionPolicy.evaluateVanillaEndAccess(playerId);
        if (decision == null || decision.allowed()) {
            return;
        }
        if (decision.allowBypassPermission() && player.hasPermission(RaidPermissions.BYPASS_END_GATE)) {
            return;
        }

        event.setCancelled(true);

        if (tryAcquireThrottle(denialFeedbackCooldowns, playerId, now, DENIAL_FEEDBACK_THROTTLE_MS)) {
            sendDenialFeedback(player, decision);
        }
        if (decision.pushBackOnDeny() && tryAcquireThrottle(pushBackCooldowns, playerId, now, PUSH_BACK_THROTTLE_MS)) {
            applyGentlePushBack(player);
        }
    }

    private void sendDenialFeedback(Player player, VanillaEndAccessDecision decision) {
        String key = decision.denialMessageKey().isBlank()
                ? "progression.end-locked-generic"
                : decision.denialMessageKey();
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> entry : decision.placeholders().entrySet()) {
            builder.resolver(Placeholder.unparsed(entry.getKey(), entry.getValue()));
        }
        messageService.send(player, key, builder.build());
        player.playSound(Sound.sound(
                Key.key("minecraft:entity.villager.no"),
                Sound.Source.MASTER,
                0.8f,
                0.9f
        ));
    }

    private static void applyGentlePushBack(Player player) {
        Location loc = player.getLocation();
        Vector direction = loc != null ? loc.getDirection() : null;
        Vector push = direction != null ? direction.clone().setY(0.0) : new Vector(0.0, 0.0, 0.0);
        if (push.lengthSquared() < 1.0e-4) {
            push = new Vector(0.0, 0.0, -0.55);
        } else {
            push.normalize().multiply(-0.55);
        }
        push.setY(0.42);
        player.setVelocity(push);
    }

    private static boolean tryAcquireThrottle(Map<UUID, Long> map, UUID playerId, long now, long intervalMs) {
        Long prev = map.get(playerId);
        if (prev != null && now - prev < intervalMs) {
            return false;
        }
        if (prev == null) {
            return map.putIfAbsent(playerId, now) == null;
        }
        return map.replace(playerId, prev, now);
    }
}
