package net.sabafly.dragonraid.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Port handling {@code /party} ({@code /p}) and {@code /pc} commands.
 */
public interface PartyCommandHandler {

    void showPartyInfo(Player player);

    void createParty(Player player);

    void invitePlayer(Player player, String targetName);

    void acceptInvite(Player player, String leaderName);

    void declineInvite(Player player, String leaderName);

    void leaveParty(Player player);

    void kickPlayer(Player player, String targetName);

    void transferLeadership(Player player, String newLeaderName);

    void disbandParty(Player player);

    void setChatChannel(Player player, String channelName);

    void toggleChatChannel(Player player);

    void setHudEnabled(Player player, boolean enabled);

    void toggleHud(Player player);

    void sendPartyChat(Player player, String message);

    void onPlayerOnly(CommandSender sender);
}
