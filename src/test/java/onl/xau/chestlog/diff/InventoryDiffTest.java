package onl.xau.chestlog.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InventoryDiffTest {

    /** Ein Slot mit Item-Art und Menge, {@code null} = leerer Slot. */
    private record Slot(String kind, int amount) {
    }

    private static Map<String, Integer> count(Slot... slots) {
        return InventoryDiff.count(slots, Slot::kind, Slot::amount);
    }

    private static Map<String, Integer> map(Object... kindAndAmount) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kindAndAmount.length; i += 2) {
            m.put((String) kindAndAmount[i], (Integer) kindAndAmount[i + 1]);
        }
        return m;
    }

    @Test
    void hinzufuegen() {
        Map<String, Integer> before = count(new Slot("stone", 10), null, null);
        Map<String, Integer> after = count(new Slot("stone", 10), new Slot("dirt", 64), new Slot("stone", 5));

        assertEquals(map("stone", 5, "dirt", 64), InventoryDiff.diff(before, after));
    }

    @Test
    void entnehmen() {
        Map<String, Integer> before = count(new Slot("stone", 64), new Slot("diamond", 3));
        Map<String, Integer> after = count(new Slot("stone", 60), null);

        assertEquals(map("stone", -4, "diamond", -3), InventoryDiff.diff(before, after));
    }

    @Test
    void umsortierenErzeugtKeinenEintrag() {
        Map<String, Integer> before = count(new Slot("stone", 64), null, new Slot("dirt", 32), new Slot("stone", 1));
        Map<String, Integer> after = count(null, new Slot("dirt", 16), new Slot("stone", 65), new Slot("dirt", 16));

        assertTrue(InventoryDiff.diff(before, after).isEmpty());
    }

    @Test
    void tauschZweierItemArten() {
        // Zahlentaste: Stein aus dem Container gegen Erde aus der Hotbar getauscht
        Map<String, Integer> before = count(new Slot("stone", 64), new Slot("oak_log", 8));
        Map<String, Integer> after = count(new Slot("dirt", 12), new Slot("oak_log", 8));

        assertEquals(map("stone", -64, "dirt", 12), InventoryDiff.diff(before, after));
    }

    @Test
    void teilweiseStacks() {
        // Rechtsklick nimmt die Hälfte, danach wird ein Teil wieder abgelegt
        Map<String, Integer> before = count(new Slot("iron_ingot", 33));
        Map<String, Integer> afterHalf = count(new Slot("iron_ingot", 16));
        Map<String, Integer> afterPlaceOne = count(new Slot("iron_ingot", 16), new Slot("iron_ingot", 1));

        assertEquals(map("iron_ingot", -17), InventoryDiff.diff(before, afterHalf));
        assertEquals(map("iron_ingot", 1), InventoryDiff.diff(afterHalf, afterPlaceOne));
    }

    @Test
    void leereInventare() {
        Map<String, Integer> empty = count(null, null, null);

        assertTrue(empty.isEmpty());
        assertTrue(InventoryDiff.diff(empty, empty).isEmpty());
        assertEquals(map("stone", 1), InventoryDiff.diff(empty, count(new Slot("stone", 1))));
        assertEquals(map("stone", -1), InventoryDiff.diff(count(new Slot("stone", 1)), empty));
    }

    @Test
    void zaehlenUeberspringtLeereUndNullmengen() {
        Map<String, Integer> counted = count(null, new Slot("stone", 0), new Slot("stone", 3), new Slot("dirt", 2),
                new Slot("stone", 4));

        assertEquals(map("stone", 7, "dirt", 2), counted);
    }
}
