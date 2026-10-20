package onl.xau.chestlog.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import onl.xau.chestlog.merge.EntryMerger.Merged;
import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogAction;

class EntryMergerTest {

    private static final long WINDOW = 10_000;
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final BlockPos CHEST = new BlockPos(WORLD, 120, 64, -35);
    private static final BlockPos BARREL = new BlockPos(WORLD, 1, 2, 3);

    private final List<Merged<String>> out = new ArrayList<>();
    private final EntryMerger<String> merger = new EntryMerger<>(WINDOW, out::add);

    @Test
    void fasstInnerhalbDesFenstersZusammen() {
        for (int i = 0; i < 20; i++) {
            merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "cobblestone", 64, 1_000 + i * 100L);
        }
        assertTrue(out.isEmpty());

        merger.flushExpired(1_000 + WINDOW);

        assertEquals(1, out.size());
        Merged<String> m = out.getFirst();
        assertEquals(1280, m.amount());
        assertEquals(1_000, m.timestamp(), "Zeitstempel = erstes Ereignis");
        assertEquals(LogAction.ADD, m.action());
        assertEquals("cobblestone", m.item());
        assertEquals("Alice", m.playerName());
        assertEquals(CHEST, m.pos());
    }

    @Test
    void trenntNachAblaufDesFensters() {
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 10, 0);
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 5, WINDOW - 1);
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 7, WINDOW);

        assertEquals(1, out.size(), "der erste Eintrag wird beim Überschreiten des Fensters ausgegeben");
        assertEquals(15, out.getFirst().amount());
        assertEquals(0, out.getFirst().timestamp());

        merger.flushAll();

        assertEquals(2, out.size());
        assertEquals(7, out.get(1).amount());
        assertEquals(WINDOW, out.get(1).timestamp());
    }

    @Test
    void flushExpiredLaesstLaufendeFensterOffen() {
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 1, 0);
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "dirt", 1, 5_000);

        merger.flushExpired(WINDOW);

        assertEquals(1, out.size());
        assertEquals("stone", out.getFirst().item());
        assertEquals(1, merger.pendingCount());
    }

    @Test
    void trenntRichtungSpielerItemUndContainer() {
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 1, 0);
        merger.record(ALICE, "Alice", CHEST, LogAction.REMOVE, "stone", 2, 1);
        merger.record(BOB, "Bob", CHEST, LogAction.ADD, "stone", 3, 2);
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "dirt", 4, 3);
        merger.record(ALICE, "Alice", BARREL, LogAction.ADD, "stone", 5, 4);
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 6, 5);

        merger.flushAll();

        assertEquals(5, out.size());
        assertEquals(List.of(7, 2, 3, 4, 5), out.stream().map(Merged::amount).toList());
    }

    @Test
    void flushPositionsGibtNurBetroffeneContainerAus() {
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 1, 0);
        merger.record(ALICE, "Alice", BARREL, LogAction.ADD, "stone", 1, 0);

        merger.flushPositions(List.of(CHEST));

        assertEquals(1, out.size());
        assertEquals(CHEST, out.getFirst().pos());
        assertEquals(1, merger.pendingCount());
    }

    @Test
    void openWirdInnerhalbDesFenstersNurEinmalGeloggt() {
        assertTrue(merger.shouldLogOpen(ALICE, CHEST, 0));
        assertFalse(merger.shouldLogOpen(ALICE, CHEST, 3_000));
        assertTrue(merger.shouldLogOpen(BOB, CHEST, 3_000), "anderer Spieler");
        assertTrue(merger.shouldLogOpen(ALICE, BARREL, 3_000), "anderer Container");
        assertTrue(merger.shouldLogOpen(ALICE, CHEST, WINDOW), "Fenster abgelaufen");
    }

    @Test
    void fensterNullFasstNichtZusammen() {
        EntryMerger<String> noMerge = new EntryMerger<>(0, out::add);
        noMerge.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 1, 0);
        noMerge.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 1, 0);
        noMerge.flushExpired(0);

        assertEquals(2, out.size());
        assertTrue(noMerge.shouldLogOpen(ALICE, CHEST, 0));
        assertTrue(noMerge.shouldLogOpen(ALICE, CHEST, 0));
    }

    @Test
    void ignoriertNullmengenUndAndereAktionen() {
        merger.record(ALICE, "Alice", CHEST, LogAction.ADD, "stone", 0, 0);
        merger.flushAll();
        assertTrue(out.isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> merger.record(ALICE, "Alice", CHEST, LogAction.BREAK, "stone", 1, 0));
    }
}
