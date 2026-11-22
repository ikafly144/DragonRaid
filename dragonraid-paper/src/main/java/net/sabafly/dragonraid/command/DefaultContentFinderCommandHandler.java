package net.sabafly.dragonraid.command;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.sabafly.dragonraid.api.finder.ContentEligibilityResult;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.finder.EligibilityIssue;
import net.sabafly.dragonraid.api.finder.ReadyCheckSession;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.encounter.RaidEncounterManager;
import net.sabafly.dragonraid.finder.DutyReadyCheckCoordinator;
import net.sabafly.dragonraid.finder.gui.ContentFinderMenu;
import net.sabafly.dragonraid.finder.gui.FinderMenuFactory;
import net.sabafly.dragonraid.i18n.MessageService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Default Paper implementation of {@link ContentFinderCommandHandler}.
 */
public final class DefaultContentFinderCommandHandler implements ContentFinderCommandHandler {

    private final ContentFinderService finderService;
    private final ContentUnlockService unlockService;
    private final RaidPartyService partyService;
    private final RaidEncounterManager encounterManager;
    private final DutyReadyCheckCoordinator readyCheckCoordinator;
    private final MessageService messageService;
    private final FinderMenuFactory menuFactory;
    private final Function<UUID, @Nullable Player> playerLookup;

    public DefaultContentFinderCommandHandler(
            ContentFinderService finderService,
            ContentUnlockService unlockService,
            RaidPartyService partyService,
            RaidEncounterManager encounterManager,
            DutyReadyCheckCoordinator readyCheckCoordinator,
            MessageService messageService,
            FinderMenuFactory menuFactory,
            Function<UUID, @Nullable Player> playerLookup) {
        this.finderService = Objects.requireNonNull(finderService, "finderService");
        this.unlockService = Objects.requireNonNull(unlockService, "unlockService");
        this.partyService = Objects.requireNonNull(partyService, "partyService");
        this.encounterManager = Objects.requireNonNull(encounterManager, "encounterManager");
        this.readyCheckCoordinator = Objects.requireNonNull(readyCheckCoordinator, "readyCheckCoordinator");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.menuFactory = Objects.requireNonNull(menuFactory, "menuFactory");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
    }

    @Override
    public void openFinder(Player player) {
        Objects.requireNonNull(player, "player");
        Optional<ReadyCheckSession> activeCheck = finderService.activeReadyCheck(player.getUniqueId());
        if (activeCheck.isPresent()) {
            readyCheckCoordinator.openReadyCheckMenu(player, activeCheck.get());
            return;
        }
        ContentFinderMenu menu = new ContentFinderMenu(
                player,
                finderService,
                unlockService,
                partyService,
                encounterManager,
                messageService,
                menuFactory,
                playerLookup
        );
        player.openInventory(menu.getInventory());
    }

    @Override
    public void applyContent(Player player, String contentId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(contentId, "contentId");
        ContentEligibilityResult eligibility = finderService.evaluateEligibility(player.getUniqueId(), contentId);
        if (!eligibility.eligible()) {
            messageService.send(player, "finder.apply-denied");
            for (EligibilityIssue issue : eligibility.issues()) {
                sendIssueMessage(player, issue);
            }
            return;
        }
        if (finderService.startReadyCheck(player.getUniqueId(), contentId).isEmpty()) {
            messageService.send(player, "finder.apply-denied");
        }
    }

    @Override
    public void acceptReadyCheck(Player player) {
        Objects.requireNonNull(player, "player");
        if (!finderService.respondReadyCheck(player.getUniqueId(), true)) {
            messageService.send(player, "finder.readycheck.none-active");
            return;
        }
        messageService.send(player, "finder.readycheck.accepted-self");
    }

    @Override
    public void acceptReadyCheck(Player player, UUID expectedSessionId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(expectedSessionId, "expectedSessionId");
        if (!finderService.respondReadyCheck(player.getUniqueId(), expectedSessionId, true)) {
            messageService.send(player, "finder.readycheck.none-active");
            return;
        }
        messageService.send(player, "finder.readycheck.accepted-self");
    }

    @Override
    public void declineReadyCheck(Player player) {
        Objects.requireNonNull(player, "player");
        if (!finderService.respondReadyCheck(player.getUniqueId(), false)) {
            messageService.send(player, "finder.readycheck.none-active");
        }
    }

