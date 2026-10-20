package onl.xau.chestlog.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
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
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import onl.xau.chestlog.LogService;
import onl.xau.chestlog.Messages;
import onl.xau.chestlog.PluginConfig;
import onl.xau.chestlog.container.ContainerTarget;
import onl.xau.chestlog.container.ItemCodec;
import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogEntry;
import onl.xau.chestlog.storage.QueryResult;
import onl.xau.chestlog.util.TimeFormat;

/**
 * Das scrollbare Log-Fenster: Truheninventar mit 6 Zeilen, oben 5 Zeilen Einträge (neueste zuerst), unten Buttons.
 * Aus dem Fenster kommt kein Item heraus und keins hinein.
 */
public final class LogGui implements Listener {

    private static final int SIZE = 54;
    private static final int ENTRY_SLOTS = LogGuiHolder.ROWS * Scroller.COLUMNS;
    private static final int SLOT_PAGE_UP = 45;
    private static final int SLOT_ROW_UP = 46;
    private static final int SLOT_FILTER = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_ROW_DOWN = 52;
    private static final int SLOT_PAGE_DOWN = 53;
    private static final int SLOT_EMPTY_NOTICE = 22;

    private final Plugin plugin;
    private final LogService log;
    private final Supplier<PluginConfig> settings;
    private final Logger logger;
    private final Set<UUID> loading = new HashSet<>();
    // Alle Log-Fenster, die noch existieren. Schwach referenziert, verschwinden also mit dem Fenster.
    private final Set<Inventory> windows = Collections.newSetFromMap(new WeakHashMap<>());

    public LogGui(Plugin plugin, LogService log, Supplier<PluginConfig> settings, Logger logger) {
        this.plugin = plugin;
        this.log = log;
        this.settings = settings;
        this.logger = logger;
    }

    /** Lädt die Einträge asynchron und öffnet danach das Fenster auf dem Main-Thread. */
    public void open(Player player, ContainerTarget target, Block clicked) {
        UUID id = player.getUniqueId();
        if (!loading.add(id)) {
            return; // es lädt schon
        }
        String coordinates = clicked.getX() + " " + clicked.getY() + " " + clicked.getZ();
        log.query(target).whenComplete((result, error) -> runSync(() -> {
            loading.remove(id);
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Log für " + target.worldName() + " " + coordinates
                        + " konnte nicht geladen werden", error);
                player.sendMessage(Messages.LOAD_FAILED);
                return;
            }
            show(player, new LogGuiHolder(target, coordinates, result));
        }));
    }

    /** Schließt alle offenen Log-Fenster, z. B. beim Deaktivieren des Plugins. */
    public void closeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (windows.contains(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
            }
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
                runSync(player::closeInventory);
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
        PluginConfig config = settings.get();
        long now = System.currentTimeMillis();
        List<LogEntry> visible = holder.visible();
        Scroller scroller = holder.scroller();
        ItemStack[] contents = new ItemStack[SIZE];

        for (int i = 0; i < ENTRY_SLOTS; i++) {
            int index = scroller.offset() + i;
            if (index < visible.size()) {
                contents[i] = EntryIcons.icon(holder.iconCache(), visible.get(index), now, config.timezone());
            }
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

    private void runSync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) {
            // Plugin wird gerade deaktiviert
        }
    }

    /** Für {@link EntryIcons}: Item aus der Datenbank, ersatzweise Material, sonst Barriere mit Materialname. */
    static ItemStack baseItem(LogEntry entry) {
        ItemStack decoded = ItemCodec.decode(entry.item());
        if (decoded != null) {
            return decoded;
        }
        Material material = entry.material() == null ? null : Material.matchMaterial(entry.material());
        if (material != null && material.isItem() && !material.isAir()) {
            return new ItemStack(material);
        }
        return named(Material.BARRIER, ChatColor.RED + (entry.material() == null ? "Unbekannt" : entry.material()));
    }
}
