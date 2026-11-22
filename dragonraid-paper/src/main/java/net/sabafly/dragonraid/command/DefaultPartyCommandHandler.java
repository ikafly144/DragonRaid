package net.sabafly.dragonraid.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.api.party.PartyChatChannel;
import net.sabafly.dragonraid.api.party.PartyHudService;
import net.sabafly.dragonraid.api.party.PartyInvite;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.party.PartyChatDispatcher;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Default Paper implementation of {@link PartyCommandHandler}.
 */
public final class DefaultPartyCommandHandler implements PartyCommandHandler {

    private final RaidPartyService partyService;
    private final PartyHudService hudService;
    private final PartyChatDispatcher chatDispatcher;
    private final MessageService messageService;
    private final Function<String, Optional<Player>> playerByName;
    private final Function<UUID, Optional<Player>> playerById;

    public DefaultPartyCommandHandler(
            RaidPartyService partyService,
            PartyHudService hudService,
            PartyChatDispatcher chatDispatcher,
            MessageService messageService,
            Function<String, Optional<Player>> playerByName,
            Function<UUID, Optional<Player>> playerById) {
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.hudService = Objects.requireNonNull(hudService, "hudService");
        this.chatDispatcher = Objects.requireNonNull(chatDispatcher, "chatDispatcher");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.playerByName = Objects.requireNonNull(playerByName, "playerByName");
        this.playerById = Objects.requireNonNull(playerById, "playerById");
    }

