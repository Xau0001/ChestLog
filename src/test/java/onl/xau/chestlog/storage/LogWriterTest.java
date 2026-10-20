package onl.xau.chestlog.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogAction;
import onl.xau.chestlog.model.LogEntry;

class LogWriterTest {

    private static final Logger LOGGER = Logger.getLogger(LogWriterTest.class.getName());
    private static final BlockPos POS = new BlockPos(UUID.randomUUID(), 1, 2, 3);

    @TempDir
    Path dir;

    private static LogEntry entry(long ts) {
        return new LogEntry(0, ts, POS, UUID.randomUUID(), "Xau", LogAction.ADD, "STONE", 1, null);
    }

    @Test
    void abfrageSiehtVorherEingereihteEintraege() throws Exception {
        // langes Intervall: nur die Abfrage selbst schreibt die Queue leer
        try (LogWriter writer = new LogWriter(LogDatabase.open(dir.resolve("a.db")), LOGGER, 100,
                Duration.ofHours(1))) {
            for (int i = 0; i < 5; i++) {
                writer.submit(entry(i));
            }

            QueryResult result = writer.query(List.of(POS), 10).get(10, TimeUnit.SECONDS);

            assertEquals(5, result.entries().size());
            assertEquals(0, writer.pending());
        }
    }

    @Test
    void schreibtAbBatchGroesse() throws Exception {
        try (LogWriter writer = new LogWriter(LogDatabase.open(dir.resolve("b.db")), LOGGER, 10,
                Duration.ofHours(1))) {
            for (int i = 0; i < 10; i++) {
                writer.submit(entry(i));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (writer.pending() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertEquals(0, writer.pending());
        }
    }

    @Test
    void schreibtPeriodisch() throws Exception {
        try (LogWriter writer = new LogWriter(LogDatabase.open(dir.resolve("c.db")), LOGGER, 100,
                Duration.ofMillis(50))) {
            writer.submit(entry(1));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (writer.pending() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertEquals(0, writer.pending());
        }
    }

    @Test
    void schliessenSchreibtAllesWeg() throws SQLException {
        Path file = dir.resolve("d.db");
        LogWriter writer = new LogWriter(LogDatabase.open(file), LOGGER, 1_000, Duration.ofHours(1));
        for (int i = 0; i < 250; i++) {
            writer.submit(entry(i));
        }

        writer.close();

        try (LogDatabase db = LogDatabase.open(file)) {
            assertEquals(250, db.query(List.of(POS), 1_000).entries().size());
        }
    }

    @Test
    void loeschenAlterEintraege() throws Exception {
        try (LogWriter writer = new LogWriter(LogDatabase.open(dir.resolve("e.db")), LOGGER, 100,
                Duration.ofHours(1))) {
            writer.submit(entry(1_000));
            writer.submit(entry(5_000));

            assertEquals(1, writer.deleteOlderThan(2_000).get(10, TimeUnit.SECONDS));
            assertEquals(1, writer.query(List.of(POS), 10).get(10, TimeUnit.SECONDS).entries().size());
        }
    }
}
