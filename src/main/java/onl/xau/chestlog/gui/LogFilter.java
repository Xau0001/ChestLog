package onl.xau.chestlog.gui;

import java.util.Set;

import onl.xau.chestlog.model.LogAction;

/** Filter des Log-Fensters, wird per Klick reihum gewechselt. */
public enum LogFilter {
    ALL("Alle", Set.of(LogAction.values())),
    REMOVED("Nur Entnahmen", Set.of(LogAction.REMOVE, LogAction.DROP)),
    ADDED("Nur Hinzugefügt", Set.of(LogAction.ADD)),
    BLOCK("Nur Öffnen/Platzieren/Abbauen", Set.of(LogAction.OPEN, LogAction.PLACE, LogAction.BREAK));

    private final String label;
    private final Set<LogAction> actions;

    LogFilter(String label, Set<LogAction> actions) {
        this.label = label;
        this.actions = actions;
    }

    public String label() {
        return label;
    }

    public boolean matches(LogAction action) {
        return actions.contains(action);
    }

    public LogFilter next() {
        LogFilter[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