    @Override
    public void declineReadyCheck(Player player, UUID expectedSessionId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(expectedSessionId, "expectedSessionId");
        if (!finderService.respondReadyCheck(player.getUniqueId(), expectedSessionId, false)) {
            messageService.send(player, "finder.readycheck.none-active");
        }
    }

    @Override
    public void cancelReadyCheck(Player player) {
        Objects.requireNonNull(player, "player");
        if (!finderService.cancelReadyCheck(player.getUniqueId())) {
            messageService.send(player, "finder.readycheck.cannot-cancel");
        }
    }

    @Override
    public void retryEncounter(Player player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        if (!encounterManager.isInWipeStandby(playerId)) {
            messageService.send(player, "finder.retry-not-available");
            return;
        }
        Optional<RaidParty> partyOpt = partyService.partyOf(playerId);
        if (partyOpt.isPresent() && !partyOpt.get().leaderId().equals(playerId)) {
            messageService.send(player, "finder.reason.not-leader");
            return;
        }
        if (!encounterManager.retryEncounter(player)) {
            messageService.send(player, "finder.retry-failed");
        }
    }

    @Override
    public void leaveEncounter(Player player) {
        Objects.requireNonNull(player, "player");
        RaidEncounterManager.LeaveEncounterResult result = encounterManager.leaveEncounterDetailed(player);
        if (result == null) {
            result = RaidEncounterManager.LeaveEncounterResult.NOT_IN_DUTY;
        }
        switch (result) {
            case LEFT -> messageService.send(player, "finder.leave-success");
            case ENDING_IN_PROGRESS -> messageService.send(player, "finder.leave-ending-in-progress");
            case NOT_IN_DUTY -> messageService.send(player, "finder.leave-not-in-duty");
        }
    }

    @Override
    public void onPlayerOnly(CommandSender sender) {
        messageService.send(sender, "command.player-only");
    }

    private void sendIssueMessage(Player viewer, EligibilityIssue issue) {
        Player subject = issue.playerId() != null ? playerLookup.apply(issue.playerId()) : null;
        String targetName = subject != null
                ? subject.getName()
                : (issue.playerId() != null ? issue.playerId().toString().substring(0, 8) : "");
        switch (issue.reason()) {
            case NOT_PARTY_LEADER -> messageService.send(viewer, "finder.reason.not-leader");
            case PARTY_TOO_SMALL -> messageService.send(
                    viewer, "finder.reason.too-few-players",
                    Placeholder.unparsed("actual", String.valueOf(issue.actualValue())),
                    Placeholder.unparsed("required", String.valueOf(issue.requiredValue())));
            case PARTY_TOO_LARGE -> messageService.send(
                    viewer, "finder.reason.too-many-players",
                    Placeholder.unparsed("actual", String.valueOf(issue.actualValue())),
                    Placeholder.unparsed("required", String.valueOf(issue.requiredValue())));
            case MEMBER_OFFLINE -> messageService.send(
                    viewer, "finder.reason.member-offline",
                    Placeholder.unparsed("player", targetName));
            case MEMBER_MISSING_UNLOCK -> messageService.send(
                    viewer, "finder.reason.missing-unlock",
                    Placeholder.unparsed("player", targetName));
            case MEMBER_LOW_ITEM_LEVEL -> messageService.send(
                    viewer, "finder.reason.low-ilvl",
                    Placeholder.unparsed("player", targetName),
                    Placeholder.unparsed("actual", String.valueOf(issue.actualValue())),
                    Placeholder.unparsed("required", String.valueOf(issue.requiredValue())));
            case MEMBER_IN_ACTIVE_ENCOUNTER -> messageService.send(
                    viewer, "finder.reason.member-in-encounter",
                    Placeholder.unparsed("player", targetName));
            case MEMBER_IN_READY_CHECK -> messageService.send(
                    viewer, "finder.reason.member-in-readycheck",
                    Placeholder.unparsed("player", targetName));
            case ENCOUNTER_BUSY -> messageService.send(viewer, "finder.reason.encounter-busy");
            case BLUEPRINT_MISSING -> messageService.send(
                    viewer, "finder.reason.blueprint-missing",
                    Placeholder.unparsed("detail", issue.detail() == null ? "" : issue.detail()));
            case CONTENT_NOT_FOUND -> messageService.send(viewer, "finder.reason.content-not-found");
        }
    }
}
