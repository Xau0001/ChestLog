package onl.xau.chestlog.storage;

import java.util.List;

import onl.xau.chestlog.model.LogEntry;

/**
 * Ergebnis einer Abfrage.
 *
 * @param entries         die neuesten Einträge, neueste zuerst
 * @param totalCount      Anzahl aller Einträge an den Positionen
 * @param oldestTimestamp Zeitstempel des ältesten Eintrags überhaupt, {@code 0} wenn keine Einträge
 */
public record QueryResult(List<LogEntry> entries, long totalCount, long oldestTimestamp) {

    public QueryResult {
        entries = List.copyOf(entries);
    }

    /** {@code true}, wenn nicht alle Einträge geladen wurden. */
    public boolean truncated() {
        return totalCount > entries.size();
    }
}
