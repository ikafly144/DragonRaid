package net.sabafly.dragonraid.scheduler;

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
     * Registers a callback invoked if this task is cancelled before executing (or immediately if
     * already cancelled/retired).
     */
    default void onCancel(Runnable callback) {
        if (isCancelled() && callback != null) {
            callback.run();
        }
    }

    /**
     * Returns a no-op handle representing a task whose target entity was already retired.
     *
     * @return an already-cancelled no-op {@link TaskHandle}
     */
    static TaskHandle retired() {
        return RetiredTaskHandle.INSTANCE;
    }
}
