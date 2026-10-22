# ChestLog – Spigot-Plugin für Truhen-Logs

Diese Datei ist Auftrag und Projektregeln zugleich.

## Ziel

Ein eigenes, kostenloses Plugin für einen **Spigot-26.3**-Server, das protokolliert, welcher Spieler wann an welchem Container (Truhe, Fass, Shulkerkiste …) was hineingelegt oder herausgenommen hat.

Berechtigte Admins klicken mit einer **Holzspitzhacke per Rechtsklick** auf einen Container und bekommen alle Einträge in einem **scrollbaren Inventar-Fenster** angezeigt. Wer berechtigt ist, steht in einer Datei. Berechtigte können per Befehl weitere Spieler hinzufügen.

## Umgebung und harte Vorgaben

| Punkt | Vorgabe |
|---|---|
| Server | Spigot 26.3 (gebaut mit BuildTools `--rev 26.3`), **kein Paper** |
| API | ausschließlich Bukkit-/Spigot-API |
| Verboten | Paper-API (Adventure, `Component`, MiniMessage, Paper-Events), NMS, `org.bukkit.craftbukkit.*` |
| Texte | `ChatColor` / §-Farbcodes oder die in Spigot enthaltene BungeeCord-Chat-API |
| Java | Java 25 |
| Build | Maven, Ergebnis eine einzelne JAR unter `target/` |
| Dependency | `org.spigotmc:spigot-api` aus `https://hub.spigotmc.org/nexus/content/repositories/snapshots/`, Scope `provided`. Die exakte Versionskennung für 26.3 (vermutlich `26.3-R0.1-SNAPSHOT`) im Repository nachsehen |
| `plugin.yml` | `api-version` auf den Wert setzen, den Spigot 26.x akzeptiert (prüfen) |
| Paket | `onl.xau.chestlog` |
| Betrieb | Der Server läuft in AMP. Die JAR kommt nach `/mnt/daten/amp/<Instanz>/Minecraft/plugins/` |

Kein Mojang-API-Aufruf, keine blockierende I/O auf dem Main-Thread.

## Funktionen

### 1. Logging

**Erfasste Blöcke:** alle Blöcke, deren `BlockState` ein `org.bukkit.block.Container` ist (Truhe, Fallentruhe, Kupfertruhen, Fass, Shulkerkisten, Trichter, Spender, Werfer, Öfen, Braustand, Crafter usw.). Über die Config auf eine Materialliste einschränkbar. Die **Endertruhe** wird nicht geloggt, weil ihr Inhalt zum Spieler gehört und nicht zum Ort.

**Aktionen:**

| Aktion | Auslöser |
|---|---|
| `OPEN` | `InventoryOpenEvent` auf einem Container (abschaltbar per Config) |
| `ADD` | Spieler legt Items hinein |
| `REMOVE` | Spieler nimmt Items heraus |
| `PLACE` | Spieler platziert einen Container (`BlockPlaceEvent`) |
| `BREAK` | Spieler baut einen Container ab (`BlockBreakEvent`) |
| `DROP` | Inhalt, der beim Abbau herausfällt (ein Eintrag pro Item-Art, Spieler = der Abbauende) |

**Erkennung von ADD/REMOVE (Diff pro Klick):**

Einzelne Klick-Typen nachzubauen (Shift-Klick, Zahlentasten, Doppelklick-Einsammeln, Bündel, Drag) ist fehleranfällig. Stattdessen:

1. Bei `InventoryClickEvent` und `InventoryDragEvent` prüfen, ob das obere Inventar der Ansicht zu einem erfassten Container gehört (`InventoryHolder` ist `Container` oder `DoubleChest`). Das gilt auch für Klicks im eigenen Inventar unten, denn Shift-Klick verschiebt nach oben.
2. Einen Snapshot (Kopien aller `ItemStack`s) des oberen Inventars anlegen.
3. **Einen Tick später** den neuen Stand mit dem Snapshot vergleichen. Die Differenz pro Item-Art (Vergleich wie `isSimilar`, also inklusive Name, Verzauberungen, Komponenten) ergibt `ADD`- bzw. `REMOVE`-Einträge mit Menge, zugeordnet dem klickenden Spieler.
4. Pro Spieler und Inventar höchstens **ein** Snapshot pro Tick. Ist für diesen Tick schon ein Vergleich geplant, keinen neuen Snapshot anlegen, sonst wird doppelt gezählt.

