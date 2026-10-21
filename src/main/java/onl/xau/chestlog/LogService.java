package onl.xau.chestlog;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import onl.xau.chestlog.container.ContainerTarget;
import onl.xau.chestlog.container.ItemCodec;
import onl.xau.chestlog.container.ItemKey;
import onl.xau.chestlog.merge.EntryMerger;
import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogAction;
import onl.xau.chestlog.model.LogEntry;
import onl.xau.chestlog.storage.LogWriter;
import onl.xau.chestlog.storage.QueryResult;

/** Nimmt Ereignisse vom Main-Thread entgegen, fasst sie zusammen und reiht sie zum Schreiben ein. */
public final class LogService {

    private final LogWriter writer;
    private final Supplier<PluginConfig> settings;
    private final Logger logger;
    private final EntryMerger<ItemKey> merger;

    LogService(LogWriter writer, Supplier<PluginConfig> settings, Logger logger) {
        this.writer = writer;
        this.settings = settings;
        this.logger = logger;
        this.merger = new EntryMerger<>(windowMillis(settings.get()), this::write);
    }

    void applySettings(PluginConfig config) {
        merger.setWindowMillis(windowMillis(config));
    }

    public void logOpen(Player player, ContainerTarget target) {
        long now = System.currentTimeMillis();
        if (settings.get().logOpen() && merger.shouldLogOpen(player.getUniqueId(), target.canonical(), now)) {
            writer.submit(new LogEntry(0, now, target.canonical(), player.getUniqueId(), player.getName(),
                    LogAction.OPEN, target.material().name(), 1, null));
        }
    }

    /** Änderungen aus einem Klick-Diff: positiv = hinzugefügt, negativ = entnommen. */
    public void logChanges(Player player, ContainerTarget target, Map<ItemKey, Integer> diff) {
        long now = System.currentTimeMillis();
        for (Map.Entry<ItemKey, Integer> change : diff.entrySet()) {
            int delta = change.getValue();
            LogAction action = delta > 0 ? LogAction.ADD : LogAction.REMOVE;
            merger.record(player.getUniqueId(), player.getName(), target.canonical(), action, change.getKey(),
                    Math.abs(delta), now);
        }
    }

    public void logPlace(Player player, ContainerTarget target) {
        writer.submit(new LogEntry(0, System.currentTimeMillis(), target.canonical(), player.getUniqueId(),
                player.getName(), LogAction.PLACE, target.material().name(), 1, null));
    }

    /**
     * Abbau mit dem herausfallenden Inhalt, ein DROP-Eintrag pro Item-Art.
     *
     * <p>Wird eine Hälfte einer Doppeltruhe abgebaut, gehören die Einträge danach zur stehengebliebenen Hälfte:
     * Abbau und Drops werden dort gespeichert, und die Einträge der abgebauten Position ziehen dorthin um. Sonst
     * wären sie von der übrigen Truhe aus nicht mehr zu sehen.
     */
    public void logBreak(Player player, ContainerTarget target, BlockPos broken, Map<ItemKey, Integer> drops) {
        long now = System.currentTimeMillis();
        BlockPos remaining = target.positions().stream().filter(p -> !p.equals(broken)).findFirst().orElse(null);
        BlockPos pos = target.isDouble() && remaining != null ? remaining : target.canonical();
        if (target.isDouble()) {
            // offene Zusammenfassungen zuerst schreiben, damit sie mit umziehen
            merger.flushPositions(target.positions());
        }
        // DROP vor BREAK einreihen: bei gleichem Zeitstempel steht BREAK im Fenster dann über seinen Drops.
        for (Map.Entry<ItemKey, Integer> drop : drops.entrySet()) {
            ItemKey key = drop.getKey();
            writer.submit(new LogEntry(0, now, pos, player.getUniqueId(), player.getName(),
                    LogAction.DROP, key.material().name(), drop.getValue(), ItemCodec.encode(key.stack())));
        }
        writer.submit(new LogEntry(0, now, pos, player.getUniqueId(), player.getName(),
                LogAction.BREAK, target.material().name(), 1, null));
        if (target.isDouble() && remaining != null) {
            writer.relocate(broken, remaining).exceptionally(e -> {
                logger.log(Level.SEVERE, "Einträge von " + broken.coordinates() + " konnten nicht nach "
                        + remaining.coordinates() + " verschoben werden", e);
                return 0;
            });
        }
    }

    /** Lädt die neuesten Einträge beider Hälften. Offene Zusammenfassungen dieses Containers werden vorher geschrieben. */
    public CompletableFuture<QueryResult> query(ContainerTarget target) {
        merger.flushPositions(target.positions());
        return writer.query(target.positions(), settings.get().maxGuiEntries());
    }

    public CompletableFuture<Integer> deleteOlderThan(long cutoffMillis) {
        return writer.deleteOlderThan(cutoffMillis);
    }

    void flushExpired() {
        merger.flushExpired(System.currentTimeMillis());
    }

    void flushAll() {
        merger.flushAll();
    }

    private void write(EntryMerger.Merged<ItemKey> merged) {
        ItemKey key = merged.item();
        writer.submit(new LogEntry(0, merged.timestamp(), merged.pos(), merged.player(), merged.playerName(),
                merged.action(), key.material().name(), merged.amount(), ItemCodec.encode(key.stack())));
    }

    private static long windowMillis(PluginConfig config) {
        return config.mergeWindowSeconds() * 1000L;
    }
}
