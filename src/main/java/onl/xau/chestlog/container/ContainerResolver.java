package onl.xau.chestlog.container;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import onl.xau.chestlog.PluginConfig;
import onl.xau.chestlog.model.BlockPos;

/**
 * Entscheidet, ob ein Block oder ein Inventar zu einem erfassten Container gehört, und bestimmt dessen Positionen.
 * Nur vom Main-Thread benutzen.
 */
public final class ContainerResolver {

    private final Supplier<PluginConfig> settings;
    // Ob der BlockState eines Materials ein Container ist. Spart das Anlegen von BlockStates bei jedem Abbau.
    private final Map<Material, Boolean> containerMaterials = new HashMap<>();

    public ContainerResolver(Supplier<PluginConfig> settings) {
        this.settings = settings;
    }

    /** Der erfasste Container an diesem Block oder {@code null}. */
    public ContainerTarget resolve(Block block) {
        if (block == null) {
            return null;
        }
        Material type = block.getType();
        if (!isAllowed(type, block.getWorld())) {
            return null;
        }
        Boolean container = containerMaterials.get(type);
        if (container == null) {
            container = block.getState() instanceof Container;
            containerMaterials.put(type, container);
        }
        return container ? target(block, type) : null;
    }

    /** Der erfasste Container, zu dem dieses Inventar gehört, oder {@code null}. */
    public ContainerTarget resolve(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        Block block;
        Block other = null;
        if (holder instanceof DoubleChest doubleChest
                && doubleChest.getLeftSide() instanceof BlockState left
                && doubleChest.getRightSide() instanceof BlockState right) {
            block = left.getBlock();
            other = right.getBlock();
        } else if (holder instanceof Container container) {
            block = container.getBlock();
        } else {
            return null;
        }
        Material type = block.getType();
        if (!isAllowed(type, block.getWorld())) {
            return null;
        }
        containerMaterials.put(type, true);
        return other != null ? target(block, other, type) : target(block, type);
    }

    /** Prüft, ob der Container noch steht. Ungeladene Chunks werden nicht geladen und gelten als vorhanden. */
    public boolean isStillPresent(ContainerTarget target) {
        World world = Bukkit.getWorld(target.worldId());
        if (world == null) {
            return false;
        }
        BlockPos pos = target.canonical();
        if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return true;
        }
        return world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() == target.material();
    }

    private boolean isAllowed(Material type, World world) {
        PluginConfig config = settings.get();
        return type != Material.ENDER_CHEST && config.isContainerAllowed(type) && !config.isWorldDisabled(world);
    }

    private static ContainerTarget target(Block block, Material type) {
        Block other = otherChestHalf(block);
        if (other == null) {
            BlockPos own = pos(block);
            return new ContainerTarget(block.getWorld().getName(), own, List.of(own), type);
        }
        return target(block, other, type);
    }

    private static ContainerTarget target(Block block, Block other, Material type) {
        BlockPos own = pos(block);
        BlockPos otherPos = pos(other);
        return new ContainerTarget(block.getWorld().getName(), BlockPos.min(own, otherPos), List.of(own, otherPos),
                type);
    }

    /** Die andere Hälfte einer Doppeltruhe oder {@code null}. */
    static Block otherChestHalf(Block block) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Chest chest)) {
            return otherChestHalfFromState(block);
        }
        if (chest.getType() == Chest.Type.SINGLE) {
            return null;
        }
        // Wie in Vanilla: LEFT verbindet im Uhrzeigersinn der Blickrichtung, RIGHT dagegen.
        BlockFace facing = chest.getFacing();
        BlockFace expected = chest.getType() == Chest.Type.LEFT ? clockwise(facing) : counterClockwise(facing);
        Chest.Type opposite = chest.getType() == Chest.Type.LEFT ? Chest.Type.RIGHT : Chest.Type.LEFT;
        for (BlockFace direction : new BlockFace[] {expected, expected.getOppositeFace()}) {
            Block neighbour = block.getRelative(direction);
            if (neighbour.getType() == block.getType()
                    && neighbour.getBlockData() instanceof Chest other
                    && other.getType() == opposite
                    && other.getFacing() == facing) {
                return neighbour;
            }
        }
        // Direkt nach dem Platzieren ist die Nachbarhälfte noch nicht aktualisiert.
        Block neighbour = block.getRelative(expected);
        return neighbour.getType() == block.getType() ? neighbour : null;
    }

    // Für Truhenarten, deren BlockData nicht Chest ist: über das Inventar. Teurer, daher nur bei *CHEST-Materialien.
    private static Block otherChestHalfFromState(Block block) {
        if (!block.getType().name().contains("CHEST")
                || !(block.getState() instanceof org.bukkit.block.Chest state)
                || !(state.getInventory().getHolder() instanceof DoubleChest doubleChest)
                || !(doubleChest.getLeftSide() instanceof BlockState left)
                || !(doubleChest.getRightSide() instanceof BlockState right)) {
            return null;
        }
        Block leftBlock = left.getBlock();
        return leftBlock.equals(block) ? right.getBlock() : leftBlock;
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    private static BlockFace counterClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> face;
        };
    }

    private static BlockPos pos(Block block) {
        return new BlockPos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