Die Diff-Logik als **reine Funktion** umsetzen, generisch über einen Schlüsseltyp und Mengen (z. B. `InventoryDiff.diff(Map<K,Integer> before, Map<K,Integer> after)`). So ist sie ohne laufenden Server testbar. Die Bukkit-Seite bildet `ItemStack` → Schlüssel ab (Kopie mit Menge 1).

**Doppeltruhen:** Einträge unter einer kanonischen Position speichern (die Hälfte mit den kleineren Koordinaten). Bei der Abfrage immer **beide** Hälften abfragen. So erscheinen auch alte Einträge aus der Zeit, als es noch eine Einzeltruhe war.

**Zusammenfassen:** `ADD`/`REMOVE` desselben Spielers, derselben Item-Art, desselben Containers und derselben Richtung innerhalb von `merge-window-seconds` zu einem Eintrag zusammenfassen (wer 20 Stacks per Shift-Klick einräumt, erzeugt einen Eintrag „+1280 Bruchstein“). Zeitstempel = erstes Ereignis. Ein erneutes `OPEN` desselben Spielers innerhalb des Fensters nicht doppelt loggen.

### 2. Inspektor (Holzspitzhacke)

- `PlayerInteractEvent`, `Action.RIGHT_CLICK_BLOCK`, nur `EquipmentSlot.HAND`
- Item in der Haupthand = `tool` aus der Config (Standard `WOODEN_PICKAXE`)
- Geklickter Block ist ein erfasster Container
- Spieler steht in der Admin-Liste

Dann: Event abbrechen (der Container öffnet sich nicht und wird nicht als `OPEN` geloggt) und das Log-Fenster öffnen. Listener mit `priority = LOWEST` und `ignoreCancelled = false`, damit der Inspektor auch in geschützten Bereichen funktioniert.

Nicht berechtigte Spieler: alles verhält sich wie Vanilla, keine Meldung.

Option `protect-containers-with-tool`: Ein berechtigter Spieler kann mit dem Werkzeug in der Hand keinen Container abbauen (`BlockBreakEvent` abbrechen, kurze Meldung). Verhindert versehentliches Zerstören beim Untersuchen.

### 3. Log-Fenster

Minecraft-Inventare lassen sich nicht mit dem Mausrad scrollen. „Scrollen“ heißt hier: zeilenweise mit Pfeil-Buttons.

**Aufbau:** Truheninventar mit 6 Zeilen (54 Slots), eigener `InventoryHolder` (`LogGuiHolder`) mit dem Zustand (Position, Einträge, Offset, Filter). Titel z. B. `Truhen-Log 120 64 -35`.

| Slots | Inhalt |
|---|---|
| 0–44 | Einträge, 5 Zeilen à 9, **neueste zuerst** |
| 45 | ⏫ eine Seite hoch (45 Einträge) |
| 46 | ▲ eine Zeile hoch (9 Einträge) |
| 48 | Filter: Alle → nur Entnahmen → nur Hinzugefügt → nur Öffnen/Platzieren/Abbauen → Alle |
| 49 | Info: Koordinaten, Welt, Anzahl Einträge, ältester Eintrag, ggf. Hinweis „nur die neuesten N geladen“ |
| 50 | Schließen |
| 52 | ▼ eine Zeile runter |
| 53 | ⏬ eine Seite runter |
| sonst | graues Glas als Füller |

Buttons, die am Anfang bzw. Ende nichts bewirken, werden als graues Glas dargestellt. Nur Vanilla-Items verwenden, keine Kopf-Texturen aus dem Netz.

**Einträge:**

| Aktion | Icon | Lore (erste Zeile) |
|---|---|---|
| `ADD` | Kopie des Items selbst | `§a+12 hineingelegt` |
| `REMOVE` | Kopie des Items selbst | `§c−3 entnommen` |
| `DROP` | Kopie des Items selbst | `§c−64 beim Abbau herausgefallen` |
| `OPEN` | Material des Containers | `§7geöffnet` |
| `PLACE` | Material des Containers | `§eplatziert` |
| `BREAK` | Material des Containers | `§4abgebaut` |

- Beim Item-Icon den Anzeigenamen **nicht** überschreiben, damit der Client den Namen in der eigenen Sprache anzeigt. Vorhandene Lore des Items bleibt, die Log-Zeilen kommen darunter.
- Icon-Anzahl = Menge, begrenzt auf die maximale Stackgröße des Items. Die genaue Menge steht in der Lore.
- Weitere Lore-Zeilen: `§7Spieler: §fName`, `§7Zeit: §f20.10.2026 18:46:12` (Zeitzone aus der Config), `§8vor 5 Min.`