    @Override
    public void showPartyInfo(Player player) {
        Objects.requireNonNull(player, "player");
        Optional<RaidParty> partyOpt = partyService.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            messageService.send(player, "party.not-in-party");
            return;
        }
        RaidParty party = partyOpt.get();
        Set<UUID> disconnected = partyService.disconnectedMemberIds(party.partyId());
        messageService.send(
                player,
                "party.info-header",
                Placeholder.unparsed("size", String.valueOf(party.size())),
                Placeholder.unparsed("max", String.valueOf(partyService.maxPartySize())),
                Placeholder.unparsed("channel", partyService.chatChannel(player.getUniqueId()).name())
        );
        for (UUID memberId : party.memberIds()) {
            String name = resolveName(memberId);
            String badge = memberId.equals(party.leaderId()) ? "★" : "•";
            String status = disconnected.contains(memberId) ? "OFFLINE" : "ONLINE";
            messageService.send(
                    player,
                    "party.info-member",
                    Placeholder.unparsed("badge", badge),
                    Placeholder.unparsed("player", name),
                    Placeholder.unparsed("status", status)
            );
        }
    }

    @Override
    public void createParty(Player player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        if (partyService.partyOf(playerId).isPresent()) {
            messageService.send(player, "party.already-in-party");
            return;
        }
        if (partyService.isMutationLocked(playerId)) {
            messageService.send(player, "party.locked-in-duty");
            return;
        }
        try {
            partyService.createParty(playerId);
        } catch (IllegalStateException ex) {
            messageService.send(player, partyService.isMutationLocked(playerId) ? "party.locked-in-duty" : "party.already-in-party");
            return;
        }
        messageService.send(player, "party.created");
    }

    @Override
    public void invitePlayer(Player player, String targetName) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetName, "targetName");
        UUID playerId = player.getUniqueId();
        Player target = playerByName.apply(targetName).orElse(null);
        if (target == null || !target.isOnline()) {
            messageService.send(player, "command.player-not-found", Placeholder.unparsed("target", targetName));
            return;
        }
        UUID targetId = target.getUniqueId();
        if (playerId.equals(targetId)) {
            messageService.send(player, "party.cannot-invite-self");
            return;
        }
        if (partyService.isMutationLocked(playerId) || partyService.isMutationLocked(targetId)) {
            messageService.send(player, "party.locked-in-duty");
            return;
        }
        if (partyService.partyOf(targetId).isPresent()) {
            messageService.send(player, "party.target-already-in-party", Placeholder.unparsed("target", target.getName()));
            return;
        }

        Optional<RaidParty> existing = partyService.partyOf(playerId);
        if (existing.isEmpty()) {
            // D5: Auto-create party before sending invite when inviter is solo and unlocked.
            try {
                partyService.createParty(playerId);
            } catch (IllegalStateException ex) {
                messageService.send(player, partyService.isMutationLocked(playerId) ? "party.locked-in-duty" : "party.already-in-party");
                return;
            }
            messageService.send(player, "party.created");
        } else if (!existing.get().leaderId().equals(playerId)) {
            messageService.send(player, "party.not-leader");
            return;
        } else if (existing.get().size() >= partyService.maxPartySize()) {
            messageService.send(player, "party.full", Placeholder.unparsed("max", String.valueOf(partyService.maxPartySize())));
            return;
        }

        if (!partyService.invite(playerId, targetId)) {
            messageService.send(player, "party.invite-failed", Placeholder.unparsed("target", target.getName()));
            return;
        }
        messageService.send(player, "party.invite-sent", Placeholder.unparsed("target", target.getName()));
        messageService.send(
                target,
                "party.invite-received",
                Placeholder.unparsed("leader", player.getName())
        );
    }

    @Override
    public void acceptInvite(Player player, @Nullable String leaderName) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        if (partyService.isMutationLocked(playerId)) {
            messageService.send(player, "party.locked-in-duty");
            return;
        }
        if (partyService.partyOf(playerId).isPresent()) {
            messageService.send(player, "party.already-in-party");
            return;
        }
        PartyInvite chosen = resolvePendingInvite(playerId, leaderName);
        if (chosen == null) {
            messageService.send(player, "party.no-pending-invite");
            return;
        }
        if (!partyService.acceptInvite(playerId, chosen.partyId())) {
            messageService.send(player, "party.invite-accept-failed");
            return;
        }
        String leaderDisplay = resolveName(chosen.inviterId());
        messageService.send(player, "party.joined-self", Placeholder.unparsed("leader", leaderDisplay));
        partyService.partyOf(playerId).ifPresent(party -> {
            for (UUID memberId : party.memberIds()) {
                if (!memberId.equals(playerId)) {
                    playerById.apply(memberId).ifPresent(other ->
                            messageService.send(other, "party.joined-member", Placeholder.unparsed("player", player.getName())));
                }
            }
        });
    }

    @Override
    public void declineInvite(Player player, @Nullable String leaderName) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        PartyInvite chosen = resolvePendingInvite(playerId, leaderName);
        if (chosen == null || !partyService.declineInvite(playerId, chosen.partyId())) {
            messageService.send(player, "party.no-pending-invite");
            return;
        }
        String leaderDisplay = resolveName(chosen.inviterId());
        messageService.send(player, "party.invite-declined-self", Placeholder.unparsed("leader", leaderDisplay));
        playerById.apply(chosen.inviterId()).ifPresent(inviter ->
                messageService.send(inviter, "party.invite-declined-leader", Placeholder.unparsed("target", player.getName())));
    }

    @Override
    public void leaveParty(Player player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        Optional<RaidParty> beforeOpt = partyService.partyOf(playerId);
        if (beforeOpt.isEmpty()) {
            messageService.send(player, "party.not-in-party");
            return;
        }
        if (partyService.isMutationLocked(playerId)) {
            messageService.send(player, "party.locked-in-duty");
            return;
        }
        if (!partyService.leave(playerId)) {
            messageService.send(player, "party.locked-in-duty");
            return;
        }
        messageService.send(player, "party.left-self");
        for (UUID memberId : beforeOpt.get().memberIds()) {
            if (!memberId.equals(playerId)) {
                playerById.apply(memberId).ifPresent(other ->
                        messageService.send(other, "party.left-member", Placeholder.unparsed("player", player.getName())));
            }
        }
    }

    @Override
    public void kickPlayer(Player player, String targetName) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetName, "targetName");
        UUID leaderId = player.getUniqueId();
        Optional<RaidParty> partyOpt = partyService.partyOf(leaderId);
        if (partyOpt.isEmpty()) {
            messageService.send(player, "party.not-in-party");
            return;
        }
        if (!partyOpt.get().leaderId().equals(leaderId)) {
            messageService.send(player, "party.not-leader");
            return;
        }
        if (partyService.isMutationLocked(leaderId)) {
            messageService.send(player, "party.locked-in-duty");
            return;
        }
        UUID targetId = findMemberIdByName(partyOpt.get(), targetName);
        if (targetId == null) {
            messageService.send(player, "party.member-not-found", Placeholder.unparsed("target", targetName));
            return;
        }
        if (!partyService.kick(leaderId, targetId)) {
            messageService.send(player, "party.kick-failed");
            return;
        }
        String resolvedName = resolveName(targetId);
        messageService.send(player, "party.kicked-leader", Placeholder.unparsed("target", resolvedName));
        playerById.apply(targetId).ifPresent(targetPlayer -> messageService.send(targetPlayer, "party.kicked-self"));
    }

    @Override
    public void transferLeadership(Player player, String newLeaderName) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(newLeaderName, "newLeaderName");
        UUID currentLeaderId = player.getUniqueId();
        Optional<RaidParty> partyOpt = partyService.partyOf(currentLeaderId);
        if (partyOpt.isEmpty()) {
            messageService.send(player, "party.not-in-party");
            return;
        }
        if (!partyOpt.get().leaderId().equals(currentLeaderId)) {
            messageService.send(player, "party.not-leader");
            return;
        }
        UUID targetId = findMemberIdByName(partyOpt.get(), newLeaderName);
        if (targetId == null) {
            messageService.send(player, "party.member-not-found", Placeholder.unparsed("target", newLeaderName));
            return;
        }
        if (!partyService.transferLeadership(currentLeaderId, targetId)) {
            messageService.send(player, "party.transfer-failed");
            return;
        }
        String targetDisplay = resolveName(targetId);
        for (UUID memberId : partyOpt.get().memberIds()) {
            playerById.apply(memberId).ifPresent(other ->
                    messageService.send(other, "party.leader-transferred", Placeholder.unparsed("leader", targetDisplay)));
        }
    }

    @Override
    public void disbandParty(Player player) {
        Objects.requireNonNull(player, "player");
        UUID leaderId = player.getUniqueId();
        Optional<RaidParty> beforeOpt = partyService.partyOf(leaderId);
        if (beforeOpt.isEmpty()) {
            messageService.send(player, "party.not-in-party");
            return;
        }
        if (!beforeOpt.get().leaderId().equals(leaderId)) {
            messageService.send(player, "party.not-leader");
            return;
        }
        if (partyService.isMutationLocked(leaderId) || !partyService.disband(leaderId)) {
            messageService.send(player, "party.locked-in-duty");
            return;
        }
        for (UUID memberId : beforeOpt.get().memberIds()) {
            playerById.apply(memberId).ifPresent(member -> messageService.send(member, "party.disbanded"));
        }
    }

    @Override
    public void setChatChannel(Player player, String channelName) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(channelName, "channelName");
        if (partyService.partyOf(player.getUniqueId()).isEmpty()) {
            messageService.send(player, "party.not-in-party");
            return;
        }
        PartyChatChannel channel = switch (channelName.trim().toLowerCase(Locale.ROOT)) {
            case "p", "party" -> PartyChatChannel.PARTY;
            case "g", "global" -> PartyChatChannel.GLOBAL;
            default -> null;
        };
        if (channel == null) {
            messageService.send(player, "command.invalid-usage", Placeholder.unparsed("usage", "/party chat [party|global]"));
            return;
        }
        partyService.setChatChannel(player.getUniqueId(), channel);
        sendChannelStatus(player, channel);
    }

    @Override
    public void toggleChatChannel(Player player) {
        Objects.requireNonNull(player, "player");
        if (partyService.partyOf(player.getUniqueId()).isEmpty()) {
            messageService.send(player, "party.not-in-party");
            return;
        }
        PartyChatChannel next = partyService.toggleChatChannel(player.getUniqueId());
        sendChannelStatus(player, next);
    }

    @Override
    public void setHudEnabled(Player player, boolean enabled) {
        Objects.requireNonNull(player, "player");
        hudService.setHudEnabled(player.getUniqueId(), enabled);
        messageService.send(player, enabled ? "party.hud-enabled" : "party.hud-disabled");
    }

    @Override
    public void toggleHud(Player player) {
        Objects.requireNonNull(player, "player");
        boolean next = !hudService.isHudEnabled(player.getUniqueId());
        setHudEnabled(player, next);
    }

    @Override
    public void sendPartyChat(Player player, String message) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            messageService.send(player, "command.invalid-usage", Placeholder.unparsed("usage", "/pc <message>"));
            return;
        }
        if (!chatDispatcher.sendPartyMessage(player, Component.text(message))) {
            messageService.send(player, "party.not-in-party");
        }
    }

    @Override
    public void onPlayerOnly(CommandSender sender) {
        messageService.send(sender, "command.player-only");
    }

    private void sendChannelStatus(Player player, PartyChatChannel channel) {
        messageService.send(
                player,
                channel == PartyChatChannel.PARTY ? "party.chat-channel-party" : "party.chat-channel-global"
        );
    }

    private @Nullable PartyInvite resolvePendingInvite(UUID playerId, @Nullable String leaderName) {
        List<PartyInvite> invites = partyService.pendingInvites(playerId);
        if (invites.isEmpty()) {
            return null;
        }
        if (leaderName == null || leaderName.isBlank()) {
            return invites.getLast();
        }
        Optional<Player> namedOnline = playerByName.apply(leaderName);
        if (namedOnline.isPresent()) {
            UUID inviterId = namedOnline.get().getUniqueId();
            for (int i = invites.size() - 1; i >= 0; i--) {
                if (invites.get(i).inviterId().equals(inviterId)) {
                    return invites.get(i);
                }
            }
        }
        for (int i = invites.size() - 1; i >= 0; i--) {
            if (resolveName(invites.get(i).inviterId()).equalsIgnoreCase(leaderName)) {
                return invites.get(i);
            }
        }
        return null;
    }

    private @Nullable UUID findMemberIdByName(RaidParty party, String targetName) {
        Optional<Player> online = playerByName.apply(targetName);
        if (online.isPresent() && party.memberIds().contains(online.get().getUniqueId())) {
            return online.get().getUniqueId();
        }
        for (UUID memberId : party.memberIds()) {
            if (resolveName(memberId).equalsIgnoreCase(targetName)) {
                return memberId;
            }
        }
        return null;
    }

    private String resolveName(UUID playerId) {
        return playerById.apply(playerId)
                .map(Player::getName)
                .orElseGet(() -> playerId.toString().substring(0, 8));
    }
}
