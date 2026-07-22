package dev.stoshe.antixray.manager;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.CameraManager;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.stoshe.antixray.AntiXray;
import dev.stoshe.antixray.util.ChatUtil;
import dev.stoshe.antixray.util.Console;
import dev.stoshe.antixray.util.Tr;

import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live spectate: attaches an admin's client camera to a suspect's entity via {@link SetServerCamera}
 * (a real server-driven follow camera, not a teleport), and detaches with
 * {@link CameraManager#resetCamera(PlayerRef)}.
 */
public final class SpectateManager {

    private final AntiXray plugin;
    /** admin uuid -> target uuid currently being watched. */
    private final Map<UUID, UUID> watching = new ConcurrentHashMap<>();
    /** admin uuid -> true if watching in first person (through the suspect's eyes). Survives target switches. */
    private final Map<UUID, Boolean> firstPerson = new ConcurrentHashMap<>();
    /** admin uuid -> where they stood (world + transform) before spectating, restored on stop. */
    private final Map<UUID, Anchor> anchors = new ConcurrentHashMap<>();
    /** admin uuid -> their attached spectator HUD (null while not spectating). */
    private final Map<UUID, dev.stoshe.antixray.ui.SpectateHud> huds = new ConcurrentHashMap<>();
    /** admin uuid -> true while the suspect's inventory panel is shown on the HUD. */
    private final Map<UUID, Boolean> invVisible = new ConcurrentHashMap<>();
    /** admin uuid -> last read of the suspect's carried items, rendered on the HUD. */
    private final Map<UUID, java.util.List<String>> invLines = new ConcurrentHashMap<>();
    /** admin uuid -> last known username of who they watch, so we can name them after they log off. */
    private final Map<UUID, String> targetNames = new ConcurrentHashMap<>();
    private final InventoryVault vault = new InventoryVault();
    private ScheduledExecutorService scheduler;

    /** Item ids of the spectator hotbar tools (assets shipped in Server/Item/Items/AntiXray). */
    public static final String TOOL_NEXT = "AntiXray_SpecNext";
    public static final String TOOL_VIEW = "AntiXray_SpecView";
    public static final String TOOL_INV = "AntiXray_SpecInv";
    public static final String TOOL_EXIT = "AntiXray_SpecExit";

    /** Where an admin was before the follow camera dragged their body across the map. */
    private record Anchor(UUID worldUuid, Transform transform) { }

    /** Blocks of drift tolerated before the admin's body is teleported along behind the suspect. */
    private static final double FOLLOW_DISTANCE = 12.0;
    /** How many of the suspect's item stacks fit on the HUD panel. */
    private static final int HUD_INV_LINES = 8;

    public SpectateManager(AntiXray plugin) {
        this.plugin = plugin;
    }

    /** The vault holding admins' real inventories while they hold the spectator tool bar. */
    public InventoryVault getVault() {
        return vault;
    }

    /** True if this admin's spectate camera is currently in first person. */
    public boolean isFirstPerson(UUID adminUuid) {
        return firstPerson.getOrDefault(adminUuid, plugin.getConfig().Spectate.FirstPerson);
    }

    /**
     * Flips the admin's spectate camera between first and third person and re-sends it, keeping the same
     * target. Returns false if the admin isn't spectating anyone (nothing to re-aim).
     */
    public boolean toggleView(PlayerRef admin) {
        if (admin == null) {
            return false;
        }
        UUID adminUuid = admin.getUuid();
        UUID targetUuid = watching.get(adminUuid);
        PlayerRef target = targetUuid == null ? null : Universe.get().getPlayer(targetUuid);
        if (target == null) {
            return false;
        }
        firstPerson.put(adminUuid, !isFirstPerson(adminUuid));
        return spectate(admin, target);
    }

    public boolean isSpectating(UUID adminUuid) {
        return watching.containsKey(adminUuid);
    }

    /** The uuid of the player the admin is currently watching, or null. */
    public UUID targetOf(UUID adminUuid) {
        return watching.get(adminUuid);
    }

    /** Begins following {@code target} from {@code admin}'s camera. Returns false if the target is offline. */
    public boolean spectate(PlayerRef admin, PlayerRef target) {
        if (admin == null || target == null) {
            return false;
        }
        if (admin.getUuid().equals(target.getUuid())) {
            return false;
        }
        World targetWorld = Universe.get().getWorld(target.getWorldUuid());
        if (targetWorld == null) {
            return false;
        }
        UUID adminUuid = admin.getUuid();
        UUID targetUuid = target.getUuid();

        // Remember where the admin was standing the FIRST time they attach (re-sends while already spectating —
        // view toggle, post-teleport re-attach — must not overwrite it with a follow position).
        if (!watching.containsKey(adminUuid)) {
            var t = admin.getTransform();
            anchors.put(adminUuid, new Anchor(admin.getWorldUuid(),
                    new Transform(new Vector3d(t.getPosition()), new Rotation3f(t.getRotation()))));
        }
        hideFromEveryone(adminUuid);
        // First attach only: park the admin's own gear and hand them the spectator tool bar. stash() is a
        // no-op while a snapshot exists, so switching suspects can never overwrite the real inventory.
        World adminWorldNow = Universe.get().getWorld(admin.getWorldUuid());
        if (adminWorldNow != null && !watching.containsKey(adminUuid)) {
            vault.stash(adminWorldNow, adminUuid, this::giveTools);
        }

        targetWorld.execute(() -> {
            try {
                Ref<EntityStore> targetEntity = target.getReference();
                if (targetEntity == null) {
                    admin.sendMessage(ChatUtil.error(Tr.t("msg.camera_missing")));
                    return;
                }
                NetworkId nid = targetEntity.getStore().getComponent(targetEntity, NetworkId.getComponentType());
                if (nid == null) {
                    admin.sendMessage(ChatUtil.error(Tr.t("msg.networkid_missing")));
                    return;
                }
                PacketHandler ph = admin.getPacketHandler();
                if (ph == null) {
                    return;
                }
                var sc = plugin.getConfig().Spectate;
                // Lerp speed is a PER-FRAME interpolation factor (vanilla's camera commands use 0.2f), not a
                // speed in blocks/s. Anything above 1 overshoots the target every frame and oscillates — that
                // is what made the camera spin and flip forever. Clamped so a stale config can't reintroduce it.
                float lerp = Math.max(0.01f, Math.min(1.0f, sc.LerpSpeed));
                boolean fp = isFirstPerson(adminUuid);
                ServerCameraSettings settings = new ServerCameraSettings();
                settings.attachedToType = AttachedToType.EntityId;
                settings.attachedToEntityId = nid.getId();
                // First person = sit in the suspect's eyes (no orbit distance); third person = follow behind.
                settings.isFirstPerson = fp;
                settings.distance = fp ? 0f : sc.CameraDistance;
                settings.positionDistanceOffsetType = fp
                        ? com.hypixel.hytale.protocol.PositionDistanceOffsetType.None
                        : com.hypixel.hytale.protocol.PositionDistanceOffsetType.DistanceOffset;
                settings.positionLerpSpeed = lerp;
                settings.rotationLerpSpeed = lerp;
                // In first person the view must follow the suspect's head exactly, so admin pitch control is off.
                settings.allowPitchControls = !fp && sc.AllowPitchControls;
                settings.eyeOffset = true;
                // positionType/rotationType default to AttachedToPlusOffset, so the offsets are read — and they
                // are null on a fresh ServerCameraSettings. Send explicit values (zero in third person; in first
                // person nudge FORWARD, otherwise the camera sits inside the suspect's head).
                settings.positionOffset = new Position(0, 0, fp ? sc.FirstPersonForward : 0);
                settings.rotationOffset = new Direction(0, 0, 0);
                // Don't let the admin's own body rotation drive the camera (feedback with the follow rotation).
                settings.movementForceRotationType = MovementForceRotationType.Custom;
                settings.movementForceRotation = new Direction(0, 0, 0);

                // ClientCameraView.Custom is what makes the client actually honour `settings` (and therefore
                // attachedToEntityId). With ThirdPerson it ignores them and just keeps the admin's own camera —
                // "nothing happens". Both vanilla camera commands (PlayerCameraTopdownCommand, CameraDemo) send
                // Custom + writeNoCache; this mirrors them.
                ph.writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
                // Only announce a NEW attachment. The follow loop re-sends this packet after every teleport, and
                // the view toggle re-sends it too — repeating the message each time was pure spam.
                UUID previous = watching.put(adminUuid, targetUuid);
                targetNames.put(adminUuid, target.getUsername());
                updateHud(admin, target);
                if (!targetUuid.equals(previous)) {
                    admin.sendMessage(ChatUtil.success(Tr.t("msg.spectating", "player", target.getUsername())));
                }
            } catch (Exception e) {
                Console.warning("spectate failed: " + e.getMessage());
                admin.sendMessage(ChatUtil.error(Tr.t("msg.spectate_failed")));
            }
        });
        return true;
    }

    /** Detaches the admin's follow camera and returns their view to normal. */
    public void stop(PlayerRef admin) {
        stop(admin, null);
    }

    /**
     * Detaches and fully restores the admin: camera, body position/world, visibility, HUD and inventory.
     * {@code reason} (nullable) replaces the plain "stopped" line when the spectate ended on its own — the
     * suspect logged off, changed nothing else — so the admin is never left wondering why the view dropped.
     */
    public void stop(PlayerRef admin, String reason) {
        if (admin == null) {
            return;
        }
        UUID adminUuid = admin.getUuid();
        if (watching.remove(adminUuid) == null) {
            return;
        }
        showToEveryone(adminUuid);
        removeHud(admin);
        invVisible.remove(adminUuid);
        invLines.remove(adminUuid);
        targetNames.remove(adminUuid);
        Anchor anchor = anchors.remove(adminUuid);
        World adminWorld = Universe.get().getWorld(admin.getWorldUuid());
        if (adminWorld == null) {
            return;
        }
        adminWorld.execute(() -> {
            try {
                Ref<EntityStore> adminEntity = admin.getReference();
                if (adminEntity == null) {
                    return;
                }
                CameraManager cam = adminEntity.getStore().getComponent(adminEntity, CameraManager.getComponentType());
                if (cam != null) {
                    cam.resetCamera(admin);
                }
                adminEntity.getStore().removeComponentIfExists(adminEntity, Invulnerable.getComponentType());
                // Tools out, the admin's own gear back in.
                vault.restore(adminWorld, adminUuid);
                // Put the admin back where the follow camera picked them up (their own world included).
                if (anchor != null) {
                    World home = Universe.get().getWorld(anchor.worldUuid());
                    if (home != null) {
                        adminEntity.getStore().addComponent(adminEntity, Teleport.getComponentType(),
                                Teleport.createForPlayer(home, anchor.transform()));
                    }
                }
                admin.sendMessage(reason != null ? ChatUtil.warning(reason)
                        : ChatUtil.info(Tr.t("msg.spectate_stopped")));
            } catch (Exception e) {
                Console.warning("stop spectate failed: " + e.getMessage());
            }
        });
    }

    /** Called when any player disconnects: stop admins watching them, and forget the admin's own session. */
    public void handleDisconnect(PlayerRef pr) {
        if (pr == null) {
            return;
        }
        UUID uuid = pr.getUuid();
        // If the leaver was spectating, run the full stop first: it un-hides them for everyone (a viewer's
        // hidden list outlives the session, so skipping this would leave them invisible after relogging) and
        // teleports their body back to where they started instead of saving it next to the suspect.
        // Order matters: restoreOnDisconnect uses the event's still-live PlayerRef and CONSUMES the snapshot.
        // stop() would consume it first via the normal restore path, which resolves the player through
        // Universe (already null at this point) — the admin would keep the spectator tools and lose their gear.
        vault.restoreOnDisconnect(pr);
        if (watching.containsKey(uuid)) {
            stop(pr);
        }
        watching.remove(uuid);
        firstPerson.remove(uuid);
        anchors.remove(uuid);
        showToEveryone(uuid);
        for (Map.Entry<UUID, UUID> e : watching.entrySet()) {
            if (uuid.equals(e.getValue())) {
                PlayerRef admin = Universe.get().getPlayer(e.getKey());
                if (admin != null) {
                    stop(admin, Tr.t("msg.spectate_target_left", "player", pr.getUsername()));
                } else {
                    watching.remove(e.getKey());
                    anchors.remove(e.getKey());
                    targetNames.remove(e.getKey());
                }
            }
        }
    }

    /**
     * Spectator hotbar tools. Hytale sends the server no keyboard input, so the shortcut is "pick the tool
     * (number keys) and click": every click is delivered as {@code PlayerMouseButtonEvent} with the held item,
     * and the item id decides the action. Same mechanism aerowars uses for its spectator hotbar.
     *
     * @return true if the click was consumed (the caller cancels it so the admin's body never hits anything).
     */
    public boolean handleToolClick(PlayerRef admin, String itemId) {
        if (admin == null || itemId == null || !isSpectating(admin.getUuid())) {
            return false;
        }
        switch (itemId) {
            case TOOL_EXIT -> stop(admin);
            case TOOL_VIEW -> toggleView(admin);
            case TOOL_NEXT -> cycleTarget(admin);
            case TOOL_INV -> openInventoryPage(admin);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Opens the interactive inventory page for whoever the admin is watching. */
    private void openInventoryPage(PlayerRef admin) {
        PlayerRef target = Universe.get().getPlayer(watching.get(admin.getUuid()));
        if (target != null) {
            dev.stoshe.antixray.ui.SuspectInventoryPage.open(plugin, admin, target);
        }
    }

    /** Shows/hides the suspect's carried items on the HUD. */
    public void toggleInventoryPanel(PlayerRef admin) {
        UUID adminUuid = admin.getUuid();
        boolean now = !invVisible.getOrDefault(adminUuid, false);
        invVisible.put(adminUuid, now);
        if (!now) {
            invLines.remove(adminUuid);
        }
        PlayerRef target = Universe.get().getPlayer(watching.get(adminUuid));
        if (target != null) {
            updateHud(admin, target);
        }
    }

    /** Fills the admin's emptied hotbar with the four spectator tools. Runs on the world thread. */
    private void giveTools(com.hypixel.hytale.server.core.inventory.Inventory inv) {
        var hotbar = inv.getHotbar();
        if (hotbar == null) {
            return;
        }
        hotbar.setItemStackForSlot((short) 0, new com.hypixel.hytale.server.core.inventory.ItemStack(TOOL_NEXT, 1));
        hotbar.setItemStackForSlot((short) 1, new com.hypixel.hytale.server.core.inventory.ItemStack(TOOL_VIEW, 1));
        hotbar.setItemStackForSlot((short) 2, new com.hypixel.hytale.server.core.inventory.ItemStack(TOOL_INV, 1));
        hotbar.setItemStackForSlot((short) 8, new com.hypixel.hytale.server.core.inventory.ItemStack(TOOL_EXIT, 1));
    }

    /**
     * Reads what the suspect is carrying (hotbar first, then storage) into the HUD lines. Runs on the
     * suspect's world thread; the HUD is refreshed once the read lands.
     */
    private void readSuspectInventory(PlayerRef admin, PlayerRef target) {
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
                java.util.List<String> lines = new java.util.ArrayList<>();
                collectItems(player.getInventory().getHotbar(), lines);
                collectItems(player.getInventory().getStorage(), lines);
                collectItems(player.getInventory().getBackpack(), lines);
                invLines.put(admin.getUuid(), lines);
                updateHud(admin, target);
            } catch (Exception e) {
                Console.warning("suspect inventory read failed: " + e.getMessage());
            }
        });
    }

    private void collectItems(com.hypixel.hytale.server.core.inventory.container.ItemContainer c,
            java.util.List<String> out) {
        if (c == null) {
            return;
        }
        c.forEach((slot, stack) -> {
            if (stack != null && !stack.isEmpty() && out.size() < HUD_INV_LINES) {
                out.add(stack.getQuantity() + "x " + stack.getItemId());
            }
        });
    }

    /** Moves the camera to the next ONLINE suspect after the current one (wraps around). */
    private void cycleTarget(PlayerRef admin) {
        UUID current = watching.get(admin.getUuid());
        java.util.List<PlayerRef> candidates = new java.util.ArrayList<>();
        for (var snap : plugin.getDetectionManager().suspects()) {
            PlayerRef pr = Universe.get().getPlayer(snap.uuid());
            if (pr != null && !pr.getUuid().equals(admin.getUuid())) {
                candidates.add(pr);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        int idx = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).getUuid().equals(current)) {
                idx = (i + 1) % candidates.size();
                break;
            }
        }
        PlayerRef next = candidates.get(idx);
        if (!next.getUuid().equals(current)) {
            spectate(admin, next);
        }
    }

    // ------------------------------------------------------------------ spectator HUD

    /**
     * Shows (or refreshes) the corner HUD naming the suspect, the current view and the mouse shortcuts.
     * There is no way for a server plugin to bind a keyboard key — the client sends no key events — so the
     * shortcuts are mouse buttons, delivered as {@code PlayerMouseButtonEvent} (the same hook aerowars uses
     * for its spectator tools).
     */
    private void updateHud(PlayerRef admin, PlayerRef target) {
        World world = Universe.get().getWorld(admin.getWorldUuid());
        if (world == null) {
            return;
        }
        UUID adminUuid = admin.getUuid();
        boolean fp = isFirstPerson(adminUuid);
        var snap = plugin.getDetectionManager().get(target.getUuid());
        String score = snap == null ? Tr.t("hud.no_score")
                : Tr.t("hud.score", "score", String.format("%.0f", snap.score()),
                        "ores", snap.oresInWindow(), "honeypots", snap.honeypotHits());
        dev.stoshe.antixray.ui.SpectateHud hud = huds.get(adminUuid);
        boolean fresh = hud == null;
        if (fresh) {
            hud = new dev.stoshe.antixray.ui.SpectateHud(admin);
            huds.put(adminUuid, hud);
        }
        hud.setData(true, Tr.t("hud.title"), Tr.t("hud.target", "player", target.getUsername()),
                Tr.t(fp ? "hud.view_first" : "hud.view_third"), score,
                Tr.t("hud.keys"), Tr.t("hud.key_next"), Tr.t("hud.key_view"), Tr.t("hud.key_inv"),
                Tr.t("hud.key_stop"));
        boolean showInv = true; // the suspect's items are always on the HUD; the tool opens the editable page
        java.util.List<String> items = invLines.getOrDefault(adminUuid, java.util.List.of());
        hud.setInventory(showInv, Tr.t("hud.inv_title", "player", target.getUsername()),
                showInv && items.isEmpty() ? java.util.List.of(Tr.t("hud.inv_empty")) : items);
        dev.stoshe.antixray.ui.SpectateHud attached = hud;
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = admin.getReference();
                if (ref == null || !ref.isValid()) {
                    return;
                }
                Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                if (player == null || player.getHudManager() == null) {
                    return;
                }
                if (fresh) {
                    player.getHudManager().addCustomHud(admin, attached);
                    attached.show();
                } else {
                    attached.requestUpdate();
                }
            } catch (Exception e) {
                Console.warning("spectate HUD update failed: " + e.getMessage());
            }
        });
    }

    /** Removes the spectator HUD (best-effort; also pushes Visible:false in case removal is a no-op). */
    private void removeHud(PlayerRef admin) {
        dev.stoshe.antixray.ui.SpectateHud hud = huds.remove(admin.getUuid());
        if (hud == null) {
            return;
        }
        hud.hide();
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
                if (player != null && player.getHudManager() != null) {
                    player.getHudManager().removeCustomHud(admin, dev.stoshe.antixray.ui.SpectateHud.KEY);
                }
            } catch (Exception e) {
                Console.warning("spectate HUD removal failed: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ visibility

    /**
     * Hides the spectating admin from every other player's client, per-viewer via each viewer's
     * {@link com.hypixel.hytale.server.core.entity.entities.player.HiddenPlayersManager} (the same mechanism
     * aerowars uses for its spectators). Needed because the follow loop physically teleports the admin's body
     * next to the suspect — without this, the suspect literally sees an admin materialise behind them.
     * Idempotent, and re-applied every follow tick so players who log in mid-spectate are covered too.
     */
    private void hideFromEveryone(UUID adminUuid) {
        try {
            for (PlayerRef other : Universe.get().getPlayers()) {
                if (other == null || other.getUuid() == null || other.getUuid().equals(adminUuid)) {
                    continue;
                }
                var hidden = other.getHiddenPlayersManager();
                if (hidden != null && !hidden.isPlayerHidden(adminUuid)) {
                    hidden.hidePlayer(adminUuid);
                }
            }
        } catch (Exception e) {
            Console.warning("spectate hide failed: " + e.getMessage());
        }
    }

    /** Undoes {@link #hideFromEveryone} — everyone can see the admin again. */
    private void showToEveryone(UUID adminUuid) {
        try {
            for (PlayerRef other : Universe.get().getPlayers()) {
                if (other == null || other.getUuid() == null || other.getUuid().equals(adminUuid)) {
                    continue;
                }
                var hidden = other.getHiddenPlayersManager();
                if (hidden != null) {
                    hidden.showPlayer(adminUuid);
                }
            }
        } catch (Exception e) {
            Console.warning("spectate unhide failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ follow loop

    /**
     * Starts the follow loop. The camera packet only attaches the <em>view</em> to the suspect's entity — the
     * admin's own body stays where it was, so as soon as the suspect walks out of the admin's chunk-view radius
     * the client has neither the suspect's entity nor those chunks and the screen goes empty (and a world change
     * loses the target entirely, since network ids are per-world). This ticks the admin's body along behind the
     * suspect with the same {@code Teleport} component the vanilla /tp command uses, re-sending the camera
     * whenever we actually moved them.
     */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AntiXray-Spectate");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::followTick, 1, 1, TimeUnit.SECONDS);
    }

    private void followTick() {
        try {
            for (Map.Entry<UUID, UUID> e : watching.entrySet()) {
                try {
                    PlayerRef admin = Universe.get().getPlayer(e.getKey());
                    PlayerRef target = Universe.get().getPlayer(e.getValue());
                    if (admin == null) {
                        watching.remove(e.getKey());
                        continue;
                    }
                    if (target == null) {
                        // Suspect logged off (or left the universe) while being watched — hand the admin their
                        // own view, body and inventory back, and say why.
                        stop(admin, Tr.t("msg.spectate_target_left",
                                "player", targetNames.getOrDefault(e.getKey(), "?")));
                        continue;
                    }
                    hideFromEveryone(admin.getUuid()); // covers players who joined mid-spectate
                    follow(admin, target);
                    readSuspectInventory(admin, target); // also refreshes the HUD when the read lands
                } catch (Exception perAdmin) {
                    // transient state during (dis)connect / world change — retry next second
                }
            }
        } catch (Exception ex) {
            Console.warning("Spectate follow tick failed: " + ex);
        }
    }

    /** Teleports the admin to the suspect when they drift too far apart or end up in different worlds. */
    private void follow(PlayerRef admin, PlayerRef target) {
        World targetWorld = Universe.get().getWorld(target.getWorldUuid());
        if (targetWorld == null) {
            return;
        }
        boolean worldChanged = !target.getWorldUuid().equals(admin.getWorldUuid());
        var ap = admin.getTransform().getPosition();
        var tp = target.getTransform().getPosition();
        // The body is parked BELOW the suspect (see followY), so measure the drift horizontally — a 3D distance
        // would always exceed the threshold and re-teleport every single tick.
        double dist = Math.sqrt(Math.pow(ap.x - tp.x, 2) + Math.pow(ap.z - tp.z, 2));
        if (!worldChanged && dist <= FOLLOW_DISTANCE) {
            return;
        }
        // Read the suspect's exact transform on ITS world thread, then apply the teleport on the admin's —
        // the same two-hop the vanilla teleport command does.
        targetWorld.execute(() -> {
            try {
                Ref<EntityStore> targetEntity = target.getReference();
                if (targetEntity == null || !targetEntity.isValid()) {
                    return;
                }
                Store<EntityStore> ts = targetEntity.getStore();
                TransformComponent tc = ts.getComponent(targetEntity, TransformComponent.getComponentType());
                HeadRotation hr = ts.getComponent(targetEntity, HeadRotation.getComponentType());
                if (tc == null || hr == null) {
                    return;
                }
                var tpos = tc.getPosition();
                Transform where = new Transform(new Vector3d(tpos.x(), followY(tpos.y()), tpos.z()),
                        new Rotation3f(hr.getRotation()));
                World adminWorld = Universe.get().getWorld(admin.getWorldUuid());
                if (adminWorld == null) {
                    return;
                }
                adminWorld.execute(() -> {
                    try {
                        Ref<EntityStore> adminEntity = admin.getReference();
                        if (adminEntity == null || !adminEntity.isValid()) {
                            return;
                        }
                        Store<EntityStore> as = adminEntity.getStore();
                        // Parked underground/mid-air and dragged around the map — never let that hurt the admin.
                        if (as.getComponent(adminEntity, Invulnerable.getComponentType()) == null) {
                            as.addComponent(adminEntity, Invulnerable.getComponentType());
                        }
                        as.addComponent(adminEntity, Teleport.getComponentType(),
                                Teleport.createForPlayer(targetWorld, where));
                        // The teleport (especially a world change) drops the client's camera attachment, and a
                        // cross-world move re-assigns network ids — so re-attach once the move has settled.
                        if (scheduler != null) {
                            scheduler.schedule(() -> spectate(admin, target),
                                    worldChanged ? 1500 : 250, TimeUnit.MILLISECONDS);
                        }
                    } catch (Exception inner) {
                        Console.warning("spectate follow teleport failed: " + inner);
                    }
                });
            } catch (Exception outer) {
                Console.warning("spectate follow read failed: " + outer);
            }
        });
    }

    /**
     * Y for the admin's parked body: {@code FollowYOffset} blocks under the suspect so the admin never has his
     * own character in shot (chunks stream per column, so the vertical offset costs nothing), clamped inside
     * the world.
     */
    private double followY(double targetY) {
        double y = targetY + plugin.getConfig().Spectate.FollowYOffset;
        return Math.max(ChunkUtil.MIN_Y + 2, y);
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (UUID adminUuid : watching.keySet()) {
            PlayerRef admin = Universe.get().getPlayer(adminUuid);
            if (admin != null) {
                stop(admin);
            }
        }
        watching.clear();
    }
}
