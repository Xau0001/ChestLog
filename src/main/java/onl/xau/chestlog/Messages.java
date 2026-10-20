package onl.xau.chestlog;

import org.bukkit.ChatColor;

/** Texte für Chat-Meldungen. */
public final class Messages {

    public static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "ChestLog" + ChatColor.DARK_GRAY
            + "] " + ChatColor.GRAY;

    public static final String NO_PERMISSION = PREFIX + ChatColor.RED + "Dazu hast du keine Berechtigung.";
    public static final String PROTECTED = PREFIX + ChatColor.RED
            + "Mit dem ChestLog-Werkzeug in der Hand kannst du keine Container abbauen.";
    public static final String LOAD_FAILED = PREFIX + ChatColor.RED
            + "Das Log konnte nicht geladen werden. Details stehen in der Server-Konsole.";

    private Messages() {
    }

    public static String info(String text) {
        return PREFIX + text;
    }

    public static String error(String text) {
        return PREFIX + ChatColor.RED + text;
    }
}
