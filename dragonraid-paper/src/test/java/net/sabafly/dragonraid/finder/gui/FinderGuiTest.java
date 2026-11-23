package net.sabafly.dragonraid.finder.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentUnlockPolicy;
import net.sabafly.dragonraid.api.finder.DutyEnvironmentChecker;
import net.sabafly.dragonraid.api.finder.ReadyCheckResponse;
import net.sabafly.dragonraid.api.finder.ReadyCheckSession;
import net.sabafly.dragonraid.api.finder.ReadyCheckStatus;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.script.EncounterBlueprint;
import net.sabafly.dragonraid.api.script.EncounterBlueprintRegistry;
import net.sabafly.dragonraid.command.ContentFinderCommandHandler;
import net.sabafly.dragonraid.command.DefaultContentFinderCommandHandler;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.finder.DefaultContentFinderService;
import net.sabafly.dragonraid.finder.DefaultContentUnlockService;
import net.sabafly.dragonraid.finder.DutyReadyCheckCoordinator;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.party.DefaultRaidPartyService;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class FinderGuiTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private MessageService messageService;
    private DefaultContentUnlockService unlockService;
    private DefaultRaidPartyService partyService;
    private DefaultContentFinderService finderService;
    private RaidEncounterManager encounterManager;
    private RaidScheduler scheduler;
    private FakeMenuFactory menuFactory;

    private record FakeItemRecord(String material, String name, List<String> lore, boolean glint) {
    }

    private static final class FakeMenuFactory implements FinderMenuFactory {
        final Map<ItemStack, FakeItemRecord> metadata = new HashMap<>();

        @Override
        public Inventory createInventory(InventoryHolder owner, int size, Component title) {
            Inventory inv = mock(Inventory.class);
            ItemStack[] slots = new ItemStack[size];
            when(inv.getSize()).thenReturn(size);
            when(inv.getHolder()).thenReturn(owner);
            when(inv.getItem(org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(i -> slots[i.getArgument(0, Integer.class)]);
            org.mockito.Mockito.doAnswer(i -> {
                slots[i.getArgument(0, Integer.class)] = i.getArgument(1);
                return null;
            }).when(inv).setItem(org.mockito.ArgumentMatchers.anyInt(), any());
            org.mockito.Mockito.doAnswer(i -> {
                java.util.Arrays.fill(slots, null);
                return null;
            }).when(inv).clear();
            return inv;
        }

        @Override
        public ItemStack createItem(String materialName, Component displayName, List<Component> lore, boolean glint) {
            ItemStack item = mock(ItemStack.class);
            List<String> lines = lore == null ? List.of() : lore.stream().map(PLAIN::serialize).toList();
            metadata.put(item, new FakeItemRecord(materialName, PLAIN.serialize(displayName), lines, glint));
            return item;
        }

        @Override
        public ItemStack createPlayerHead(UUID playerId, String fallbackName, Component displayName, List<Component> lore) {
            return createItem("PLAYER_HEAD", displayName, lore, false);
        }
    }

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(tempDir, name -> FinderGuiTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        messageService = new MessageService(configManager);
        messageService.reload();

        unlockService = new DefaultContentUnlockService();
        partyService = new DefaultRaidPartyService();
        finderService = new DefaultContentFinderService(
                unlockService, partyService, null, DutyEnvironmentChecker.ALWAYS_READY, System::currentTimeMillis, 30_000L, false);
        encounterManager = mock(RaidEncounterManager.class);
        scheduler = mock(RaidScheduler.class);
        when(scheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        menuFactory = new FakeMenuFactory();
    }

    @Test
    void filtersHiddenUntilUnlockedShowsLockReasonsAndRetryLeaveButtons() {
        UUID playerId = UUID.randomUUID();
        Player viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(playerId);
        when(viewer.getName()).thenReturn("Alice");
        when(viewer.locale()).thenReturn(Locale.US);

        finderService.registerContent(new ContentEntrySpec("normal_trial", "Normal Trial", "bp1", "", 1, 8, 0));
        finderService.registerContent(new ContentEntrySpec(
                "locked_shown", "Locked Visible", "bp2", "req_key", 1, 8, 50,
                List.of(), ContentUnlockPolicy.ALL_MEMBERS, false, "", "ENDER_EYE", ""));
        finderService.registerContent(new ContentEntrySpec(
                "secret_hidden", "Secret Savage", "bp3", "secret_key", 1, 8, 0,
                List.of(), ContentUnlockPolicy.ALL_MEMBERS, true, "", "NETHER_STAR", ""));

        when(encounterManager.isInWipeStandby(playerId)).thenReturn(true);
        when(encounterManager.isInDutyOrInstance(playerId)).thenReturn(true);

        ContentFinderMenu menu = new ContentFinderMenu(
                viewer, finderService, unlockService, partyService, encounterManager, messageService, menuFactory, id -> viewer);

        // Slot 10 = locked_shown (alphabetical by id: locked_shown < normal_trial), secret_hidden omitted!
        assertEquals("locked_shown", menu.contentIdAt(10));
        assertEquals("normal_trial", menu.contentIdAt(11));
        assertNull(menu.contentIdAt(12));

        FakeItemRecord lockedMeta = menuFactory.metadata.get(menu.getInventory().getItem(10));
        assertEquals("IRON_BARS", lockedMeta.material());
        assertFalse(lockedMeta.glint());
        assertTrue(lockedMeta.lore().stream().anyMatch(l -> l.contains("has not unlocked")));

        FakeItemRecord openMeta = menuFactory.metadata.get(menu.getInventory().getItem(11));
        assertEquals("ENDER_EYE", openMeta.material());
        assertTrue(openMeta.glint());

        assertTrue(menu.hasRetryButton(ContentFinderMenu.SLOT_RETRY));
        assertTrue(menu.hasLeaveButton(ContentFinderMenu.SLOT_LEAVE));
        assertNotNull(menu.getInventory().getItem(ContentFinderMenu.SLOT_RETRY));
        assertNotNull(menu.getInventory().getItem(ContentFinderMenu.SLOT_LEAVE));

        // Clicking slot 11 dispatches applyContent; drag is cancelled
        ContentFinderCommandHandler handler = mock(ContentFinderCommandHandler.class);
        FinderGuiListener listener = new FinderGuiListener(handler, scheduler);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(menu.getInventory());
        when(view.getPlayer()).thenReturn(viewer);

        InventoryClickEvent applyClick = new InventoryClickEvent(view, SlotType.CONTAINER, 11, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(applyClick);
        assertTrue(applyClick.isCancelled());
        verify(handler).applyContent(viewer, "normal_trial");

        InventoryClickEvent retryClick = new InventoryClickEvent(view, SlotType.CONTAINER, ContentFinderMenu.SLOT_RETRY, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(retryClick);
        assertTrue(retryClick.isCancelled());
        verify(handler).retryEncounter(viewer);
        verify(viewer).closeInventory();

        InventoryDragEvent drag = new InventoryDragEvent(view, null, mock(ItemStack.class), false, Map.of());
        listener.onInventoryDrag(drag);
        assertTrue(drag.isCancelled());

        // ReadyCheckMenu accept / decline dispatching
        ReadyCheckSession session = new ReadyCheckSession(
                UUID.randomUUID(), "normal_trial", null, playerId, List.of(playerId),
                Map.of(playerId, ReadyCheckResponse.PENDING), ReadyCheckStatus.PENDING, 1000L, 31_000L);
        ReadyCheckMenu rcMenu = new ReadyCheckMenu(viewer, session, 5_000L, finderService, messageService, menuFactory, id -> viewer);
        InventoryView rcView = mock(InventoryView.class);
        when(rcView.getTopInventory()).thenReturn(rcMenu.getInventory());
        when(rcView.getPlayer()).thenReturn(viewer);

        InventoryClickEvent acceptClick = new InventoryClickEvent(rcView, SlotType.CONTAINER, ReadyCheckMenu.SLOT_ACCEPT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(acceptClick);
        assertTrue(acceptClick.isCancelled());
        verify(handler).acceptReadyCheck(viewer, session.sessionId());
        verify(handler, never()).declineReadyCheck(any(), any());

        InventoryClickEvent declineClick = new InventoryClickEvent(rcView, SlotType.CONTAINER, ReadyCheckMenu.SLOT_DECLINE, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(declineClick);
        assertTrue(declineClick.isCancelled());
        verify(handler).declineReadyCheck(viewer, session.sessionId());
    }

    @Test
    void nonLeaderCannotSeeOrInvokeRetryWhileLeaderCan() {
        UUID leaderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Player leader = mock(Player.class);
        when(leader.getUniqueId()).thenReturn(leaderId);
        when(leader.getName()).thenReturn("Alice");
        when(leader.locale()).thenReturn(Locale.US);
        Player member = mock(Player.class);
        when(member.getUniqueId()).thenReturn(memberId);
        when(member.getName()).thenReturn("Bob");
        when(member.locale()).thenReturn(Locale.US);

        RaidParty party = partyService.createParty(leaderId);
        assertTrue(partyService.invite(leaderId, memberId));
        assertTrue(partyService.acceptInvite(memberId, party.partyId()));

        when(encounterManager.isInWipeStandby(leaderId)).thenReturn(true);
        when(encounterManager.isInWipeStandby(memberId)).thenReturn(true);
        when(encounterManager.retryEncounter(leader)).thenReturn(true);

        ContentFinderMenu memberMenu = new ContentFinderMenu(
                member, finderService, unlockService, partyService, encounterManager,
                messageService, menuFactory, id -> id.equals(leaderId) ? leader : member);
        assertFalse(memberMenu.hasRetryButton(ContentFinderMenu.SLOT_RETRY));
        FakeItemRecord memberSlotMeta = menuFactory.metadata.get(memberMenu.getInventory().getItem(ContentFinderMenu.SLOT_RETRY));
        assertEquals("GRAY_STAINED_GLASS_PANE", memberSlotMeta.material());

        ContentFinderMenu leaderMenu = new ContentFinderMenu(
                leader, finderService, unlockService, partyService, encounterManager,
                messageService, menuFactory, id -> id.equals(leaderId) ? leader : member);
        assertTrue(leaderMenu.hasRetryButton(ContentFinderMenu.SLOT_RETRY));
        FakeItemRecord leaderSlotMeta = menuFactory.metadata.get(leaderMenu.getInventory().getItem(ContentFinderMenu.SLOT_RETRY));
        assertEquals("LIME_DYE", leaderSlotMeta.material());

        DefaultContentFinderCommandHandler handler = new DefaultContentFinderCommandHandler(
                finderService,
                unlockService,
                partyService,
                encounterManager,
                mock(DutyReadyCheckCoordinator.class),
                messageService,
                menuFactory,
                id -> id.equals(leaderId) ? leader : member
        );

        handler.retryEncounter(member);
        verify(encounterManager, never()).retryEncounter(member);
        ArgumentCaptor<Component> msgCaptor = ArgumentCaptor.forClass(Component.class);
        verify(member).sendMessage(msgCaptor.capture());
        assertTrue(PLAIN.serialize(msgCaptor.getValue()).contains("party leader"));

        handler.retryEncounter(leader);
        verify(encounterManager).retryEncounter(leader);
    }

    @Test
    void soloSkipCommenceClosesOpenContentFinderMenuAndSkipsCommenceSoundWhenBlueprintMissing() {
        UUID playerId = UUID.randomUUID();
        Player viewer = mock(Player.class);
        World world = mock(World.class);
        when(viewer.getUniqueId()).thenReturn(playerId);
        when(viewer.getName()).thenReturn("SoloAlice");
        when(viewer.locale()).thenReturn(Locale.US);
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getLocation()).thenReturn(new Location(world, 10.0, 64.0, 20.0));

        when(scheduler.runAtEntity(any(), any(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return TaskHandle.retired();
        });
        when(scheduler.runGlobal(any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return TaskHandle.retired();
        });

        DefaultContentFinderService soloFinder = new DefaultContentFinderService(
                unlockService, partyService, null, DutyEnvironmentChecker.ALWAYS_READY,
                System::currentTimeMillis, 30_000L, true);
        soloFinder.registerContent(new ContentEntrySpec("normal_trial", "Normal Trial", "bp1", "", 1, 8, 0));

        EncounterBlueprintRegistry blueprintRegistry = mock(EncounterBlueprintRegistry.class);
        EncounterBlueprint bp = mock(EncounterBlueprint.class);
        when(blueprintRegistry.get("bp1")).thenReturn(Optional.of(bp));
        when(encounterManager.startEncounter(any(), any(), any())).thenReturn(true);

        DutyReadyCheckCoordinator coordinator = new DutyReadyCheckCoordinator(
                configManager,
                soloFinder,
                blueprintRegistry,
                encounterManager,
                messageService,
                scheduler,
                menuFactory,
                id -> id.equals(playerId) ? viewer : null,
                System::currentTimeMillis,
                Logger.getLogger("FinderGuiTest")
        );
        soloFinder.addReadyCheckListener(coordinator);

        ContentFinderMenu menu = new ContentFinderMenu(
                viewer, soloFinder, unlockService, partyService, encounterManager,
                messageService, menuFactory, id -> viewer);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(menu.getInventory());
        when(viewer.getOpenInventory()).thenReturn(view);

        assertTrue(soloFinder.startReadyCheck(playerId, "normal_trial").isPresent());

        verify(viewer).closeInventory();
        verify(viewer).playSound(any(Sound.class));
        verify(encounterManager).startEncounter(any(), any(), any());

        // When blueprint is unexpectedly missing at commence time, menu closes but no commence chime plays
        org.mockito.Mockito.clearInvocations(viewer, encounterManager);
        when(blueprintRegistry.get("bp1")).thenReturn(Optional.empty());
        assertTrue(soloFinder.startReadyCheck(playerId, "normal_trial").isPresent());
        verify(viewer).closeInventory();
        verify(viewer, never()).playSound(any(Sound.class));
        verify(encounterManager, never()).startEncounter(any(), any(), any());
    }

    @Test
    void coordinatorIgnoresQueuedStartTasksAfterSessionEndedAndReportsEndingOnLeave() {
        UUID playerId = UUID.randomUUID();
        Player viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(playerId);
        when(viewer.getName()).thenReturn("LateAlice");
        when(viewer.locale()).thenReturn(Locale.US);
        when(viewer.isOnline()).thenReturn(true);

        List<Runnable> deferredEntityTasks = new ArrayList<>();
        RaidScheduler deferredScheduler = mock(RaidScheduler.class);
        when(deferredScheduler.runAtEntity(any(), any())).thenAnswer(inv -> {
            deferredEntityTasks.add(inv.getArgument(1));
            return TaskHandle.retired();
        });

        DutyReadyCheckCoordinator coordinator = new DutyReadyCheckCoordinator(
                configManager,
                finderService,
                mock(EncounterBlueprintRegistry.class),
                encounterManager,
                messageService,
                deferredScheduler,
                menuFactory,
                id -> id.equals(playerId) ? viewer : null,
                System::currentTimeMillis,
                Logger.getLogger("FinderGuiTest")
        );

        UUID sid = UUID.randomUUID();
        ReadyCheckSession pending = new ReadyCheckSession(
                sid, "normal_trial", null, playerId, List.of(playerId),
                Map.of(playerId, ReadyCheckResponse.PENDING), ReadyCheckStatus.PENDING,
                System.currentTimeMillis(), System.currentTimeMillis() + 30_000L);
        ReadyCheckSession ended = new ReadyCheckSession(
                sid, "normal_trial", null, playerId, List.of(playerId),
                Map.of(playerId, ReadyCheckResponse.DECLINED), ReadyCheckStatus.CANCELLED,
                pending.createdAtMillis(), pending.expiresAtMillis());

        // Schedule start task, end session BEFORE entity task executes, then run queued tasks -> no BossBar/GUI opened
        coordinator.onSessionStarted(pending);
        coordinator.onSessionEnded(ended, playerId, "cancelled");
        deferredEntityTasks.forEach(Runnable::run);
        verify(viewer, never()).showBossBar(any());
        verify(viewer, never()).openInventory(any(Inventory.class));

        // Duplicate late onSessionStarted for already-ended session ID is also ignored
        deferredEntityTasks.clear();
        coordinator.onSessionStarted(pending);
        assertTrue(deferredEntityTasks.isEmpty());

        // #3: ENDING_IN_PROGRESS guidance from DefaultContentFinderCommandHandler.leaveEncounter
        DefaultContentFinderCommandHandler handler = new DefaultContentFinderCommandHandler(
                finderService, unlockService, partyService, encounterManager, coordinator,
                messageService, menuFactory, id -> viewer);
        when(encounterManager.leaveEncounterDetailed(viewer))
                .thenReturn(RaidEncounterManager.LeaveEncounterResult.ENDING_IN_PROGRESS);
        org.mockito.Mockito.clearInvocations(viewer);
        handler.leaveEncounter(viewer);
        ArgumentCaptor<Component> msgCaptor = ArgumentCaptor.forClass(Component.class);
        verify(viewer).sendMessage(msgCaptor.capture());
        assertTrue(PLAIN.serialize(msgCaptor.getValue()).contains("finishing cleanup"));

        // Round 4 #1: RETURN_WORLD_UNAVAILABLE must NOT send finder.leave-success from command handler
        when(encounterManager.leaveEncounterDetailed(viewer))
                .thenReturn(RaidEncounterManager.LeaveEncounterResult.RETURN_WORLD_UNAVAILABLE);
        org.mockito.Mockito.clearInvocations(viewer);
        handler.leaveEncounter(viewer);
        verify(viewer, never()).sendMessage(any(Component.class));
    }
}
