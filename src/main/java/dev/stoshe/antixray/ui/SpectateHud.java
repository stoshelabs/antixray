package dev.stoshe.antixray.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/**
 * The spectator HUD: a small right-anchored panel telling the admin that they are attached, who they are
 * watching, in which view, and which mouse shortcuts are live. Modelled on aerowars' scoreboard HUD —
 * element ids mirror {@code HUD/AntiXraySpectate.ui}. Driven by
 * {@link dev.stoshe.antixray.manager.SpectateManager}.
 */
public class SpectateHud extends CustomUIHud {

    /** Stable key so the HUD can be removed again without holding the instance. */
    public static final String KEY = "antixray_spectate";

    /** Item lines the panel can render — must match the label count in AntiXraySpectate.ui. */
    public static final int INV_LINES = 8;

    private boolean visible;
    private String target = "";
    private String title = "";
    private String view = "";
    private String score = "";
    private String keysTitle = "";
    private String key1 = "";
    private String key2 = "";
    private String key3 = "";
    private String key4 = "";
    private String invTitle = "";
    private java.util.List<String> inventory = java.util.List.of();
    private boolean inventoryVisible;

    public SpectateHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, KEY);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder builder) {
        builder.append("HUD/AntiXraySpectate.ui");
        pushData(builder);
    }

    public void setData(boolean visible, String title, String target, String view, String score,
            String keysTitle, String key1, String key2, String key3, String key4) {
        this.visible = visible;
        this.title = nz(title);
        this.target = nz(target);
        this.view = nz(view);
        this.score = nz(score);
        this.keysTitle = nz(keysTitle);
        this.key1 = nz(key1);
        this.key2 = nz(key2);
        this.key3 = nz(key3);
        this.key4 = nz(key4);
    }

    /** The suspect's carried items, or an empty list to hide the panel. */
    public void setInventory(boolean visible, String title, java.util.List<String> lines) {
        this.inventoryVisible = visible;
        this.invTitle = nz(title);
        this.inventory = lines == null ? java.util.List.of() : lines;
    }

    /** Re-sends the current data to the client. */
    public void requestUpdate() {
        if (getPlayerRef() == null || !getPlayerRef().isValid()) {
            return;
        }
        UICommandBuilder builder = new UICommandBuilder();
        pushData(builder);
        super.update(false, builder);
    }

    /** Pushes {@code Visible: false} so the panel disappears even if the HUD is never removed. */
    public void hide() {
        this.visible = false;
        if (getPlayerRef() == null || !getPlayerRef().isValid()) {
            return;
        }
        UICommandBuilder builder = new UICommandBuilder();
        builder.set("#AxSpecRoot.Visible", false);
        super.update(false, builder);
    }

    private void pushData(@Nonnull UICommandBuilder builder) {
        builder.set("#AxSpecRoot.Visible", visible);
        builder.set("#AxSpecTitle.Text", title);
        builder.set("#AxSpecTarget.Text", target);
        builder.set("#AxSpecView.Text", view);
        builder.set("#AxSpecScore.Text", score);
        builder.set("#AxSpecKeysTitle.Text", keysTitle);
        builder.set("#AxSpecKey1.Text", key1);
        builder.set("#AxSpecKey2.Text", key2);
        builder.set("#AxSpecKey3.Text", key3);
        builder.set("#AxSpecKey4.Text", key4);
        builder.set("#AxSpecInvSection.Visible", inventoryVisible);
        builder.set("#AxSpecInvTitle.Text", invTitle);
        for (int i = 0; i < INV_LINES; i++) {
            builder.set("#AxSpecInv" + i + ".Text", i < inventory.size() ? inventory.get(i) : "");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
