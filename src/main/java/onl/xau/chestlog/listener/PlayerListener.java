package onl.xau.chestlog.listener;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import onl.xau.chestlog.admin.AdminStore;

/** Hält die gespeicherten Namen der Berechtigten aktuell. */
public final class PlayerListener implements Listener {

    private final AdminStore admins;
    private final Logger logger;

    public PlayerListener(AdminStore admins, Logger logger) {
        this.admins = admins;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (admins.updateName(player.getUniqueId(), player.getName())) {
            admins.save().exceptionally(e -> {
                logger.log(Level.SEVERE, "admins.yml konnte nicht gespeichert werden", e);
                return null;
            });
        }
    }
}
