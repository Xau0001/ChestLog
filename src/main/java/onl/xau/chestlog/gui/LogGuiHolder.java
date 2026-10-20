package onl.xau.chestlog.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import onl.xau.chestlog.container.ContainerTarget;
import onl.xau.chestlog.model.LogEntry;
import onl.xau.chestlog.storage.QueryResult;

/** Zustand eines geöffneten Log-Fensters: Container, geladene Einträge, Filter und Scroll-Position. */
public final class LogGuiHolder implements InventoryHolder {

    static final int ROWS = 5;

    private final ContainerTarget target;
    private final String coordinates;
    private final QueryResult result;
    private final Scroller scroller;
    // dekodierte Item-Icons nach Eintrags-ID, damit beim Scrollen nicht jedes Mal YAML gelesen wird
    private final Map<Long, ItemStack> iconCache = new HashMap<>();
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

    Map<Long, ItemStack> iconCache() {
        return iconCache;
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
}
