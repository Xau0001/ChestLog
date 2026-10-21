package onl.xau.chestlog.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import onl.xau.chestlog.ChestLogPlugin;
import onl.xau.chestlog.LogService;
import onl.xau.chestlog.Messages;
import onl.xau.chestlog.PluginConfig;
import onl.xau.chestlog.admin.AdminStore;
import onl.xau.chestlog.container.ContainerResolver;
import onl.xau.chestlog.container.ContainerTarget;
import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogEntry;
import onl.xau.chestlog.storage.QueryResult;
import onl.xau.chestlog.util.TimeFormat;

/**
 * Das scrollbare Log-Fenster: Truheninventar mit 6 Zeilen, oben 5 Zeilen Einträge (neueste zuerst), unten Buttons.
 * Aus dem Fenster kommt kein Item heraus und keins hinein.
 *
 * <p>Die Einträge werden in einer Abfrage geladen, das Item-YAML aber nur für die sichtbare und die nächste Seite.
 * So bleibt der Speicher klein, auch wenn ein Container 2000 Einträge mit großen Items hat.
 */
public final class LogGui implements Listener {

    private static final int SIZE = 54;
    private static final int ENTRY_SLOTS = LogGuiHolder.ROWS * Scroller.COLUMNS;
    // sichtbare Seite und die nächste, damit Scrollen um eine Zeile meist schon geladen ist
    private static final int ITEM_WINDOW = 2 * ENTRY_SLOTS;
    // Höchstens so lange pro Tick Items dekodieren, der Rest folgt in den nächsten Ticks (gegen Lag bei großen Items).
    private static final long DECODE_BUDGET_NANOS = 3_000_000;
    private static final int SLOT_PAGE_UP = 45;
    private static final int SLOT_ROW_UP = 46;
    private static final int SLOT_FILTER = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_ROW_DOWN = 52;
    private static final int SLOT_PAGE_DOWN = 53;
    private static final int SLOT_EMPTY_NOTICE = 22;

    private record Loaded(QueryResult result, Map<Long, String> items) {
    }

    private final ChestLogPlugin plugin;
    private final LogService log;
    private final AdminStore admins;
    private final Set<UUID> loading = new HashSet<>();
    // Alle Log-Fenster, die noch existieren. Schwach referenziert, verschwinden also mit dem Fenster.
    private final Set<Inventory> windows = Collections.newSetFromMap(new WeakHashMap<>());

    public LogGui(ChestLogPlugin plugin, LogService log, AdminStore admins) {
        this.plugin = plugin;
        this.log = log;
        this.admins = admins;
    }

