package net.sabafly.dragonraid.api.script;

import java.util.Set;

/**
 * Thread-safe registry of named {@link ScriptConditionHook}s and {@link ScriptActionHook}s
 * shared across achievement, reward, progression, and M4 Kotlin script modules.
 */
public interface ScriptHookRegistry {

    void registerCondition(String hookId, ScriptConditionHook hook);

    boolean unregisterCondition(String hookId);

    /**
     * Evaluates the condition hook identified by {@code hookId}.
     *
     * @return {@code false} if no hook is registered for {@code hookId}
     */
    boolean evaluateCondition(String hookId, ScriptHookContext context);

    Set<String> conditionIds();

    void registerAction(String hookId, ScriptActionHook hook);

    boolean unregisterAction(String hookId);

    /**
     * Executes the action hook identified by {@code hookId}.
     *
     * @return {@code true} if a registered hook was found and invoked
     */
    boolean executeAction(String hookId, ScriptHookContext context);

    Set<String> actionIds();
}
