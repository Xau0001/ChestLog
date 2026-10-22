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

    private static final Set<String> KNOWN_KEYS = Set.of("tool", "log-open", "merge-window-seconds", "max-gui-entries",
            "retention-days", "timezone", "protect-containers-with-tool", "containers", "disabled-worlds");
    private static final Set<String> INT_KEYS = Set.of("merge-window-seconds", "max-gui-entries", "retention-days");
    private static final Set<String> BOOLEAN_KEYS = Set.of("log-open", "protect-containers-with-tool");

    /** Liest die Werte; ungültige Angaben werden mit einer Warnung durch den Standard ersetzt. */
    public static PluginConfig from(ConfigurationSection c, Logger logger) {
        checkTypes(c, logger);
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

    // Falsche Typen liefert die Bukkit-Config still als Standardwert; das soll nicht unbemerkt bleiben. Besonders bei
    // containers: ohne Liste wären es sonst stillschweigend alle Container.
    private static void checkTypes(ConfigurationSection c, Logger logger) {
        for (String key : c.getKeys(false)) {
            if (!KNOWN_KEYS.contains(key)) {
                logger.warning("config.yml: unbekannter Schlüssel '" + key + "' wird ignoriert (Tippfehler?)");
            }
        }
        for (String key : INT_KEYS) {
            if (c.contains(key) && !c.isInt(key)) {
                logger.warning("config.yml: " + key + " muss eine ganze Zahl sein, verwende den Standardwert");
            }
        }
        for (String key : BOOLEAN_KEYS) {
            if (c.contains(key) && !c.isBoolean(key)) {
                logger.warning("config.yml: " + key + " muss true oder false sein, verwende den Standardwert");
            }
        }
        if (c.contains("containers") && !c.isList("containers")) {
            logger.warning("config.yml: containers muss eine Liste sein, z. B. [CHEST, BARREL]; "
                    + "bis zur Korrektur werden alle Container geloggt");
        }
        if (c.contains("disabled-worlds") && !c.isList("disabled-worlds")) {
            logger.warning("config.yml: disabled-worlds muss eine Liste sein, z. B. [world_nether]; "
                    + "bis zur Korrektur wird in allen Welten geloggt");
        }
    }

    public boolean isWorldDisabled(World world) {
        return disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /** {@code true}, wenn das Material laut Config erfasst wird (leere Liste = alle Container). */
    public boolean isContainerAllowed(Material material) {
        return containers.isEmpty() || containers.contains(material);
    }
}
