package onl.xau.chestlog.model;

/**
 * Art eines Log-Eintrags. Die Zahl ist der in der Datenbank gespeicherte Code und darf sich nie ändern.
 */
public enum LogAction {
    OPEN(0),
    ADD(1),
    REMOVE(2),
    PLACE(3),
    BREAK(4),
    DROP(5);

    private final int id;

    LogAction(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** Liefert die Aktion zum Datenbank-Code oder {@code null}, wenn der Code unbekannt ist. */
    public static LogAction fromId(int id) {
        for (LogAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        return null;
    }
}
