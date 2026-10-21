package onl.xau.chestlog.gui;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import onl.xau.chestlog.container.ItemCodec;
import onl.xau.chestlog.model.LogAction;
import onl.xau.chestlog.model.LogEntry;
import onl.xau.chestlog.util.TimeFormat;

/** Icons für Log-Einträge. */
final class EntryIcons {

    private EntryIcons() {
    }

    /**
     * Das Icon eines Eintrags.
     *
     * @param item das dekodierte Item bei ADD/REMOVE/DROP, {@code null} solange es noch lädt
     */
    static ItemStack icon(LogEntry entry, ItemStack item, long now, ZoneId zone) {
        boolean loading = false;
        ItemStack icon;
        if (LogGuiHolder.hasItem(entry)) {
            loading = item == null;
            icon = loading ? materialItem(entry) : item.clone();
            icon.setAmount(Math.clamp(entry.amount(), 1, Math.max(1, icon.getMaxStackSize())));
        } else {
            icon = new ItemStack(entry.action() == LogAction.OPEN ? Material.CHEST : containerMaterial(entry));
        }
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        // Anzeigenamen nicht überschreiben (der Client zeigt ihn in der eigenen Sprache), Log-Zeilen unter die Lore.
        List<String> existing = meta.hasLore() ? meta.getLore() : null;
        List<String> lore = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        lore.add(actionLine(entry));
        lore.add(ChatColor.GRAY + "Spieler: " + ChatColor.WHITE
                + (entry.playerName() == null ? "Unbekannt" : entry.playerName()));
        lore.add(ChatColor.GRAY + "Zeit: " + ChatColor.WHITE + TimeFormat.dateTime(entry.timestamp(), zone));
        lore.add(ChatColor.DARK_GRAY + TimeFormat.relative(entry.timestamp(), now));
        if (loading) {
            lore.add(ChatColor.DARK_GRAY + "lädt …");
        }
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    /**
     * Das Item aus der Datenbank. Lässt es sich nicht lesen, das Material, sonst eine Barriere mit dem Materialnamen.
     */
    static ItemStack decode(LogEntry entry, String yaml) {
        ItemStack decoded = ItemCodec.decode(yaml);
        if (decoded != null) {
            return decoded;
        }
        ItemStack fallback = materialItem(entry);
        if (ItemCodec.OMITTED.equals(yaml)) {
            ItemMeta meta = fallback.getItemMeta();
            if (meta != null) {
                meta.setLore(List.of(ChatColor.DARK_GRAY + "Item-Daten zu groß, nicht gespeichert"));
                fallback.setItemMeta(meta);
            }
        }
        return fallback;
    }

    static String actionLine(LogEntry entry) {
        return switch (entry.action()) {
            case ADD -> ChatColor.GREEN + "+" + entry.amount() + " hineingelegt";
            case REMOVE -> ChatColor.RED + "−" + entry.amount() + " entnommen";
            case DROP -> ChatColor.RED + "−" + entry.amount() + " beim Abbau herausgefallen";
            case OPEN -> ChatColor.GRAY + "geöffnet";
            case PLACE -> ChatColor.YELLOW + "platziert";
            case BREAK -> ChatColor.DARK_RED + "abgebaut";
        };
    }

    private static ItemStack materialItem(LogEntry entry) {
        Material material = entry.material() == null ? null : Material.matchMaterial(entry.material());
        if (material != null && material.isItem() && !material.isAir()) {
            return new ItemStack(material);
        }
        return LogGui.named(Material.BARRIER,
                ChatColor.RED + (entry.material() == null ? "Unbekannt" : entry.material()));
    }

    private static Material containerMaterial(LogEntry entry) {
        Material material = entry.material() == null ? null : Material.matchMaterial(entry.material());
        return material != null && material.isItem() && !material.isAir() ? material : Material.CHEST;
    }
}
