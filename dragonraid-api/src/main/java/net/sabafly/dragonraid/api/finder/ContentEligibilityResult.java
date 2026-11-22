package net.sabafly.dragonraid.api.finder;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Detailed outcome of Content Finder application eligibility evaluation for a solo player or pre-formed party.
 */
public record ContentEligibilityResult(
        boolean eligible,
        @Nullable ContentEntrySpec content,
        List<UUID> applicantIds,
        List<EligibilityIssue> issues
) {

    public ContentEligibilityResult {
        applicantIds = applicantIds == null ? List.of() : List.copyOf(applicantIds);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static ContentEligibilityResult allowed(
            @Nullable ContentEntrySpec content,
            List<UUID> applicantIds) {
        Objects.requireNonNull(applicantIds, "applicantIds");
        return new ContentEligibilityResult(true, content, applicantIds, List.of());
    }

    public static ContentEligibilityResult denied(
            @Nullable ContentEntrySpec content,
            List<UUID> applicantIds,
            List<EligibilityIssue> issues) {
        return new ContentEligibilityResult(false, content, applicantIds, issues);
    }
}
