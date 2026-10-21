package onl.xau.chestlog.container;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Item-Art als Map-Schlüssel: eine Kopie mit Menge 1, Gleichheit wie {@link ItemStack#isSimilar(ItemStack)} (also
 * inklusive Name, Verzauberungen und Komponenten).
 */
public final class ItemKey {

    private final ItemStack stack;
    private final int hash;

    private ItemKey(ItemStack stack) {
        this.stack = stack;
        // ItemStack#hashCode passt zu equals, bei Menge 1 also zu isSimilar. Ein reiner Material-Hash würde z. B. in
        // einer Kiste voller verschiedener verzauberter Bücher alle Schlüssel kollidieren lassen (quadratischer
        // Aufwand pro Klick).
        this.hash = stack.hashCode();
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
        return o instanceof ItemKey other && hash == other.hash && stack.isSimilar(other.stack);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "ItemKey[" + stack.getType() + "]";
    }
}
