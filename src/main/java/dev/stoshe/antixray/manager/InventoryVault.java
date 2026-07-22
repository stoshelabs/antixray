package dev.stoshe.antixray.manager;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores an admin's whole inventory while they spectate (so the hotbar can be replaced with the spectator
 * tools) and hands it back untouched when they detach, disconnect, or the server stops.
 *
 * <p>Ported from the same-named class in aerowars, trimmed to what spectate needs. Every section a tool could
 * land in is captured AND cleared — {@code Inventory.clear()} only touches hotbar/storage/backpack, so armor
 * and utility/tools would otherwise survive. All ECS work is marshalled onto the owning world thread, and the
 * {@link PlayerRef} is resolved fresh there because a cross-world teleport changes the ECS ref.
 */
public final class InventoryVault {

    private static final int HOTBAR = 0;
    private static final int STORAGE = 1;
    private static final int BACKPACK = 2;
    private static final int ARMOR = 3;
    private static final int UTILITY = 4;
    private static final int TOOLS = 5;
    private static final int SECTION_COUNT = 6;

    private record SavedSlot(int container, short slot, ItemStack stack) { }

    private final Map<UUID, List<SavedSlot>> saved = new ConcurrentHashMap<>();


    public boolean has(UUID uuid) {
        return saved.containsKey(uuid);
    }

    /**
     * Snapshots and clears the player's inventory, then runs {@code andThen} on the world thread with the now
     * empty {@link Inventory} so the caller can drop its own items in. No-op if a snapshot already exists
     * (re-attaching to another suspect must not overwrite the real inventory with the tool bar).
     */
    public void stash(World world, UUID uuid, java.util.function.Consumer<Inventory> andThen) {
        if (world == null || uuid == null || saved.containsKey(uuid)) {
            return;
        }
        world.execute(() -> {
            try {
                PlayerRef pr = Universe.get().getPlayer(uuid);
                if (pr == null) {
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                Ref<EntityStore> ref = pr.getReference();
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) {
                    return;
                }
                Inventory inv = player.getInventory();
                List<SavedSlot> slots = new ArrayList<>();
                for (int i = 0; i < SECTION_COUNT; i++) {
                    snapshot(container(inv, i), i, slots);
                }
                saved.put(uuid, slots);
                clearAll(inv);
                if (andThen != null) {
                    andThen.accept(inv);
                }
            } catch (Exception e) {
                Console.warning("InventoryVault.stash failed: " + e.getMessage());
            }
        });
    }

    /**
     * Drops a stack into a STASHED snapshot, so an admin who confiscates evidence while spectating actually
     * gets it when they detach (their live inventory holds the spectator tools and is wiped by the restore).
     * Returns false when nothing is stashed — the caller should then give the item normally.
     */
    public boolean addToSnapshot(UUID uuid, ItemStack stack) {
        List<SavedSlot> slots = saved.get(uuid);
        if (slots == null || stack == null || stack.isEmpty()) {
            return false;
        }
        synchronized (slots) {
            for (short slot = 0; slot < 64; slot++) {
                final short s = slot;
                boolean taken = slots.stream().anyMatch(x -> x.container() == STORAGE && x.slot() == s);
                if (!taken) {
                    slots.add(new SavedSlot(STORAGE, s, stack));
                    return true;
                }
            }
        }
        return false; // snapshot storage full — fall back to a live give
    }

    /** Gives the saved inventory back. Safe to call when nothing is stashed. */
    public void restore(World world, UUID uuid) {
        if (world == null || uuid == null) {
            return;
        }
        if (Universe.get().getPlayer(uuid) == null) {
            return; // player not resolvable (mid-disconnect) — keep the snapshot for restoreOnDisconnect
        }
        List<SavedSlot> slots = saved.remove(uuid);
        if (slots == null) {
            return;
        }
        world.execute(() -> {
            try {
                PlayerRef pr = Universe.get().getPlayer(uuid);
                if (pr == null) {
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                Ref<EntityStore> ref = pr.getReference();
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) {
                    return;
                }
                apply(player.getInventory(), slots);
            } catch (Exception e) {
                Console.warning("InventoryVault.restore failed: " + e.getMessage());
            }
        });
    }

    /**
     * Restore path for a DISCONNECT: uses the event's own live {@link PlayerRef} (by then
     * {@code Universe.getPlayer(uuid)} already returns null) so the admin logs back in with their own gear
     * instead of the spectator tools. Still marshalled onto the world thread — the disconnect event fires on
     * a network worker and direct ECS access there throws.
     */
    public void restoreOnDisconnect(PlayerRef pr) {
        if (pr == null || pr.getWorldUuid() == null) {
            return;
        }
        List<SavedSlot> slots = saved.remove(pr.getUuid());
        if (slots == null) {
            return;
        }
        World world = Universe.get().getWorld(pr.getWorldUuid());
        if (world == null) {
            return;
        }
        Ref<EntityStore> ref = pr.getReference();
        world.execute(() -> {
            try {
                Player player = world.getEntityStore().getStore().getComponent(ref, Player.getComponentType());
                if (player != null) {
                    apply(player.getInventory(), slots);
                }
            } catch (Exception e) {
                Console.warning("InventoryVault.restoreOnDisconnect failed: " + e.getMessage());
            }
        });
    }

    private void apply(Inventory inv, List<SavedSlot> slots) {
        clearAll(inv);
        for (SavedSlot slot : slots) {
            ItemContainer c = container(inv, slot.container());
            if (c != null && slot.stack() != null) {
                c.setItemStackForSlot(slot.slot(), slot.stack());
            }
        }
    }

    private void snapshot(ItemContainer container, int index, List<SavedSlot> out) {
        if (container == null) {
            return;
        }
        container.forEach((slot, stack) -> {
            if (stack != null && !stack.isEmpty()) {
                out.add(new SavedSlot(index, slot, stack));
            }
        });
    }

    private ItemContainer container(Inventory inv, int index) {
        return switch (index) {
            case HOTBAR -> inv.getHotbar();
            case STORAGE -> inv.getStorage();
            case BACKPACK -> inv.getBackpack();
            case ARMOR -> inv.getArmor();
            case UTILITY -> inv.getUtility();
            case TOOLS -> inv.getTools();
            default -> null;
        };
    }

    private void clearAll(Inventory inv) {
        for (int i = 0; i < SECTION_COUNT; i++) {
            ItemContainer c = container(inv, i);
            if (c != null) {
                c.clear();
            }
        }
    }
}
