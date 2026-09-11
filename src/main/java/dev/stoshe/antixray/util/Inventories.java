package dev.stoshe.antixray.util;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A player's six inventory sections, addressed by a stable index. Hytale 0.6 split the old {@code Inventory}
 * object (now deprecated for removal) into one ECS component per section; each still wraps an
 * {@link ItemContainer}, and edits made through it are picked up and synced by the component itself.
 * World thread only, like every other ECS read.
 */
public final class Inventories {

    public static final int HOTBAR = 0;
    public static final int STORAGE = 1;
    public static final int BACKPACK = 2;
    public static final int ARMOR = 3;
    public static final int UTILITY = 4;
    public static final int TOOLS = 5;
    public static final int SECTION_COUNT = 6;

    private Inventories() {
    }

    /** The container behind one section, or null if the entity doesn't have that section. */
    public static ItemContainer section(Store<EntityStore> store, Ref<EntityStore> ref, int section) {
        if (store == null || ref == null || !ref.isValid()) {
            return null;
        }
        InventoryComponent c = switch (section) {
            case HOTBAR -> store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            case STORAGE -> store.getComponent(ref, InventoryComponent.Storage.getComponentType());
            case BACKPACK -> store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
            case ARMOR -> store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            case UTILITY -> store.getComponent(ref, InventoryComponent.Utility.getComponentType());
            case TOOLS -> store.getComponent(ref, InventoryComponent.Tool.getComponentType());
            default -> null;
        };
        return c == null ? null : c.getInventory();
    }
}
