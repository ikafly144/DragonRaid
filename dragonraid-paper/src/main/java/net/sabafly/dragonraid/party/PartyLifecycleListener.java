package net.sabafly.dragonraid.party;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.api.party.PartyHudService;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.i18n.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges player join/quit lifecycle events to party disconnect grace seats and sidebar HUD refresh.
 */
public final class PartyLifecycleListener implements Listener {

    private final RaidPartyService partyService;
    private final PartyHudService hudService;
    private final MessageService messageService;
    private final Function<UUID, @Nullable Player> playerLookup;

    public PartyLifecycleListener(
            RaidPartyService partyService,
            PartyHudService hudService,
            MessageService messageService,
            Function<UUID, @Nullable Player> playerLookup) {
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.hudService = Objects.requireNonNull(hudService, "hudService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        RaidParty before = partyService.partyOf(playerId).orElse(null);
        boolean marked = partyService.markDisconnected(playerId);
        hudService.clearViewer(playerId);
        if (marked && before != null) {
            RaidParty after = partyService.partyById(before.partyId()).orElse(null);
            boolean leaderChanged = after != null && !after.leaderId().equals(before.leaderId());
            String newLeaderName = "";
            if (leaderChanged) {
                Player nextLeader = playerLookup.apply(after.leaderId());
                newLeaderName = nextLeader != null ? nextLeader.getName() : after.leaderId().toString().substring(0, 8);
            }
            for (UUID memberId : before.memberIds()) {
                if (!memberId.equals(playerId)) {
                    Player member = playerLookup.apply(memberId);
                    if (member != null && member.isOnline()) {
                        messageService.send(
                                member,
                                "party.disconnected-member",
                                Placeholder.unparsed("player", player.getName())
                        );
                        if (leaderChanged) {
                            messageService.send(
                                    member,
                                    "party.leader-transferred",
                                    Placeholder.unparsed("leader", newLeaderName)
                            );
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean reconnected = partyService.reconnect(playerId);
        if (reconnected) {
            RaidParty party = partyService.partyOf(playerId).orElse(null);
            if (party != null) {
                messageService.send(player, "party.reconnected-self");
                for (UUID memberId : party.memberIds()) {
                    if (!memberId.equals(playerId)) {
                        Player other = playerLookup.apply(memberId);
                        if (other != null && other.isOnline()) {
                            messageService.send(
                                    other,
                                    "party.reconnected-member",
                                    Placeholder.unparsed("player", player.getName())
                            );
                        }
                    }
                }
                hudService.refreshParty(party.partyId());
            }
        } else if (partyService.partyOf(playerId).isPresent()) {
            hudService.refreshViewer(playerId);
        }
    }
}
