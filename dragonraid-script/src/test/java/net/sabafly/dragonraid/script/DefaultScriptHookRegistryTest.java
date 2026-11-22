package net.sabafly.dragonraid.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.sabafly.dragonraid.api.script.ScriptHookContext;
import org.junit.jupiter.api.Test;

class DefaultScriptHookRegistryTest {

    @Test
    void registersEvaluatesExecutesAndUnregistersHooksSafely() {
        DefaultScriptHookRegistry registry = new DefaultScriptHookRegistry();
        UUID playerId = UUID.randomUUID();
        ScriptHookContext ctx = new ScriptHookContext(
                null,
                playerId,
                "Alice",
                List.of(playerId),
                null,
                Map.of("minScore", 10),
                Map.of("score", 25)
        );

        assertFalse(registry.evaluateCondition("missing_cond", ctx));
        assertFalse(registry.executeAction("missing_action", ctx));

        registry.registerCondition("score_ok", c ->
                ((Number) c.attribute("score").orElse(0)).intValue() >= ((Number) c.param("minScore").orElse(0)).intValue());
        assertEquals(Set.of("score_ok"), registry.conditionIds());
        assertTrue(registry.evaluateCondition("score_ok", ctx));

        AtomicInteger counter = new AtomicInteger();
        registry.registerAction("bump_counter", c -> counter.incrementAndGet());
        assertEquals(Set.of("bump_counter"), registry.actionIds());
        assertTrue(registry.executeAction("bump_counter", ctx));
        assertEquals(1, counter.get());

        assertTrue(registry.unregisterCondition("score_ok"));
        assertFalse(registry.evaluateCondition("score_ok", ctx));
        assertTrue(registry.unregisterAction("bump_counter"));
        assertFalse(registry.executeAction("bump_counter", ctx));
    }
}
