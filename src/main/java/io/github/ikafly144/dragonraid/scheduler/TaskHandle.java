package io.github.ikafly144.dragonraid.scheduler;

/**
 * Handle to a task scheduled via {@link RaidScheduler}.
 */
public interface TaskHandle {

    /**
     * Cancels this scheduled task if it has not already completed or been cancelled.
     */
    void cancel();

    /**
     * Returns whether this task has been cancelled or could not be scheduled because its target retired.
     *
     * @return {@code true} if cancelled or retired
     */
    boolean isCancelled();

    /**
     * Returns a no-op handle representing a task whose target entity was already retired.
     *
     * @return an already-cancelled no-op {@link TaskHandle}
     */
    static TaskHandle retired() {
        return RetiredTaskHandle.INSTANCE;
    }
}
