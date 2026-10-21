package onl.xau.chestlog.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import onl.xau.chestlog.container.ContainerTarget;
import onl.xau.chestlog.model.LogAction;
import onl.xau.chestlog.model.LogEntry;
import onl.xau.chestlog.storage.QueryResult;

/** Zustand eines geöffneten Log-Fensters: Container, geladene Einträge, Filter und Scroll-Position. */
public final class LogGuiHolder implements InventoryHolder {

    static final int ROWS = 5;
    // Dekodierte Items, höchstens so viele: hält den Speicher klein, auch wenn jemand durch alle Seiten blättert.
    private static final int MAX_CACHED_ITEMS = 6 * ROWS * Scroller.COLUMNS;

    private final ContainerTarget target;
    private final String coordinates;
    private final QueryResult result;
    private final Scroller scroller;
    private final Map<Long, ItemStack> items = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ItemStack> eldest) {
            return size() > MAX_CACHED_ITEMS;
        }
    };
    private final Set<Long> loading = new HashSet<>();
    private Inventory inventory;
    private LogFilter filter = LogFilter.ALL;
    private List<LogEntry> visible;

    LogGuiHolder(ContainerTarget target, String coordinates, QueryResult result) {
        this.target = target;
        this.coordinates = coordinates;
        this.result = result;
        this.visible = result.entries();
        this.scroller = new Scroller(ROWS, visible.size());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    ContainerTarget target() {
        return target;
    }

    String coordinates() {
        return coordinates;
    }

    QueryResult result() {
        return result;
    }

    LogFilter filter() {
        return filter;
    }

    /** Die Einträge nach Filter, neueste zuerst. */
    List<LogEntry> visible() {
        return visible;
    }

    Scroller scroller() {
        return scroller;
    }

    void nextFilter() {
        filter = filter.next();
        visible = filter == LogFilter.ALL
                ? result.entries()
                : result.entries().stream().filter(e -> filter.matches(e.action())).toList();
        scroller.reset(visible.size());
    }

    /** Das dekodierte Item eines Eintrags oder {@code null}, wenn es (noch) nicht geladen ist. */
    ItemStack item(LogEntry entry) {
        return items.get(entry.id());
    }

    void putItem(LogEntry entry, ItemStack stack) {
        items.put(entry.id(), stack);
    }

    /** Einträge im Bereich {@code [from, to)} der Anzeige, deren Item weder geladen ist noch gerade lädt. */
    List<LogEntry> missingItems(int from, int to) {
        List<LogEntry> missing = new ArrayList<>();
        for (int i = Math.max(0, from); i < Math.min(to, visible.size()); i++) {
            LogEntry entry = visible.get(i);
            if (hasItem(entry) && !items.containsKey(entry.id()) && !loading.contains(entry.id())) {
                missing.add(entry);
            }
        }
        return missing;
    }

    void markLoading(List<LogEntry> entries) {
        entries.forEach(e -> loading.add(e.id()));
    }

    void unmarkLoading(List<LogEntry> entries) {
        entries.forEach(e -> loading.remove(e.id()));
    }

    /** Einträge, deren Icon das Item selbst ist. */
    static boolean hasItem(LogEntry entry) {
        return entry.action() == LogAction.ADD || entry.action() == LogAction.REMOVE
                || entry.action() == LogAction.DROP;
    }
}
