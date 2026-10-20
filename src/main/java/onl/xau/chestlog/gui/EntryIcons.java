package onl.xau.chestlog.gui;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import onl.xau.chestlog.model.LogEntry;
import onl.xau.chestlog.util.TimeFormat;

/** Icons für Log-Einträge. */
final class EntryIcons {

    private EntryIcons() {
    }

    static ItemStack icon(Map<Long, ItemStack> cache, LogEntry entry, long now, ZoneId zone) {
        ItemStack icon = switch (entry.action()) {
            case ADD, REMOVE, DROP -> {
                ItemStack item = cache.computeIfAbsent(entry.id(), id -> LogGui.baseItem(entry)).clone();
                item.setAmount(Math.clamp(entry.amount(), 1, Math.max(1, item.getMaxStackSize())));
                yield item;
            }
            case OPEN -> new ItemStack(Material.CHEST);
            case PLACE, BREAK -> new ItemStack(containerMaterial(entry));
        };
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
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
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

    private static Material containerMaterial(LogEntry entry) {
        Material material = entry.material() == null ? null : Material.matchMaterial(entry.material());
        return material != null && material.isItem() && !material.isAir() ? material : Material.CHEST;
    }
}
