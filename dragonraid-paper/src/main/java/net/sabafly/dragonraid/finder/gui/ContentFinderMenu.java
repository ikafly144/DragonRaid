package net.sabafly.dragonraid.finder.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.api.finder.ContentEligibilityResult;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockPolicy;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.finder.EligibilityFailureReason;
import net.sabafly.dragonraid.api.finder.EligibilityIssue;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.i18n.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * FF14-style 54-slot Content Finder GUI listing available duties, detailed unlock/iLvl/size
 * gate status, pagination, and in-duty Retry / Leave actions.
 */
public final class ContentFinderMenu implements InventoryHolder {

    public static final int INVENTORY_SIZE = 54;
    public static final int SLOT_HEADER = 4;
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_RETRY = 48;
    public static final int SLOT_LEAVE = 50;
    public static final int SLOT_NEXT_PAGE = 53;

    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Player viewer;
    private final ContentFinderService finderService;
    private final ContentUnlockService unlockService;
    private final RaidPartyService partyService;
    private final RaidEncounterManager encounterManager;
    private final MessageService messageService;
    private final FinderMenuFactory menuFactory;
    private final Function<UUID, @Nullable Player> playerLookup;

    private final Map<Integer, String> slotContentIds = new HashMap<>();
    private final Inventory inventory;
    private int page;
    private int totalPages = 1;

    public ContentFinderMenu(
            Player viewer,
            ContentFinderService finderService,
            ContentUnlockService unlockService,
            RaidPartyService partyService,
            RaidEncounterManager encounterManager,
            MessageService messageService,
            FinderMenuFactory menuFactory,
            Function<UUID, @Nullable Player> playerLookup) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.finderService = Objects.requireNonNull(finderService, "finderService");
        this.unlockService = Objects.requireNonNull(unlockService, "unlockService");
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.encounterManager = Objects.requireNonNull(encounterManager, "encounterManager");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.menuFactory = Objects.requireNonNull(menuFactory, "menuFactory");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        Component title = messageService.render(viewer, "finder.gui.title");
        this.inventory = menuFactory.createInventory(this, INVENTORY_SIZE, title);
        render(0);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public int page() {
        return page;
    }

    public int totalPages() {
        return totalPages;
    }

    public @Nullable String contentIdAt(int slot) {
        return slotContentIds.get(slot);
    }

    public boolean hasPrevPageButton(int slot) {
        return slot == SLOT_PREV_PAGE && page > 0;
    }

    public boolean hasNextPageButton(int slot) {
        return slot == SLOT_NEXT_PAGE && page + 1 < totalPages;
    }

    public boolean hasRetryButton(int slot) {
        return slot == SLOT_RETRY && canRetry();
    }

    private boolean canRetry() {
        UUID viewerId = viewer.getUniqueId();
        if (!encounterManager.isInWipeStandby(viewerId)) {
            return false;
        }
        Optional<RaidParty> partyOpt = partyService.partyOf(viewerId);
        return partyOpt.isEmpty() || partyOpt.get().leaderId().equals(viewerId);
    }

    public boolean hasLeaveButton(int slot) {
        return slot == SLOT_LEAVE && encounterManager.isInDutyOrInstance(viewer.getUniqueId());
    }

