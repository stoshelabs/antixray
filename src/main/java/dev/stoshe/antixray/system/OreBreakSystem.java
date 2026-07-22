package dev.stoshe.antixray.system;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.stoshe.antixray.AntiXray;
import org.joml.Vector3i;

import java.util.UUID;

/**
 * Observes player block breaks (never cancels them). On each break it:
 * <ol>
 *   <li>reveals the real blocks of the freshly exposed neighbours to that player, and</li>
 *   <li>feeds the detection heuristics — a honeypot hit (breaking a fake ore) or a tracked-ore break.</li>
 * </ol>
 */
public final class OreBreakSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private final AntiXray plugin;

    public OreBreakSystem(AntiXray plugin) {
        super(BreakBlockEvent.class);
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.of(PlayerRef.getComponentType());
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
            CommandBuffer<EntityStore> buffer, BreakBlockEvent event) {
        PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        UUID uuid = playerRef.getUuid();
        if (plugin.isBypassed(playerRef)) {
            return;
        }
        Vector3i pos = event.getTargetBlock();
        if (pos == null) {
            return;
        }
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null || !plugin.isWorldEnabled(world.getName())) {
            return;
        }

        BlockType blockType = event.getBlockType();
        String name = blockType == null ? null : blockType.getId();

        if (plugin.isProbing(uuid)) {
            int id = -1;
            try {
                id = world.getBlock(pos.x, pos.y, pos.z);
            } catch (Exception ignored) {
                // best effort
            }
            playerRef.sendMessage(dev.stoshe.antixray.util.ChatUtil.info(dev.stoshe.antixray.util.Tr.t(
                    "msg.probe_hit", "block", name, "id", id, "pos", pos.x + "," + pos.y + "," + pos.z)));
        }

        boolean wasTrap = false;
        try {
            wasTrap = plugin.getObfuscationManager().revealAround(playerRef, world, pos.x, pos.y, pos.z);
        } catch (Exception ignored) {
            // Detection must never interfere with the break itself.
        }

        if (wasTrap) {
            plugin.getDetectionManager().recordHoneypotHit(playerRef, name);
        }
        if (plugin.getBlockCatalog().isTrackedOreName(name)) {
            plugin.getDetectionManager().recordOreBreak(playerRef, name);
        }
    }
}
