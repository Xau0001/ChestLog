package onl.xau.chestlog.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogAction;
import onl.xau.chestlog.model.LogEntry;

/**
 * SQLite-Zugriff. Nicht threadsicher, wird nur vom DB-Thread ({@link LogWriter}) benutzt.
 */
public final class LogDatabase implements AutoCloseable {

    private static final String SCHEMA_TABLE = """
            CREATE TABLE IF NOT EXISTS log (
              id          INTEGER PRIMARY KEY,
              ts          INTEGER NOT NULL,
              world       TEXT    NOT NULL,
              x           INTEGER NOT NULL,
              y           INTEGER NOT NULL,
              z           INTEGER NOT NULL,
              player_uuid TEXT,
              player_name TEXT,
              action      INTEGER NOT NULL,
              material    TEXT,
              amount      INTEGER,
              item        TEXT
            )""";
    private static final String SCHEMA_INDEX_POS =
            "CREATE INDEX IF NOT EXISTS idx_log_pos ON log(world, x, y, z, ts DESC)";
    private static final String SCHEMA_INDEX_TS =
            "CREATE INDEX IF NOT EXISTS idx_log_ts ON log(ts)";

    private static final String INSERT = """
            INSERT INTO log (ts, world, x, y, z, player_uuid, player_name, action, material, amount, item)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String COLUMNS =
            "id, ts, world, x, y, z, player_uuid, player_name, action, material, amount, item";

    private final Connection connection;

    private LogDatabase(Connection connection) {
        this.connection = connection;
    }

    /** Öffnet (und erzeugt bei Bedarf) die Datenbank und legt das Schema an. */
    public static LogDatabase open(Path file) throws SQLException {
        Connection connection = connect(file);
        try {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute(SCHEMA_TABLE);
                st.execute(SCHEMA_INDEX_POS);
                st.execute(SCHEMA_INDEX_TS);
            }
            return new LogDatabase(connection);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    private static Connection connect(Path file) throws SQLException {
        // Treiber direkt instanziieren statt über DriverManager: der sieht Treiber aus Plugin-Classloadern nicht immer.
        Driver driver;
        try {
            driver = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new SQLException("SQLite-JDBC-Treiber (org.sqlite.JDBC) nicht gefunden", e);
        }
        Connection connection = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        if (connection == null) {
            throw new SQLException("SQLite-Verbindung konnte nicht geöffnet werden: " + file);
        }
        return connection;
    }

    /** Speichert alle Einträge in einer Transaktion. */
    public void insert(List<LogEntry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
            for (LogEntry e : entries) {
                ps.setLong(1, e.timestamp());
                ps.setString(2, e.pos().world().toString());
                ps.setInt(3, e.pos().x());
                ps.setInt(4, e.pos().y());
                ps.setInt(5, e.pos().z());
                setNullableString(ps, 6, e.playerUuid() == null ? null : e.playerUuid().toString());
                setNullableString(ps, 7, e.playerName());
                ps.setInt(8, e.action().id());
                setNullableString(ps, 9, e.material());
                ps.setInt(10, e.amount());
                setNullableString(ps, 11, e.item());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * Lädt die neuesten {@code limit} Einträge an allen angegebenen Positionen (z. B. beiden Hälften einer
     * Doppeltruhe) in einer Abfrage, neueste zuerst.
     */
    public QueryResult query(List<BlockPos> positions, int limit) throws SQLException {
        if (positions.isEmpty() || limit <= 0) {
            return new QueryResult(List.of(), 0, 0);
        }
        String where = whereClause(positions.size());
        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM log WHERE " + where + " ORDER BY ts DESC, id DESC LIMIT ?")) {
            int index = bindPositions(ps, positions);
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LogEntry entry = read(rs);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
        }
        if (entries.size() < limit) {
            long oldest = entries.isEmpty() ? 0 : entries.getLast().timestamp();
            return new QueryResult(entries, entries.size(), oldest);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*), MIN(ts) FROM log WHERE " + where)) {
            bindPositions(ps, positions);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new QueryResult(entries, rs.getLong(1), rs.getLong(2));
            }
        }
    }

    /** Löscht höchstens {@code limit} Einträge, die älter als {@code cutoffMillis} sind, und liefert deren Anzahl. */
    public int deleteOlderThan(long cutoffMillis, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM log WHERE id IN (SELECT id FROM log WHERE ts < ? LIMIT ?)")) {
            ps.setLong(1, cutoffMillis);
            ps.setInt(2, limit);
            return ps.executeUpdate();
        }
    }

    /**
     * Verschiebt alle Einträge von {@code from} nach {@code to} (gleiche Welt), z. B. von einer abgebauten
     * Doppeltruhen-Hälfte auf die stehengebliebene. Liefert die Anzahl der verschobenen Einträge.
     */
    public int relocate(BlockPos from, BlockPos to) throws SQLException {
        if (!from.world().equals(to.world())) {
            throw new IllegalArgumentException("Umzug nur innerhalb einer Welt: " + from + " -> " + to);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE log SET x = ?, y = ?, z = ? WHERE " + whereClause(1))) {
            ps.setInt(1, to.x());
            ps.setInt(2, to.y());
            ps.setInt(3, to.z());
            bindPositions(ps, List.of(from), 4);
            return ps.executeUpdate();
        }
    }

    /** Für Tests: der Abfrageplan der Positionsabfrage. */
    String explainQuery(List<BlockPos> positions) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement ps = connection.prepareStatement("EXPLAIN QUERY PLAN SELECT " + COLUMNS
                + " FROM log WHERE " + whereClause(positions.size()) + " ORDER BY ts DESC, id DESC LIMIT ?")) {
            int index = bindPositions(ps, positions);
            ps.setInt(index, 10);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    plan.append(rs.getString("detail")).append('\n');
                }
            }
        }
        return plan.toString();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    // Jede Position mit eigener Welt-Bedingung, damit SQLite pro Hälfte den Index idx_log_pos nutzt.
    private static String whereClause(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append("(world = ? AND x = ? AND y = ? AND z = ?)");
        }
        return sb.toString();
    }

    private static int bindPositions(PreparedStatement ps, List<BlockPos> positions) throws SQLException {
        return bindPositions(ps, positions, 1);
    }

    private static int bindPositions(PreparedStatement ps, List<BlockPos> positions, int firstIndex)
            throws SQLException {
        int index = firstIndex;
        for (BlockPos pos : positions) {
            ps.setString(index++, pos.world().toString());
            ps.setInt(index++, pos.x());
            ps.setInt(index++, pos.y());
            ps.setInt(index++, pos.z());
        }
        return index;
    }

    private static LogEntry read(ResultSet rs) throws SQLException {
        LogAction action = LogAction.fromId(rs.getInt("action"));
        if (action == null) {
            return null;
        }
        UUID world;
        UUID player;
        try {
            world = UUID.fromString(rs.getString("world"));
            String playerUuid = rs.getString("player_uuid");
            player = playerUuid == null ? null : UUID.fromString(playerUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        BlockPos pos = new BlockPos(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
        return new LogEntry(
                rs.getLong("id"),
                rs.getLong("ts"),
                pos,
                player,
                rs.getString("player_name"),
                action,
                rs.getString("material"),
                rs.getInt("amount"),
                rs.getString("item"));
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