    public void render(int requestedPage) {
        inventory.clear();
        slotContentIds.clear();

        List<ContentEntrySpec> visible = visibleEntriesSorted();
        this.totalPages = Math.max(1, (int) Math.ceil((double) visible.size() / CONTENT_SLOTS.length));
        this.page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        ItemStack filler = menuFactory.createItem("GRAY_STAINED_GLASS_PANE", Component.text(" "), List.of(), false);
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, filler);
        }
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(SLOT_HEADER, buildHeaderItem());

        if (visible.isEmpty()) {
            inventory.setItem(22, menuFactory.createItem(
                    "BARRIER",
                    messageService.render(viewer, "finder.gui.empty-title"),
                    List.of(messageService.render(viewer, "finder.gui.empty-lore")),
                    false
            ));
        } else {
            int offset = page * CONTENT_SLOTS.length;
            int end = Math.min(visible.size(), offset + CONTENT_SLOTS.length);
            for (int i = offset; i < end; i++) {
                ContentEntrySpec entry = visible.get(i);
                int slot = CONTENT_SLOTS[i - offset];
                slotContentIds.put(slot, entry.contentId());
                inventory.setItem(slot, buildContentItem(entry));
            }
        }

        if (page > 0) {
            inventory.setItem(SLOT_PREV_PAGE, menuFactory.createItem(
                    "ARROW",
                    messageService.render(viewer, "finder.gui.prev-page", Placeholder.unparsed("page", String.valueOf(page))),
                    List.of(),
                    false
            ));
        }
        if (page + 1 < totalPages) {
            inventory.setItem(SLOT_NEXT_PAGE, menuFactory.createItem(
                    "ARROW",
                    messageService.render(viewer, "finder.gui.next-page", Placeholder.unparsed("page", String.valueOf(page + 2))),
                    List.of(),
                    false
            ));
        }
        if (canRetry()) {
            inventory.setItem(SLOT_RETRY, menuFactory.createItem(
                    "LIME_DYE",
                    messageService.render(viewer, "finder.gui.retry-button"),
                    List.of(messageService.render(viewer, "finder.gui.retry-lore")),
                    true
            ));
        }
        if (encounterManager.isInDutyOrInstance(viewer.getUniqueId())) {
            inventory.setItem(SLOT_LEAVE, menuFactory.createItem(
                    "RED_DOOR",
                    messageService.render(viewer, "finder.gui.leave-button"),
                    List.of(messageService.render(viewer, "finder.gui.leave-lore")),
                    false
            ));
        }
    }

    private List<ContentEntrySpec> visibleEntriesSorted() {
        List<ContentEntrySpec> list = new ArrayList<>();
        for (ContentEntrySpec entry : finderService.allContents()) {
            if (isVisibleToViewer(entry)) {
                list.add(entry);
            }
        }
        list.sort(Comparator.comparing(ContentEntrySpec::category).thenComparing(ContentEntrySpec::contentId));
        return list;
    }

    private boolean isVisibleToViewer(ContentEntrySpec entry) {
        if (!entry.hiddenUntilUnlocked() || entry.requiredUnlockKey().isBlank()) {
            return true;
        }
        UUID viewerId = viewer.getUniqueId();
        if (unlockService.isUnlocked(viewerId, entry.requiredUnlockKey())) {
            return true;
        }
        if (entry.unlockPolicy() == ContentUnlockPolicy.LEADER_ONLY) {
            Optional<RaidParty> partyOpt = partyService.partyOf(viewerId);
            if (partyOpt.isPresent()) {
                return unlockService.isUnlocked(partyOpt.get().leaderId(), entry.requiredUnlockKey());
            }
        }
        return false;
    }

    private ItemStack buildHeaderItem() {
        UUID viewerId = viewer.getUniqueId();
        Optional<RaidParty> partyOpt = partyService.partyOf(viewerId);
        List<Component> lore = new ArrayList<>();
        if (partyOpt.isPresent()) {
            RaidParty party = partyOpt.get();
            boolean isLeader = party.leaderId().equals(viewerId);
            lore.add(messageService.render(
                    viewer,
                    "finder.gui.header-party",
                    Placeholder.unparsed("size", String.valueOf(party.size())),
                    Placeholder.unparsed("max", String.valueOf(partyService.maxPartySize())),
                    Placeholder.unparsed("role", isLeader ? "LEADER" : "MEMBER")
            ));
        } else {
            lore.add(messageService.render(viewer, "finder.gui.header-solo"));
        }
        if (encounterManager.hasActiveDutySession()) {
            lore.add(messageService.render(viewer, "finder.gui.header-busy"));
        }
        return menuFactory.createItem(
                "COMPASS",
                messageService.render(viewer, "finder.gui.header-title"),
                lore,
                false
        );
    }

    private ItemStack buildContentItem(ContentEntrySpec entry) {
        ContentEligibilityResult eligibility = finderService.evaluateEligibility(viewer.getUniqueId(), entry.contentId());
        Component displayName = resolveOptionalKey(entry.displayNameKey(), entry.contentId());
        List<Component> lore = new ArrayList<>();
        if (!entry.descriptionKey().isBlank()) {
            lore.add(resolveOptionalKey(entry.descriptionKey(), entry.descriptionKey()));
        }
        lore.add(messageService.render(
                viewer,
                "finder.gui.entry-players",
                Placeholder.unparsed("min", String.valueOf(entry.minPlayers())),
                Placeholder.unparsed("max", String.valueOf(entry.maxPlayers()))
        ));
        if (entry.minAverageItemLevel() > 0) {
            lore.add(messageService.render(
                    viewer,
                    "finder.gui.entry-ilvl",
                    Placeholder.unparsed("ilvl", String.valueOf(entry.minAverageItemLevel()))
            ));
        }
        if (eligibility.eligible()) {
            lore.add(messageService.render(viewer, "finder.gui.entry-ready"));
            return menuFactory.createItem(entry.iconMaterial(), displayName, lore, true);
        }
        lore.add(messageService.render(viewer, "finder.gui.entry-locked"));
        for (EligibilityIssue issue : eligibility.issues()) {
            lore.add(formatIssueLine(issue));
        }
        return menuFactory.createItem("IRON_BARS", displayName, lore, false);
    }

    private Component formatIssueLine(EligibilityIssue issue) {
        Player subject = issue.playerId() != null ? playerLookup.apply(issue.playerId()) : null;
        String targetName = subject != null
                ? subject.getName()
                : (issue.playerId() != null ? issue.playerId().toString().substring(0, 8) : "");
        return switch (issue.reason()) {
            case NOT_PARTY_LEADER -> messageService.render(viewer, "finder.reason.not-leader");
            case PARTY_TOO_SMALL -> messageService.render(
                    viewer, "finder.reason.too-few-players",
                    Placeholder.unparsed("actual", String.valueOf(issue.actualValue())),
                    Placeholder.unparsed("required", String.valueOf(issue.requiredValue())));
            case PARTY_TOO_LARGE -> messageService.render(
                    viewer, "finder.reason.too-many-players",
                    Placeholder.unparsed("actual", String.valueOf(issue.actualValue())),
                    Placeholder.unparsed("required", String.valueOf(issue.requiredValue())));
            case MEMBER_OFFLINE -> messageService.render(
                    viewer, "finder.reason.member-offline",
                    Placeholder.unparsed("player", targetName));
            case MEMBER_MISSING_UNLOCK -> messageService.render(
                    viewer, "finder.reason.missing-unlock",
                    Placeholder.unparsed("player", targetName));
            case MEMBER_LOW_ITEM_LEVEL -> messageService.render(
                    viewer, "finder.reason.low-ilvl",
                    Placeholder.unparsed("player", targetName),
                    Placeholder.unparsed("actual", String.valueOf(issue.actualValue())),
                    Placeholder.unparsed("required", String.valueOf(issue.requiredValue())));
            case MEMBER_IN_ACTIVE_ENCOUNTER -> messageService.render(
                    viewer, "finder.reason.member-in-encounter",
                    Placeholder.unparsed("player", targetName));
            case MEMBER_IN_READY_CHECK -> messageService.render(
                    viewer, "finder.reason.member-in-readycheck",
                    Placeholder.unparsed("player", targetName));
            case ENCOUNTER_BUSY -> messageService.render(viewer, "finder.reason.encounter-busy");
            case BLUEPRINT_MISSING -> messageService.render(
                    viewer, "finder.reason.blueprint-missing",
                    Placeholder.unparsed("detail", issue.detail() == null ? "" : issue.detail()));
            case CONTENT_NOT_FOUND -> messageService.render(viewer, "finder.reason.content-not-found");
        };
    }

    private Component resolveOptionalKey(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return Component.text(fallback);
        }
        String locale = messageService.resolveLocale(viewer);
        if (messageService.hasKey(locale, key) || messageService.hasKey("en_US", key)) {
            return messageService.render(viewer, key);
        }
        return Component.text(key);
    }
}
