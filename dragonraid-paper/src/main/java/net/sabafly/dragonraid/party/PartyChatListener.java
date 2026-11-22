package net.sabafly.dragonraid.party;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Objects;
import net.sabafly.dragonraid.api.party.PartyChatChannel;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Intercepts Paper {@link AsyncChatEvent} when a player's active chat channel is set to
 * {@link PartyChatChannel#PARTY}, cancelling global broadcast and routing only to online party members.
 */
public final class PartyChatListener implements Listener {

    private final RaidPartyService partyService;
    private final PartyChatDispatcher dispatcher;

    public PartyChatListener(RaidPartyService partyService, PartyChatDispatcher dispatcher) {
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        if (partyService.chatChannel(sender.getUniqueId()) != PartyChatChannel.PARTY) {
            return;
        }
        event.setCancelled(true);
        if (!dispatcher.sendPartyMessage(sender, event.message())) {
            // Player is no longer in a party; revert channel cleanly to GLOBAL so global chat is never trapped.
            partyService.setChatChannel(sender.getUniqueId(), PartyChatChannel.GLOBAL);
        }
    }
}
