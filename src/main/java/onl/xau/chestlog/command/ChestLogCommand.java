package onl.xau.chestlog.command;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import onl.xau.chestlog.ChestLogPlugin;
import onl.xau.chestlog.Messages;
import onl.xau.chestlog.admin.AdminStore;
import onl.xau.chestlog.admin.AdminStore.Admin;

/**
 * {@code /chestlog} (Alias {@code /cl}). Erlaubt für die Konsole und für Spieler aus {@code admins.yml}; ohne
 * Bukkit-Permission, die Prüfung passiert hier.
 */
public final class ChestLogCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("add", "remove", "list", "tool", "reload", "help");
    private static final String CONSOLE = "CONSOLE";
    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ChestLogPlugin plugin;
    private final AdminStore admins;
    private final Logger logger;

    public ChestLogCommand(ChestLogPlugin plugin, AdminStore admins) {
        this.plugin = plugin;
        this.admins = admins;
        this.logger = plugin.getLogger();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isAllowed(sender)) {
            sender.sendMessage(Messages.NO_PERMISSION);
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add" -> add(sender, label, args);
            case "remove" -> remove(sender, label, args);
            case "list" -> list(sender);
            case "tool" -> tool(sender);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isAllowed(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("add")) {
                List<String> names = new ArrayList<>();
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (!admins.isAdmin(player.getUniqueId())) {
                        names.add(player.getName());
                    }
                }
                return matching(names, args[1]);
            }
            if (sub.equals("remove")) {
                return matching(admins.list().stream().map(Admin::name).filter(n -> n != null).toList(), args[1]);
            }
        }
        return List.of();
    }

    private boolean isAllowed(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) {
            return true;
        }
        return sender instanceof Player player && admins.isAdmin(player.getUniqueId());
    }

    private static boolean isConsole(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    private void add(CommandSender sender, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(Messages.error("Benutzung: /" + label + " add <Spieler>"));
            return;
        }
        String name = args[1];
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            finishAdd(sender, online.getUniqueId(), online.getName());
            return;
        }
        // getOfflinePlayers() liest Spielerdateien, daher nicht auf dem Main-Thread. Kein Mojang-Lookup.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer found = null;
            for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
                String playerName = player.getName();
                if (playerName != null && playerName.equalsIgnoreCase(name)) {
                    found = player;
                    break;
                }
            }
            UUID uuid = found == null ? null : found.getUniqueId();
            String foundName = found == null ? null : found.getName();
            plugin.runSync(() -> {
                if (uuid == null) {
                    sender.sendMessage(Messages.error("Spieler war noch nie auf dem Server: " + name));
                } else {
                    finishAdd(sender, uuid, foundName);
                }
            });
        });
    }

    private void finishAdd(CommandSender sender, UUID uuid, String name) {
        String addedBy = isConsole(sender) ? CONSOLE : sender.getName();
        String addedAt = OffsetDateTime.now(plugin.settings().timezone()).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (!admins.add(new Admin(uuid, name, addedBy, addedAt))) {
            sender.sendMessage(Messages.info(ChatColor.WHITE + name + ChatColor.GRAY + " ist schon berechtigt."));
            return;
        }
        save(sender);
        sender.sendMessage(Messages.info(ChatColor.WHITE + name + ChatColor.GRAY + " ist jetzt berechtigt."));
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player != sender) {
            player.sendMessage(Messages.info("Du kannst jetzt Container untersuchen: Rechtsklick mit dem Werkzeug, "
                    + "das du mit " + ChatColor.WHITE + "/chestlog tool" + ChatColor.GRAY + " bekommst."));
        }
    }

    private void remove(CommandSender sender, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(Messages.error("Benutzung: /" + label + " remove <Spieler>"));
            return;
        }
        Admin admin = admins.findByName(args[1]).orElse(null);
        if (admin == null) {
            try {
                admin = admins.get(UUID.fromString(args[1])).orElse(null);
            } catch (IllegalArgumentException e) {
                // kein UUID-Format
            }
        }
        if (admin == null) {
            sender.sendMessage(Messages.error(args[1] + " steht nicht in der Liste."));
            return;
        }
        switch (admins.remove(admin.uuid(), isConsole(sender))) {
            case REMOVED -> {
                save(sender);
                sender.sendMessage(Messages.info(ChatColor.WHITE + admin.name() + ChatColor.GRAY
                        + " ist nicht mehr berechtigt."));
            }
            case LAST_ADMIN -> sender.sendMessage(Messages.error(
                    "Den letzten Eintrag kann nur die Konsole entfernen."));
            case NOT_FOUND -> sender.sendMessage(Messages.error(args[1] + " steht nicht in der Liste."));
        }
    }

    private void list(CommandSender sender) {
        List<Admin> list = admins.list();
        if (list.isEmpty()) {
            sender.sendMessage(Messages.info("Niemand ist berechtigt. Eintragen über die Konsole: chestlog add <Name>"));
            return;
        }
        sender.sendMessage(Messages.info("Berechtigte Spieler (" + list.size() + "):"));
        for (Admin admin : list) {
            sender.sendMessage(ChatColor.DARK_GRAY + " - " + ChatColor.WHITE + admin.name()
                    + ChatColor.GRAY + ", hinzugefügt von " + ChatColor.WHITE + admin.addedBy()
                    + ChatColor.GRAY + " am " + ChatColor.WHITE + formatDate(admin.addedAt()));
        }
    }

    private void tool(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.error("Das geht nur als Spieler."));
            return;
        }
        ItemStack tool = new ItemStack(plugin.settings().tool());
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(tool);
        for (ItemStack stack : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
        player.sendMessage(Messages.info("Rechtsklick mit dem Werkzeug auf einen Container zeigt das Log."));
    }

    private void reload(CommandSender sender) {
        plugin.reloadAsync().whenComplete((ok, error) -> plugin.runSync(() -> {
            if (error != null) {
                sender.sendMessage(Messages.error("Neuladen fehlgeschlagen: " + rootMessage(error)));
            } else {
                sender.sendMessage(Messages.info("Config und admins.yml neu geladen."));
            }
        }));
    }

    private static void help(CommandSender sender, String label) {
        String cmd = ChatColor.WHITE + "/" + label + " ";
        sender.sendMessage(Messages.info("Befehle:"));
        sender.sendMessage(cmd + "add <Spieler>" + ChatColor.GRAY + " – Spieler berechtigen");
        sender.sendMessage(cmd + "remove <Spieler>" + ChatColor.GRAY + " – Berechtigung entfernen");
        sender.sendMessage(cmd + "list" + ChatColor.GRAY + " – alle Berechtigten");
        sender.sendMessage(cmd + "tool" + ChatColor.GRAY + " – Werkzeug zum Untersuchen");
        sender.sendMessage(cmd + "reload" + ChatColor.GRAY + " – Config und admins.yml neu laden");
        sender.sendMessage(cmd + "help" + ChatColor.GRAY + " – diese Übersicht");
    }

    private void save(CommandSender sender) {
        admins.save().exceptionally(e -> {
            logger.log(Level.SEVERE, "admins.yml konnte nicht gespeichert werden", e);
            plugin.runSync(() -> sender.sendMessage(Messages.error("admins.yml konnte nicht gespeichert werden.")));
            return null;
        });
    }

    private String formatDate(String isoDate) {
        if (isoDate == null) {
            return "?";
        }
        try {
            return OffsetDateTime.parse(isoDate).atZoneSameInstant(plugin.settings().timezone()).format(LIST_DATE);
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
