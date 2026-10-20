package onl.xau.chestlog.diff;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Vergleicht zwei Inventarstände als Mengen pro Item-Art. Reine Funktionen, ohne Bukkit.
 */
public final class InventoryDiff {

    private InventoryDiff() {
    }

    /**
     * Differenz pro Schlüssel: positiv = hinzugefügt, negativ = entnommen. Schlüssel ohne Änderung fehlen im Ergebnis.
     * Umsortieren innerhalb des Inventars ergibt daher eine leere Map.
     */
    public static <K> Map<K, Integer> diff(Map<K, Integer> before, Map<K, Integer> after) {
        Map<K, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<K, Integer> entry : before.entrySet()) {
            int delta = after.getOrDefault(entry.getKey(), 0) - entry.getValue();
            if (delta != 0) {
                result.put(entry.getKey(), delta);
            }
        }
        for (Map.Entry<K, Integer> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey()) && entry.getValue() != 0) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Zählt Mengen pro Schlüssel. {@code null}-Elemente und Elemente mit Menge ≤ 0 werden übersprungen.
     */
    public static <T, K> Map<K, Integer> count(T[] items, Function<T, K> key, ToIntFunction<T> amount) {
        Map<K, Integer> result = new LinkedHashMap<>();
        for (T item : items) {
            if (item == null) {
                continue;
            }
            int n = amount.applyAsInt(item);
            if (n <= 0) {
                continue;
            }
            K k = key.apply(item);
            if (k != null) {
                result.merge(k, n, Integer::sum);
            }
        }
        return result;
    }
}
