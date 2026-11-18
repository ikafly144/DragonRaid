package net.sabafly.dragonraid.api.script;

import java.util.Map;

/**
 * Modular, scriptable unit of mechanic logic executed during an encounter timeline.
 */
public interface GimmickAction {

    /**
     * Unique uppercase action identifier (e.g. {@code "SWEEPING_LASER"}).
     */
    String id();

    /**
     * Initiates or performs this mechanic against the Folia-safe {@code context}.
     */
    void execute(RaidScriptContext context, Map<String, Object> params);
}
