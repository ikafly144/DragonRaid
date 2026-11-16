package io.github.ikafly144.dragonraid.scheduler;

/**
 * No-op {@link TaskHandle} returned when an entity retires before a task can be scheduled.
 */
final class RetiredTaskHandle implements TaskHandle {

    static final TaskHandle INSTANCE = new RetiredTaskHandle();

    private RetiredTaskHandle() {
    }

    @Override
    public void cancel() {
        // Already retired/cancelled; nothing to cancel.
    }

    @Override
    public boolean isCancelled() {
        return true;
    }
}
