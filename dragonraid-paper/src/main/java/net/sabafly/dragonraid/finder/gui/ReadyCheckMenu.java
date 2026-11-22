package net.sabafly.dragonraid.finder.gui;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ReadyCheckResponse;
import net.sabafly.dragonraid.api.finder.ReadyCheckSession;
import net.sabafly.dragonraid.i18n.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 27-slot FF14-style Ready Check confirmation GUI displaying duty info, countdown, Accept/Decline buttons,
 * and live per-member ready status heads. Closing the GUI does NOT decline.
 */
public final class ReadyCheckMenu implements InventoryHolder {

    public static final int INVENTORY_SIZE = 27;
    public static final int SLOT_INFO = 4;
    public static final int SLOT_ACCEPT = 11;
    public static final int SLOT_DECLINE = 15;
    private static final int MEMBER_START_SLOT = 18;

    private final Player viewer;
    private final ContentFinderService finderService;
    private final MessageService messageService;
    private final FinderMenuFactory menuFactory;
    private final Function<UUID, @Nullable Player> playerLookup;
    private final Inventory inventory;

    public ReadyCheckMenu(
            Player viewer,
            ReadyCheckSession session,
            long nowMillis,
            ContentFinderService finderService,
            MessageService messageService,
            FinderMenuFactory menuFactory,
            Function<UUID, @Nullable Player> playerLookup) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.finderService = Objects.requireNonNull(finderService, "finderService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.menuFactory = Objects.requireNonNull(menuFactory, "menuFactory");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.inventory = menuFactory.createInventory(
                this,
                INVENTORY_SIZE,
                messageService.render(viewer, "finder.readycheck.gui-title")
        );
        refresh(session, nowMillis);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID viewerId() {
        return viewer.getUniqueId();
    }

    public void refresh(ReadyCheckSession session, long nowMillis) {
        inventory.clear();
        ItemStack border = menuFactory.createItem("BLACK_STAINED_GLASS_PANE", Component.text(" "), List.of(), false);
        for (int i = 0; i < 18; i++) {
            inventory.setItem(i, border);
        }

        ContentEntrySpec spec = finderService.getContent(session.contentId()).orElse(null);
        Component dutyName = resolveContentTitle(spec, session.contentId());
        long remainingSec = Math.max(0L, (long) Math.ceil(session.remainingMillis(nowMillis) / 1000.0));
        inventory.setItem(SLOT_INFO, menuFactory.createItem(
                spec != null ? spec.iconMaterial() : ContentEntrySpec.DEFAULT_ICON_MATERIAL,
                dutyName,
                List.of(
                        messageService.render(
                                viewer,
                                "finder.readycheck.gui-remaining",
                                Placeholder.unparsed("seconds", String.valueOf(remainingSec)),
                                Placeholder.unparsed("accepted", String.valueOf(session.acceptedCount())),
                                Placeholder.unparsed("total", String.valueOf(session.participantIds().size()))
                        )
                ),
                true
        ));

        ReadyCheckResponse ownResponse = session.responses()
                .getOrDefault(viewer.getUniqueId(), ReadyCheckResponse.PENDING);
        inventory.setItem(SLOT_ACCEPT, menuFactory.createItem(
                ownResponse == ReadyCheckResponse.ACCEPTED ? "LIME_STAINED_GLASS_PANE" : "EMERALD_BLOCK",
                messageService.render(viewer, "finder.readycheck.accept-button"),
                List.of(messageService.render(
                        viewer,
                        ownResponse == ReadyCheckResponse.ACCEPTED
                                ? "finder.readycheck.already-accepted"
                                : "finder.readycheck.accept-lore"
                )),
                ownResponse != ReadyCheckResponse.ACCEPTED
        ));

        inventory.setItem(SLOT_DECLINE, menuFactory.createItem(
                "BARRIER",
                messageService.render(viewer, "finder.readycheck.decline-button"),
                List.of(messageService.render(viewer, "finder.readycheck.decline-lore")),
                false
        ));

        List<UUID> participants = session.participantIds();
        int count = Math.min(8, participants.size());
        for (int i = 0; i < count; i++) {
            UUID memberId = participants.get(i);
            Player onlineMember = playerLookup.apply(memberId);
            String memberName = onlineMember != null ? onlineMember.getName() : memberId.toString().substring(0, 8);
            ReadyCheckResponse response = session.responses().getOrDefault(memberId, ReadyCheckResponse.PENDING);
            String statusKey = switch (response) {
                case ACCEPTED -> "finder.readycheck.status-accepted";
                case DECLINED -> "finder.readycheck.status-declined";
                case PENDING -> "finder.readycheck.status-pending";
            };
            inventory.setItem(
                    MEMBER_START_SLOT + i,
                    menuFactory.createPlayerHead(
                            memberId,
                            memberName,
                            Component.text(memberName),
                            List.of(messageService.render(viewer, statusKey))
                    )
            );
        }
    }

    private Component resolveContentTitle(@Nullable ContentEntrySpec spec, String contentId) {
        if (spec == null || spec.displayNameKey().isBlank()) {
            return Component.text(contentId);
        }
        String key = spec.displayNameKey();
        String locale = messageService.resolveLocale(viewer);
        if (messageService.hasKey(locale, key) || messageService.hasKey("en_US", key)) {
            return messageService.render(viewer, key);
        }
        return Component.text(key);
    }
}