**Schutz:** Jeder Klick und jeder Drag in einer Ansicht mit `LogGuiHolder` oben wird abgebrochen, auch Klicks im eigenen Inventar (Shift-Klick). Aus dem Fenster darf kein Item herauskommen und keins hinein.

**Laden:** Die Abfrage läuft asynchron. Geladen werden die neuesten `max-gui-entries` Einträge beider Doppeltruhen-Hälften in einer Abfrage, gehalten im Holder. Das Fenster öffnet sich danach auf dem Main-Thread. Bei null Einträgen trotzdem öffnen, mit einem Info-Item „Keine Einträge“.

### 4. Berechtigte Spieler

Datei `plugins/ChestLog/admins.yml`, Schlüssel ist die UUID (Namensänderungen):

```yaml
admins:
  0f3a1c2e-...:
    name: Xau
    added-by: CONSOLE
    added-at: "2026-10-20T18:50:00+02:00"
```

- Beim Join den gespeicherten Namen aktualisieren.
- Schreiben atomar (temporäre Datei, dann verschieben).
- Keine Bukkit-Permissions, nur diese Datei. Der Befehl wird ohne `permission` in der `plugin.yml` registriert, die Prüfung passiert im Code.

**Befehl `/chestlog` (Alias `/cl`):**

| Unterbefehl | Wirkung |
|---|---|
| `add <Spieler>` | Spieler zur Liste hinzufügen |
| `remove <Spieler>` | Spieler entfernen |
| `list` | alle Berechtigten mit Namen und wer sie hinzugefügt hat |
| `tool` | gibt eine Holzspitzhacke |
| `reload` | Config und `admins.yml` neu laden |
| `help` | Übersicht |

- Erlaubt für die **Konsole** und für Spieler, die in der Liste stehen. Alle anderen bekommen „Dazu hast du keine Berechtigung.“ und keine Tab-Vervollständigung.
- `add`: Der Spieler muss online sein oder schon einmal auf dem Server gewesen sein (Suche über `Bukkit.getOfflinePlayers()` nach Namen, ohne Groß-/Kleinschreibung). Kein Mojang-Lookup. Sonst Fehlermeldung „Spieler war noch nie auf dem Server“.
- `remove`: Den letzten Eintrag kann nur die Konsole entfernen, damit sich niemand aussperrt.
- Tab-Vervollständigung: Unterbefehle, bei `add` Online-Spieler, bei `remove` Spieler aus der Liste.
- Der erste Admin wird über die Konsole eingetragen: `chestlog add <Name>`.

### 5. Konfiguration `config.yml`

```yaml
tool: WOODEN_PICKAXE
log-open: true
merge-window-seconds: 10
max-gui-entries: 2000
retention-days: 180          # 0 = nie löschen
timezone: Europe/Berlin
protect-containers-with-tool: true
containers: []               # leer = alle Container außer der Endertruhe; sonst Liste von Materialien
disabled-worlds: []
```

## Datenhaltung

- SQLite, Datei `plugins/ChestLog/chestlog.db`, WAL-Modus.
- JDBC-Treiber: zuerst prüfen, ob Spigot 26.3 `org.sqlite.JDBC` mitbringt. Wenn nicht, über `libraries:` in der `plugin.yml` laden (`org.xerial:sqlite-jdbc`). Nicht shaden und relocaten, das bricht die nativen Bibliotheken.
- Schreiben: Queue und ein eigener DB-Thread, Batch-Inserts (etwa jede Sekunde oder ab 100 Einträgen). In `onDisable` alles flushen, auch die offenen Merge-Einträge.
- Lesen: asynchron, Ergebnis per `Bukkit.getScheduler().runTask(...)` zurück auf den Main-Thread.
- Aufräumen: Einträge älter als `retention-days` beim Start und einmal täglich asynchron löschen.

