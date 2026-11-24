package net.sabafly.dragonraid.testharness;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.pack.ContentPackLoadReport;
import net.sabafly.dragonraid.api.pack.ContentPackLoader;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterBlueprintRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers the OP-only {@code /drtest} helper command in interactive {@code runFolia} sessions
 * so testers can quickly equip raid gear, spawn an End Portal block, adjust boss HP, or trigger
 * downed/wipe states on real Folia region/entity threads.
 */
final class DrTestCommandRegistrar {

    private final RaidServiceRegistry registry;

    DrTestCommandRegistrar(RaidServiceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    void register(JavaPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                event -> event.registrar().register(
                        Commands.literal("drtest")
                                .requires(src -> src.getSender().isOp()
                                        || src.getSender().hasPermission("dragonraid.testharness.admin"))
                                .then(Commands.literal("status")
                                        .executes(ctx -> executeStatus(ctx.getSource())))
                                .then(Commands.literal("kit")
                                        .executes(ctx -> executeKit(ctx.getSource())))
                                .then(Commands.literal("unlock-all")
                                        .executes(ctx -> executeUnlockAll(ctx.getSource())))
                                .then(Commands.literal("portal-here")
                                        .executes(ctx -> executePortalHere(ctx.getSource())))
                                .then(Commands.literal("boss-hp")
                                        .then(Commands.argument("hp", DoubleArgumentType.doubleArg(0.5))
                                                .executes(ctx -> executeBossHp(
                                                        ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "hp")))))
                                .then(Commands.literal("self-damage")
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1.0))
                                                .executes(ctx -> executeSelfDamage(
                                                        ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "amount")))))
                                .build(),
                        "DragonRaid interactive test helper commands",
                        List.of()
                )
        );
    }

    private int executeStatus(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        ContentPackLoader packLoader = registry.require(ContentPackLoader.class);
        EncounterBlueprintRegistry blueprints = registry.require(EncounterBlueprintRegistry.class);
        RaidInstanceManager instances = registry.require(RaidInstanceManager.class);
        ContentPackLoadReport report = packLoader.lastReport();
        RaidInstanceManager.RaidInstance active = instances.activeInstance();

        sender.sendMessage(Component.text("=== DragonRaid Test Harness Status ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Loaded packs: " + report.loadedPacks().size()
                + " (contents=" + report.totalContents()
                + ", achievements=" + report.totalAchievements()
                + ", rewardTables=" + report.totalRewardTables() + ")", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Registered blueprints: "
                + blueprints.all().stream().map(EncounterBlueprint::id).sorted().toList(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Active instance: "
                + (active != null && active.world() != null ? active.world().getName() : "none"),
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int executeKit(CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Player only command.", NamedTextColor.RED));
            return 0;
        }
        RaidScheduler scheduler = registry.require(RaidScheduler.class);
        scheduler.runAtEntity(player, () -> {
            player.getInventory().addItem(
                    new ItemStack(Material.NETHERITE_SWORD, 1),
                    new ItemStack(Material.BOW, 1),
                    new ItemStack(Material.ARROW, 64),
                    new ItemStack(Material.SHIELD, 1),
                    new ItemStack(Material.GOLDEN_APPLE, 16),
                    new ItemStack(Material.ENDER_PEARL, 16),
                    new ItemStack(Material.END_STONE, 64)
            );
            player.getInventory().setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE, 1));
            player.getInventory().setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS, 1));
            player.getInventory().setBoots(new ItemStack(Material.NETHERITE_BOOTS, 1));
            player.sendMessage(Component.text("[drtest] Combat test kit granted.", NamedTextColor.GREEN));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int executeUnlockAll(CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Player only command.", NamedTextColor.RED));
            return 0;
        }
        ContentFinderService finder = registry.require(ContentFinderService.class);
        ContentUnlockService unlocks = registry.require(ContentUnlockService.class);
        RaidScheduler scheduler = registry.require(RaidScheduler.class);
        for (ContentEntrySpec spec : finder.allContents()) {
            String key = spec.requiredUnlockKey().isEmpty() ? spec.contentId() : spec.requiredUnlockKey();
            unlocks.unlockAsync(player.getUniqueId(), player.getName(), key);
        }
        scheduler.runAtEntity(player, () -> player.sendMessage(
                Component.text("[drtest] Granted unlock keys for all registered contents.", NamedTextColor.GREEN)));
        return Command.SINGLE_SUCCESS;
    }

    private int executePortalHere(CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Player only command.", NamedTextColor.RED));
            return 0;
        }
        RaidScheduler scheduler = registry.require(RaidScheduler.class);
        scheduler.runAtEntity(player, () -> {
            Location base = player.getLocation();
            World world = base.getWorld();
            if (world == null) {
                return;
            }
            Location target = new Location(world, base.getBlockX() + 2, base.getBlockY(), base.getBlockZ());
            scheduler.runAtRegion(target, () -> {
                target.getBlock().setType(Material.END_PORTAL, false);
                scheduler.runAtEntity(player, () -> player.sendMessage(
                        Component.text("[drtest] Placed END_PORTAL at (" + target.getBlockX() + ", "
                                + target.getBlockY() + ", " + target.getBlockZ()
                                + "). Walk into it to test END_PORTAL_TOUCH!", NamedTextColor.AQUA)));
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int executeBossHp(CommandSourceStack source, double hp) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Player only command.", NamedTextColor.RED));
            return 0;
        }
        RaidScheduler scheduler = registry.require(RaidScheduler.class);
        scheduler.runAtEntity(player, () -> {
            World world = player.getWorld();
            var dragons = world.getEntitiesByClass(EnderDragon.class);
            if (dragons.isEmpty()) {
                player.sendMessage(Component.text("[drtest] No EnderDragon found in current world.", NamedTextColor.RED));
                return;
            }
            for (EnderDragon dragon : dragons) {
                scheduler.runAtEntity(dragon, () -> {
                    if (!dragon.isDead()) {
                        double maxHp = dragon.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                                ? dragon.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                                : dragon.getHealth();
                        double clamped = Math.min(hp, maxHp);
                        dragon.setHealth(Math.max(0.5, clamped));
                    }
                });
            }
            player.sendMessage(Component.text("[drtest] Updated EnderDragon HP to " + hp, NamedTextColor.GREEN));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int executeSelfDamage(CommandSourceStack source, double amount) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Player only command.", NamedTextColor.RED));
            return 0;
        }
        RaidScheduler scheduler = registry.require(RaidScheduler.class);
        scheduler.runAtEntity(player, () -> {
            player.damage(amount);
            player.sendMessage(Component.text("[drtest] Applied " + amount + " damage to self.", NamedTextColor.YELLOW));
        });
        return Command.SINGLE_SUCCESS;
    }
}
