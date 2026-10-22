package onl.xau.chestlog.container;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** ItemStack ↔ YAML ({@link YamlConfiguration#saveToString()}). Nur vom Main-Thread benutzen. */
public final class ItemCodec {

    /**
     * Gespeichert statt des YAML, wenn ein Item zu groß ist (z. B. eine Shulkerkiste voller beschriebener Bücher).
     * Sonst könnte jemand mit solchen Items die Datenbank aufblähen und beim Inspizieren den Speicher füllen.
     */
    public static final String OMITTED = "#omitted";
    /** Höchstgröße des gespeicherten YAML in Zeichen. Eine Shulkerkiste voller verzauberter Ausrüstung passt. */
    public static final int MAX_STORED_CHARS = 64 * 1024;

    private static final String KEY = "item";

    private ItemCodec() {
    }

    public static String encode(ItemStack stack) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(KEY, stack);
        return yaml.saveToString();
    }

    /** Wie {@link #encode}, aber {@link #OMITTED} statt zu großer Items. */
    public static String encodeForLog(ItemStack stack) {
        String yaml = encode(stack);
        return yaml.length() > MAX_STORED_CHARS ? OMITTED : yaml;
    }

    /** Liefert {@code null}, wenn sich das Item nicht (mehr) lesen lässt, z. B. nach einem Versionswechsel. */
    public static ItemStack decode(String text) {
        if (text == null || text.isEmpty() || text.equals(OMITTED)) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            ItemStack stack = yaml.getItemStack(KEY);
            return stack == null || stack.getType().isAir() ? null : stack;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
}
