package onl.xau.chestlog.listener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;

import onl.xau.chestlog.LogService;
import onl.xau.chestlog.container.ContainerResolver;
import onl.xau.chestlog.container.ContainerTarget;
import onl.xau.chestlog.container.ItemKey;
import onl.xau.chestlog.diff.InventoryDiff;

/**
 * Logging von OPEN, ADD/REMOVE (Diff pro Klick), PLACE, BREAK und DROP.
 *
 * <p>ADD/REMOVE: Bei jedem Klick oder Drag in einer Ansicht mit erfasstem Container oben wird ein Snapshot des oberen
 * Inventars angelegt und einen Tick später mit dem neuen Stand verglichen. Pro Spieler und Inventar gibt es höchstens
 * einen Snapshot pro Tick.
 */
public final class ContainerListener implements Listener {

    /** Das obere Inventar der aktuellen Ansicht eines Spielers und der zugehörige Container ({@code null} = keiner). */
    private record Session(Inventory inventory, ContainerTarget target) {
    }

    private record PendingKey(UUID player, Inventory inventory) {
    }

    private record Pending(Player player, Inventory inventory, ContainerTarget target, Map<ItemKey, Integer> before) {
    }

    private static final int MAX_BUNDLE_DEPTH = 4;

    private final Plugin plugin;
    private final LogService log;
    private final ContainerResolver resolver;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<PendingKey, Pending> pending = new HashMap<>();

    public ContainerListener(Plugin plugin, LogService log, ContainerResolver resolver) {
        this.plugin = plugin;
        this.log = log;
        this.resolver = resolver;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        ContainerTarget target = resolver.resolve(top);
        sessions.put(player.getUniqueId(), new Session(top, target));
        if (target != null) {
            log.logOpen(player, target);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.inventory().equals(event.getInventory())) {
            sessions.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        // auch Klicks im eigenen Inventar unten: Shift-Klick und Doppelklick verändern das obere Inventar
        track(event.getWhoClicked(), event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        int topSize = top.getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                track(event.getWhoClicked(), top);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ContainerTarget target = resolver.resolve(event.getBlockPlaced());
        if (target != null) {
            log.logPlace(event.getPlayer(), target);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        ContainerTarget target = resolver.resolve(block);
        if (target == null) {
            return;
        }
        // Klicks aus diesem Tick jetzt auswerten, solange der Inhalt noch im Container liegt. Nach dem Abbau wäre er
        // herausgefallen und würde sonst dem Klickenden als entnommen zugerechnet.
        completePendingAt(target);
        Map<ItemKey, Integer> drops = Map.of();
        BlockState state = block.getState();
        // Shulkerkisten behalten ihren Inhalt im Item, da fällt nichts heraus.
        if (state instanceof Container container && !(state instanceof ShulkerBox)) {
            drops = InventoryDiff.count(container.getSnapshotInventory().getContents(), ItemKey::of,
                    ItemStack::getAmount);
        }
        log.logBreak(event.getPlayer(), target, ContainerResolver.pos(block), drops);
    }

    /** Wertet alle geplanten Vergleiche sofort aus, z. B. beim Deaktivieren des Plugins. */
    public void completeAll() {
        for (PendingKey key : new ArrayList<>(pending.keySet())) {
            complete(key);
        }
    }

    private void completePendingAt(ContainerTarget target) {
        for (Map.Entry<PendingKey, Pending> entry : new ArrayList<>(pending.entrySet())) {
            if (!Collections.disjoint(entry.getValue().target().positions(), target.positions())) {
                complete(entry.getKey());
            }
        }
    }

    private void track(HumanEntity who, Inventory top) {
        if (!(who instanceof Player player)) {
            return;
        }
        ContainerTarget target = target(player, top);
        if (target == null) {
            return;
        }
        PendingKey key = new PendingKey(player.getUniqueId(), top);
        if (pending.containsKey(key)) {
            return; // in diesem Tick ist schon ein Vergleich geplant
        }
        pending.put(key, new Pending(player, top, target, snapshot(top)));
        plugin.getServer().getScheduler().runTask(plugin, () -> complete(key));
    }

    private ContainerTarget target(Player player, Inventory top) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.inventory().equals(top)) {
            return session.target();
        }
        // z. B. nach einem Neuladen des Plugins bei schon geöffnetem Inventar
        ContainerTarget target = resolver.resolve(top);
        sessions.put(player.getUniqueId(), new Session(top, target));
        return target;
    }

    private void complete(PendingKey key) {
        Pending p = pending.remove(key);
        if (p == null) {
            return;
        }
        Map<ItemKey, Integer> diff = InventoryDiff.diff(p.before(), snapshot(p.inventory()));
        // Fehlt ein Teil des Containers (z. B. durch eine Explosion), ist der Inhalt herausgefallen, nicht entnommen.
        if (!diff.isEmpty() && resolver.isStillPresent(p.target())) {
            log.logChanges(p.player(), p.target(), diff);
        }
    }

    /**
     * Mengen pro Item-Art im Inventar. Bündel zählen mit ihrem Inhalt: das Bündel selbst als leeres Bündel, die Items
     * darin einzeln. Sonst würde Herausnehmen aus einem Bündel im Container nur als „Bündel getauscht“ erscheinen.
     */
    static Map<ItemKey, Integer> snapshot(Inventory inventory) {
        Map<ItemKey, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : inventory.getContents()) {
            count(counts, stack, 0);
        }
        return counts;
    }

    private static void count(Map<ItemKey, Integer> counts, ItemStack stack, int depth) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        // Materialname zuerst prüfen: getItemMeta() legt jedes Mal eine Kopie an.
        if (depth < MAX_BUNDLE_DEPTH && stack.getType().name().endsWith("BUNDLE")
                && stack.getItemMeta() instanceof BundleMeta bundle) {
            List<ItemStack> contents = bundle.hasItems() ? bundle.getItems() : List.of();
            // Jedes Bündel, auch ein schon leeres, geht durch dieselbe Meta-Umwandlung. So ergeben ein geleertes und
            // ein von Vanilla leer erzeugtes Bündel denselben Schlüssel.
            ItemStack empty = stack.clone();
            BundleMeta emptyMeta = (BundleMeta) empty.getItemMeta();
            emptyMeta.setItems(null);
            empty.setItemMeta(emptyMeta);
            counts.merge(ItemKey.of(empty), stack.getAmount(), Integer::sum);
            for (int i = 0; i < stack.getAmount(); i++) {
                for (ItemStack inner : contents) {
                    count(counts, inner, depth + 1);
                }
            }
            return;
        }
        ItemKey key = ItemKey.of(stack);
        if (key != null) {
            counts.merge(key, stack.getAmount(), Integer::sum);
        }
    }
}
