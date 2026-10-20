package onl.xau.chestlog.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScrollerTest {

    @Test
    void wenigerAlsEineSeite() {
        Scroller s = new Scroller(5, 30);

        assertEquals(0, s.maxOffset());
        assertFalse(s.canScrollUp());
        assertFalse(s.canScrollDown());
        assertFalse(s.scroll(9));
        assertFalse(s.scroll(-45));
    }

    @Test
    void zeilenweiseBisZumEnde() {
        // 100 Einträge = 12 Zeilen, 5 sichtbar: letzter Offset 63 (Zeilen 8–12)
        Scroller s = new Scroller(5, 100);
        assertEquals(63, s.maxOffset());
        assertTrue(s.canScrollDown());

        for (int i = 0; i < 7; i++) {
            assertTrue(s.scroll(9));
        }
        assertEquals(63, s.offset());
        assertFalse(s.canScrollDown());
        assertFalse(s.scroll(9));
        assertTrue(s.canScrollUp());
    }

    @Test
    void seitenweiseMitBegrenzung() {
        Scroller s = new Scroller(5, 100);

        assertTrue(s.scroll(45));
        assertEquals(45, s.offset());
        assertTrue(s.scroll(45));
        assertEquals(63, s.offset(), "begrenzt auf das Ende");
        assertTrue(s.scroll(-45));
        assertEquals(18, s.offset());
        assertTrue(s.scroll(-45));
        assertEquals(0, s.offset(), "begrenzt auf den Anfang");
    }

    @Test
    void genauEineSeiteUndLeer() {
        assertEquals(0, new Scroller(5, 45).maxOffset());
        assertEquals(9, new Scroller(5, 46).maxOffset());
        assertEquals(0, new Scroller(5, 0).maxOffset());
    }

    @Test
    void resetSpringtAnDenAnfang() {
        Scroller s = new Scroller(5, 100);
        s.scroll(45);

        s.reset(10);

        assertEquals(0, s.offset());
        assertEquals(0, s.maxOffset());
    }
}
