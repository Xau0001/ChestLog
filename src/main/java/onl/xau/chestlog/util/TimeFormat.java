package onl.xau.chestlog.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Zeitangaben für die Anzeige. */
public final class TimeFormat {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private TimeFormat() {
    }

    /** Zum Beispiel {@code 20.10.2026 18:46:12}. */
    public static String dateTime(long epochMillis, ZoneId zone) {
        return DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }

    /** Zum Beispiel {@code vor 5 Min.}. */
    public static String relative(long epochMillis, long nowMillis) {
        return relative(Duration.ofMillis(nowMillis - epochMillis));
    }

    public static String relative(Duration age) {
        long seconds = age.toSeconds();
        if (seconds < 5) {
            return "gerade eben";
        }
        if (seconds < 60) {
            return "vor " + seconds + " Sek.";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return "vor " + minutes + " Min.";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "vor " + hours + " Std.";
        }
        long days = hours / 24;
        if (days < 30) {
            return days == 1 ? "vor 1 Tag" : "vor " + days + " Tagen";
        }
        if (days < 365) {
            long months = days / 30;
            return months == 1 ? "vor 1 Monat" : "vor " + months + " Monaten";
        }
        long years = days / 365;
        return years == 1 ? "vor 1 Jahr" : "vor " + years + " Jahren";
    }
}
