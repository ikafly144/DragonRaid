package net.sabafly.dragonraid.api.finder;

/**
 * Enumerated eligibility gate failure codes returned by {@link ContentFinderService#evaluateEligibility}.
 */
public enum EligibilityFailureReason {
    CONTENT_NOT_FOUND,
    NOT_PARTY_LEADER,
    PARTY_TOO_SMALL,
    PARTY_TOO_LARGE,
    MEMBER_OFFLINE,
    MEMBER_MISSING_UNLOCK,
    MEMBER_LOW_ITEM_LEVEL,
    MEMBER_IN_ACTIVE_ENCOUNTER,
    MEMBER_IN_READY_CHECK,
    ENCOUNTER_BUSY,
    BLUEPRINT_MISSING
}
