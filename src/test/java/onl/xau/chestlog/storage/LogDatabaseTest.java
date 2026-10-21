package onl.xau.chestlog.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogAction;
import onl.xau.chestlog.model.LogEntry;

class LogDatabaseTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID NETHER = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static final BlockPos LEFT = new BlockPos(WORLD, 10, 64, 20);
    private static final BlockPos RIGHT = new BlockPos(WORLD, 11, 64, 20);
    private static final BlockPos OTHER = new BlockPos(WORLD, 50, 64, 50);

    @TempDir
    Path dir;

    private LogDatabase db;

    @BeforeEach
    void setUp() throws SQLException {
        db = LogDatabase.open(dir.resolve("chestlog.db"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    private static LogEntry entry(BlockPos pos, long ts, LogAction action, String material, int amount) {
        return new LogEntry(0, ts, pos, PLAYER, "Xau", action, material, amount, "item: yaml");
    }

    @Test
    void einfuegenUndAbfragen() throws SQLException {
        db.insert(List.of(entry(LEFT, 1_000, LogAction.ADD, "STONE", 64)));

        QueryResult result = db.query(List.of(LEFT), 10);

        assertEquals(1, result.entries().size());
        LogEntry e = result.entries().getFirst();
        assertTrue(e.id() > 0);
        assertEquals(1_000, e.timestamp());
        assertEquals(LEFT, e.pos());
        assertEquals(PLAYER, e.playerUuid());
        assertEquals("Xau", e.playerName());
        assertEquals(LogAction.ADD, e.action());
        assertEquals("STONE", e.material());
        assertEquals(64, e.amount());
        assertEquals("item: yaml", e.item());
        assertEquals(1, result.totalCount());
        assertEquals(1_000, result.oldestTimestamp());
        assertFalse(result.truncated());
    }

    @Test
    void nullWerteBleibenErhalten() throws SQLException {
        db.insert(List.of(new LogEntry(0, 5, LEFT, null, null, LogAction.BREAK, null, 1, null)));

        LogEntry e = db.query(List.of(LEFT), 10).entries().getFirst();

        assertNull(e.playerUuid());
        assertNull(e.playerName());
        assertNull(e.material());
        assertNull(e.item());
    }

    @Test
    void abfrageBeiderDoppeltruhenHaelften() throws SQLException {
        // alter Eintrag aus der Zeit als Einzeltruhe an der rechten Hälfte, neuere an der kanonischen linken
        db.insert(List.of(
                entry(RIGHT, 1_000, LogAction.PLACE, "CHEST", 1),
                entry(LEFT, 2_000, LogAction.PLACE, "CHEST", 1),
                entry(LEFT, 3_000, LogAction.ADD, "STONE", 5),
                entry(OTHER, 4_000, LogAction.ADD, "DIRT", 1),
                entry(new BlockPos(NETHER, 10, 64, 20), 5_000, LogAction.ADD, "NETHERRACK", 1)));

        QueryResult result = db.query(List.of(LEFT, RIGHT), 100);

        assertEquals(List.of(3_000L, 2_000L, 1_000L), result.entries().stream().map(LogEntry::timestamp).toList());
        assertEquals(List.of(LEFT, LEFT, RIGHT), result.entries().stream().map(LogEntry::pos).toList());
    }

    @Test
    void sortierungNeuesteZuerstUndBeiGleicherZeitNachId() throws SQLException {
        db.insert(List.of(
                entry(LEFT, 2_000, LogAction.ADD, "A", 1),
                entry(LEFT, 3_000, LogAction.ADD, "B", 1),
                entry(LEFT, 1_000, LogAction.ADD, "C", 1),
                entry(LEFT, 3_000, LogAction.ADD, "D", 1)));

        List<String> materials = db.query(List.of(LEFT), 10).entries().stream().map(LogEntry::material).toList();

        assertEquals(List.of("D", "B", "A", "C"), materials);
    }

    @Test
    void limitMitGesamtzahlUndAeltestemEintrag() throws SQLException {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) {
            entries.add(entry(i % 2 == 0 ? LEFT : RIGHT, 10_000 + i, LogAction.ADD, "STONE", 1));
        }
        db.insert(entries);

        QueryResult result = db.query(List.of(LEFT, RIGHT), 2_000);

        assertEquals(2_000, result.entries().size());
        assertEquals(12_499, result.entries().getFirst().timestamp());
        assertEquals(2_500, result.totalCount());
        assertEquals(10_000, result.oldestTimestamp());
        assertTrue(result.truncated());
    }

    @Test
    void leereAbfrage() throws SQLException {
        QueryResult result = db.query(List.of(LEFT), 10);

        assertTrue(result.entries().isEmpty());
        assertEquals(0, result.totalCount());
        assertEquals(0, result.oldestTimestamp());
    }

    @Test
    void altesLoeschen() throws SQLException {
        db.insert(List.of(
                entry(LEFT, 1_000, LogAction.ADD, "OLD", 1),
                entry(RIGHT, 1_999, LogAction.ADD, "OLD", 1),
                entry(LEFT, 2_000, LogAction.ADD, "NEW", 1),
                entry(OTHER, 3_000, LogAction.ADD, "NEW", 1)));

        assertEquals(2, db.deleteOlderThan(2_000, 100));

        assertEquals(List.of("NEW"),
                db.query(List.of(LEFT, RIGHT), 10).entries().stream().map(LogEntry::material).toList());
        assertEquals(1, db.query(List.of(OTHER), 10).entries().size());
    }

    @Test
    void altesLoeschenInBloecken() throws SQLException {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            entries.add(entry(LEFT, 1_000 + i, LogAction.ADD, "OLD", 1));
        }
        entries.add(entry(LEFT, 5_000, LogAction.ADD, "NEW", 1));
        db.insert(entries);

        assertEquals(10, db.deleteOlderThan(2_000, 10));
        assertEquals(10, db.deleteOlderThan(2_000, 10));
        assertEquals(5, db.deleteOlderThan(2_000, 10));
        assertEquals(0, db.deleteOlderThan(2_000, 10));
        assertEquals(List.of("NEW"), db.query(List.of(LEFT), 100).entries().stream().map(LogEntry::material).toList());
    }

    @Test
    void umzugAufDieStehengebliebeneHaelfte() throws SQLException {
        db.insert(List.of(
                entry(LEFT, 1_000, LogAction.ADD, "A", 1),
                entry(LEFT, 2_000, LogAction.ADD, "B", 1),
                entry(RIGHT, 3_000, LogAction.ADD, "C", 1),
                entry(OTHER, 4_000, LogAction.ADD, "D", 1)));

        assertEquals(2, db.relocate(LEFT, RIGHT));

        assertEquals(List.of("C", "B", "A"),
                db.query(List.of(RIGHT), 10).entries().stream().map(LogEntry::material).toList());
        assertTrue(db.query(List.of(LEFT), 10).entries().isEmpty());
        assertEquals(1, db.query(List.of(OTHER), 10).entries().size());
    }

    @Test
    void umzugNurInnerhalbEinerWelt() {
        assertThrows(IllegalArgumentException.class, () -> db.relocate(LEFT, new BlockPos(NETHER, 10, 64, 20)));
    }

    @Test
    void datenBleibenNachNeuemOeffnenErhalten() throws SQLException {
        db.insert(List.of(entry(LEFT, 1_000, LogAction.OPEN, "CHEST", 1)));
        db.close();

        db = LogDatabase.open(dir.resolve("chestlog.db"));

        assertEquals(1, db.query(List.of(LEFT), 10).entries().size());
    }

    @Test
    void abfrageNutztDenPositionsindex() throws SQLException {
        String plan = db.explainQuery(List.of(LEFT, RIGHT));

        assertTrue(plan.contains("idx_log_pos"), plan);
        assertFalse(plan.contains("SCAN log"), plan);
    }
}
