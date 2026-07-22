package dev.stoshe.antixray.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.stoshe.antixray.AntiXray;
import dev.stoshe.antixray.ui.AntiXrayPanelPage;
import dev.stoshe.antixray.util.ChatUtil;
import dev.stoshe.antixray.util.PermissionUtil;
import dev.stoshe.antixray.util.Tr;

import javax.annotation.Nonnull;

/**
 * Root {@code /antixray} command (alias {@code /ax}). Opens the admin panel — every feature lives in the
 * panel's tabs (Suspects / Tools / Status).
 */
public class AntiXrayCommand extends AbstractPlayerCommand {
    private final AntiXray plugin;

    public AntiXrayCommand(@Nonnull AntiXray plugin) {
        super("antixray", "Open the AntiXray admin panel");
        this.plugin = plugin;
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        if (!PermissionUtil.isAdmin(playerRef.getUuid())) {
            playerRef.sendMessage(ChatUtil.error(Tr.t("general.no_permission")));
            return;
        }
        world.execute(() -> {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            AntiXrayPanelPage.open(player, ref, store, playerRef, plugin);
        });
    }
}
