package onl.xau.chestlog.merge;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import onl.xau.chestlog.model.BlockPos;
import onl.xau.chestlog.model.LogAction;

/**
 * Fasst ADD/REMOVE desselben Spielers, derselben Item-Art, desselben Containers und derselben Richtung innerhalb
 * eines Zeitfensters zu einem Eintrag zusammen. Das Fenster beginnt mit dem ersten Ereignis, dessen Zeitstempel
 * der Eintrag behält. Außerdem unterdrückt es wiederholte OPEN-Einträge innerhalb des Fensters.
 *
 * <p>Nicht threadsicher, wird nur vom Main-Thread benutzt.
 *
 * @param <K> Schlüssel der Item-Art, {@code equals}/{@code hashCode} müssen „gleiche Item-Art“ abbilden
 */
public final class EntryMerger<K> {

    /** Ein fertig zusammengefasster Eintrag. */
    public record Merged<K>(UUID player, String playerName, BlockPos pos, LogAction action, K item, int amount,
                            long timestamp) {
    }

    private record BucketKey<K>(UUID player, BlockPos pos, LogAction action, K item) {
    }

    private record OpenKey(UUID player, BlockPos pos) {
    }

    private static final class Bucket {
        final String playerName;
        final long firstTimestamp;
        int amount;

        Bucket(String playerName, long firstTimestamp, int amount) {
            this.playerName = playerName;
            this.firstTimestamp = firstTimestamp;
            this.amount = amount;
        }
    }

    private final Consumer<Merged<K>> sink;
    private final Map<BucketKey<K>, Bucket> buckets = new LinkedHashMap<>();
    private final Map<OpenKey, Long> recentOpens = new HashMap<>();
    private long windowMillis;

    public EntryMerger(long windowMillis, Consumer<Merged<K>> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
        setWindowMillis(windowMillis);
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = Math.max(0, windowMillis);
    }

    public long windowMillis() {
        return windowMillis;
    }

    /**
     * Erfasst eine Menge für ADD oder REMOVE. Liegt ein offener Eintrag mit gleichem Schlüssel im Fenster, wird die
     * Menge addiert, sonst wird der alte Eintrag ausgegeben und ein neuer begonnen.
     */
    public void record(UUID player, String playerName, BlockPos pos, LogAction action, K item, int amount, long now) {
        if (action != LogAction.ADD && action != LogAction.REMOVE) {
            throw new IllegalArgumentException("Nur ADD und REMOVE werden zusammengefasst: " + action);
        }
        if (amount <= 0) {
            return;
        }
        BucketKey<K> key = new BucketKey<>(player, pos, action, item);
        Bucket bucket = buckets.get(key);
        if (bucket != null && now - bucket.firstTimestamp < windowMillis) {
            bucket.amount += amount;
            return;
        }
        if (bucket != null) {
            buckets.remove(key);
            emit(key, bucket);
        }
        buckets.put(key, new Bucket(playerName, now, amount));
    }

    /**
     * Prüft, ob ein OPEN geloggt werden soll. Liefert {@code false}, wenn derselbe Spieler denselben Container
     * innerhalb des Fensters schon geöffnet hat.
     */
    public boolean shouldLogOpen(UUID player, BlockPos pos, long now) {
        OpenKey key = new OpenKey(player, pos);
        Long last = recentOpens.get(key);
        if (last != null && now - last < windowMillis) {
            return false;
        }
        recentOpens.put(key, now);
        return true;
    }

    /** Gibt alle Einträge aus, deren Fenster abgelaufen ist. */
    public void flushExpired(long now) {
        Iterator<Map.Entry<BucketKey<K>, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BucketKey<K>, Bucket> e = it.next();
            if (now - e.getValue().firstTimestamp >= windowMillis) {
                it.remove();
                emit(e.getKey(), e.getValue());
            }
        }
        recentOpens.values().removeIf(ts -> now - ts >= windowMillis);
    }

    /** Gibt alle offenen Einträge an den angegebenen Positionen sofort aus, z. B. vor einer Abfrage. */
    public void flushPositions(Collection<BlockPos> positions) {
        Set<BlockPos> set = new HashSet<>(positions);
        Iterator<Map.Entry<BucketKey<K>, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BucketKey<K>, Bucket> e = it.next();
            if (set.contains(e.getKey().pos())) {
                it.remove();
                emit(e.getKey(), e.getValue());
            }
        }
    }

    /** Gibt alle offenen Einträge aus. */
    public void flushAll() {
        Iterator<Map.Entry<BucketKey<K>, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BucketKey<K>, Bucket> e = it.next();
            it.remove();
            emit(e.getKey(), e.getValue());
        }
        recentOpens.clear();
    }

    public int pendingCount() {
        return buckets.size();
    }

    private void emit(BucketKey<K> key, Bucket bucket) {
        sink.accept(new Merged<>(key.player(), bucket.playerName, key.pos(), key.action(), key.item(), bucket.amount,
                bucket.firstTimestamp));
    }
}
