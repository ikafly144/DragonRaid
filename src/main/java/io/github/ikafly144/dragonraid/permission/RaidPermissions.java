package io.github.ikafly144.dragonraid.permission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Permission node constants and evaluation helpers for DragonRaid.
 */
public final class RaidPermissions {

    public static final String USE = "dragonraid.use";
    public static final String SHOP = "dragonraid.shop";
    public static final String STATS = "dragonraid.stats";
    public static final String STATS_OTHERS = "dragonraid.stats.others";
    public static final String READY = "dragonraid.ready";
    public static final String START = "dragonraid.start";

    public static final String ADMIN = "dragonraid.admin";
    public static final String ADMIN_REGION = "dragonraid.admin.region";
    public static final String ADMIN_START = "dragonraid.admin.start";
    public static final String ADMIN_RESET = "dragonraid.admin.reset";
    public static final String ADMIN_POINTS = "dragonraid.admin.points";
    public static final String ADMIN_RELOAD = "dragonraid.admin.reload";

    private static final String ADMIN_PREFIX = ADMIN + ".";

    /**
     * All permission nodes defined by DragonRaid.
     */
    public static final List<String> ALL_NODES = List.of(
        USE,
        SHOP,
        STATS,
        STATS_OTHERS,
        READY,
        START,
        ADMIN,
        ADMIN_REGION,
        ADMIN_START,
        ADMIN_RESET,
        ADMIN_POINTS,
        ADMIN_RELOAD
    );

    private static final List<String> ADMIN_SUB_NODES = List.of(
        ADMIN_REGION,
        ADMIN_START,
        ADMIN_RESET,
        ADMIN_POINTS,
        ADMIN_RELOAD
    );

    private RaidPermissions() {
    }

    /**
     * Checks whether {@code sender} holds {@code permission}, inheriting {@link #ADMIN}
     * for {@code dragonraid.admin.*} sub-nodes unless explicitly negated.
     *
     * @param sender the command sender
     * @param permission the permission node to check
     * @return {@code true} if permitted
     */
    public static boolean hasPermission(CommandSender sender, String permission) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(permission, "permission");
        if (sender.hasPermission(permission)) {
            return true;
        }
        if (permission.startsWith(ADMIN_PREFIX) && !sender.isPermissionSet(permission)) {
            return sender.hasPermission(ADMIN);
        }
        return false;
    }

    /**
     * Checks whether {@code sender} can view another player's stats.
     *
     * @param sender the command sender
     * @return {@code true} if permitted via {@link #STATS_OTHERS} or {@link #ADMIN}
     */
    public static boolean hasStatsOthersPermission(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (sender.hasPermission(STATS_OTHERS)) {
            return true;
        }
        return !sender.isPermissionSet(STATS_OTHERS) && sender.hasPermission(ADMIN);
    }

    /**
     * Checks whether {@code sender} has {@link #ADMIN} or any {@code dragonraid.admin.*} permission.
     *
     * @param sender the command sender
     * @return {@code true} if any admin command is accessible
     */
    public static boolean hasAnyAdminPermission(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        for (String node : ADMIN_SUB_NODES) {
            if (sender.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether {@code sender} has access to the root {@code /dragonraid} command.
     *
     * @param sender the command sender
     * @return {@code true} if the sender holds {@link #USE} or any subcommand permission
     */
    public static boolean hasAnyCommandAccess(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        return sender.hasPermission(USE)
            || sender.hasPermission(SHOP)
            || sender.hasPermission(STATS)
            || sender.hasPermission(STATS_OTHERS)
            || sender.hasPermission(READY)
            || sender.hasPermission(START)
            || hasAnyAdminPermission(sender);
    }

    /**
     * Registers DragonRaid permission definitions and default values idempotently
     * with the given {@link PluginManager}.
     *
     * @param pluginManager the Bukkit/Folia plugin manager
     */
    public static void registerDefaults(PluginManager pluginManager) {
        Objects.requireNonNull(pluginManager, "pluginManager");
        registerIfAbsent(pluginManager, USE, "Allows base access to /dragonraid", PermissionDefault.TRUE, Map.of());
        registerIfAbsent(pluginManager, SHOP, "Allows opening the DragonRaid reward shop", PermissionDefault.TRUE, Map.of());
        registerIfAbsent(pluginManager, STATS, "Allows viewing own DragonRaid stats", PermissionDefault.TRUE, Map.of());
        registerIfAbsent(pluginManager, STATS_OTHERS, "Allows viewing other players' DragonRaid stats", PermissionDefault.OP, Map.of());
        registerIfAbsent(pluginManager, READY, "Allows toggling ready status before a raid", PermissionDefault.TRUE, Map.of());
        registerIfAbsent(pluginManager, START, "Allows triggering a DragonRaid start", PermissionDefault.TRUE, Map.of());

        registerIfAbsent(pluginManager, ADMIN_REGION, "Allows configuring DragonRaid regions", PermissionDefault.OP, Map.of());
        registerIfAbsent(pluginManager, ADMIN_START, "Allows force-starting a DragonRaid", PermissionDefault.OP, Map.of());
        registerIfAbsent(pluginManager, ADMIN_RESET, "Allows force-resetting a DragonRaid", PermissionDefault.OP, Map.of());
        registerIfAbsent(pluginManager, ADMIN_POINTS, "Allows modifying player DragonRaid points", PermissionDefault.OP, Map.of());
        registerIfAbsent(pluginManager, ADMIN_RELOAD, "Allows reloading DragonRaid configuration", PermissionDefault.OP, Map.of());

        Map<String, Boolean> adminChildren = new LinkedHashMap<>();
        for (String node : ADMIN_SUB_NODES) {
            adminChildren.put(node, Boolean.TRUE);
        }
        adminChildren.put(STATS_OTHERS, Boolean.TRUE);
        registerIfAbsent(pluginManager, ADMIN, "Grants all DragonRaid administrative permissions", PermissionDefault.OP, adminChildren);
    }

    private static void registerIfAbsent(
        PluginManager pluginManager,
        String name,
        String description,
        PermissionDefault defaultValue,
        Map<String, Boolean> children
    ) {
        if (pluginManager.getPermission(name) != null) {
            return;
        }
        pluginManager.addPermission(new Permission(name, description, defaultValue, children));
    }
}
