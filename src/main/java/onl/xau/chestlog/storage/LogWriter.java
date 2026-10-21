package onl.xau.chestlog.storage;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogEntry;

/**
 * Schreib-Queue mit eigenem DB-Thread. Einträge werden gesammelt und etwa jede Sekunde oder ab {@code batchSize}
 * Einträgen in einer Transaktion geschrieben. Abfragen laufen auf demselben Thread und schreiben vorher die Queue
 * leer, sehen also alles, was vor ihnen eingereiht wurde.
 */
public final class LogWriter implements AutoCloseable {

    private static final int MAX_PER_TRANSACTION = 1000;
    static final int PURGE_CHUNK = 10_000;

    private final LogDatabase database;
    private final Logger logger;
    private final int batchSize;
    private final ScheduledExecutorService executor;
    private final ConcurrentLinkedQueue<LogEntry> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private volatile boolean closed;

    public LogWriter(LogDatabase database, Logger logger, int batchSize, Duration flushInterval) {
        this.database = database;
        this.logger = logger;
        this.batchSize = Math.max(1, batchSize);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChestLog-DB");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(1, flushInterval.toMillis());
        executor.scheduleWithFixedDelay(this::flushSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Reiht einen Eintrag zum Schreiben ein. Darf von jedem Thread aufgerufen werden. */
    public void submit(LogEntry entry) {
        if (closed) {
            logger.warning("Log-Eintrag nach dem Schließen der Datenbank verworfen: " + entry);
            return;
        }
        queue.add(entry);
        if (queued.incrementAndGet() >= batchSize && flushScheduled.compareAndSet(false, true)) {
            try {
                executor.execute(this::flushSafely);
            } catch (RejectedExecutionException e) {
                flushScheduled.set(false);
            }
        }
    }

    /** Anzahl noch nicht geschriebener Einträge. */
    public int pending() {
        return queued.get();
    }

    /** Lädt asynchron die neuesten Einträge an den Positionen. */
    public CompletableFuture<QueryResult> query(List<BlockPos> positions, int limit) {
        return supply(() -> {
            flush();
            return database.query(positions, limit);
        });
    }

    /** Verschiebt asynchron alle Einträge (auch die noch eingereihten) von {@code from} nach {@code to}. */
    public CompletableFuture<Integer> relocate(BlockPos from, BlockPos to) {
        return supply(() -> {
            flush();
            return database.relocate(from, to);
        });
    }

    /**
     * Löscht asynchron alle Einträge, die älter als {@code cutoffMillis} sind, und liefert deren Anzahl. Gelöscht wird
     * in Blöcken; jeder weitere Block wird hinten angestellt, damit Schreiben und Abfragen dazwischen laufen.
     */
    public CompletableFuture<Integer> deleteOlderThan(long cutoffMillis) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        deleteChunk(cutoffMillis, 0, result);
        return result;
    }

    private void deleteChunk(long cutoffMillis, int deletedSoFar, CompletableFuture<Integer> result) {
        try {
            executor.execute(() -> {
                try {
                    flush();
                    int deleted = database.deleteOlderThan(cutoffMillis, PURGE_CHUNK);
                    if (deleted < PURGE_CHUNK) {
                        result.complete(deletedSoFar + deleted);
                    } else {
                        deleteChunk(cutoffMillis, deletedSoFar + deleted, result);
                    }
                } catch (SQLException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            // beim Herunterfahren abgebrochen, der Rest folgt beim nächsten Start
            result.complete(deletedSoFar);
        }
    }

    /** Schreibt die Queue leer, beendet den DB-Thread und schließt die Datenbank. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.severe("DB-Thread hat sich nicht rechtzeitig beendet.");
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        // Nach dem Ende des DB-Threads ist dieser Thread der einzige Nutzer der Verbindung.
        flushSafely();
        try {
            database.close();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Datenbank konnte nicht geschlossen werden", e);
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (SQLException | RuntimeException e) {
            logger.log(Level.SEVERE, "Log-Einträge konnten nicht gespeichert werden", e);
        }
    }

    private void flush() throws SQLException {
        flushScheduled.set(false);
        while (true) {
            List<LogEntry> batch = new ArrayList<>();
            LogEntry entry;
            while (batch.size() < MAX_PER_TRANSACTION && (entry = queue.poll()) != null) {
                batch.add(entry);
            }
            if (batch.isEmpty()) {
                return;
            }
            try {
                database.insert(batch);
            } catch (SQLException e) {
                throw new SQLException(batch.size() + " Einträge verworfen: " + e.getMessage(), e);
            } finally {
                queued.addAndGet(-batch.size());
            }
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private <T> CompletableFuture<T> supply(SqlSupplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
