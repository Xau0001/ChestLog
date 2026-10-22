package onl.xau.chestlog.listener;

import java.util.function.Supplier;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import onl.xau.chestlog.Messages;
import onl.xau.chestlog.PluginConfig;
import onl.xau.chestlog.admin.AdminStore;
import onl.xau.chestlog.container.ContainerResolver;
import onl.xau.chestlog.container.ContainerTarget;
import onl.xau.chestlog.gui.LogGui;

/** Rechtsklick mit dem Werkzeug auf einen Container öffnet für Berechtigte das Log-Fenster. */
public final class InspectorListener implements Listener {

    private final Supplier<PluginConfig> settings;
    private final AdminStore admins;
    private final ContainerResolver resolver;
    private final LogGui gui;

    public InspectorListener(Supplier<PluginConfig> settings, AdminStore admins, ContainerResolver resolver,
                             LogGui gui) {
        this.settings = settings;
        this.admins = admins;
        this.resolver = resolver;
        this.gui = gui;
    }

    // LOWEST und auch abgebrochene Events: funktioniert so auch in geschützten Bereichen.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!holdsTool(player) || !admins.isAdmin(player.getUniqueId())) {
            return;
        }
        Block block = event.getClickedBlock();
        ContainerTarget target = resolver.resolve(block);
        if (target == null) {
            return;
        }
        // Der Container öffnet sich nicht und wird daher auch nicht als OPEN geloggt.
        event.setCancelled(true);
        gui.open(player, target, block);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!settings.get().protectContainersWithTool() || !holdsTool(player)
                || !admins.isAdmin(player.getUniqueId())) {
            return;
        }
        if (resolver.resolve(event.getBlock()) != null) {
            event.setCancelled(true);
            player.sendMessage(Messages.PROTECTED);
        }
    }

    private boolean holdsTool(Player player) {
        return player.getInventory().getItemInMainHand().getType() == settings.get().tool();
    }
}
