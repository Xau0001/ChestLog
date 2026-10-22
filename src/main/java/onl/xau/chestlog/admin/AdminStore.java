package onl.xau.chestlog.admin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Liste der berechtigten Spieler in {@code admins.yml}, Schlüssel ist die UUID.
 *
 * <p>Änderungen nur vom Main-Thread. Gespeichert wird atomar (temporäre Datei, dann verschieben) über den
 * übergebenen Executor, damit keine Datei-I/O auf dem Main-Thread passiert.
 */
public final class AdminStore {

    /** Ein berechtigter Spieler. {@code addedAt} im ISO-8601-Format mit Offset. */
    public record Admin(UUID uuid, String name, String addedBy, String addedAt) {
    }

    public enum RemoveResult {
        REMOVED,
        NOT_FOUND,
        /** Der letzte Eintrag darf nur von der Konsole entfernt werden. */
        LAST_ADMIN
    }

    private final Path file;
    private final Executor writeExecutor;
    private volatile Map<UUID, Admin> admins = Map.of();
    // zählt jede Änderung, damit ein Neuladen erkennt, ob es inzwischen veraltet ist
    private int version;

    public AdminStore(Path file, Executor writeExecutor) {
        this.file = file;
        this.writeExecutor = writeExecutor;
    }

    public Path file() {
        return file;
    }

    /** Liest die Datei. Fehlt sie, ist die Liste leer. */
    public static Map<UUID, Admin> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Übernimmt eine gelesene Liste, z. B. nach {@link #read(Path)}. */
    public void replaceAll(Map<UUID, Admin> loaded) {
        admins = Map.copyOf(loaded);
        version++;
    }

    /** Zählt die Änderungen; steigt bei jedem add, remove, updateName und replaceAll. */
    public int version() {
        return version;
    }

    public boolean isAdmin(UUID uuid) {
        return uuid != null && admins.containsKey(uuid);
    }

    public Optional<Admin> get(UUID uuid) {
        return Optional.ofNullable(admins.get(uuid));
    }

    /** Alle Einträge mit diesem Namen (ohne Groß-/Kleinschreibung); mehrere, wenn sich jemand umbenannt hat. */
    public List<Admin> findByName(String name) {
        return admins.values().stream().filter(a -> a.name() != null && a.name().equalsIgnoreCase(name)).toList();
    }

    /** Alle Einträge, nach Namen sortiert. */
    public List<Admin> list() {
        List<Admin> list = new ArrayList<>(admins.values());
        list.sort(Comparator.comparing((Admin a) -> a.name() == null ? "" : a.name(), String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public int size() {
        return admins.size();
    }

    /** Fügt hinzu. Liefert {@code false}, wenn der Spieler schon in der Liste steht. */
    public boolean add(Admin admin) {
        if (admins.containsKey(admin.uuid())) {
            return false;
        }
        Map<UUID, Admin> copy = new LinkedHashMap<>(admins);
        copy.put(admin.uuid(), admin);
        admins = Map.copyOf(copy);
        version++;
        return true;
    }

    /**
     * Entfernt einen Spieler. Den letzten Eintrag kann nur die Konsole entfernen, damit sich niemand aussperrt.
     */
    public RemoveResult remove(UUID uuid, boolean byConsole) {
        if (!admins.containsKey(uuid)) {
            return RemoveResult.NOT_FOUND;
        }
        if (admins.size() == 1 && !byConsole) {
            return RemoveResult.LAST_ADMIN;
        }
        Map<UUID, Admin> copy = new LinkedHashMap<>(admins);
        copy.remove(uuid);
        admins = Map.copyOf(copy);
        version++;
        return RemoveResult.REMOVED;
    }

    /** Aktualisiert den gespeicherten Namen. Liefert {@code true}, wenn sich etwas geändert hat. */
    public boolean updateName(UUID uuid, String name) {
        Admin admin = admins.get(uuid);
        if (admin == null || name == null || name.equals(admin.name())) {
            return false;
        }
        Map<UUID, Admin> copy = new LinkedHashMap<>(admins);
        copy.put(uuid, new Admin(uuid, name, admin.addedBy(), admin.addedAt()));
        admins = Map.copyOf(copy);
        version++;
        return true;
    }

    /** Speichert den aktuellen Stand asynchron und atomar. */
    public CompletableFuture<Void> save() {
        String content = serialize(list());
        return CompletableFuture.runAsync(() -> {
            try {
                writeAtomically(file, content);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, writeExecutor);
    }

    static Map<UUID, Admin> parse(String content) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
        } catch (YAMLException e) {
            throw new IllegalArgumentException("admins.yml ist kein gültiges YAML: " + e.getMessage(), e);
        }
        Map<UUID, Admin> result = new LinkedHashMap<>();
        if (root == null) {
            return result;
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException("admins.yml: oberste Ebene muss eine Map sein");
        }
        Object adminsNode = rootMap.get("admins");
        if (adminsNode == null) {
            return result;
        }
        if (!(adminsNode instanceof Map<?, ?> adminsMap)) {
            throw new IllegalArgumentException("admins.yml: 'admins' muss eine Map sein");
        }
        for (Map.Entry<?, ?> e : adminsMap.entrySet()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(String.valueOf(e.getKey()).trim());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("admins.yml: ungültige UUID '" + e.getKey() + "'", ex);
            }
            Map<?, ?> values = e.getValue() instanceof Map<?, ?> m ? m : Map.of();
            result.put(uuid, new Admin(uuid, text(values.get("name")), text(values.get("added-by")),
                    text(values.get("added-at"))));
        }
        return result;
    }

    static String serialize(List<Admin> list) {
        Map<String, Object> adminsMap = new LinkedHashMap<>();
        for (Admin admin : list) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", admin.name());
            values.put("added-by", admin.addedBy());
            values.put("added-at", admin.addedAt());
            adminsMap.put(admin.uuid().toString(), values);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("admins", adminsMap);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(Integer.MAX_VALUE);
        return new Yaml(options).dump(root);
    }

    static void writeAtomically(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        // erst auf die Platte zwingen, dann verschieben: sonst kann nach einem Stromausfall eine leere Datei bleiben
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Von Hand eingetragene Zeitstempel ohne Anführungszeichen liest SnakeYAML als Date.
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(date.toInstant().atOffset(ZoneOffset.UTC));
        }
        return String.valueOf(value);
    }
}