```sql
CREATE TABLE IF NOT EXISTS log (
  id          INTEGER PRIMARY KEY,
  ts          INTEGER NOT NULL,   -- Epoch-Millisekunden
  world       TEXT    NOT NULL,   -- Welt-UUID
  x           INTEGER NOT NULL,
  y           INTEGER NOT NULL,
  z           INTEGER NOT NULL,
  player_uuid TEXT,
  player_name TEXT,
  action      INTEGER NOT NULL,   -- OPEN, ADD, REMOVE, PLACE, BREAK, DROP
  material    TEXT,
  amount      INTEGER,
  item        TEXT                -- ItemStack als YAML (YamlConfiguration#saveToString), für die Anzeige
);
CREATE INDEX IF NOT EXISTS idx_log_pos ON log(world, x, y, z, ts DESC);
CREATE INDEX IF NOT EXISTS idx_log_ts  ON log(ts);
```

Lässt sich ein gespeichertes Item nicht mehr deserialisieren (z. B. nach einem Versionswechsel), stattdessen `material` mit `amount` anzeigen, und wenn auch das nicht geht, `BARRIER` mit dem Materialnamen.

## Nicht Teil von Version 1

- Rollback
- Endertruhe
- Truhen-Loren und Truhenboote (Entities)
- Trichter-Transfers (`InventoryMoveItemEvent`)
- Explosionen
- MySQL, Weboberfläche

## Bekannte Grenzen (in der README festhalten)

- Zieht ein Trichter im selben Tick wie ein Spielerklick Items aus dem Container, wird diese Änderung dem Spieler zugerechnet.
- Klicken zwei Spieler im selben Tick in denselben Container, ist die Zuordnung nicht eindeutig.

## Tests

**Unit-Tests (JUnit 5), ohne laufenden Server:**

- `InventoryDiff`: Hinzufügen, Entnehmen, Umsortieren (darf keinen Eintrag erzeugen), Tausch zweier Item-Arten, teilweise Stacks, leere Inventare
- Zusammenfassen innerhalb des Zeitfensters und Trennen danach
- `admins.yml` lesen, schreiben, Name aktualisieren, letzten Admin schützen
- DB-Schicht gegen eine temporäre SQLite-Datei: Insert, Abfrage beider Doppeltruhen-Hälften, Sortierung, Purge

Kein MockBukkit (Paper-basiert, für 26.3 nicht verlässlich).

**Manuelle Checkliste** für die Test-Instanz:

1. Server startet, `/plugins` zeigt ChestLog grün, keine Fehler im Log
2. Konsole: `chestlog add <Name>`, danach `chestlog list`
3. Einzeltruhe: Items per Klick, Shift-Klick, Zahlentaste, Doppelklick und Drag hinein und heraus, dann mit Holzspitzhacke prüfen
4. Doppeltruhe: von beiden Hälften aus inspizieren, gleicher Inhalt
5. Einzeltruhe mit Einträgen zur Doppeltruhe erweitern, alte Einträge sichtbar
6. Fass, Shulkerkiste, Trichter
7. Container mit Inhalt abbauen: `BREAK` und `DROP`-Einträge
8. Nicht berechtigter Spieler: Truhe öffnet normal mit Holzspitzhacke, `/chestlog` verweigert
9. Berechtigter Spieler fügt einen zweiten hinzu, der kann sofort inspizieren
10. Aus dem Log-Fenster lässt sich nichts herausnehmen (Klick, Shift, Zahlentaste, Drag, Q)
11. Scrollen: Zeile und Seite, Anfang und Ende, Filter
12. Server stoppen und starten: Einträge und Admin-Liste bleiben erhalten

## Abnahme

- Läuft auf Spigot 26.3 ohne Fehler und ohne Warnungen von ChestLog im Log
- Alle Unit-Tests grün, `mvn package` erzeugt eine einzelne JAR
- Checkliste vollständig bestanden
- Kein spürbarer Lag beim Inspizieren einer Truhe mit 2000 Einträgen
- `README.md` mit Installation, Befehlen, Config, bekannten Grenzen

## Vorgehen

1. Maven-Projekt anlegen, leeres Plugin baut und lädt auf Spigot 26.3. Erst danach weiter.
2. `InventoryDiff` mit Unit-Tests
3. DB-Schicht mit Queue und Unit-Tests
4. Listener fürs Logging
5. `admins.yml` und `/chestlog`
6. Inspektor und Log-Fenster
7. README, Checkliste durchgehen
8. Optional: GitHub-Actions-Workflow, der bei einem Tag die JAR baut und als Release-Asset anhängt

Wenn eine Vorgabe hier mit der tatsächlichen Spigot-26.3-API kollidiert (Versionskennung, `api-version`, fehlende Methode), die nächstliegende Lösung wählen und in der README unter „Abweichungen“ festhalten, statt die Arbeit anzuhalten.
