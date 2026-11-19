package net.sabafly.dragonraid.script;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.sabafly.dragonraid.api.script.ScriptActionHook;
import net.sabafly.dragonraid.api.script.ScriptConditionHook;
import net.sabafly.dragonraid.api.script.ScriptHookContext;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;

/**
 * Thread-safe {@link ScriptHookRegistry} storing condition and action hooks for data-driven
 * achievements, rewards, progression rules, and Kotlin DSL scripts.
 */
public final class DefaultScriptHookRegistry implements ScriptHookRegistry {

    private final Map<String, ScriptConditionHook> conditionHooks = new ConcurrentHashMap<>();
    private final Map<String, ScriptActionHook> actionHooks = new ConcurrentHashMap<>();

    @Override
    public void registerCondition(String hookId, ScriptConditionHook hook) {
        String key = requireHookId(hookId);
        Objects.requireNonNull(hook, "hook");
        conditionHooks.put(key, hook);
    }

    @Override
    public boolean unregisterCondition(String hookId) {
        if (hookId == null || hookId.isBlank()) {
            return false;
        }
        return conditionHooks.remove(hookId.trim()) != null;
    }

    @Override
    public boolean evaluateCondition(String hookId, ScriptHookContext context) {
        Objects.requireNonNull(context, "context");
        if (hookId == null || hookId.isBlank()) {
            return false;
        }
        ScriptConditionHook hook = conditionHooks.get(hookId.trim());
        return hook != null && hook.test(context);
    }

    @Override
    public Set<String> conditionIds() {
        return Set.copyOf(conditionHooks.keySet());
    }

    @Override
    public void registerAction(String hookId, ScriptActionHook hook) {
        String key = requireHookId(hookId);
        Objects.requireNonNull(hook, "hook");
        actionHooks.put(key, hook);
    }

    @Override
    public boolean unregisterAction(String hookId) {
        if (hookId == null || hookId.isBlank()) {
            return false;
        }
        return actionHooks.remove(hookId.trim()) != null;
    }

    @Override
    public boolean executeAction(String hookId, ScriptHookContext context) {
        Objects.requireNonNull(context, "context");
        if (hookId == null || hookId.isBlank()) {
            return false;
        }
        ScriptActionHook hook = actionHooks.get(hookId.trim());
        if (hook == null) {
            return false;
        }
        hook.execute(context);
        return true;
    }

    @Override
    public Set<String> actionIds() {
        return Set.copyOf(actionHooks.keySet());
    }

    private static String requireHookId(String hookId) {
        if (hookId == null || hookId.isBlank()) {
            throw new IllegalArgumentException("hookId must not be blank");
        }
        return hookId.trim();
    }
}
