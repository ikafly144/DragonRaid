package net.sabafly.dragonraid.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Port handling {@code /cf} ({@code /finder}, {@code /duty}) commands and GUI interactions.
 */
public interface ContentFinderCommandHandler {

    void openFinder(Player player);

    void applyContent(Player player, String contentId);

    void acceptReadyCheck(Player player);

    void declineReadyCheck(Player player);

    void cancelReadyCheck(Player player);

    void retryEncounter(Player player);

    void leaveEncounter(Player player);

    void onPlayerOnly(CommandSender sender);
}
