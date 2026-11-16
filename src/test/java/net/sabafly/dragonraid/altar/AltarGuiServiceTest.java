package net.sabafly.dragonraid.altar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.sabafly.dragonraid.altar.AltarGuiService.GuiBackend;
import net.sabafly.dragonraid.altar.AltarGuiService.InventoryCostHandler;
import net.sabafly.dragonraid.command.RaidCommandHandler;
import net.sabafly.dragonraid.config.BossConfig;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.config.PluginConfig.ItemCost;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.encounter.RaidState;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.region.DefaultRaidRegionService;
import net.sabafly.dragonraid.region.RaidRegionService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.storage.PlayerDataRepository;
import net.sabafly.dragonraid.storage.PlayerRaidStats;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AltarGuiServiceTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private MessageService messageService;
    private RaidScheduler scheduler;
    private RaidRegionService regionService;
    private PlayerDataRepository repository;
    private RaidEncounterManager encounterManager;
    private RaidCommandHandler commandHandler;
    private World endWorld;
    private AtomicBoolean playerHasCosts;
    private AtomicBoolean costsConsumed;
    private AltarGuiService service;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(
                tempDir,
                name -> AltarGuiServiceTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();

        messageService = new MessageService(configManager);
        messageService.reload();

        endWorld = mock(World.class);
        lenient().when(endWorld.getName()).thenReturn("world_the_end");

        scheduler = mock(RaidScheduler.class);
        lenient().when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });

        regionService = new DefaultRaidRegionService(configManager, scheduler);
        repository = mock(PlayerDataRepository.class);
        encounterManager = mock(RaidEncounterManager.class);
        commandHandler = mock(RaidCommandHandler.class);

        lenient().when(encounterManager.state()).thenReturn(RaidState.IDLE);
        lenient().when(encounterManager.isFirstClearEncounterAvailable()).thenReturn(false);

        playerHasCosts = new AtomicBoolean(true);
        costsConsumed = new AtomicBoolean(false);

        GuiBackend mockGuiBackend = new GuiBackend() {
            @Override
            public Inventory createInventory(AltarInventoryHolder holder, int size, Component title) {
                Inventory inv = mock(Inventory.class);
                lenient().when(inv.getHolder()).thenReturn(holder);
                return inv;
            }

            @Override
            public ItemStack createButton(Material material, Component displayName, List<Component> lore) {
                return mock(ItemStack.class);
            }
        };

        InventoryCostHandler mockCostHandler = new InventoryCostHandler() {
            @Override
            public boolean hasCosts(Player player, List<ItemCost> costs) {
                return playerHasCosts.get();
            }

            @Override
            public void consumeCosts(Player player, List<ItemCost> costs) {
                costsConsumed.set(true);
            }
        };

        service = new AltarGuiService(
                configManager,
                messageService,
                scheduler,
                regionService,
                repository,
                encounterManager,
                commandHandler,
                Logger.getLogger("AltarTest"),
                mockGuiBackend,
                mockCostHandler
        );
    }

    private Player mockPlayer(String name) {
        Player p = mock(Player.class);
        lenient().when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        lenient().when(p.locale()).thenReturn(Locale.US);
        return p;
    }

    @Test
    void detectsExitPortalAltarBlocksWithinRadius() {
        Block altarBedrock = mock(Block.class);
        when(altarBedrock.getLocation()).thenReturn(new Location(endWorld, 0.0, 65.0, 0.0));
        when(altarBedrock.getType()).thenReturn(Material.BEDROCK);
        assertTrue(service.isAltarInteractionBlock(altarBedrock));

        Block farBedrock = mock(Block.class);
        when(farBedrock.getLocation()).thenReturn(new Location(endWorld, 20.0, 65.0, 0.0));
        when(farBedrock.getType()).thenReturn(Material.BEDROCK);
        assertFalse(service.isAltarInteractionBlock(farBedrock));
    }

    @Test
    void opensGuiAndHandlesNormalSummonWithAndWithoutCost() {
        Player player = mockPlayer("Alice");
        UUID playerId = player.getUniqueId();
        when(repository.findByUuid(playerId))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(PlayerRaidStats.empty(playerId, "Alice"))));

        service.openAltarGui(player);
        verify(player).openInventory(any(Inventory.class));

        // Missing summon items -> blocked
        playerHasCosts.set(false);
        service.handleClick(player, AltarGuiService.NORMAL_SLOT);
        verify(player).closeInventory();
        verify(encounterManager, never()).startAltarPull(any(), any());
        assertFalse(costsConsumed.get());

        // Has summon items -> starts Normal pull and consumes items
        playerHasCosts.set(true);
        when(encounterManager.startAltarPull(player, BossConfig.Difficulty.NORMAL)).thenReturn(true);
        service.handleClick(player, AltarGuiService.NORMAL_SLOT);
        verify(encounterManager).startAltarPull(player, BossConfig.Difficulty.NORMAL);
        assertTrue(costsConsumed.get());

        // First-clear available -> does NOT consume items
        costsConsumed.set(false);
        when(encounterManager.isFirstClearEncounterAvailable()).thenReturn(true);
        service.handleClick(player, AltarGuiService.NORMAL_SLOT);
        assertFalse(costsConsumed.get());

        // Shop slot -> opens shop
        service.handleClick(player, AltarGuiService.SHOP_SLOT);
        verify(commandHandler).openShop(player);
    }

    @Test
    void formatCosts_formatsConfiguredList() {
        String formatted = AltarGuiService.formatCosts(configManager.pluginConfig().encounter().altar().normalCosts());
        assertNotNull(formatted);
        assertEquals("END_CRYSTAL x4", formatted);
    }
}
