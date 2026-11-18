package net.sabafly.dragonraid.api.finder;

import java.util.Collection;
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
     * @return {@code true} if eligibility gates pass and ready-check / entry started
     */
    boolean requestEntry(UUID initiatorId, String contentId);
}
