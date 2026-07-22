package dev.stoshe.antixray.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.stoshe.antixray.AntiXray;
import dev.stoshe.antixray.manager.SuspectInventory;
import dev.stoshe.antixray.util.ChatUtil;
import dev.stoshe.antixray.util.Tr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shows what a suspect is carrying, with per-stack actions: <em>Take</em> confiscates it as evidence (it
 * reaches the admin when they stop spectating, since the live inventory is the spectator tool bar) and
 * <em>Drop</em> destroys it.
 *
 * <p>The suspect's inventory is read on their world thread, so the rows come from a snapshot taken one tick
 * earlier; every action re-checks the slot's item id before touching it, so a stack moved in between is
 * refused instead of the wrong one being taken.
 */
public class SuspectInventoryPage extends InteractiveCustomUIPage<SuspectInventoryPage.PageData> {

    private static final int MAX_ROWS = 12;

    private final PlayerRef playerRef;
    private final AntiXray plugin;
    private final UUID targetUuid;
    private final String targetName;
    private final List<SuspectInventory.Entry> entries;

    private SuspectInventoryPage(@Nonnull PlayerRef playerRef, @Nonnull AntiXray plugin, UUID targetUuid,
            String targetName, List<SuspectInventory.Entry> entries) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.playerRef = playerRef;
        this.plugin = plugin;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.entries = entries;
    }

    /**
     * Reads the suspect's inventory (async, on their world thread) and then opens the page for {@code admin}.
     * Opening is marshalled back onto the admin's world thread because it touches their page manager.
     */
    public static void open(AntiXray plugin, PlayerRef admin, PlayerRef target) {
        if (plugin == null || admin == null || target == null) {
            return;
        }
        UUID targetUuid = target.getUuid();
        String name = target.getUsername();
        plugin.getSuspectInventory().read(target, entries -> {
            var world = Universe.get().getWorld(admin.getWorldUuid());
            if (world == null) {
                return;
            }
            world.execute(() -> {
                Ref<EntityStore> ref = admin.getReference();
                if (ref == null || !ref.isValid()) {
                    return;
                }
                Store<EntityStore> store = ref.getStore();
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) {
                    return;
                }
                player.getPageManager().openCustomPage(ref, store, (CustomUIPage)
                        new SuspectInventoryPage(admin, plugin, targetUuid, name, new ArrayList<>(entries)));
            });
        });
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cb,
            @Nonnull UIEventBuilder eb, @Nonnull Store<EntityStore> store) {
        cb.append("Pages/AntiXraySuspectInv.ui");
        cb.set("#InvTitle.Text", Tr.t("inv.title"));
        cb.set("#InvSubtitle.Text", Tr.t("inv.subtitle", "player", targetName, "n", entries.size()));
        cb.set("#InvEmpty.Text", Tr.t("inv.empty"));
        cb.set("#InvEmpty.Visible", entries.isEmpty());
        cb.set("#BtnInvRefresh.Text", Tr.t("inv.refresh"));
        cb.set("#BtnInvClose.Text", Tr.t("panel.close"));
        eb.addEventBinding(CustomUIEventBindingType.Activating, "#BtnInvRefresh",
                EventData.of("Action", "Refresh"), false);
        eb.addEventBinding(CustomUIEventBindingType.Activating, "#BtnInvClose",
                EventData.of("Action", "Close"), false);

        String take = Tr.t("inv.take");
        String drop = Tr.t("inv.drop");
        for (int i = 0; i < MAX_ROWS; i++) {
            if (i >= entries.size()) {
                cb.set("#InvRow" + i + ".Visible", false);
                continue;
            }
            SuspectInventory.Entry e = entries.get(i);
            cb.set("#InvRow" + i + ".Visible", true);
            cb.set("#InvName" + i + ".Text",
                    Tr.t("inv.row", "qty", e.quantity(), "item", e.itemId(), "where", e.sectionName()));
            cb.set("#BtnTake" + i + ".Text", take);
            cb.set("#BtnDrop" + i + ".Text", drop);
            eb.addEventBinding(CustomUIEventBindingType.Activating, "#BtnTake" + i,
                    EventData.of("Action", "Take").append("Param", String.valueOf(i)), false);
            eb.addEventBinding(CustomUIEventBindingType.Activating, "#BtnDrop" + i,
                    EventData.of("Action", "Drop").append("Param", String.valueOf(i)), false);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PageData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = data.action == null ? "" : data.action.trim();
        PlayerRef target = Universe.get().getPlayer(targetUuid);
        switch (action) {
            case "Close" -> player.getPageManager().setPage(ref, store, Page.None);
            case "Refresh" -> {
                player.getPageManager().setPage(ref, store, Page.None);
                if (target == null) {
                    playerRef.sendMessage(ChatUtil.error(Tr.t("inv.offline", "player", targetName)));
                } else {
                    open(plugin, playerRef, target);
                }
            }
            case "Take", "Drop" -> {
                int idx = parseIndex(data.param);
                if (idx < 0 || idx >= entries.size()) {
                    return;
                }
                SuspectInventory.Entry e = entries.get(idx);
                player.getPageManager().setPage(ref, store, Page.None);
                if (target == null) {
                    playerRef.sendMessage(ChatUtil.error(Tr.t("inv.offline", "player", targetName)));
                    return;
                }
                boolean toAdmin = "Take".equals(action);
                plugin.getSuspectInventory().confiscate(playerRef, target, e, toAdmin, ok -> {
                    if (!ok) {
                        playerRef.sendMessage(ChatUtil.warning(Tr.t("inv.moved")));
                        return;
                    }
                    playerRef.sendMessage(ChatUtil.success(Tr.t(toAdmin ? "inv.taken" : "inv.dropped",
                            "qty", e.quantity(), "item", e.itemId(), "player", targetName)));
                });
            }
            default -> {
            }
        }
    }

    private static int parseIndex(String param) {
        try {
            return Integer.parseInt(param == null ? "" : param.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public static class PageData {
        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("Param", Codec.STRING), (o, v) -> o.param = v, o -> o.param).add()
                .build();
        public String action;
        public String param;
    }
}
