package net.sabafly.dragonraid.api.reward;

/**
 * Outcome of an item reward delivery attempt, capturing partial stack delivery counts so retries
 * only deliver the remaining unfulfilled quantity.
 */
public record ItemDeliveryResult(int requestedAmount, int deliveredAmount, boolean completed) {

    public ItemDeliveryResult {
        if (requestedAmount < 0 || deliveredAmount < 0 || deliveredAmount > requestedAmount) {
            throw new IllegalArgumentException(
                    "Invalid item delivery counts: requested=" + requestedAmount + ", delivered=" + deliveredAmount);
        }
        if (completed && deliveredAmount != requestedAmount) {
            throw new IllegalArgumentException(
                    "completed item delivery requires deliveredAmount == requestedAmount");
        }
    }

    public int remainingAmount() {
        return requestedAmount - deliveredAmount;
    }

    public static ItemDeliveryResult full(int amount) {
        return new ItemDeliveryResult(Math.max(0, amount), Math.max(0, amount), amount > 0);
    }

    public static ItemDeliveryResult failed(int requestedAmount) {
        return new ItemDeliveryResult(Math.max(0, requestedAmount), 0, false);
    }

    public static ItemDeliveryResult partial(int requestedAmount, int deliveredAmount) {
        return new ItemDeliveryResult(requestedAmount, deliveredAmount, false);
    }
}
