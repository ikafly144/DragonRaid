package net.sabafly.dragonraid.api.script;

/**
 * Scriptable predicate hook evaluated by data-driven achievement triggers or progression gates.
 */
@FunctionalInterface
public interface ScriptConditionHook {

    boolean test(ScriptHookContext context);
}
