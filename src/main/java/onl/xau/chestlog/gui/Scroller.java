package onl.xau.chestlog.gui;

/**
 * Zeilenweises Scrollen über eine Liste in einem Raster mit 9 Spalten und {@code rows} sichtbaren Zeilen.
 * Der Offset ist immer ein Vielfaches von 9.
 */
final class Scroller {

    static final int COLUMNS = 9;

    private final int rows;
    private int size;
    private int offset;

    Scroller(int rows, int size) {
        this.rows = rows;
        this.size = size;
    }

    int pageSize() {
        return rows * COLUMNS;
    }

    int offset() {
        return offset;
    }

    /** Neue Listengröße, z. B. nach einem Filterwechsel; springt an den Anfang. */
    void reset(int newSize) {
        size = newSize;
        offset = 0;
    }

    /** Größter Offset, bei dem die letzte Zeile unten im Fenster steht. */
    int maxOffset() {
        int totalRows = (size + COLUMNS - 1) / COLUMNS;
        return Math.max(0, (totalRows - rows) * COLUMNS);
    }

    boolean canScrollUp() {
        return offset > 0;
    }

    boolean canScrollDown() {
        return offset < maxOffset();
    }

    /** Verschiebt um {@code delta} Einträge. Liefert {@code true}, wenn sich der Offset geändert hat. */
    boolean scroll(int delta) {
        int next = Math.clamp((long) offset + delta, 0, maxOffset());
        if (next == offset) {
            return false;
        }
        offset = next;
        return true;
    }
}
