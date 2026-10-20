package onl.xau.chestlog;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** Eingelesene und geprüfte Werte aus {@code config.yml}. */
public record PluginConfig(
        Material tool,
        boolean logOpen,
        int mergeWindowSeconds,
        int maxGuiEntries,
        int retentionDays,
        ZoneId timezone,
        boolean protectContainersWithTool,
        Set<Material> containers,
        Set<String> disabledWorlds) {

    static final Material DEFAULT_TOOL = Material.WOODEN_PICKAXE;
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Berlin");
    private static final int MAX_GUI_ENTRIES_LIMIT = 50_000;

    public PluginConfig {
        containers = Set.copyOf(containers);
        disabledWorlds = Set.copyOf(disabledWorlds);
    }

    /** Liest die Werte; ungültige Angaben werden mit einer Warnung durch den Standard ersetzt. */
    public static PluginConfig from(ConfigurationSection c, Logger logger) {
        Material tool = DEFAULT_TOOL;
        String toolName = c.getString("tool", DEFAULT_TOOL.name());
        Material parsedTool = toolName == null ? null : Material.matchMaterial(toolName);
        if (parsedTool == null || !parsedTool.isItem() || parsedTool.isAir()) {
            logger.warning("config.yml: unbekanntes Werkzeug '" + toolName + "', verwende " + DEFAULT_TOOL.name());
        } else {
            tool = parsedTool;
        }

        int mergeWindow = c.getInt("merge-window-seconds", 10);
        if (mergeWindow < 0) {
            logger.warning("config.yml: merge-window-seconds darf nicht negativ sein, verwende 0");
            mergeWindow = 0;
        }

        int maxGuiEntries = c.getInt("max-gui-entries", 2000);
        if (maxGuiEntries < 1 || maxGuiEntries > MAX_GUI_ENTRIES_LIMIT) {
            int clamped = Math.clamp(maxGuiEntries, 1, MAX_GUI_ENTRIES_LIMIT);
            logger.warning("config.yml: max-gui-entries muss zwischen 1 und " + MAX_GUI_ENTRIES_LIMIT
                    + " liegen, verwende " + clamped);
            maxGuiEntries = clamped;
        }

        int retentionDays = c.getInt("retention-days", 180);
        if (retentionDays < 0) {
            logger.warning("config.yml: retention-days darf nicht negativ sein, verwende 0 (nie löschen)");
            retentionDays = 0;
        }

        ZoneId zone = DEFAULT_ZONE;
        String zoneName = c.getString("timezone", DEFAULT_ZONE.getId());
        try {
            if (zoneName != null) {
                zone = ZoneId.of(zoneName);
            }
        } catch (DateTimeException e) {
            logger.warning("config.yml: unbekannte Zeitzone '" + zoneName + "', verwende " + DEFAULT_ZONE.getId());
        }

        Set<Material> containers = new HashSet<>();
        for (String name : c.getStringList("containers")) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) {
                logger.warning("config.yml: unbekannter Container '" + name + "' wird ignoriert");
            } else if (material == Material.ENDER_CHEST) {
                logger.warning("config.yml: die Endertruhe wird nicht geloggt und daher ignoriert");
            } else {
                containers.add(material);
            }
        }

        Set<String> disabledWorlds = new HashSet<>();
        for (String world : c.getStringList("disabled-worlds")) {
            disabledWorlds.add(world.toLowerCase(Locale.ROOT));
        }

        return new PluginConfig(tool, c.getBoolean("log-open", true), mergeWindow, maxGuiEntries, retentionDays, zone,
                c.getBoolean("protect-containers-with-tool", true), containers, disabledWorlds);
    }

    public boolean isWorldDisabled(World world) {
        return disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /** {@code true}, wenn das Material laut Config erfasst wird (leere Liste = alle Container). */
    public boolean isContainerAllowed(Material material) {
        return containers.isEmpty() || containers.contains(material);
    }
}
