package onl.xau.chestlog;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
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

    /** Liest die Werte; ungültige Angaben werden mit einer Warnung durch den Standard ersetzt. */
    public static PluginConfig from(ConfigurationSection c, Logger logger) {
        ConfigReader reader = new ConfigReader(c, logger);
        reader.warnUnknownKeys(KNOWN_KEYS);

        Material tool = DEFAULT_TOOL;
        String toolName = reader.readString("tool", DEFAULT_TOOL.name());
        Material parsedTool = Material.matchMaterial(toolName);
        if (parsedTool == null || !parsedTool.isItem() || parsedTool.isAir()) {
            reader.warn("unbekanntes Werkzeug '" + toolName + "', verwende " + DEFAULT_TOOL.name());
        } else {
            tool = parsedTool;
        }

        int mergeWindow = reader.readInt("merge-window-seconds", 10);
        if (mergeWindow < 0) {
            reader.warn("merge-window-seconds darf nicht negativ sein, verwende 0");
            mergeWindow = 0;
        }

        int maxGuiEntries = reader.readInt("max-gui-entries", 2000);
        if (maxGuiEntries < 1 || maxGuiEntries > MAX_GUI_ENTRIES_LIMIT) {
            int clamped = Math.clamp(maxGuiEntries, 1, MAX_GUI_ENTRIES_LIMIT);
            reader.warn("max-gui-entries muss zwischen 1 und " + MAX_GUI_ENTRIES_LIMIT + " liegen, verwende " + clamped);
            maxGuiEntries = clamped;
        }

        int retentionDays = reader.readInt("retention-days", 180);
        if (retentionDays < 0) {
            reader.warn("retention-days darf nicht negativ sein, verwende 0 (nie löschen)");
            retentionDays = 0;
        }

        ZoneId zone = DEFAULT_ZONE;
        String zoneName = reader.readString("timezone", DEFAULT_ZONE.getId());
        try {
            zone = ZoneId.of(zoneName);
        } catch (DateTimeException e) {
            reader.warn("unbekannte Zeitzone '" + zoneName + "', verwende " + DEFAULT_ZONE.getId());
        }

        List<String> containerNames = reader.readList("containers", "bis zur Korrektur werden alle Container geloggt");
        Set<Material> containers = new HashSet<>();
        for (String name : containerNames) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) {
                reader.warn("unbekannter Container '" + name + "' wird ignoriert");
            } else if (material == Material.ENDER_CHEST) {
                reader.warn("die Endertruhe wird nicht geloggt und daher ignoriert");
            } else {
                containers.add(material);
            }
        }
        // Eine leere Menge heißt „alle“. Waren alle Angaben ungültig, soll das nicht unbemerkt passieren.
        if (!containerNames.isEmpty() && containers.isEmpty()) {
            reader.warn("containers enthält keinen gültigen Container; bis zur Korrektur werden alle Container geloggt");
        }

        Set<String> disabledWorlds = new HashSet<>();
        for (String world : reader.readList("disabled-worlds", "bis zur Korrektur wird in allen Welten geloggt")) {
            disabledWorlds.add(world.toLowerCase(Locale.ROOT));
        }

        return new PluginConfig(tool, reader.readBoolean("log-open", true), mergeWindow, maxGuiEntries, retentionDays,
                zone, reader.readBoolean("protect-containers-with-tool", true), containers, disabledWorlds);
    }

    public boolean isWorldDisabled(World world) {
        return disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /** {@code true}, wenn das Material laut Config erfasst wird (leere Liste = alle Container). */
    public boolean isContainerAllowed(Material material) {
        return containers.isEmpty() || containers.contains(material);
    }
}
