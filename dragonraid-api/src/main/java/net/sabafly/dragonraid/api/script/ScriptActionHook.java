package net.sabafly.dragonraid.api.script;

/**
 * Scriptable action hook executed by data-driven rewards or vanilla progression clear hooks.
 */
@FunctionalInterface
public interface ScriptActionHook {

    void execute(ScriptHookContext context);
}
