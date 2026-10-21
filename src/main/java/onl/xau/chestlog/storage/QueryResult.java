package onl.xau.chestlog.storage;

import java.util.List;

import onl.xau.chestlog.model.LogEntry;

/**
 * Ergebnis einer Abfrage.
 *
 * @param entries         die neuesten Einträge, neueste zuerst, ohne Item-YAML ({@link LogEntry#item()} ist
 *                        {@code null}, nachladen mit {@link LogDatabase#loadItems})
 * @param totalCount      Anzahl aller Einträge an den Positionen
 * @param oldestTimestamp Zeitstempel des ältesten Eintrags überhaupt, {@code 0} wenn keine Einträge
 * @param truncated       {@code true}, wenn wegen des Limits nicht alle Einträge geladen wurden
 */
public record QueryResult(List<LogEntry> entries, long totalCount, long oldestTimestamp, boolean truncated) {

    public QueryResult {
        entries = List.copyOf(entries);
    }
}
