package net.sabafly.dragonraid.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.sabafly.dragonraid.permission.RaidPermissions;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class RaidPermissionsTest {

    @Test
    void constantsMatchExpectedPermissionStrings() {
        assertEquals("dragonraid.use", RaidPermissions.USE);
        assertEquals("dragonraid.shop", RaidPermissions.SHOP);
        assertEquals("dragonraid.stats", RaidPermissions.STATS);
        assertEquals("dragonraid.stats.others", RaidPermissions.STATS_OTHERS);
        assertEquals("dragonraid.bypass.end_gate", RaidPermissions.BYPASS_END_GATE);
        assertEquals("dragonraid.admin", RaidPermissions.ADMIN);
        assertEquals("dragonraid.admin.region", RaidPermissions.ADMIN_REGION);
        assertEquals("dragonraid.admin.start", RaidPermissions.ADMIN_START);
        assertEquals("dragonraid.admin.reset", RaidPermissions.ADMIN_RESET);
        assertEquals("dragonraid.admin.points", RaidPermissions.ADMIN_POINTS);
        assertEquals("dragonraid.admin.reload", RaidPermissions.ADMIN_RELOAD);
        assertEquals("dragonraid.admin.packs", RaidPermissions.ADMIN_PACKS);
        assertEquals("dragonraid.admin.unlock", RaidPermissions.ADMIN_UNLOCK);
        assertEquals("dragonraid.admin.achievement", RaidPermissions.ADMIN_ACHIEVEMENT);
        assertEquals("dragonraid.admin.progression", RaidPermissions.ADMIN_PROGRESSION);
        assertEquals("dragonraid.admin.reward", RaidPermissions.ADMIN_REWARD);
        assertEquals(16, RaidPermissions.ALL_NODES.size());
        assertEquals(16, Set.copyOf(RaidPermissions.ALL_NODES).size());
    }

    @Test
    void adminPermissionInheritsToAdminSubNodesUnlessExplicitlyNegated() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(RaidPermissions.ADMIN)).thenReturn(true);

        assertTrue(RaidPermissions.hasPermission(sender, RaidPermissions.ADMIN_RELOAD));
        assertTrue(RaidPermissions.hasPermission(sender, RaidPermissions.ADMIN_PACKS));
        assertTrue(RaidPermissions.hasPermission(sender, RaidPermissions.ADMIN_UNLOCK));
        assertTrue(RaidPermissions.hasPermission(sender, RaidPermissions.ADMIN_ACHIEVEMENT));
        assertTrue(RaidPermissions.hasPermission(sender, RaidPermissions.ADMIN_PROGRESSION));
        assertTrue(RaidPermissions.hasPermission(sender, RaidPermissions.ADMIN_REWARD));
        assertTrue(RaidPermissions.hasStatsOthersPermission(sender));
        assertTrue(RaidPermissions.hasAnyAdminPermission(sender));
        assertTrue(RaidPermissions.hasAnyCommandAccess(sender));

        when(sender.isPermissionSet(RaidPermissions.ADMIN_RESET)).thenReturn(true);
        when(sender.hasPermission(RaidPermissions.ADMIN_RESET)).thenReturn(false);
        assertFalse(RaidPermissions.hasPermission(sender, RaidPermissions.ADMIN_RESET));

        when(sender.isPermissionSet(RaidPermissions.STATS_OTHERS)).thenReturn(true);
        when(sender.hasPermission(RaidPermissions.STATS_OTHERS)).thenReturn(false);
        assertFalse(RaidPermissions.hasStatsOthersPermission(sender));
    }

    @Test
    void nonAdminPermissionDoesNotInheritFromAdmin() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(RaidPermissions.ADMIN)).thenReturn(true);
        when(sender.hasPermission(RaidPermissions.SHOP)).thenReturn(false);
        when(sender.hasPermission(RaidPermissions.BYPASS_END_GATE)).thenReturn(false);

        assertFalse(RaidPermissions.hasPermission(sender, RaidPermissions.SHOP));
        assertFalse(RaidPermissions.hasPermission(sender, RaidPermissions.BYPASS_END_GATE));
    }

    @Test
    void registerDefaultsRegistersAllNodesIdempotently() {
        PluginManager pluginManager = mock(PluginManager.class);
        Map<String, Permission> registered = new HashMap<>();
        when(pluginManager.getPermission(any(String.class)))
            .thenAnswer(inv -> registered.get(inv.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            Permission perm = inv.getArgument(0);
            registered.put(perm.getName(), perm);
            return null;
        }).when(pluginManager).addPermission(any(Permission.class));

        RaidPermissions.registerDefaults(pluginManager);
        RaidPermissions.registerDefaults(pluginManager);

        verify(pluginManager, times(16)).addPermission(any(Permission.class));
        for (String node : RaidPermissions.ALL_NODES) {
            assertNotNull(registered.get(node), "Missing registered permission: " + node);
        }
        assertEquals(PermissionDefault.TRUE, registered.get(RaidPermissions.USE).getDefault());
        assertEquals(PermissionDefault.TRUE, registered.get(RaidPermissions.SHOP).getDefault());
        assertEquals(PermissionDefault.FALSE, registered.get(RaidPermissions.BYPASS_END_GATE).getDefault());
        assertEquals(PermissionDefault.OP, registered.get(RaidPermissions.ADMIN).getDefault());
        assertEquals(PermissionDefault.OP, registered.get(RaidPermissions.STATS_OTHERS).getDefault());
        assertTrue(registered.get(RaidPermissions.ADMIN).getChildren().get(RaidPermissions.ADMIN_RELOAD));
        assertTrue(registered.get(RaidPermissions.ADMIN).getChildren().get(RaidPermissions.ADMIN_PACKS));
        assertTrue(registered.get(RaidPermissions.ADMIN).getChildren().get(RaidPermissions.ADMIN_UNLOCK));
        assertTrue(registered.get(RaidPermissions.ADMIN).getChildren().get(RaidPermissions.ADMIN_ACHIEVEMENT));
        assertTrue(registered.get(RaidPermissions.ADMIN).getChildren().get(RaidPermissions.ADMIN_PROGRESSION));
        assertTrue(registered.get(RaidPermissions.ADMIN).getChildren().get(RaidPermissions.ADMIN_REWARD));
        assertTrue(registered.get(RaidPermissions.ADMIN).getChildren().get(RaidPermissions.STATS_OTHERS));
    }
}
