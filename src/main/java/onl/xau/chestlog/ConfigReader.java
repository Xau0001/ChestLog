package onl.xau.chestlog;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Liest Werte aus {@code config.yml} mit Typprüfung. Die Bukkit-Config liefert bei falschem Typ still den Standard
 * oder wandelt um (aus {@code 2.5} wird {@code 2}); hier gibt es stattdessen eine Warnung und genau den Standardwert,
 * den die Warnung nennt.
 */
final class ConfigReader {

    private final ConfigurationSection section;
    private final Logger logger;

    ConfigReader(ConfigurationSection section, Logger logger) {
        this.section = section;
        this.logger = logger;
    }

    int readInt(String key, int def) {
        if (!section.contains(key)) {
            return def;
        }
        if (!section.isInt(key)) {
            warn(key + " muss eine ganze Zahl sein, verwende " + def);
            return def;
        }
        return section.getInt(key);
    }

    boolean readBoolean(String key, boolean def) {
        if (!section.contains(key)) {
            return def;
        }
        if (!section.isBoolean(key)) {
            warn(key + " muss true oder false sein, verwende " + def);
            return def;
        }
        return section.getBoolean(key);
    }

    String readString(String key, String def) {
        if (!section.contains(key)) {
            return def;
        }
        if (!section.isString(key)) {
            warn(key + " muss ein Text sein, verwende " + def);
            return def;
        }
        return section.getString(key);
    }

    /** Die Liste oder eine leere Liste. {@code fallback} beschreibt in der Warnung, was dann gilt. */
    List<String> readList(String key, String fallback) {
        if (!section.contains(key)) {
            return List.of();
        }
        if (!section.isList(key)) {
            warn(key + " muss eine Liste sein (Einträge mit - oder in [ ]); " + fallback);
            return List.of();
        }
        return section.getStringList(key);
    }

    /** Meldet Schlüssel, die es nicht gibt, meist Tippfehler. */
    void warnUnknownKeys(Set<String> known) {
        for (String key : section.getKeys(false)) {
            if (!known.contains(key)) {
                warn("unbekannter Schlüssel '" + key + "' wird ignoriert (Tippfehler?)");
            }
        }
    }

    void warn(String message) {
        logger.warning("config.yml: " + message);
    }
}
