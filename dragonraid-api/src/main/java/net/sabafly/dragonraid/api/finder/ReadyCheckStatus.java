package net.sabafly.dragonraid.api.finder;

/**
 * Lifecycle state of a Content Finder ready-check session.
 */
public enum ReadyCheckStatus {
    PENDING,
    COMMENCED,
    DECLINED,
    TIMED_OUT,
    CANCELLED
}
