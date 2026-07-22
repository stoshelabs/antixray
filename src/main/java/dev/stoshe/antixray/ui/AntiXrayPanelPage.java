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
import dev.stoshe.antixray.manager.DetectionManager;
import dev.stoshe.antixray.util.ChatUtil;
import dev.stoshe.antixray.util.Tr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * The AntiXray admin panel: a sidebar of tabs (Suspects / Tools / Status), modelled on the AeroWars admin
 * menu. Every admin feature lives here — spectating suspects, the probe/test/reload/clear tools, and a status
 * overview.
 */
public class AntiXrayPanelPage extends InteractiveCustomUIPage<AntiXrayPanelPage.PageData> {

    private static final int MAX_ROWS = 10;

    /**
     * Diagnostic tools (fake-field test flash, X-ray audit, trap list, synthetic suspect) are hidden from the
     * normal panel — they exist to answer "is obfuscation reaching the client?" during development, not for
     * day-to-day moderation. Boot with {@code -Dantixray.debug=true} to get them back, same convention as
     * {@code -Dantixray.sendtime.probe}.
     */
    private static final boolean DEBUG_TOOLS = Boolean.getBoolean("antixray.debug");

    private enum Tab { SUSPECTS, TOOLS, STATUS }

    private final PlayerRef playerRef;
    private final AntiXray plugin;
    private Tab tab;
    private List<DetectionManager.Snapshot> displayed = new ArrayList<>();

