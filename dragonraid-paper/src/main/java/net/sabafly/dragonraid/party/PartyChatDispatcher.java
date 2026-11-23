package net.sabafly.dragonraid.party;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.i18n.MessageService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Shared thread-safe dispatcher rendering and delivering party chat messages from
 * {@code /pc <message>} and {@link PartyChatListener}.
 */
public final class PartyChatDispatcher {

    private final RaidPartyService partyService;
    private final MessageService messageService;
    private final Function<UUID, @Nullable Player> playerLookup;

    public PartyChatDispatcher(
            RaidPartyService partyService,
            MessageService messageService,
            Function<UUID, @Nullable Player> playerLookup) {
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
    }

    public boolean sendPartyMessage(Player sender, Component messageContent) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(messageContent, "messageContent");
        RaidParty party = partyService.partyOf(sender.getUniqueId()).orElse(null);
        if (party == null) {
            return false;
        }
        boolean isLeader = party.leaderId().equals(sender.getUniqueId());
        String leaderBadge = isLeader ? "★" : "";
        for (UUID memberId : party.memberIds()) {
            Player member = playerLookup.apply(memberId);
            if (member != null && member.isOnline()) {
                Component formatted = messageService.render(
                        member,
                        "party.chat-format",
                        Placeholder.unparsed("leader_badge", leaderBadge),
                        Placeholder.unparsed("sender", sender.getName()),
                        Placeholder.component("message", messageContent)
                );
                member.sendMessage(formatted);
            }
        }
        return true;
    }
}
