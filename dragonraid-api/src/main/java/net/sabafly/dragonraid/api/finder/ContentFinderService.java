package net.sabafly.dragonraid.api.finder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FF14-style Content Finder registry and entry/ready-check coordinator SPI (solo or pre-formed party).
 */
public interface ContentFinderService {

    void registerContent(ContentEntrySpec entry);

    Optional<ContentEntrySpec> getContent(String contentId);

    Collection<ContentEntrySpec> allContents();

    /**
     * Submits a solo or pre-formed party application for {@code contentId}.
     *
     * @return {@code true} if eligibility gates pass and ready-check / entry can start
     */
    boolean requestEntry(UUID initiatorId, String contentId);

    default ContentEligibilityResult evaluateEligibility(UUID initiatorId, String contentId) {
        ContentEntrySpec spec = getContent(contentId).orElse(null);
        List<UUID> applicants = List.of(initiatorId);
        return requestEntry(initiatorId, contentId)
                ? ContentEligibilityResult.allowed(spec, applicants)
                : ContentEligibilityResult.denied(spec, applicants, List.of());
    }

    default Optional<ReadyCheckSession> startReadyCheck(UUID initiatorId, String contentId) {
        return Optional.empty();
    }

    default boolean respondReadyCheck(UUID playerId, boolean accepted) {
        return false;
    }

    default boolean respondReadyCheck(UUID playerId, UUID expectedSessionId, boolean accepted) {
        return respondReadyCheck(playerId, accepted);
    }

    default boolean cancelReadyCheck(UUID actorId) {
        return false;
    }

    default Optional<ReadyCheckSession> activeReadyCheck(UUID playerId) {
        return Optional.empty();
    }

    default void tickReadyChecks() {
    }

    default void addReadyCheckListener(ReadyCheckListener listener) {
    }

    default void removeReadyCheckListener(ReadyCheckListener listener) {
    }

    default boolean unregisterContent(String contentId) {
        return false;
    }

    default void replacePackContents(Collection<ContentEntrySpec> packEntries) {
        if (packEntries != null) {
            for (ContentEntrySpec entry : packEntries) {
                registerContent(entry);
            }
        }
    }
}
