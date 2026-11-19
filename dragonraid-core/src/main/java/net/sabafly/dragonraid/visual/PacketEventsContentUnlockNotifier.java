package net.sabafly.dragonraid.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.advancements.Advancement;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementDisplay;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementHolder;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementProgress;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAdvancements;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotification;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotifier;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ContentUnlockNotifier} delivering Adventure Title/Subtitle/Sound/Chat announcements
 * together with a transient client-side PacketEvents advancement Toast.
 */
public final class PacketEventsContentUnlockNotifier implements ContentUnlockNotifier {

    private static final Title.Times UNLOCK_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(300),
            Duration.ofMillis(2600),
            Duration.ofMillis(600)
    );

    @FunctionalInterface
    public interface ToastTransport {
        void sendUnlockToast(Player player, Component title, Component description, String iconMaterial, String contentId);
    }

    private final MessageService messageService;
    private final RaidScheduler scheduler;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final ToastTransport toastTransport;
    private final Logger logger;

    public PacketEventsContentUnlockNotifier(
            MessageService messageService,
            RaidScheduler scheduler,
            Logger logger) {
        this(messageService, scheduler, Bukkit::getPlayer, new PacketEventsToastTransport(logger), logger);
    }

    public PacketEventsContentUnlockNotifier(
            MessageService messageService,
            RaidScheduler scheduler,
            Function<UUID, @Nullable Player> playerLookup,
            Logger logger) {
        this(messageService, scheduler, playerLookup, new PacketEventsToastTransport(logger), logger);
    }

    public PacketEventsContentUnlockNotifier(
            MessageService messageService,
            RaidScheduler scheduler,
            Function<UUID, @Nullable Player> playerLookup,
            ToastTransport toastTransport,
            Logger logger) {
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.toastTransport = Objects.requireNonNull(toastTransport, "toastTransport");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void notifyContentUnlocked(UUID playerId, ContentUnlockNotification notification) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(notification, "notification");
        Player player = playerLookup.apply(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            Component contentName = resolveContentName(player, notification);
            TagResolver resolver = TagResolver.resolver(
                    Placeholder.component("content", contentName),
                    Placeholder.unparsed("content_id", notification.contentId())
            );

            Component titleComp = messageService.render(player, notification.titleKey(), resolver);
            Component subtitleComp = messageService.render(player, notification.subtitleKey(), resolver);
            player.showTitle(Title.title(titleComp, subtitleComp, UNLOCK_TITLE_TIMES));

            Key soundKey = parseSoundKey(notification.soundKey());
            player.playSound(Sound.sound(
                    soundKey,
                    Sound.Source.MASTER,
                    notification.soundVolume(),
                    notification.soundPitch()
            ));

            messageService.send(player, "content.unlock.chat", resolver);

            Component toastTitle = messageService.render(player, notification.toastTitleKey(), resolver);
            Component toastDesc = messageService.render(player, notification.toastDescriptionKey(), resolver);
            try {
                toastTransport.sendUnlockToast(player, toastTitle, toastDesc, notification.iconMaterial(), notification.contentId());
            } catch (NoClassDefFoundError | RuntimeException ex) {
                logger.log(Level.FINE, "PacketEvents unlock toast skipped for " + player.getName(), ex);
            }
        });
    }

    private Component resolveContentName(Player player, ContentUnlockNotification notification) {
        String key = notification.displayNameKey();
        if (key.isBlank()) {
            return Component.text(notification.contentId());
        }
        String locale = messageService.resolveLocale(player);
        if (messageService.hasKey(locale, key) || messageService.hasKey("en_US", key)) {
            return messageService.render(player, key);
        }
        return Component.text(key);
    }

    private static Key parseSoundKey(String rawSound) {
        try {
            return Key.key(rawSound);
        } catch (RuntimeException ex) {
            return Key.key(ContentUnlockNotification.DEFAULT_SOUND_KEY);
        }
    }

    private static final class PacketEventsToastTransport implements ToastTransport {

        private static final AtomicLong SEQUENCE = new AtomicLong(0L);
        private final Logger logger;

        private PacketEventsToastTransport(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void sendUnlockToast(
                Player player,
                Component title,
                Component description,
                String iconMaterial,
                String contentId) {
            PacketEventsAPI<?> api;
            try {
                api = PacketEvents.getAPI();
            } catch (NoClassDefFoundError ex) {
                return;
            }
            if (api == null || !api.isInitialized()) {
                return;
            }
            ItemType iconType = resolveItemType(iconMaterial);
            ItemStack icon = ItemStack.builder().type(iconType).amount(1).build();
            ResourceLocation toastId = new ResourceLocation(
                    "dragonraid",
                    "toast/" + sanitizePath(contentId) + "_" + SEQUENCE.incrementAndGet()
            );
            AdvancementDisplay display = new AdvancementDisplay(
                    title,
                    description,
                    icon,
                    AdvancementType.GOAL,
                    null,
                    true,
                    true
            );
            Advancement advancement = new Advancement(
                    null,
                    display,
                    List.of("unlock"),
                    List.of(List.of("unlock")),
                    false
            );
            AdvancementProgress progress = new AdvancementProgress(
                    Map.of("unlock", new AdvancementProgress.CriterionProgress(System.currentTimeMillis()))
            );
            WrapperPlayServerUpdateAdvancements showPacket = new WrapperPlayServerUpdateAdvancements(
                    false,
                    List.of(new AdvancementHolder(toastId, advancement)),
                    Set.of(),
                    Map.of(toastId, progress),
                    true
            );
            WrapperPlayServerUpdateAdvancements clearPacket = new WrapperPlayServerUpdateAdvancements(
                    false,
                    List.of(),
                    Set.of(toastId),
                    Map.of(),
                    false
            );
            try {
                api.getPlayerManager().sendPacket(player, showPacket);
                api.getPlayerManager().sendPacket(player, clearPacket);
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to transmit PacketEvents unlock toast packet", ex);
            }
        }

        private static ItemType resolveItemType(String iconMaterial) {
            if (iconMaterial != null && !iconMaterial.isBlank()) {
                String normalized = iconMaterial.trim().toLowerCase(Locale.ROOT);
                if (!normalized.contains(":")) {
                    normalized = "minecraft:" + normalized;
                }
                ItemType resolved = ItemTypes.getByName(normalized);
                if (resolved != null) {
                    return resolved;
                }
            }
            return ItemTypes.ENDER_EYE;
        }

        private static String sanitizePath(String contentId) {
            String lower = contentId.toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder(lower.length());
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                    sb.append(c);
                } else {
                    sb.append('_');
                }
            }
            return sb.isEmpty() ? "unlock" : sb.toString();
        }
    }
}
