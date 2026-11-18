package net.sabafly.dragonraid.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.sabafly.dragonraid.api.item.CustomItemDefinition;
import org.junit.jupiter.api.Test;

class DefaultRaidItemServiceTest {

    @Test
    void registersCustomItemsAndComputesAverageItemLevel() {
        DefaultRaidItemService itemService = new DefaultRaidItemService();
        itemService.registerItem(new CustomItemDefinition("blade_alpha", "item.blade_alpha", 120, Map.of("attack_damage_multiplier", 1.15)));
        itemService.registerItem(new CustomItemDefinition("helm_alpha", "item.helm_alpha", 100, Map.of("max_health_bonus", 10.0)));

        assertTrue(itemService.getItem("blade_alpha").isPresent());
        assertEquals(2, itemService.allItems().size());

        UUID player = UUID.randomUUID();
        assertEquals(0, itemService.averageItemLevel(player));

        itemService.setEquippedItems(player, List.of("blade_alpha", "helm_alpha"));
        assertEquals(110, itemService.averageItemLevel(player));

        itemService.clearEquippedItems(player);
        assertEquals(0, itemService.averageItemLevel(player));
    }

    @Test
    void rejectsBlankKeysAndNonFiniteBaseStats() {
        assertThrows(IllegalArgumentException.class,
                () -> new CustomItemDefinition("bad1", "item.bad1", 10, Map.of(" ", 1.0)));
        assertThrows(IllegalArgumentException.class,
                () -> new CustomItemDefinition("bad2", "item.bad2", 10, Map.of("attack_damage_multiplier", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> new CustomItemDefinition("bad3", "item.bad3", 10, Map.of("attack_damage_multiplier", Double.POSITIVE_INFINITY)));
    }
}
