package onl.xau.chestlog.command;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
                return matching(admins.list().stream().map(Admin::name).filter(Objects::nonNull).toList(), args[1]);
            }
        }
        return List.of();
    }

    private boolean isAllowed(CommandSender sender) {
        return isConsole(sender) || sender instanceof Player player && admins.isAdmin(player.getUniqueId());
    }

    private static boolean isConsole(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    private void add(CommandSender sender, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(Messages.error("Benutzung: /" + label + " add <Spieler oder UUID>"));
            return;
        }
        String input = args[1];
        UUID uuid = parseUuid(input);
        if (uuid == null) {
            Player online = plugin.getServer().getPlayerExact(input);
            if (online != null) {
                finishAdd(sender, online.getUniqueId(), online.getName());
                return;
            }
        }
        // Kein Mojang-Lookup: nur Spieler, die schon auf dem Server waren. getOfflinePlayers() liest das
        // Spielerdaten-Verzeichnis und teils die Spielerdateien, daher asynchron. Schlägt das fehl (die Bukkit-API ist
        // dafür nicht ausdrücklich threadsicher), bekommt der Absender eine Meldung statt keiner Antwort.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, String> matches = new LinkedHashMap<>();
            RuntimeException failure = null;
            try {
                OfflinePlayer[] candidates = uuid != null
                        ? new OfflinePlayer[] {plugin.getServer().getOfflinePlayer(uuid)}
                        : plugin.getServer().getOfflinePlayers();
                for (OfflinePlayer player : candidates) {
                    String playerName = player.getName();
                    boolean match = uuid != null
                            ? player.isOnline() || player.hasPlayedBefore()
                            : playerName != null && playerName.equalsIgnoreCase(input);
                    if (match) {
                        matches.put(player.getUniqueId(), playerName);
                    }
                }
            } catch (RuntimeException e) {
                failure = e;
            }
            RuntimeException error = failure;
            plugin.runSync(() -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Spielersuche für /chestlog add fehlgeschlagen", error);
                    sender.sendMessage(Messages.error("Die Spielersuche ist fehlgeschlagen, bitte nochmal versuchen."));
                } else if (!isAllowed(sender)) {
                    sender.sendMessage(Messages.NO_PERMISSION); // inzwischen entfernt
                } else if (matches.isEmpty()) {
                    sender.sendMessage(Messages.error("Spieler war noch nie auf dem Server: " + input));
                } else if (matches.size() > 1) {
                    // z. B. nach einer Namensänderung: ein alter Account trägt den Namen noch in seinen Daten
                    sender.sendMessage(Messages.error("Mehrere Spieler hießen " + input
                            + ". Bitte mit der UUID hinzufügen: /" + label + " add <UUID>"));
                    matches.keySet().forEach(id -> sender.sendMessage(ChatColor.DARK_GRAY + " - " + ChatColor.WHITE
                            + id));
                } else {
                    // Name kann fehlen (add per UUID); er wird beim nächsten Join eingetragen
                    Map.Entry<UUID, String> match = matches.entrySet().iterator().next();
                    finishAdd(sender, match.getKey(), match.getValue());
                }
            });
        });
    }

    private void finishAdd(CommandSender sender, UUID uuid, String name) {
        String addedBy = isConsole(sender) ? CONSOLE : sender.getName();
        String addedAt = OffsetDateTime.now(plugin.settings().timezone()).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Admin admin = new Admin(uuid, name, addedBy, addedAt);
        if (!admins.add(admin)) {
            sender.sendMessage(Messages.info(ChatColor.WHITE + displayName(admin) + ChatColor.GRAY
                    + " ist schon berechtigt."));
            return;
        }
        save(sender);
        sender.sendMessage(Messages.info(ChatColor.WHITE + displayName(admin) + ChatColor.GRAY
                + " ist jetzt berechtigt."));
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player != sender) {
            player.sendMessage(Messages.info("Du kannst jetzt Container untersuchen: Rechtsklick mit dem Werkzeug, "
                    + "das du mit " + ChatColor.WHITE + "/chestlog tool" + ChatColor.GRAY + " bekommst."));
        }
    }

    private void remove(CommandSender sender, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(Messages.error("Benutzung: /" + label + " remove <Spieler oder UUID>"));
            return;
        }
        UUID uuid = parseUuid(args[1]);
        Admin admin;
        if (uuid != null) {
            admin = admins.get(uuid).orElse(null);
        } else {
            List<Admin> byName = admins.findByName(args[1]);
            if (byName.size() > 1) {
                sender.sendMessage(Messages.error("Mehrere Einträge heißen " + args[1]
                        + ". Bitte mit der UUID entfernen: /" + label + " remove <UUID>"));
                byName.forEach(a -> sender.sendMessage(ChatColor.DARK_GRAY + " - " + ChatColor.WHITE + a.uuid()));
                return;
            }
            admin = byName.isEmpty() ? null : byName.getFirst();
        }
        if (admin == null) {
            sender.sendMessage(Messages.error(args[1] + " steht nicht in der Liste."));
            return;
        }
        switch (admins.remove(admin.uuid(), isConsole(sender))) {
            case REMOVED -> {
                save(sender);
                plugin.closeLogWindow(admin.uuid());
                sender.sendMessage(Messages.info(ChatColor.WHITE + displayName(admin) + ChatColor.GRAY
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
            sender.sendMessage(ChatColor.DARK_GRAY + " - " + ChatColor.WHITE + displayName(admin)
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
        sender.sendMessage(cmd + "add <Spieler|UUID>" + ChatColor.GRAY + " – Spieler berechtigen");
        sender.sendMessage(cmd + "remove <Spieler|UUID>" + ChatColor.GRAY + " – Berechtigung entfernen");
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

    private static String displayName(Admin admin) {
        return admin.name() == null ? admin.uuid().toString() : admin.name();
    }

    private static UUID parseUuid(String text) {
        try {
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
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
