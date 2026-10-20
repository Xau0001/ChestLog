package onl.xau.chestlog.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Ein Log-Eintrag.
 *
 * @param id          Datenbank-ID, {@code 0} für noch nicht gespeicherte Einträge
 * @param timestamp   Epoch-Millisekunden
 * @param pos         (kanonische) Position des Containers
 * @param playerUuid  Spieler, kann {@code null} sein
 * @param playerName  Name des Spielers zum Zeitpunkt des Ereignisses, kann {@code null} sein
 * @param action      Aktion
 * @param material    Material des Items (ADD/REMOVE/DROP) bzw. des Containers
 * @param amount      Menge
 * @param item        Item als YAML, nur für die Anzeige, kann {@code null} sein
 */
public record LogEntry(
        long id,
        long timestamp,
        BlockPos pos,
        UUID playerUuid,
        String playerName,
        LogAction action,
        String material,
        int amount,
        String item) {

    public LogEntry {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(action, "action");
    }
}
