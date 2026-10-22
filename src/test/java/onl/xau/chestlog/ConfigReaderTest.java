package onl.xau.chestlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigReaderTest {

    private final List<String> warnings = new ArrayList<>();
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    /** Eine ConfigurationSection über einer Map, mit denselben Typregeln wie die Bukkit-Config. */
    private static ConfigurationSection section(Map<String, Object> values) {
        return (ConfigurationSection) Proxy.newProxyInstance(ConfigurationSection.class.getClassLoader(),
                new Class<?>[] {ConfigurationSection.class}, (proxy, method, args) -> {
                    String key = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                    Object value = key == null ? null : values.get(key);
                    return switch (method.getName()) {
                        case "contains" -> values.containsKey(key);
                        case "isInt" -> value instanceof Integer;
                        case "isBoolean" -> value instanceof Boolean;
                        case "isString" -> value instanceof String;
                        case "isList" -> value instanceof List;
                        case "getInt" -> ((Number) value).intValue();
                        case "getBoolean" -> (Boolean) value;
                        case "getString" -> String.valueOf(value);
                        case "getStringList" -> ((List<?>) value).stream().map(String::valueOf).toList();
                        case "getKeys" -> Set.copyOf(values.keySet());
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private ConfigReader reader(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new ConfigReader(section(values), logger);
    }

    @Test
    void gueltigeWerteOhneWarnung() {
        ConfigReader r = reader("n", 7, "b", false, "s", "UTC", "l", List.of("a", "b"));

        assertEquals(7, r.readInt("n", 10));
        assertFalse(r.readBoolean("b", true));
        assertEquals("UTC", r.readString("s", "x"));
        assertEquals(List.of("a", "b"), r.readList("l", "egal"));
        r.warnUnknownKeys(Set.of("n", "b", "s", "l"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void fehlendeSchluesselErgebenDenStandardOhneWarnung() {
        ConfigReader r = reader();

        assertEquals(10, r.readInt("n", 10));
        assertTrue(r.readBoolean("b", true));
        assertEquals("x", r.readString("s", "x"));
        assertTrue(r.readList("l", "egal").isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void kommazahlUndZuGrosseZahlErgebenGenauDenGenanntenStandard() {
        // Die Bukkit-Config würde 2.5 zu 2 abschneiden und 3000000000 überlaufen lassen.
        ConfigReader r = reader("window", 2.5, "days", 3_000_000_000L, "text", "zehn");

        assertEquals(10, r.readInt("window", 10));
        assertEquals(180, r.readInt("days", 180));
        assertEquals(5, r.readInt("text", 5));
        assertEquals(3, warnings.size());
        assertTrue(warnings.get(0).contains("window") && warnings.get(0).contains("verwende 10"), warnings.get(0));
        assertTrue(warnings.get(1).contains("verwende 180"), warnings.get(1));
    }

    @Test
    void falscherTypBeiBooleanUndText() {
        ConfigReader r = reader("b", "ja", "s", 123);

        assertTrue(r.readBoolean("b", true));
        assertEquals("x", r.readString("s", "x"));
        assertEquals(2, warnings.size());
    }

    @Test
    void keineListeWirdGemeldetMitDerFolge() {
        ConfigReader r = reader("containers", "CHEST");

        assertTrue(r.readList("containers", "alle Container werden geloggt").isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("alle Container werden geloggt"), warnings.getFirst());
    }

    @Test
    void unbekannteSchluesselWerdenGemeldet() {
        ConfigReader r = reader("merge-window-second", 5, "tool", "WOODEN_PICKAXE");

        r.warnUnknownKeys(Set.of("tool", "merge-window-seconds"));

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("merge-window-second"), warnings.getFirst());
    }
}
