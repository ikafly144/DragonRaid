package net.sabafly.dragonraid.finder;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;

/**
 * Thread-safe M1 scaffold of {@link ContentFinderService} handling duty registration
 * and solo/party eligibility validation.
 */
public final class DefaultContentFinderService implements ContentFinderService {

    private final Map<String, ContentEntrySpec> entries = new ConcurrentHashMap<>();
    private final ContentUnlockService unlockService;
    private final RaidPartyService partyService;
    private final RaidItemService itemService;

    public DefaultContentFinderService(ContentUnlockService unlockService) {
        this(unlockService, null, null);
    }

    public DefaultContentFinderService(
            ContentUnlockService unlockService,
            RaidPartyService partyService,
            RaidItemService itemService) {
        this.unlockService = Objects.requireNonNull(unlockService, "unlockService");
        this.partyService = partyService;
        this.itemService = itemService;
    }

    @Override
    public void registerContent(ContentEntrySpec entry) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.contentId(), entry);
    }

    @Override
    public Optional<ContentEntrySpec> getContent(String contentId) {
        if (contentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(contentId));
    }

    @Override
    public Collection<ContentEntrySpec> allContents() {
        return List.copyOf(entries.values());
    }

    @Override
    public boolean requestEntry(UUID initiatorId, String contentId) {
        Objects.requireNonNull(initiatorId, "initiatorId");
        ContentEntrySpec spec = getContent(contentId).orElse(null);
        if (spec == null) {
            return false;
        }
        List<UUID> applicants;
        Optional<RaidParty> partyOpt = partyService != null ? partyService.partyOf(initiatorId) : Optional.empty();
        if (partyOpt.isPresent()) {
            RaidParty party = partyOpt.get();
            if (!party.leaderId().equals(initiatorId)) {
                return false;
            }
            applicants = party.memberIds();
        } else {
            applicants = List.of(initiatorId);
        }

        if (applicants.size() < spec.minPlayers() || applicants.size() > spec.maxPlayers()) {
            return false;
        }
        String unlockKey = spec.requiredUnlockKey();
        if (!unlockKey.isBlank()) {
            for (UUID memberId : applicants) {
                if (!unlockService.isUnlocked(memberId, unlockKey)) {
                    return false;
                }
            }
        }
        if (spec.minAverageItemLevel() > 0 && itemService != null) {
            for (UUID memberId : applicants) {
                if (itemService.averageItemLevel(memberId) < spec.minAverageItemLevel()) {
                    return false;
                }
            }
        }
        return true;
    }
}
