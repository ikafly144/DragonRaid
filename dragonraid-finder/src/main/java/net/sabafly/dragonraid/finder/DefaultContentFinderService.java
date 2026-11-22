package net.sabafly.dragonraid.finder;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * Thread-safe {@link ContentFinderService} handling duty registration, Content Pack hot-reload,
 * and solo/pre-formed party eligibility validation.
 */
public final class DefaultContentFinderService implements ContentFinderService {

    private final Map<String, ContentEntrySpec> staticEntries = new ConcurrentHashMap<>();
    private volatile Map<String, ContentEntrySpec> packEntries = Map.of();
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
        staticEntries.put(entry.contentId(), entry);
    }

    @Override
    public synchronized boolean unregisterContent(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return false;
        }
        boolean removedStatic = staticEntries.remove(contentId) != null;
        Map<String, ContentEntrySpec> currentPack = this.packEntries;
        boolean removedPack = false;
        if (currentPack.containsKey(contentId)) {
            Map<String, ContentEntrySpec> next = new LinkedHashMap<>(currentPack);
            next.remove(contentId);
            this.packEntries = Collections.unmodifiableMap(next);
            removedPack = true;
        }
        return removedStatic || removedPack;
    }

    @Override
    public synchronized void replacePackContents(Collection<ContentEntrySpec> entries) {
        Map<String, ContentEntrySpec> next = new LinkedHashMap<>();
        if (entries != null) {
            for (ContentEntrySpec entry : entries) {
                if (entry != null) {
                    next.put(entry.contentId(), entry);
                }
            }
        }
        this.packEntries = Collections.unmodifiableMap(next);
    }

    @Override
    public Optional<ContentEntrySpec> getContent(String contentId) {
        if (contentId == null) {
            return Optional.empty();
        }
        ContentEntrySpec fromPack = packEntries.get(contentId);
        if (fromPack != null) {
            return Optional.of(fromPack);
        }
        return Optional.ofNullable(staticEntries.get(contentId));
    }

    @Override
    public Collection<ContentEntrySpec> allContents() {
        Map<String, ContentEntrySpec> merged = new LinkedHashMap<>(staticEntries);
        merged.putAll(packEntries);
        return List.copyOf(merged.values());
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
        if (spec.minAverageItemLevel() > 0) {
            if (itemService == null) {
                return false;
            }
            for (UUID memberId : applicants) {
                if (itemService.averageItemLevel(memberId) < spec.minAverageItemLevel()) {
                    return false;
                }
            }
        }
        return true;
    }
}
