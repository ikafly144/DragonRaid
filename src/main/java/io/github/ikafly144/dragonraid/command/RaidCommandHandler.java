package io.github.ikafly144.dragonraid.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Decoupled port interface invoked by {@link DragonRaidCommandRegistrar} after
 * Brigadier parsing, argument validation, and permission checks succeed.
 */
public interface RaidCommandHandler {

    /**
     * Sub-actions for {@code /dr admin region <action>}.
     */
    enum RegionAction {
        INFO("info"),
        SET_ARENA("set-arena"),
        SET_SAFEZONE("set-safezone");

        private final String cliName;

        RegionAction(String cliName) {
            this.cliName = cliName;
        }

        /**
         * Returns the CLI literal name for this action.
         *
         * @return literal name
         */
        public String cliName() {
            return cliName;
        }
    }

    /**
     * Sub-actions for {@code /dr admin points <action> ...}.
     */
    enum PointsAction {
        ADD("add"),
        SET("set"),
        TAKE("take");

        private final String cliName;

        PointsAction(String cliName) {
            this.cliName = cliName;
        }

        /**
         * Returns the CLI literal name for this action.
         *
         * @return literal name
         */
        public String cliName() {
            return cliName;
        }
    }

    /**
     * Raid difficulties selectable via {@code /dr admin start <difficulty>}.
     */
    enum Difficulty {
        NORMAL("normal"),
        HARD("hard");

        private final String cliName;

        Difficulty(String cliName) {
            this.cliName = cliName;
        }

        /**
         * Returns the CLI literal name for this difficulty.
         *
         * @return literal name
         */
        public String cliName() {
            return cliName;
        }
    }

    /**
     * Opens the DragonRaid reward exchange shop GUI for the player.
     *
     * @param player the executing player
     */
    void openShop(Player player);

    /**
     * Displays raid statistics and points for the sender or a target player.
     *
     * @param sender the command sender
     * @param targetPlayerName target player name, or {@code null} for the sender's own stats
     */
    void showStats(CommandSender sender, @Nullable String targetPlayerName);

    /**
     * Toggles ready status for the executing player before a raid begins.
     *
     * @param player the executing player
     */
    void ready(Player player);

    /**
     * Triggers a raid start countdown from the command sender.
     *
     * @param sender the command sender
     */
    void start(CommandSender sender);

    /**
     * Views or updates DragonRaid arena/safe-zone region boundaries.
     *
     * @param sender the command sender
     * @param location the sender/source location (used for {@code set-arena} / {@code set-safezone})
     * @param action the region action
     */
    void adminRegion(CommandSender sender, @Nullable Location location, RegionAction action);

    /**
     * Force-starts a DragonRaid encounter at the specified difficulty.
     *
     * @param sender the command sender
     * @param difficulty the target difficulty
     */
    void adminStart(CommandSender sender, Difficulty difficulty);

    /**
     * Force-resets any active DragonRaid encounter and restores temporary arena state.
     *
     * @param sender the command sender
     */
    void adminReset(CommandSender sender);

    /**
     * Modifies a player's virtual points balance.
     *
     * @param sender the command sender
     * @param action add, set, or take
     * @param targetPlayerName target player name
     * @param currency optional currency ID, or {@code null} for the default currency
     * @param amount point amount
     */
    void adminPoints(
        CommandSender sender,
        PointsAction action,
        String targetPlayerName,
        @Nullable String currency,
        int amount
    );

    /**
     * Reloads DragonRaid YAML configuration and language files.
     *
     * @param sender the command sender
     */
    void adminReload(CommandSender sender);

    /**
     * Sends command usage summary when {@code /dr} is invoked without subcommands.
     *
     * @param sender the command sender
     */
    default void showHelp(CommandSender sender) {
        sender.sendMessage(Component.text(
            "DragonRaid commands: /dr shop, /dr stats [player], /dr ready, /dr start, /dr admin <region|start|reset|points|reload>"
        ));
    }

    /**
     * Notifies the sender that a subcommand may only be executed by a player.
     *
     * @param sender the non-player sender
     */
    default void onPlayerOnly(CommandSender sender) {
        sender.sendMessage(Component.text("This command can only be used by players."));
    }

    /**
     * Notifies the sender that they lack permission for the requested operation.
     *
     * @param sender the command sender
     */
    default void onNoPermission(CommandSender sender) {
        sender.sendMessage(Component.text("You do not have permission to execute this command."));
    }
}
