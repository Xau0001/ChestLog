# ChestLog

Ein kostenloses Spigot-Plugin, das protokolliert, welcher Spieler wann an welchem Container etwas hineingelegt oder herausgenommen hat. Berechtigte Admins klicken mit einer **Holzspitzhacke per Rechtsklick** auf eine Truhe, ein Fass, eine Shulkerkiste und so weiter und sehen alle Einträge in einem scrollbaren Inventar-Fenster.

- Server: **Spigot 26.3** (kein Paper nötig, nur Bukkit-/Spigot-API)
- Java: **25**
- Speicherung: SQLite (`plugins/ChestLog/chestlog.db`)

## Inhalt

- [Installation](#installation)
- [Benutzung](#benutzung)
- [Befehle](#befehle)
- [Berechtigte Spieler](#berechtigte-spieler)
- [Konfiguration](#konfiguration)
- [Was geloggt wird](#was-geloggt-wird)
- [Datenhaltung](#datenhaltung)
- [Bekannte Grenzen](#bekannte-grenzen)
- [Nicht Teil von Version 1](#nicht-teil-von-version-1)
- [Entwicklung](#entwicklung)
- [Abweichungen](#abweichungen)
- [Manuelle Checkliste](#manuelle-checkliste)

## Installation

1. JAR bauen (siehe [Entwicklung](#entwicklung)) oder aus einem GitHub-Release herunterladen. Ergebnis: `target/ChestLog-1.0.0.jar`.
2. Server in AMP stoppen.
3. Die JAR nach `/mnt/daten/amp/<Instanz>/Minecraft/plugins/` kopieren.
4. Server starten. ChestLog legt `plugins/ChestLog/config.yml` und `plugins/ChestLog/chestlog.db` an.
5. Den ersten Admin **in der Server-Konsole** eintragen (der Spieler muss schon einmal auf dem Server gewesen sein):

   ```
   chestlog add <Name>
   ```

6. Im Spiel: `/chestlog tool` gibt eine Holzspitzhacke, Rechtsklick auf einen Container öffnet das Log.

Ein Update geht genauso: Server stoppen, alte JAR durch die neue ersetzen, starten. Datenbank, Config und Admin-Liste bleiben erhalten.

## Benutzung

### Inspizieren

Ein berechtigter Spieler hält das Werkzeug (Standard: Holzspitzhacke) in der Haupthand und klickt mit **Rechtsklick** auf einen Container. Der Container öffnet sich dann nicht (und es wird kein „geöffnet“ geloggt), stattdessen erscheint das Log-Fenster. Das funktioniert auch in geschützten Bereichen.

Für alle anderen Spieler verhält sich die Holzspitzhacke ganz normal, es gibt keine Meldung.

Mit `protect-containers-with-tool: true` (Standard) können Berechtigte mit dem Werkzeug in der Hand keine Container abbauen, damit beim Untersuchen nichts versehentlich zerstört wird.

### Das Log-Fenster

Minecraft-Inventare lassen sich nicht mit dem Mausrad scrollen, daher wird zeilenweise mit Pfeil-Buttons geblättert.

| Slots | Inhalt |
|---|---|
| obere 5 Zeilen | Einträge, **neueste zuerst** |
| unten links | **Spektralpfeil** eine Seite hoch, **Pfeil** eine Zeile hoch |
| Trichter | Filter: Alle → nur Entnahmen → nur Hinzugefügt → nur Öffnen/Platzieren/Abbauen → Alle |
| Buch | Info: Welt, Koordinaten, Anzahl Einträge, ältester Eintrag, angezeigter Bereich, ggf. „Nur die neuesten N geladen“ |
| Barriere | Schließen |
| unten rechts | **Pfeil** eine Zeile runter, **Spektralpfeil** eine Seite runter |

Buttons, die gerade nichts bewirken (am Anfang bzw. Ende der Liste), werden als graues Glas angezeigt.

Jeder Eintrag zeigt:

| Aktion | Icon | erste Log-Zeile |
|---|---|---|
| hineingelegt | das Item selbst | `+12 hineingelegt` (grün) |
| entnommen | das Item selbst | `−3 entnommen` (rot) |
| beim Abbau herausgefallen | das Item selbst | `−64 beim Abbau herausgefallen` (rot) |
| geöffnet | Truhe | `geöffnet` (grau) |
| platziert | der Container | `platziert` (gelb) |
| abgebaut | der Container | `abgebaut` (dunkelrot) |

Darunter folgen Spieler, Zeit (`20.10.2026 18:46:12`, Zeitzone aus der Config) und die relative Zeit (`vor 5 Min.`). Bei Item-Icons bleibt der Name unverändert, damit der Client ihn in der eigenen Sprache anzeigt. Vorhandene Lore des Items bleibt erhalten, die Log-Zeilen stehen darunter. Die Icon-Anzahl entspricht der Menge, begrenzt auf die maximale Stackgröße; die genaue Menge steht in der Lore.

Aus dem Fenster lässt sich nichts herausnehmen und nichts hineinlegen (Klick, Shift-Klick, Zahlentasten, Doppelklick, Drag, Q).

Bei Doppeltruhen zeigt das Fenster immer die Einträge **beider Hälften**, auch alte Einträge aus der Zeit, als es noch eine Einzeltruhe war. Wird eine Hälfte abgebaut, ziehen ihre Einträge auf die stehengebliebene Hälfte um. Die gemeinsame Historie, der Abbau und die herausgefallenen Items bleiben so an der übrigen Truhe sichtbar.

## Befehle

`/chestlog`, Alias `/cl`

| Befehl | Wirkung |
|---|---|
| `/chestlog add <Spieler>` | Spieler zur Liste der Berechtigten hinzufügen |
| `/chestlog remove <Spieler>` | Spieler entfernen |
| `/chestlog list` | alle Berechtigten mit Namen, wer sie hinzugefügt hat und wann |
| `/chestlog tool` | gibt das Werkzeug (Holzspitzhacke) |
| `/chestlog reload` | `config.yml` und `admins.yml` neu laden |
| `/chestlog help` | Übersicht |

- Erlaubt für die **Konsole** (auch RCON) und für Spieler aus `admins.yml`. Alle anderen bekommen „Dazu hast du keine Berechtigung.“ und keine Tab-Vervollständigung.
- `add`: Der Spieler muss online sein oder schon einmal auf dem Server gewesen sein. Gesucht wird ohne Groß-/Kleinschreibung unter den bekannten Spielern des Servers, ohne Anfrage bei Mojang. Sonst: „Spieler war noch nie auf dem Server“.
- `remove`: Den letzten Eintrag kann nur die Konsole entfernen, damit sich niemand aussperrt. Statt des Namens geht auch die UUID.
- Tab-Vervollständigung: Unterbefehle, bei `add` Online-Spieler, bei `remove` Spieler aus der Liste.

Es gibt **keine Bukkit-Permissions**. Wer berechtigt ist, steht ausschließlich in `admins.yml`.

## Berechtigte Spieler

Datei `plugins/ChestLog/admins.yml`. Schlüssel ist die UUID, damit Namensänderungen nichts kaputt machen. Der gespeicherte Name wird beim Join aktualisiert.

```yaml
admins:
  0f3a1c2e-1111-4222-8333-444455556666:
    name: Xau
    added-by: CONSOLE
    added-at: '2026-10-20T18:50:00+02:00'
```

Die Datei wird atomar geschrieben (erst eine temporäre Datei, dann verschoben). Man kann sie auch von Hand bearbeiten und danach `/chestlog reload` ausführen. Ist sie beim Start nicht lesbar, wird sie nach `admins.yml.broken` verschoben und die Liste ist leer; das steht dann als Fehler im Server-Log.

## Konfiguration

`plugins/ChestLog/config.yml`, nach Änderungen `/chestlog reload`:

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

| Schlüssel | Bedeutung |
|---|---|
| `tool` | Material des Werkzeugs zum Inspizieren |
| `log-open` | Öffnen von Containern loggen |
| `merge-window-seconds` | Hineinlegen/Entnehmen desselben Spielers, derselben Item-Art, desselben Containers und derselben Richtung innerhalb dieser Zeit wird zu einem Eintrag zusammengefasst (wer 20 Stacks per Shift-Klick einräumt, erzeugt einen Eintrag „+1280 Bruchstein“). Der Zeitstempel ist der des ersten Ereignisses. Ein erneutes Öffnen im selben Zeitraum wird nicht doppelt geloggt. `0` = nicht zusammenfassen. |
| `max-gui-entries` | höchstens so viele Einträge (die neuesten) werden pro Container ins Fenster geladen (1 bis 50000) |
| `retention-days` | Einträge, die älter sind, werden beim Start und dann einmal täglich gelöscht. `0` = nie löschen |
| `timezone` | Zeitzone für die Anzeige, z. B. `Europe/Berlin` oder `UTC` |
| `protect-containers-with-tool` | Berechtigte können mit dem Werkzeug in der Hand keine Container abbauen |
| `containers` | leer = alle Container; sonst z. B. `[CHEST, TRAPPED_CHEST, BARREL]` |
| `disabled-worlds` | Weltnamen, in denen nichts geloggt wird |

Ungültige Werte (unbekanntes Material, unbekannte Zeitzone, negative Zahlen) werden mit einer Warnung im Log durch den Standardwert ersetzt.

## Was geloggt wird

Erfasst werden alle Blöcke, deren BlockState ein Container ist: Truhe, Fallentruhe, Kupfertruhen, Fass, Shulkerkisten, Trichter, Spender, Werfer, Öfen, Schmelz- und Räucherofen, Braustand, Crafter. Die **Endertruhe** wird nicht geloggt, weil ihr Inhalt zum Spieler gehört und nicht zum Ort.

| Aktion | Auslöser |
|---|---|
| geöffnet | ein Spieler öffnet einen Container (abschaltbar mit `log-open`) |
| hineingelegt / entnommen | ein Spieler verändert den Inhalt |
| platziert | ein Spieler platziert einen Container |
| abgebaut | ein Spieler baut einen Container ab |
| beim Abbau herausgefallen | der Inhalt, der dabei herausfällt, ein Eintrag pro Item-Art |

**So werden Hineinlegen und Entnehmen erkannt:** Bei jedem Klick und jedem Drag in einem Container-Fenster (auch im eigenen Inventar unten, denn Shift-Klick verschiebt nach oben) merkt sich das Plugin den Inhalt des Containers und vergleicht ihn einen Tick später mit dem neuen Stand. Die Differenz pro Item-Art (inklusive Name, Verzauberungen und anderer Komponenten) ergibt die Einträge. Dadurch funktionieren alle Klickarten gleich zuverlässig: normaler Klick, Rechtsklick, Shift-Klick, Zahlentasten, Doppelklick zum Einsammeln, Drag, Bündel. Umsortieren innerhalb des Containers erzeugt keinen Eintrag.

## Datenhaltung

- SQLite-Datei `plugins/ChestLog/chestlog.db` im WAL-Modus (daneben liegen zur Laufzeit `chestlog.db-wal` und `chestlog.db-shm`).
- Geschrieben wird über eine Queue auf einem eigenen Thread, gebündelt etwa jede Sekunde oder ab 100 Einträgen. Beim Stoppen des Servers wird alles geschrieben, auch noch offene Zusammenfassungen.
- Alte Einträge (`retention-days`) werden in Blöcken zu 10.000 gelöscht, dazwischen laufen Schreiben und Abfragen weiter. Eine Inspektion muss so auch bei einem großen Löschlauf nicht warten.
- Abfragen laufen asynchron, das Fenster öffnet sich danach auf dem Main-Thread. Der Main-Thread macht keine blockierende Datei- oder Datenbank-I/O (Ausnahme: das einmalige Einlesen der Dateien beim Start).
- Die Items werden als YAML gespeichert. Lässt sich ein gespeichertes Item später nicht mehr lesen (z. B. nach einem Versionswechsel), zeigt das Fenster stattdessen das Material mit der Menge, und wenn auch das nicht geht, eine Barriere mit dem Materialnamen.
- **Backup:** Bei gestopptem Server `chestlog.db` (und falls vorhanden `-wal`/`-shm`) zusammen mit `admins.yml` und `config.yml` kopieren.

## Bekannte Grenzen

- Zieht ein Trichter im selben Tick wie ein Spielerklick Items aus dem Container (oder schiebt welche hinein), wird diese Änderung dem Spieler zugerechnet. Dasselbe gilt für Öfen, Brau- und Crafter-Vorgänge, die genau in diesem Tick etwas verbrauchen oder erzeugen.
- Klicken zwei Spieler im selben Tick in denselben Container, ist die Zuordnung nicht eindeutig.
- Beim Abbau einer **Shulkerkiste** gibt es keine „herausgefallen“-Einträge, weil der Inhalt in der Shulkerkiste bleibt. Der Abbau selbst wird geloggt.
- Wird ein Container inspiziert, werden seine noch offenen Zusammenfassungen sofort gespeichert, damit sie im Fenster erscheinen. Legt derselbe Spieler danach innerhalb des Zeitfensters weiter dasselbe hinein, entsteht ein zweiter Eintrag.
- Der Fenster-Inhalt ist ein Schnappschuss beim Öffnen. Neue Ereignisse erscheinen erst beim nächsten Inspizieren.
- Platziert man eine Truhe neben eine vorhandene (es entsteht eine Doppeltruhe), kann der „platziert“-Eintrag an der neuen Hälfte statt an der kanonischen gespeichert werden. Da immer beide Hälften abgefragt werden, ist er trotzdem sichtbar.
- Der Befehl `/chestlog` erscheint wegen der fehlenden Bukkit-Permission in der Befehlsliste aller Spieler, ausführen können ihn aber nur Berechtigte.

## Nicht Teil von Version 1

- Rollback
- Endertruhe
- Truhen-Loren und Truhenboote (Entities)
- Trichter-Transfers (`InventoryMoveItemEvent`)
- Explosionen
- MySQL, Weboberfläche

## Entwicklung

Voraussetzungen: JDK 25, Maven 3.9.

```bash
mvn package        # baut target/ChestLog-1.0.0.jar und führt die Unit-Tests aus
mvn test           # nur die Tests
```

Die Abhängigkeit `org.spigotmc:spigot-api` (Scope `provided`) kommt aus `https://hub.spigotmc.org/nexus/content/repositories/snapshots/`. Die Version steht als Property `spigot.version` in der `pom.xml` und lässt sich beim Bauen überschreiben, z. B. `mvn package -Dspigot.version=26.3-R0.1-SNAPSHOT`. Wer Spigot mit BuildTools selbst baut, hat die passende API danach auch im lokalen Maven-Repository.

**Unit-Tests** (JUnit 5, ohne laufenden Server, kein MockBukkit):

- `InventoryDiffTest`: Hinzufügen, Entnehmen, Umsortieren (kein Eintrag), Tausch zweier Item-Arten, teilweise Stacks, leere Inventare
- `EntryMergerTest`: Zusammenfassen im Zeitfenster, Trennen danach, getrennte Richtungen/Spieler/Container, OPEN nur einmal pro Fenster
- `AdminStoreTest`: `admins.yml` lesen, schreiben (atomar), Name aktualisieren, letzten Admin schützen
- `LogDatabaseTest` / `LogWriterTest`: gegen eine temporäre SQLite-Datei: Insert, Abfrage beider Doppeltruhen-Hälften, Sortierung, Limit mit Gesamtzahl, Purge, Umzug nach Abbau einer Hälfte, Nutzung des Index, Queue und Batches
- `ScrollerTest`, `TimeFormatTest`: Scrollen im Fenster, Zeitangaben

**GitHub Actions:**

| Workflow | Wann | Was |
|---|---|---|
| `build.yml` | bei jedem Push und Pull Request | `mvn verify`, die JAR als Artefakt |
| `server-test.yml` | bei Änderungen am Code, manuell | baut Spigot 26.3 mit BuildTools (zwischengespeichert), startet einen Testserver mit dem Plugin, führt Konsolenbefehle aus und prüft das Log auf Fehler und Warnungen von ChestLog. Der Testserver akzeptiert dafür die [Mojang-EULA](https://aka.ms/MinecraftEULA). |
| `release.yml` | bei einem Tag `v*` | baut die JAR und hängt sie an ein GitHub-Release |

Ein Release: `git tag v1.0.0 && git push origin v1.0.0`.

Paketstruktur unter `onl.xau.chestlog`:

| Paket | Inhalt |
|---|---|
| `diff`, `merge`, `storage`, `admin`, `model`, `util` | reine Logik ohne Bukkit, vollständig unit-getestet |
| `container` | Container-Erkennung, Doppeltruhen, Item-Schlüssel und -Serialisierung |
| `listener` | Logging-Listener, Inspektor, Join |
| `gui` | Log-Fenster |
| `command` | `/chestlog` |

## Abweichungen

Punkte, an denen die Umsetzung von den Vorgaben abweicht oder die beim Schreiben nicht gegen einen echten 26.3-Server geprüft werden konnten:

- **Nicht gegen die echte API gebaut.** In der Umgebung, in der der Code entstanden ist, war `hub.spigotmc.org` gesperrt und Pushen nach GitHub nicht möglich. Die reine Logik (Diff, Zusammenfassen, SQLite, Admin-Datei, Scrollen) ist mit JDK 25 getestet, alle Tests grün. Der Bukkit-Teil wurde nur gegen eine selbst geschriebene Nachbildung der genutzten API-Signaturen kompiliert. Der erste echte Build und der Servertest laufen in GitHub Actions (`build.yml`, `server-test.yml`).
- **Versionskennung der API:** `26.3-R0.1-SNAPSHOT` wie in der Vorgabe vermutet, nicht im Repository nachgesehen. Falls Maven die Version nicht findet: `server-test.yml` listet im ersten Schritt die tatsächlich vorhandenen Versionen auf; dann `spigot.version` in der `pom.xml` anpassen.
- **`api-version`:** auf `'26.3'` gesetzt. Spigot lehnt nur Versionen ab, die neuer sind als der Server, ein 26.3-Server akzeptiert also `26.3`. Meldet der Server trotzdem „Unsupported API version“, in `plugin.yml` auf den Wert ändern, den `server-test.yml` im Schritt „Server-JAR untersuchen“ ausgibt.
- **SQLite-Treiber:** Es wird davon ausgegangen, dass Spigot 26.3 `org.sqlite.JDBC` mitbringt (das tut Spigot seit vielen Versionen). Deshalb steht kein `libraries:`-Eintrag in der `plugin.yml`. Fehlt der Treiber, deaktiviert sich ChestLog mit der Meldung „SQLite-JDBC-Treiber (org.sqlite.JDBC) nicht gefunden“. Abhilfe: in der `plugin.yml` ergänzen

  ```yaml
  libraries:
    - org.xerial:sqlite-jdbc:3.53.4.0
  ```

  `server-test.yml` zeigt an, ob der Treiber im Server-JAR enthalten ist.
- **`admins.yml` wird mit SnakeYAML geschrieben** (das Spigot mitbringt) statt mit Bukkits `YamlConfiguration`, damit die Tests ohne Bukkit laufen. Das Format ist das vorgegebene; Datumswerte stehen in einfachen statt doppelten Anführungszeichen, beides ist gültiges YAML.
- **Suche bei `add` asynchron:** `Bukkit.getOfflinePlayers()` liest Spielerdateien von der Platte und läuft deshalb nicht auf dem Main-Thread.
- **Buttons:** Pfeil (Zeile) und Spektralpfeil (Seite) mit den Beschriftungen „▲ Eine Zeile hoch“ bzw. „▲▲ Eine Seite hoch“; die Zeichen ⏫/⏬ fehlen in manchen Schriftarten.
- **Filter „nur Entnahmen“** zeigt auch „beim Abbau herausgefallen“, weil diese Items den Container ebenfalls verlassen haben.
- **Abbau einer Doppeltruhen-Hälfte:** Die Vorgabe speichert unter der kanonischen Hälfte und fragt beide Hälften ab. Nach dem Abbau einer Hälfte wäre die gemeinsame Historie von der übrigen Truhe aus nicht mehr zu sehen, ein Dieb könnte so seine Spuren verwischen. Deshalb werden in diesem Fall „abgebaut“ und „herausgefallen“ an der stehengebliebenen Hälfte gespeichert, und alle Einträge der abgebauten Position ziehen dorthin um.

## Manuelle Checkliste

Für die Test-Instanz:

1. Server startet, `/plugins` zeigt ChestLog grün, keine Fehler im Log
2. Konsole: `chestlog add <Name>`, danach `chestlog list`
3. Einzeltruhe: Items per Klick, Shift-Klick, Zahlentaste, Doppelklick und Drag hinein und heraus, dann mit Holzspitzhacke prüfen
4. Doppeltruhe: von beiden Hälften aus inspizieren, gleicher Inhalt
5. Einzeltruhe mit Einträgen zur Doppeltruhe erweitern, alte Einträge sichtbar
6. Fass, Shulkerkiste, Trichter
7. Container mit Inhalt abbauen: Einträge „abgebaut“ und „beim Abbau herausgefallen“
8. Nicht berechtigter Spieler: Truhe öffnet normal mit Holzspitzhacke, `/chestlog` verweigert
9. Berechtigter Spieler fügt einen zweiten hinzu, der kann sofort inspizieren
10. Aus dem Log-Fenster lässt sich nichts herausnehmen (Klick, Shift, Zahlentaste, Drag, Q)
11. Scrollen: Zeile und Seite, Anfang und Ende, Filter
12. Server stoppen und starten: Einträge und Admin-Liste bleiben erhalten
