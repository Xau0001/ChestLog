package onl.xau.chestlog.container;

import java.util.List;
import java.util.UUID;

import org.bukkit.Material;

import onl.xau.chestlog.model.BlockPos;

/**
 * Ein erfasster Container.
 *
 * @param worldName  Name der Welt für die Anzeige
 * @param canonical  Position, unter der neue Einträge gespeichert werden (bei Doppeltruhen die Hälfte mit den
 *                   kleineren Koordinaten)
 * @param positions  alle Positionen, die bei einer Abfrage gelesen werden (eine oder beide Hälften)
 * @param material   Material des Containers
 */
public record ContainerTarget(String worldName, BlockPos canonical, List<BlockPos> positions, Material material) {

    public ContainerTarget {
        positions = List.copyOf(positions);
    }

    public UUID worldId() {
        return canonical.world();
    }

    public boolean isDouble() {
        return positions.size() > 1;
    }
}
