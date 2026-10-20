package onl.xau.chestlog.container;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Item-Art als Map-Schlüssel: eine Kopie mit Menge 1, Gleichheit wie {@link ItemStack#isSimilar(ItemStack)} (also
 * inklusive Name, Verzauberungen und Komponenten).
 */
public final class ItemKey {

    private final ItemStack stack;

    private ItemKey(ItemStack stack) {
        this.stack = stack;
    }

    /** Schlüssel für den Stack oder {@code null} für leere Slots. */
    public static ItemKey of(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return null;
        }
        ItemStack copy = stack.clone();
        copy.setAmount(1);
        return new ItemKey(copy);
    }

    public Material material() {
        return stack.getType();
    }

    /** Eine Kopie mit Menge 1. */
    public ItemStack stack() {
        return stack.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemKey other && stack.isSimilar(other.stack);
    }

    // Grob, aber passend zu isSimilar: ähnliche Items haben immer dasselbe Material.
    @Override
    public int hashCode() {
        return stack.getType().hashCode();
    }

    @Override
    public String toString() {
        return "ItemKey[" + stack.getType() + "]";
    }
}