    /** Lädt die Einträge asynchron und öffnet danach das Fenster auf dem Main-Thread. */
    public void open(Player player, ContainerTarget target, Block clicked) {
        UUID id = player.getUniqueId();
        if (!loading.add(id)) {
            return; // es lädt schon
        }
        String coordinates = ContainerResolver.pos(clicked).coordinates();
        log.query(target)
                .thenCompose(result -> log.loadItems(itemIds(firstWindow(result)))
                        .exceptionally(e -> {
                            // Die Liste ist da; die Items lädt das Fenster beim Zeichnen noch einmal nach.
                            plugin.getLogger().log(Level.WARNING, "Items für das Log-Fenster nicht geladen", e);
                            return null;
                        })
                        .thenApply(items -> new Loaded(result, items)))
                .whenComplete((loaded, error) -> plugin.runSync(() -> {
                    loading.remove(id);
                    // inzwischen offline oder nicht mehr berechtigt
                    if (!player.isOnline() || !admins.isAdmin(id)) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Log für " + target.worldName() + " " + coordinates
                                + " konnte nicht geladen werden", error);
                        player.sendMessage(Messages.LOAD_FAILED);
                        return;
                    }
                    LogGuiHolder holder = new LogGuiHolder(target, coordinates, loaded.result());
                    if (loaded.items() != null) {
                        storeRaw(holder, firstWindow(loaded.result()), loaded.items());
                    }
                    show(player, holder);
                }));
    }

    /** Schließt alle offenen Log-Fenster, z. B. beim Deaktivieren des Plugins. */
    public void closeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            close(player);
        }
    }

    /** Schließt das Log-Fenster des Spielers, falls eins offen ist, z. B. wenn er nicht mehr berechtigt ist. */
    public void close(Player player) {
        if (windows.contains(player.getOpenInventory().getTopInventory())) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        LogGuiHolder holder = holderOf(top);
        if (holder == null) {
            return;
        }
        // jeden Klick abbrechen, auch im eigenen Inventar (Shift-Klick, Doppelklick, Zahlentasten, Q)
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean changed = switch (slot) {
            case SLOT_PAGE_UP -> holder.scroller().scroll(-ENTRY_SLOTS);
            case SLOT_ROW_UP -> holder.scroller().scroll(-Scroller.COLUMNS);
            case SLOT_ROW_DOWN -> holder.scroller().scroll(Scroller.COLUMNS);
            case SLOT_PAGE_DOWN -> holder.scroller().scroll(ENTRY_SLOTS);
            case SLOT_FILTER -> {
                holder.nextFilter();
                yield true;
            }
            case SLOT_CLOSE -> {
                // closeInventory() ist im Klick-Event nicht erlaubt, daher einen Tick später
                plugin.runSync(player::closeInventory);
                yield false;
            }
            default -> false;
        };
        if (changed) {
            render(holder);
        }
    }

    // Falls ein anderes Plugin den Klick wieder freigibt: zum Schluss nochmals abbrechen.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClickFinal(InventoryClickEvent event) {
        if (holderOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (holderOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragFinal(InventoryDragEvent event) {
        onDrag(event);
    }

    private LogGuiHolder holderOf(Inventory top) {
        // Erst die Mengenprüfung: getHolder() legt bei Block-Inventaren einen BlockState-Snapshot an.
        if (top == null || !windows.contains(top)) {
            return null;
        }
        return top.getHolder() instanceof LogGuiHolder holder ? holder : null;
    }

    private void show(Player player, LogGuiHolder holder) {
        Inventory inventory = plugin.getServer().createInventory(holder, SIZE, "Truhen-Log " + holder.coordinates());
        holder.setInventory(inventory);
        windows.add(inventory);
        render(holder);
        player.openInventory(inventory);
    }

    private void render(LogGuiHolder holder) {
        PluginConfig config = plugin.settings();
        long now = System.currentTimeMillis();
        List<LogEntry> visible = holder.visible();
        Scroller scroller = holder.scroller();
        ItemStack[] contents = new ItemStack[SIZE];

        long deadline = System.nanoTime() + DECODE_BUDGET_NANOS;
        boolean decodedAny = false;
        boolean deferred = false;
        for (int i = 0; i < ENTRY_SLOTS; i++) {
            int index = scroller.offset() + i;
            if (index >= visible.size()) {
                break;
            }
            LogEntry entry = visible.get(index);
            ItemStack item = holder.item(entry);
            if (item == null && LogGuiHolder.hasItem(entry) && holder.hasRaw(entry)) {
                // mindestens eins pro Durchgang, damit es auch bei einem sehr großen Item weitergeht
                if (!decodedAny || System.nanoTime() < deadline) {
                    item = EntryIcons.decode(entry, holder.takeRaw(entry));
                    holder.putItem(entry, item);
                    decodedAny = true;
                } else {
                    deferred = true;
                }
            }
            contents[i] = EntryIcons.icon(entry, item, now, config.timezone());
        }
        if (visible.isEmpty()) {
            contents[SLOT_EMPTY_NOTICE] = holder.filter() == LogFilter.ALL
                    ? named(Material.PAPER, ChatColor.GRAY + "Keine Einträge")
                    : named(Material.PAPER, ChatColor.GRAY + "Keine Einträge",
                            ChatColor.DARK_GRAY + "für den Filter „" + holder.filter().label() + "“");
        }

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ENTRY_SLOTS; i < SIZE; i++) {
            contents[i] = filler;
        }
        if (scroller.canScrollUp()) {
            contents[SLOT_PAGE_UP] = named(Material.SPECTRAL_ARROW, ChatColor.YELLOW + "▲▲ Eine Seite hoch");
            contents[SLOT_ROW_UP] = named(Material.ARROW, ChatColor.YELLOW + "▲ Eine Zeile hoch");
        }
        if (scroller.canScrollDown()) {
            contents[SLOT_ROW_DOWN] = named(Material.ARROW, ChatColor.YELLOW + "▼ Eine Zeile runter");
            contents[SLOT_PAGE_DOWN] = named(Material.SPECTRAL_ARROW, ChatColor.YELLOW + "▼▼ Eine Seite runter");
        }
        contents[SLOT_FILTER] = filterButton(holder.filter());
        contents[SLOT_INFO] = infoItem(holder, config);
        contents[SLOT_CLOSE] = named(Material.BARRIER, ChatColor.RED + "Schließen");

        holder.getInventory().setContents(contents);
        if (deferred) {
            renderNextTick(holder);
        }
        loadMissingItems(holder);
    }

    private void renderNextTick(LogGuiHolder holder) {
        if (!holder.scheduleRender()) {
            return;
        }
        plugin.runSync(() -> {
            holder.renderDone();
            if (!holder.getInventory().getViewers().isEmpty()) {
                render(holder);
            }
        });
    }

    /** Lädt das Item-YAML für die sichtbare und die nächste Seite nach und zeichnet danach neu. */
    private void loadMissingItems(LogGuiHolder holder) {
        int offset = holder.scroller().offset();
        List<LogEntry> missing = holder.missingItems(offset, offset + ITEM_WINDOW);
        if (missing.isEmpty()) {
            return;
        }
        holder.markLoading(missing);
        log.loadItems(itemIds(missing)).whenComplete((items, error) -> plugin.runSync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Items für das Log-Fenster konnten nicht geladen werden", error);
            }
            // auch bei einem Fehler eintragen (dann als Material), sonst würde es endlos neu versucht
            storeRaw(holder, missing, items == null ? Map.of() : items);
            holder.unmarkLoading(missing);
            if (!holder.getInventory().getViewers().isEmpty()) {
                render(holder);
            }
        }));
    }

    // Dekodiert wird erst beim Zeichnen, mit Zeitbudget.
    private static void storeRaw(LogGuiHolder holder, List<LogEntry> entries, Map<Long, String> items) {
        for (LogEntry entry : entries) {
            if (LogGuiHolder.hasItem(entry)) {
                holder.putRaw(entry, items.get(entry.id()));
            }
        }
    }

    // Die Einträge, die beim Öffnen sichtbar sind, und die nächste Seite.
    private static List<LogEntry> firstWindow(QueryResult result) {
        return result.entries().subList(0, Math.min(ITEM_WINDOW, result.entries().size()));
    }

    private static List<Long> itemIds(List<LogEntry> entries) {
        List<Long> ids = new ArrayList<>();
        for (LogEntry entry : entries) {
            if (LogGuiHolder.hasItem(entry)) {
                ids.add(entry.id());
            }
        }
        return ids;
    }

    private static ItemStack filterButton(LogFilter current) {
        List<String> lore = new ArrayList<>();
        for (LogFilter filter : LogFilter.values()) {
            lore.add(filter == current
                    ? ChatColor.GREEN + "» " + filter.label()
                    : ChatColor.GRAY + "  " + filter.label());
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Klicken zum Wechseln");
        return named(Material.HOPPER, ChatColor.YELLOW + "Filter: " + ChatColor.WHITE + current.label(), lore);
    }

    private static ItemStack infoItem(LogGuiHolder holder, PluginConfig config) {
        ContainerTarget target = holder.target();
        QueryResult result = holder.result();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Welt: " + ChatColor.WHITE + target.worldName());
        lore.add(ChatColor.GRAY + "Position: " + ChatColor.WHITE
                + target.positions().stream().map(BlockPos::coordinates).collect(Collectors.joining(" / ")));
        lore.add(ChatColor.GRAY + "Einträge: " + ChatColor.WHITE + result.totalCount());
        if (holder.filter() != LogFilter.ALL) {
            lore.add(ChatColor.GRAY + "Im Filter: " + ChatColor.WHITE + holder.visible().size());
        }
        if (result.totalCount() > 0) {
            lore.add(ChatColor.GRAY + "Ältester Eintrag: " + ChatColor.WHITE
                    + TimeFormat.dateTime(result.oldestTimestamp(), config.timezone()));
        }
        int size = holder.visible().size();
        if (size > 0) {
            int first = holder.scroller().offset() + 1;
            int last = Math.min(holder.scroller().offset() + ENTRY_SLOTS, size);
            lore.add(ChatColor.GRAY + "Anzeige: " + ChatColor.WHITE + first + "–" + last + " von " + size);
        }
        if (result.truncated()) {
            lore.add(ChatColor.YELLOW + "Nur die neuesten " + result.entries().size() + " geladen");
        }
        return named(Material.BOOK, ChatColor.GOLD + "Truhen-Log", lore);
    }

    static ItemStack named(Material material, String name, String... lore) {
        return named(material, name, List.of(lore));
    }

    static ItemStack named(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
