package dev.stoshe.antixray.manager;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.stoshe.antixray.util.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads and edits a suspect's inventory on behalf of an admin: the backing service for the panel's
 * "Inventory" view. Every operation is marshalled onto the suspect's world thread and identifies a stack by
 * (section, slot) rather than by index in a snapshot, so a stack the suspect moved between the read and the
 * click is never confiscated by mistake — the id is re-checked before anything is taken.
 */
public final class SuspectInventory {

    private final InventoryVault vault;

    public SuspectInventory(InventoryVault vault) {
        this.vault = vault;
    }

    /** Inventory sections we expose, in display order. */
    public static final int HOTBAR = 0;
    public static final int STORAGE = 1;
    public static final int BACKPACK = 2;
    public static final int ARMOR = 3;
    public static final int UTILITY = 4;
    public static final int TOOLS = 5;
    private static final int[] SECTIONS = {HOTBAR, STORAGE, BACKPACK, ARMOR, UTILITY, TOOLS};

    /** One stack in a suspect's inventory, addressable across reads. */
    public record Entry(int section, short slot, String itemId, int quantity) {

        public String sectionName() {
            return switch (section) {
                case HOTBAR -> "hotbar";
                case STORAGE -> "storage";
                case BACKPACK -> "backpack";
                case ARMOR -> "armor";
                case UTILITY -> "utility";
                case TOOLS -> "tools";
                default -> "?";
            };
        }
    }

    /** Reads everything the suspect carries and hands it to {@code out} on the world thread. */
    public void read(PlayerRef target, Consumer<List<Entry>> out) {
        onSuspect(target, (player, inv) -> {
            List<Entry> entries = new ArrayList<>();
            for (int section : SECTIONS) {
                ItemContainer c = container(inv, section);
                if (c == null) {
                    continue;
                }
                c.forEach((slot, stack) -> {
                    if (stack != null && !stack.isEmpty()) {
                        entries.add(new Entry(section, slot, stack.getItemId(), stack.getQuantity()));
                    }
                });
            }
            out.accept(entries);
        });
    }

    /**
     * Takes a stack off the suspect. {@code toAdmin} decides whether it lands in the admin's inventory
     * (evidence) or is destroyed. Only acts if the slot still holds the same item id.
     *
     * @param onDone called with true if the stack was taken, false if it had already moved/changed.
     */
    public void confiscate(PlayerRef admin, PlayerRef target, Entry entry, boolean toAdmin,
            Consumer<Boolean> onDone) {
        onSuspect(target, (player, inv) -> {
            ItemContainer c = container(inv, entry.section());
            if (c == null) {
                onDone.accept(false);
                return;
            }
            ItemStack current = c.getItemStack(entry.slot());
            if (current == null || current.isEmpty() || !entry.itemId().equals(current.getItemId())) {
                onDone.accept(false); // the suspect moved it between the read and the click
                return;
            }
            ItemStack taken = new ItemStack(current.getItemId(), current.getQuantity());
            c.setItemStackForSlot(entry.slot(), ItemStack.EMPTY);
            if (toAdmin) {
                giveToAdmin(admin, taken);
            }
            onDone.accept(true);
        });
    }

    /**
     * Hands a confiscated stack to the admin. While they are spectating their real inventory is stashed in
     * the {@link InventoryVault} and the live one holds the spectator tools, so the stack goes into the
     * SNAPSHOT — it appears when they detach, instead of being wiped by the restore.
     */
    private void giveToAdmin(PlayerRef admin, ItemStack stack) {
        if (admin == null) {
            return;
        }
        World world = Universe.get().getWorld(admin.getWorldUuid());
        if (world == null) {
            return;
        }
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = admin.getReference();
                if (ref == null || !ref.isValid()) {
                    return;
                }
                Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                if (player == null) {
                    return;
                }
                // While spectating, the live inventory is the tool bar and gets wiped on detach — so the
                // evidence goes into the stashed snapshot and shows up when the admin gets their gear back.
                if (vault == null || !vault.addToSnapshot(admin.getUuid(), stack)) {
                    Player.giveItem(stack, ref, ref.getStore());
                }
            } catch (Exception e) {
                Console.warning("confiscate → give failed: " + e.getMessage());
            }
        });
    }

    private void onSuspect(PlayerRef target, java.util.function.BiConsumer<Player, Inventory> action) {
        if (target == null) {
            return;
        }
        World world = Universe.get().getWorld(target.getWorldUuid());
        if (world == null) {
            return;
        }
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = target.getReference();
                if (ref == null || !ref.isValid()) {
                    return;
                }
                Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                if (player == null || player.getInventory() == null) {
                    return;
                }
                action.accept(player, player.getInventory());
            } catch (Exception e) {
                Console.warning("suspect inventory access failed: " + e.getMessage());
            }
        });
    }

    private ItemContainer container(Inventory inv, int section) {
        return switch (section) {
            case HOTBAR -> inv.getHotbar();
            case STORAGE -> inv.getStorage();
            case BACKPACK -> inv.getBackpack();
            case ARMOR -> inv.getArmor();
            case UTILITY -> inv.getUtility();
            case TOOLS -> inv.getTools();
            default -> null;
        };
    }
}
