package onl.xau.chestlog.container;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** ItemStack ↔ YAML ({@link YamlConfiguration#saveToString()}). Nur vom Main-Thread benutzen. */
public final class ItemCodec {

    private static final String KEY = "item";

    private ItemCodec() {
    }

    public static String encode(ItemStack stack) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(KEY, stack);
        return yaml.saveToString();
    }

    /** Liefert {@code null}, wenn sich das Item nicht (mehr) lesen lässt, z. B. nach einem Versionswechsel. */
    public static ItemStack decode(String text) {
        if (text == null || text.isEmpty()) {
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
