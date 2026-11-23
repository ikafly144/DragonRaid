package net.sabafly.dragonraid.api.party;

/**
 * Listener notified after party membership, leadership, or connection state transitions complete.
 */
@FunctionalInterface
public interface PartyChangeListener {

    void onPartyChanged(PartyChangeEvent event);
}