    private AntiXrayPanelPage(@Nonnull PlayerRef playerRef, @Nonnull AntiXray plugin, @Nonnull Tab tab) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.playerRef = playerRef;
        this.plugin = plugin;
        this.tab = tab;
    }

    public static void open(Player player, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef,
            AntiXray plugin) {
        open(player, ref, store, playerRef, plugin, Tab.SUSPECTS);
    }

    private static void open(Player player, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef,
            AntiXray plugin, Tab tab) {
        if (player == null || ref == null || store == null || playerRef == null || plugin == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store,
                (CustomUIPage) new AntiXrayPanelPage(playerRef, plugin, tab));
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cb,
            @Nonnull UIEventBuilder eb, @Nonnull Store<EntityStore> store) {
        cb.append("Pages/AntiXrayPanel.ui");
        cb.set("#AdminTitle.Text", Tr.t("panel.title") + "  v" + plugin.getVersion());

        navButton(cb, eb, "Suspects", Tr.t("panel.tab_suspects"), tab == Tab.SUSPECTS, "TabSuspects");
        navButton(cb, eb, "Tools", Tr.t("panel.tab_tools"), tab == Tab.TOOLS, "TabTools");
        navButton(cb, eb, "Status", Tr.t("panel.tab_status"), tab == Tab.STATUS, "TabStatus");
        cb.set("#BtnClose.Text", Tr.t("panel.close"));
        eb.addEventBinding(CustomUIEventBindingType.Activating, "#BtnClose", EventData.of("Action", "Close"), false);

        cb.set("#PanelSuspects.Visible", tab == Tab.SUSPECTS);
        cb.set("#PanelTools.Visible", tab == Tab.TOOLS);
        cb.set("#PanelStatus.Visible", tab == Tab.STATUS);

        switch (tab) {
            case SUSPECTS -> buildSuspects(cb, eb);
            case TOOLS -> buildTools(cb, eb);
            case STATUS -> buildStatus(cb);
        }
    }

    private void buildSuspects(UICommandBuilder cb, UIEventBuilder eb) {
        this.displayed = plugin.getDetectionManager().suspects();
        cb.set("#SuspSubtitle.Text", Tr.t("panel.suspects_subtitle"));
        cb.set("#SuspCount.Text", Tr.t("panel.suspects_count", "n", displayed.size()));
        cb.set("#SuspEmpty.Text", Tr.t("panel.suspects_empty"));
        cb.set("#SuspEmpty.Visible", displayed.isEmpty());
        cb.set("#BtnRefresh.Text", Tr.t("panel.refresh"));
        eb.addEventBinding(CustomUIEventBindingType.Activating, "#BtnRefresh",
                EventData.of("Action", "Refresh"), false);

        String spec = Tr.t("panel.row_spectate");
        String flag = Tr.t("row.flagged");
        for (int i = 0; i < MAX_ROWS; i++) {
            if (i < displayed.size()) {
                DetectionManager.Snapshot s = displayed.get(i);
                String line = Tr.t("row.info",
                        "tag", s.flagged() ? flag : "",
                        "name", s.name(),
                        "score", String.format("%.0f", s.score()),
                        "ores", s.oresInWindow(),
                        "honeypots", s.honeypotHits());
                cb.set("#SuspRow" + i + ".Visible", true);
                cb.set("#SuspName" + i + ".Text", line);
                cb.set("#BtnSpec" + i + ".Visible", true);
                cb.set("#BtnSpec" + i + ".Text", spec);
                eb.addEventBinding(CustomUIEventBindingType.Activating, "#BtnSpec" + i,
                        EventData.of("Action", "Spectate").append("Param", String.valueOf(i)), false);
            } else {
                cb.set("#SuspRow" + i + ".Visible", false);
            }
        }
    }

    private void buildTools(UICommandBuilder cb, UIEventBuilder eb) {
        cb.set("#ToolsTitle.Text", Tr.t("tools.title"));
        boolean probing = plugin.isProbing(playerRef.getUuid());
        toolButton(cb, eb, "Probe", probing ? Tr.t("tools.probe_on") : Tr.t("tools.probe_off"),
                Tr.t("tools.probe_hint"), "Probe");
        if (DEBUG_TOOLS) {
            toolButton(cb, eb, "Test", Tr.t("tools.test"), Tr.t("tools.test_hint"), "Test");
            toolButton(cb, eb, "Sim", Tr.t("tools.sim"), Tr.t("tools.sim_hint"), "Sim");
            toolButton(cb, eb, "Traps", Tr.t("tools.traps"), Tr.t("tools.traps_hint"), "Traps");
            toolButton(cb, eb, "Audit", Tr.t("tools.audit"), Tr.t("tools.audit_hint"), "Audit");
        }
        toolButton(cb, eb, "Reload", Tr.t("tools.reload"), Tr.t("tools.reload_hint"), "Reload");
        toolButton(cb, eb, "Clear", Tr.t("tools.clear"), Tr.t("tools.clear_hint"), "Clear");
        boolean fp = plugin.getSpectateManager().isFirstPerson(playerRef.getUuid());
        toolButton(cb, eb, "View", Tr.t(fp ? "tools.view_first" : "tools.view_third"),
                Tr.t("tools.view_hint"), "View");
        toolButton(cb, eb, "StopSpec", Tr.t("tools.stopspec"), Tr.t("tools.stopspec_hint"), "StopSpec");
    }

    private void buildStatus(UICommandBuilder cb) {
        cb.set("#StatusTitle.Text", Tr.t("status.title"));
        var obf = plugin.getConfig().Obfuscation;
        var det = plugin.getConfig().Detection;
        String on = Tr.t("status.on");
        String off = Tr.t("status.off");
        cb.set("#StatObf.Text", Tr.t("status.obfuscation", "state", obf.Enabled ? on : off));
        cb.set("#StatDet.Text", Tr.t("status.detection", "state", det.Enabled ? on : off));
        cb.set("#StatFakeOres.Text", Tr.t("status.fake_ores", "n", plugin.getBlockCatalog().fakeOreCount()));
        cb.set("#StatProtected.Text", Tr.t("status.protected",
                "n", plugin.getBlockCatalog().protectedCount(),
                "d", plugin.getBlockCatalog().decoyCount()));
        cb.set("#StatTracked.Text", Tr.t("status.tracked", "n", plugin.getDetectionManager().suspects().size()));
        var worlds = plugin.getConfig().General.EnabledWorlds;
        String worldsStr = (worlds == null || worlds.isEmpty()) ? Tr.t("status.all_worlds") : String.join(", ", worlds);
        cb.set("#StatWorlds.Text", Tr.t("status.worlds", "worlds", worldsStr));
        cb.set("#StatProbe.Text", Tr.t("status.probe",
                "state", plugin.isProbing(playerRef.getUuid()) ? on : off));
        java.util.UUID targetUuid = plugin.getSpectateManager().targetOf(playerRef.getUuid());
        PlayerRef target = targetUuid == null ? null : Universe.get().getPlayer(targetUuid);
        cb.set("#StatSpec.Text", Tr.t("status.spectating",
                "target", target != null ? target.getUsername() : Tr.t("status.not_spectating")));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PageData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = data.action == null ? "" : data.action.trim();
        switch (action) {
            case "Close" -> player.getPageManager().setPage(ref, store, Page.None);
            case "TabSuspects" -> open(player, ref, store, playerRef, plugin, Tab.SUSPECTS);
            case "TabTools" -> open(player, ref, store, playerRef, plugin, Tab.TOOLS);
            case "TabStatus" -> open(player, ref, store, playerRef, plugin, Tab.STATUS);
            case "Refresh" -> open(player, ref, store, playerRef, plugin, Tab.SUSPECTS);
            case "Spectate" -> {
                int idx = parseIndex(data.param);
                if (idx >= 0 && idx < displayed.size()) {
                    DetectionManager.Snapshot s = displayed.get(idx);
                    PlayerRef target = Universe.get().getPlayer(s.uuid());
                    player.getPageManager().setPage(ref, store, Page.None);
                    if (target == null) {
                        playerRef.sendMessage(ChatUtil.error(Tr.t("msg.spectate_offline", "player", s.name())));
                    } else {
                        plugin.getSpectateManager().spectate(playerRef, target);
                    }
                }
            }
            case "Probe" -> {
                plugin.toggleProbe(playerRef.getUuid());
                open(player, ref, store, playerRef, plugin, Tab.TOOLS);
            }
            case "Test" -> {
                if (DEBUG_TOOLS) {
                    plugin.getObfuscationManager().testFlash(playerRef, 5, 12);
                }
                player.getPageManager().setPage(ref, store, Page.None);
            }
            case "Sim" -> {
                if (DEBUG_TOOLS) {
                    int n = plugin.getDetectionManager().flagOnlinePlayers();
                    playerRef.sendMessage(ChatUtil.success(Tr.t("msg.sim_done", "n", n)));
                }
                open(player, ref, store, playerRef, plugin, Tab.SUSPECTS);
            }
            case "Traps" -> {
                if (DEBUG_TOOLS) {
                    var pos = playerRef.getTransform().getPosition();
                    var mgr = plugin.getObfuscationManager();
                    java.util.UUID uuid = playerRef.getUuid();
                    java.util.List<String> lines = mgr.nearestTraps(uuid, (int) Math.floor(pos.x),
                            (int) Math.floor(pos.y), (int) Math.floor(pos.z), 10);
                    playerRef.sendMessage(ChatUtil.info(Tr.t("msg.traps_header", "n", mgr.trapCount(uuid))));
                    for (String line : lines) {
                        playerRef.sendMessage(ChatUtil.info(line));
                    }
                }
                player.getPageManager().setPage(ref, store, Page.None);
            }
            case "Audit" -> {
                if (DEBUG_TOOLS) {
                    plugin.getObfuscationManager().xrayAudit(playerRef, 16, 15);
                }
                player.getPageManager().setPage(ref, store, Page.None);
            }
            case "Reload" -> {
                boolean ok = plugin.reload();
                playerRef.sendMessage(ok ? ChatUtil.success(Tr.t("msg.reloaded"))
                        : ChatUtil.error(Tr.t("msg.reload_failed")));
                open(player, ref, store, playerRef, plugin, Tab.TOOLS);
            }
            case "Clear" -> {
                plugin.getDetectionManager().clearAll();
                playerRef.sendMessage(ChatUtil.success(Tr.t("msg.cleared_all")));
                open(player, ref, store, playerRef, plugin, Tab.STATUS);
            }
            case "View" -> {
                if (!plugin.getSpectateManager().toggleView(playerRef)) {
                    playerRef.sendMessage(ChatUtil.info(Tr.t("msg.not_spectating")));
                }
                open(player, ref, store, playerRef, plugin, Tab.TOOLS);
            }
            case "StopSpec" -> {
                if (plugin.getSpectateManager().isSpectating(playerRef.getUuid())) {
                    plugin.getSpectateManager().stop(playerRef);
                } else {
                    playerRef.sendMessage(ChatUtil.info(Tr.t("msg.not_spectating")));
                }
                player.getPageManager().setPage(ref, store, Page.None);
            }
            default -> {
            }
        }
    }

    private static int parseIndex(String param) {
        try {
            return Integer.parseInt(param == null ? "" : param.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Sidebar nav item: shows the highlighted Active variant for the current tab. */
    private void navButton(UICommandBuilder cb, UIEventBuilder eb, String id, String text, boolean active,
            String action) {
        cb.set("#Nav" + id + ".Text", text);
        cb.set("#Nav" + id + "Active.Text", text);
        cb.set("#Nav" + id + ".Visible", !active);
        cb.set("#Nav" + id + "Active.Visible", active);
        eb.addEventBinding(CustomUIEventBindingType.Activating, "#Nav" + id, EventData.of("Action", action), false);
        eb.addEventBinding(CustomUIEventBindingType.Activating, "#Nav" + id + "Active",
                EventData.of("Action", action), false);
    }

    private void toolButton(UICommandBuilder cb, UIEventBuilder eb, String id, String text, String hint,
            String action) {
        // The debug tools ship Visible:false in the .ui so they stay hidden unless the builder draws them.
        cb.set("#Btn" + id + ".Visible", true);
        cb.set("#Hint" + id + ".Visible", true);
        cb.set("#Btn" + id + ".Text", text);
        cb.set("#Hint" + id + ".Text", hint);
        eb.addEventBinding(CustomUIEventBindingType.Activating, "#Btn" + id, EventData.of("Action", action), false);
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
