package onl.xau.chestlog.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import onl.xau.chestlog.admin.AdminStore.Admin;
import onl.xau.chestlog.admin.AdminStore.RemoveResult;

class AdminStoreTest {

    private static final UUID XAU = UUID.fromString("0f3a1c2e-1111-4222-8333-444455556666");
    private static final UUID BOB = UUID.fromString("9b8c7d6e-aaaa-4bbb-8ccc-ddddeeeeffff");

    @TempDir
    Path dir;

    private Path file;
    private AdminStore store;

    @BeforeEach
    void setUp() {
        file = dir.resolve("admins.yml");
        store = new AdminStore(file, Runnable::run);
    }

    @Test
    void liestDasDokumentierteFormat() throws IOException {
        Files.writeString(file, """
                admins:
                  0f3a1c2e-1111-4222-8333-444455556666:
                    name: Xau
                    added-by: CONSOLE
                    added-at: "2026-10-20T18:50:00+02:00"
                """, StandardCharsets.UTF_8);

        Map<UUID, Admin> admins = AdminStore.read(file);

        assertEquals(Map.of(XAU, new Admin(XAU, "Xau", "CONSOLE", "2026-10-20T18:50:00+02:00")), admins);
    }

    @Test
    void fehlendeOderLeereDateiErgibtLeereListe() throws IOException {
        assertTrue(AdminStore.read(file).isEmpty());

        Files.writeString(file, "", StandardCharsets.UTF_8);
        assertTrue(AdminStore.read(file).isEmpty());
    }

    @Test
    void schreibenUndWiederLesen() throws IOException {
        store.add(new Admin(XAU, "Xau", "CONSOLE", "2026-10-20T18:50:00+02:00"));
        store.add(new Admin(BOB, "true", "Xau", "2026-10-20T19:00:00+02:00"));

        store.save().join();

        assertFalse(Files.exists(dir.resolve("admins.yml.tmp")), "temporäre Datei wurde verschoben");
        Map<UUID, Admin> read = AdminStore.read(file);
        assertEquals(2, read.size());
        assertEquals("Xau", read.get(XAU).name());
        assertEquals("true", read.get(BOB).name(), "Namen, die wie YAML-Werte aussehen, bleiben Text");
        assertEquals("Xau", read.get(BOB).addedBy());
        assertEquals("2026-10-20T19:00:00+02:00", read.get(BOB).addedAt());
    }

    @Test
    void speichernUeberschreibtVorhandeneDatei() throws IOException {
        store.add(new Admin(XAU, "Xau", "CONSOLE", "2026-10-20T18:50:00+02:00"));
        store.save().join();
        store.add(new Admin(BOB, "Bob", "Xau", "2026-10-20T19:00:00+02:00"));
        store.save().join();

        assertEquals(2, AdminStore.read(file).size());
    }

    @Test
    void doppeltHinzufuegenWirdAbgelehnt() {
        assertTrue(store.add(new Admin(XAU, "Xau", "CONSOLE", "x")));
        assertFalse(store.add(new Admin(XAU, "Xau", "CONSOLE", "y")));
        assertEquals(1, store.size());
    }

    @Test
    void nameAktualisieren() throws IOException {
        store.add(new Admin(XAU, "Xau", "CONSOLE", "2026-10-20T18:50:00+02:00"));

        assertFalse(store.updateName(XAU, "Xau"), "gleicher Name ist keine Änderung");
        assertFalse(store.updateName(BOB, "Bob"), "unbekannter Spieler");
        assertTrue(store.updateName(XAU, "XauNeu"));
        store.save().join();

        Admin admin = AdminStore.read(file).get(XAU);
        assertEquals("XauNeu", admin.name());
        assertEquals("CONSOLE", admin.addedBy());
        assertEquals("2026-10-20T18:50:00+02:00", admin.addedAt());
        assertEquals(List.of(store.get(XAU).orElseThrow()), store.findByName("xauneu"));
    }

    @Test
    void letzterAdminIstGeschuetzt() {
        store.add(new Admin(XAU, "Xau", "CONSOLE", "x"));
        store.add(new Admin(BOB, "Bob", "Xau", "y"));

        assertEquals(RemoveResult.REMOVED, store.remove(BOB, false));
        assertEquals(RemoveResult.LAST_ADMIN, store.remove(XAU, false));
        assertTrue(store.isAdmin(XAU));
        assertEquals(RemoveResult.NOT_FOUND, store.remove(BOB, false));
        assertEquals(RemoveResult.REMOVED, store.remove(XAU, true), "die Konsole darf den letzten entfernen");
        assertEquals(0, store.size());
    }

    @Test
    void versionZaehltJedeAenderung() {
        int start = store.version();
        store.add(new Admin(XAU, "Xau", "CONSOLE", "x"));
        store.add(new Admin(XAU, "Xau", "CONSOLE", "x")); // schon vorhanden, keine Änderung
        store.updateName(XAU, "Xau2");
        store.add(new Admin(BOB, "Bob", "CONSOLE", "y"));
        store.remove(BOB, false);
        store.replaceAll(Map.of());

        assertEquals(start + 5, store.version());
    }

    @Test
    void gleicherNameBeiZweiAccountsWirdErkannt() {
        // z. B. hat Bob sich in Xau umbenannt, der alte Xau-Account steht noch mit dem Namen in der Liste
        store.add(new Admin(XAU, "Xau", "CONSOLE", "x"));
        store.add(new Admin(BOB, "xau", "CONSOLE", "y"));

        assertEquals(2, store.findByName("XAU").size());
        assertTrue(store.findByName("niemand").isEmpty());
    }

    @Test
    void listeIstNachNamenSortiert() {
        store.add(new Admin(XAU, "xau", "CONSOLE", "x"));
        store.add(new Admin(BOB, "Bob", "CONSOLE", "y"));

        assertEquals("Bob", store.list().get(0).name());
        assertEquals("xau", store.list().get(1).name());
    }

    @Test
    void zeitstempelOhneAnfuehrungszeichen() throws IOException {
        Files.writeString(file, """
                admins:
                  0f3a1c2e-1111-4222-8333-444455556666:
                    name: Xau
                    added-by: CONSOLE
                    added-at: 2026-10-20T18:50:00+02:00
                """, StandardCharsets.UTF_8);

        assertEquals("2026-10-20T16:50:00Z", AdminStore.read(file).get(XAU).addedAt());
    }

    @Test
    void ungueltigeDateiWirftFehler() throws IOException {
        Files.writeString(file, "admins:\n  keine-uuid:\n    name: X\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> AdminStore.read(file));

        Files.writeString(file, "admins: [a, b\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> AdminStore.read(file));
    }
}
