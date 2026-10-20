package onl.xau.chestlog.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class TimeFormatTest {

    @Test
    void datumUndUhrzeitInDerKonfiguriertenZeitzone() {
        long millis = ZonedDateTime.of(2026, 10, 20, 16, 46, 12, 0, ZoneId.of("UTC")).toInstant().toEpochMilli();

        assertEquals("20.10.2026 18:46:12", TimeFormat.dateTime(millis, ZoneId.of("Europe/Berlin")));
    }

    @Test
    void relativeZeit() {
        assertEquals("gerade eben", TimeFormat.relative(Duration.ofSeconds(-3)));
        assertEquals("gerade eben", TimeFormat.relative(Duration.ofSeconds(4)));
        assertEquals("vor 12 Sek.", TimeFormat.relative(Duration.ofSeconds(12)));
        assertEquals("vor 5 Min.", TimeFormat.relative(Duration.ofMinutes(5).plusSeconds(30)));
        assertEquals("vor 3 Std.", TimeFormat.relative(Duration.ofHours(3)));
        assertEquals("vor 1 Tag", TimeFormat.relative(Duration.ofDays(1)));
        assertEquals("vor 2 Tagen", TimeFormat.relative(Duration.ofDays(2)));
        assertEquals("vor 1 Monat", TimeFormat.relative(Duration.ofDays(45)));
        assertEquals("vor 5 Monaten", TimeFormat.relative(Duration.ofDays(150)));
        assertEquals("vor 2 Jahren", TimeFormat.relative(Duration.ofDays(800)));
        assertEquals("vor 5 Min.", TimeFormat.relative(0, 300_000));
    }
}
