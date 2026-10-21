package onl.xau.chestlog.listener;

import java.util.ArrayList;
import java.util.HashMap;
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
import org.bukkit.plugin.Plugin;

import onl.xau.chestlog.LogService;
import onl.xau.chestlog.container.ContainerResolver;
import onl.xau.chestlog.container.ContainerTarget;
import onl.xau.chestlog.container.ItemKey;
import onl.xau.chestlog.diff.InventoryDiff;
import onl.xau.chestlog.model.BlockPos;

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
        Map<ItemKey, Integer> drops = Map.of();
        BlockState state = block.getState();
        // Shulkerkisten behalten ihren Inhalt im Item, da fällt nichts heraus.
        if (state instanceof Container container && !(state instanceof ShulkerBox)) {
            drops = snapshot(container.getSnapshotInventory());
        }
        BlockPos broken = new BlockPos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        log.logBreak(event.getPlayer(), target, broken, drops);
    }

    /** Wertet alle geplanten Vergleiche sofort aus, z. B. beim Deaktivieren des Plugins. */
    public void completeAll() {
        for (PendingKey key : new ArrayList<>(pending.keySet())) {
            complete(key);
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
        // Wurde der Container im selben Tick abgebaut, ist der Inhalt herausgefallen und nicht entnommen.
        if (!diff.isEmpty() && resolver.isStillPresent(p.target())) {
            log.logChanges(p.player(), p.target(), diff);
        }
    }

    private static Map<ItemKey, Integer> snapshot(Inventory inventory) {
        return InventoryDiff.count(inventory.getContents(), ItemKey::of, ItemStack::getAmount);
    }
}
