package onl.xau.chestlog.model;

import java.util.Objects;
import java.util.UUID;

/** Blockposition in einer Welt, identifiziert über die Welt-UUID. */
public record BlockPos(UUID world, int x, int y, int z) {

    public BlockPos {
        Objects.requireNonNull(world, "world");
    }

    /** {@code true}, wenn diese Position kleinere Koordinaten hat (x, dann y, dann z). */
    public boolean isBefore(BlockPos other) {
        if (x != other.x) {
            return x < other.x;
        }
        if (y != other.y) {
            return y < other.y;
        }
        return z < other.z;
    }

    /** Die Position mit den kleineren Koordinaten, z. B. die kanonische Hälfte einer Doppeltruhe. */
    public static BlockPos min(BlockPos a, BlockPos b) {
        return b.isBefore(a) ? b : a;
    }

    public String coordinates() {
        return x + " " + y + " " + z;
    }
}
