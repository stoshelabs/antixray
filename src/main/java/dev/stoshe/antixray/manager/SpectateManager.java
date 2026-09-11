package dev.stoshe.antixray.manager;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.asset.type.gamemode.GameModeType;
import com.hypixel.hytale.server.core.entity.entities.player.CameraManager;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.gamemode.GameModeTypes;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.stoshe.antixray.AntiXray;
import dev.stoshe.antixray.util.ChatUtil;
import dev.stoshe.antixray.util.Console;
import dev.stoshe.antixray.util.Inventories;
import dev.stoshe.antixray.util.Tr;

import org.joml.Vector3d;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live spectate, built on Hytale 0.6's native spectator.
 *
 * <p>The admin enters AntiXray's spectator game mode ({@value #MODE_ID}, shipped in
 * {@code Server/Entity/GameMode/} — the vanilla {@code Spectator} with the hotbar kept so the spectator tools
 * stay usable) and gets a native {@link Spectating} component pointing at the suspect. From there the server
 * does the heavy lifting itself: it hides the admin from every non-spectator (entity tracker and player list),
 * makes them fly, no-clip, invulnerable and intangible, keeps their body within 32 blocks of the target, and
 * mutes them onto the spectator voice channel.
 *
 * <p>What stays ours is what the native mode doesn't do: following a suspect into another world, cycling
 * through <em>suspects</em> rather than every player, the first/third person camera, the HUD with the live
 * detection score, and the inventory tools.
 */
public final class SpectateManager {

    /** Our spectator game mode (asset id = file name under Server/Entity/GameMode). */
    public static final String MODE_ID = "AntiXray_Spectator";
    /** Used if our asset didn't load: still hides and protects the admin, but hides the hotbar too. */
    private static final String FALLBACK_MODE_ID = "Spectator";

    private final AntiXray plugin;
    /** admin uuid -> target uuid currently being watched. */
    private final Map<UUID, UUID> watching = new ConcurrentHashMap<>();
    /** admin uuid -> true if watching in first person (through the suspect's eyes). Survives target switches. */
    private final Map<UUID, Boolean> firstPerson = new ConcurrentHashMap<>();
    /** admin uuid -> where they stood (world + transform) before spectating, restored on stop. */
    private final Map<UUID, Anchor> anchors = new ConcurrentHashMap<>();
    /** admin uuid -> their attached spectator HUD (null while not spectating). */
    private final Map<UUID, dev.stoshe.antixray.ui.SpectateHud> huds = new ConcurrentHashMap<>();
    /** admin uuid -> last read of the suspect's carried items, rendered on the HUD. */
    private final Map<UUID, java.util.List<String>> invLines = new ConcurrentHashMap<>();
    /** admin uuid -> last known username of who they watch, so we can name them after they log off. */
    private final Map<UUID, String> targetNames = new ConcurrentHashMap<>();
    /** admin uuid -> the target we last told the admin about, so re-attaches don't repeat the message. */
    private final Map<UUID, UUID> announced = new ConcurrentHashMap<>();
    /** admin uuid -> epoch ms until which a cross-world move is in flight (don't re-teleport meanwhile). */
    private final Map<UUID, Long> movingUntil = new ConcurrentHashMap<>();
    /** Admins currently inside our game mode — entered by us, so stop() knows to exit it. */
    private final Set<UUID> inMode = ConcurrentHashMap.newKeySet();
    private final InventoryVault vault = new InventoryVault();
    private ScheduledExecutorService scheduler;
    private volatile boolean fallbackWarned;

    /** Item ids of the spectator hotbar tools (assets shipped in Server/Item/Items/AntiXray). */
    public static final String TOOL_NEXT = "AntiXray_SpecNext";
    public static final String TOOL_VIEW = "AntiXray_SpecView";
    public static final String TOOL_INV = "AntiXray_SpecInv";
    public static final String TOOL_EXIT = "AntiXray_SpecExit";

    /** Where an admin was before spectating dragged their body across the map. */
    private record Anchor(UUID worldUuid, Transform transform) { }

    /** How many of the suspect's item stacks fit on the HUD panel. */
    private static final int HUD_INV_LINES = 8;
    /** How long a cross-world move is given to land before it is tried again. */
    private static final long WORLD_MOVE_GRACE_MS = 5000;

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

    /** Logs which spectator game mode will be used — run once the asset store has loaded. */
    public void logModeStatus() {
        if (GameModeType.getAssetMap().getAsset(MODE_ID) != null) {
            Console.info("Spectate: using the native spectator game mode '" + MODE_ID + "'.");
        } else {
            modeId(); // warns about the fallback
        }
    }

    /**
     * Begins (or re-aims) spectating {@code target} from {@code admin}. Returns false if the target is offline.
     * Same world: attaches right away. Different worlds: moves the admin's body into the target's world first,
     * and the follow tick attaches once it has landed.
     */
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

        // First attach only: remember where the admin stood, park their own gear and hand them the tool bar.
        // Re-sends while already spectating (view toggle, target switch) must not overwrite either.
        if (!watching.containsKey(adminUuid)) {
            var t = admin.getTransform();
            anchors.put(adminUuid, new Anchor(admin.getWorldUuid(),
                    new Transform(new Vector3d(t.getPosition()), new Rotation3f(t.getRotation()))));
            World adminWorld = Universe.get().getWorld(admin.getWorldUuid());
            if (adminWorld != null) {
                vault.stash(adminWorld, adminUuid, this::giveTools);
            }
        }
        watching.put(adminUuid, target.getUuid());
        targetNames.put(adminUuid, target.getUsername());

        if (!target.getWorldUuid().equals(admin.getWorldUuid())) {
            moveToWorldOf(admin, target);
            return true;
        }
        targetWorld.execute(() -> attach(admin, target));
        return true;
    }

    /**
     * World thread (admin and target share it): puts the admin into our spectator mode if they aren't yet,
     * points their native {@link Spectating} component at the target, and sends our camera on top of the
     * native one. Idempotent — the follow tick calls it again whenever the attachment was lost.
     */
    private void attach(PlayerRef admin, PlayerRef target) {
        UUID adminUuid = admin.getUuid();
        UUID targetUuid = target.getUuid();
        if (!targetUuid.equals(watching.get(adminUuid))) {
            return; // stopped, or switched to someone else, while this was queued
        }
        try {
            Ref<EntityStore> adminRef = admin.getReference();
            Ref<EntityStore> targetRef = target.getReference();
            if (adminRef == null || !adminRef.isValid() || targetRef == null || !targetRef.isValid()) {
                return; // mid world-change — the follow tick retries
            }
            Store<EntityStore> store = adminRef.getStore();
            if (targetRef.getStore() != store) {
                return; // not in the same world (yet) — the follow tick moves the body first
            }
            if (!inOurMode(adminRef, store)) {
                // GameModeTypes.enter refuses while the player is in ANY game-mode type (vanilla /spectate,
                // another plugin's mode) — say so rather than silently stealing it.
                String current = GameModeTypes.getCurrentTypeId(adminRef, store);
                if (current != null || !GameModeTypes.enter(adminRef, store, modeId())) {
                    admin.sendMessage(ChatUtil.error(current != null
                            ? Tr.t("msg.spectate_mode_busy", "mode", current)
                            : Tr.t("msg.spectate_failed")));
                    stop(admin);
                    return;
                }
                inMode.add(adminUuid);
            }
            // The native systems take it from here: OnSpectatingChange teleports the body onto the target and
            // sends the follow camera, FollowTarget keeps the body within 32 blocks, HideFromNonSpectators
            // keeps it invisible. Only re-put on a real change — a same-target put would reset the camera.
            Spectating current = store.getComponent(adminRef, Spectating.getComponentType());
            if (current == null || current.getTargetRef() != targetRef) {
                store.putComponent(adminRef, Spectating.getComponentType(), new Spectating(targetRef));
            }
            // RefChangeSystems run synchronously inside putComponent, so this lands after the native camera
            // and wins: it carries our first/third person choice and camera settings.
            sendCamera(admin, targetRef, store);
            updateHud(admin, target);
            if (!targetUuid.equals(announced.put(adminUuid, targetUuid))) {
                admin.sendMessage(ChatUtil.success(Tr.t("msg.spectating", "player", target.getUsername())));
            }
        } catch (Exception e) {
            Console.warning("spectate failed: " + e.getMessage());
            admin.sendMessage(ChatUtil.error(Tr.t("msg.spectate_failed")));
        }
    }

    /**
     * The follow camera. Same shape as the native one (attached to the target's entity, followed, pulled in
     * by a raycast so it never sits inside a wall) plus what the native camera doesn't offer: first person,
     * and the distance/smoothing/pitch settings from the config.
     */
    private void sendCamera(PlayerRef admin, Ref<EntityStore> targetRef, Store<EntityStore> store) {
        NetworkId nid = store.getComponent(targetRef, NetworkId.getComponentType());
        PacketHandler ph = admin.getPacketHandler();
        if (nid == null || ph == null) {
            return;
        }
        var sc = plugin.getConfig().Spectate;
        // Lerp speed is a PER-FRAME interpolation factor (vanilla's camera commands use 0.2f), not a speed in
        // blocks/s. Anything above 1 overshoots every frame and oscillates — clamped so a stale config can't.
        float lerp = Math.max(0.01f, Math.min(1.0f, sc.LerpSpeed));
        boolean fp = isFirstPerson(admin.getUuid());
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.attachedToType = AttachedToType.EntityId;
        settings.attachedToEntityId = nid.getId();
        settings.followAttachedEntity = true;
        // First person = sit in the suspect's eyes (no orbit distance); third person = follow behind, pulled
        // in by a raycast like the native spectator camera so a tunnel wall never fills the screen.
        settings.isFirstPerson = fp;
        settings.distance = fp ? 0f : sc.CameraDistance;
        settings.positionDistanceOffsetType = fp
                ? PositionDistanceOffsetType.None
                : PositionDistanceOffsetType.DistanceOffsetRaycast;
        settings.positionLerpSpeed = lerp;
        settings.rotationLerpSpeed = lerp;
        // In first person the view must follow the suspect's head exactly, so admin pitch control is off.
        settings.allowPitchControls = !fp && sc.AllowPitchControls;
        settings.eyeOffset = true;
        // positionType/rotationType default to AttachedToPlusOffset, so the offsets are read — and they are
        // null on a fresh ServerCameraSettings. Send explicit values (zero in third person; in first person
        // nudge FORWARD, otherwise the camera sits inside the suspect's head).
        settings.positionOffset = new Position(0, 0, fp ? sc.FirstPersonForward : 0);
        settings.rotationOffset = new Direction(0, 0, 0);
        // Don't let the admin's own body rotation drive the camera (feedback with the follow rotation).
        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.movementForceRotation = new Direction(0, 0, 0);
        // ClientCameraView.Custom is what makes the client honour `settings` at all (ThirdPerson ignores them).
        ph.writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
    }

    /** Detaches the admin's follow camera and returns their view to normal. */
    public void stop(PlayerRef admin) {
        stop(admin, null);
    }

    /**
     * Detaches and fully restores the admin: game mode, camera, body position/world, HUD and inventory.
     * {@code reason} (nullable) replaces the plain "stopped" line when the spectate ended on its own — the
     * suspect logged off — so the admin is never left wondering why the view dropped.
     */
    public void stop(PlayerRef admin, String reason) {
        if (admin == null) {
            return;
        }
        UUID adminUuid = admin.getUuid();
        if (watching.remove(adminUuid) == null) {
            return;
        }
        boolean wasInMode = inMode.remove(adminUuid);
        announced.remove(adminUuid);
        movingUntil.remove(adminUuid);
        removeHud(admin);
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
                if (adminEntity == null || !adminEntity.isValid()) {
                    return;
                }
                Store<EntityStore> store = adminEntity.getStore();
                // Exiting the mode undoes everything it applied — flight, no-clip, invulnerability, the hidden
                // state, the native HUD and voice channel — and removes Spectating, which resets the camera.
                if (wasInMode && inOurMode(adminEntity, store)) {
                    GameModeTypes.exit(adminEntity, store);
                }
                store.removeComponentIfExists(adminEntity, Spectating.getComponentType());
                CameraManager cam = store.getComponent(adminEntity, CameraManager.getComponentType());
                if (cam != null) {
                    cam.resetCamera(admin);
                }
                // Tools out, the admin's own gear back in.
                vault.restore(adminWorld, adminUuid);
                // Put the admin back where spectate picked them up (their own world included). This replaces
                // the "camera exit position" teleport the native removal queued a moment ago.
                if (anchor != null) {
                    World home = Universe.get().getWorld(anchor.worldUuid());
                    if (home != null) {
                        store.addComponent(adminEntity, Teleport.getComponentType(),
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
        inMode.remove(uuid);
        announced.remove(uuid);
        movingUntil.remove(uuid);
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
     * A player finished loading into a world. Game modes persist with the player, so an admin who
     * disconnected (or whose server stopped) mid-spectate comes back still in our spectator mode — flying,
     * invisible, with no target. Anyone in that state who isn't actually spectating is let out of it.
     */
    public void handleReady(Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        world.execute(() -> {
            try {
                if (!ref.isValid()) {
                    return;
                }
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                if (pr == null || watching.containsKey(pr.getUuid())
                        || !MODE_ID.equals(GameModeTypes.getCurrentTypeId(ref, store))) {
                    return;
                }
                GameModeTypes.exit(ref, store);
                CameraManager cam = store.getComponent(ref, CameraManager.getComponentType());
                if (cam != null) {
                    cam.resetCamera(pr);
                }
                pr.sendMessage(ChatUtil.info(Tr.t("msg.spectate_recovered")));
            } catch (Exception e) {
                Console.warning("spectate recovery failed: " + e.getMessage());
            }
        });
    }

    /**
     * Spectator hotbar tools. Hytale sends the server no keyboard input, so the shortcut is "pick the tool
     * (number keys) and click": every click is delivered as {@code PlayerMouseButtonEvent} with the held item,
     * and the item id decides the action. Our game mode overrides every interaction, so the click does
     * nothing else; the event still fires because InteractionModule dispatches it before any interaction runs.
     *
     * @return true if the click was consumed (the caller cancels it).
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

    /** Fills the admin's emptied hotbar with the four spectator tools. Runs on the world thread. */
    private void giveTools(ItemContainer hotbar) {
        hotbar.setItemStackForSlot((short) 0, new ItemStack(TOOL_NEXT, 1));
        hotbar.setItemStackForSlot((short) 1, new ItemStack(TOOL_VIEW, 1));
        hotbar.setItemStackForSlot((short) 2, new ItemStack(TOOL_INV, 1));
        hotbar.setItemStackForSlot((short) 8, new ItemStack(TOOL_EXIT, 1));
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
                Store<EntityStore> store = ref.getStore();
                java.util.List<String> lines = new java.util.ArrayList<>();
                collectItems(Inventories.section(store, ref, Inventories.HOTBAR), lines);
                collectItems(Inventories.section(store, ref, Inventories.STORAGE), lines);
                collectItems(Inventories.section(store, ref, Inventories.BACKPACK), lines);
                invLines.put(admin.getUuid(), lines);
                updateHud(admin, target);
            } catch (Exception e) {
                Console.warning("suspect inventory read failed: " + e.getMessage());
            }
        });
    }

    private void collectItems(ItemContainer c, java.util.List<String> out) {
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
     * Shows (or refreshes) the corner HUD naming the suspect, the current view, their detection score and the
     * hotbar shortcuts. Sits on the right; the native spectator's own "SPECTATING" label is top-left.
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
        java.util.List<String> items = invLines.getOrDefault(adminUuid, java.util.List.of());
        hud.setInventory(true, Tr.t("hud.inv_title", "player", target.getUsername()),
                items.isEmpty() ? java.util.List.of(Tr.t("hud.inv_empty")) : items);
        dev.stoshe.antixray.ui.SpectateHud attached = hud;
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = admin.getReference();
                if (ref == null || !ref.isValid()) {
                    return;
                }
                var player = ref.getStore().getComponent(ref,
                        com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
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
                var player = ref.getStore().getComponent(ref,
                        com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
                if (player != null && player.getHudManager() != null) {
                    player.getHudManager().removeCustomHud(admin, dev.stoshe.antixray.ui.SpectateHud.KEY);
                }
            } catch (Exception e) {
                Console.warning("spectate HUD removal failed: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ game mode

    /** Our mode if its asset loaded, otherwise vanilla Spectator (warned once). */
    private String modeId() {
        if (GameModeType.getAssetMap().getAsset(MODE_ID) != null) {
            return MODE_ID;
        }
        if (!fallbackWarned) {
            fallbackWarned = true;
            Console.warning("Spectate: game mode '" + MODE_ID + "' is not registered (asset pack not loaded?) — "
                    + "falling back to vanilla '" + FALLBACK_MODE_ID + "', which hides the hotbar tools. "
                    + "Use /antixray → Tools to stop spectating.");
        }
        return FALLBACK_MODE_ID;
    }

    private boolean inOurMode(Ref<EntityStore> ref, Store<EntityStore> store) {
        String id = GameModeTypes.getCurrentTypeId(ref, store);
        return id != null && id.equals(modeId());
    }

    // ------------------------------------------------------------------ follow loop

    /**
     * Starts the once-a-second upkeep. The native spectator follows within a world on its own; this covers
     * what it doesn't: moving the admin into the suspect's world when they change worlds (the native
     * attachment is simply dropped then, since entity refs are per-world), re-attaching after anything else
     * the native side detached on (the suspect dying and respawning), and refreshing the HUD.
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
                    if (!target.getWorldUuid().equals(admin.getWorldUuid())) {
                        moveToWorldOf(admin, target);
                        continue;
                    }
                    World world = Universe.get().getWorld(admin.getWorldUuid());
                    if (world != null) {
                        world.execute(() -> keepAttached(admin, target));
                    }
                    readSuspectInventory(admin, target); // also refreshes the HUD when the read lands
                } catch (Exception perAdmin) {
                    // transient state during (dis)connect / world change — retry next second
                }
            }
        } catch (Exception ex) {
            Console.warning("Spectate follow tick failed: " + ex);
        }
    }

    /** World thread: re-attaches if the native side dropped the attachment (or the mode) since last tick. */
    private void keepAttached(PlayerRef admin, PlayerRef target) {
        try {
            Ref<EntityStore> adminRef = admin.getReference();
            Ref<EntityStore> targetRef = target.getReference();
            if (adminRef == null || !adminRef.isValid() || targetRef == null || !targetRef.isValid()
                    || targetRef.getStore() != adminRef.getStore()) {
                return;
            }
            Store<EntityStore> store = adminRef.getStore();
            // A dead suspect makes FollowTarget detach every tick; wait for the respawn instead of fighting it.
            if (store.getArchetype(targetRef).contains(DeathComponent.getComponentType())) {
                return;
            }
            Spectating sp = store.getComponent(adminRef, Spectating.getComponentType());
            if (!inOurMode(adminRef, store) || sp == null || sp.getTargetRef() != targetRef) {
                movingUntil.remove(admin.getUuid());
                attach(admin, target);
            }
        } catch (Exception e) {
            // transient — retry next second
        }
    }

    /**
     * Moves the admin's body into the target's world, onto the target. Read the suspect's exact transform on
     * ITS world thread, apply the teleport on the admin's — the same two-hop the vanilla teleport command does.
     * The follow tick attaches once both are in the same world.
     */
    private void moveToWorldOf(PlayerRef admin, PlayerRef target) {
        UUID adminUuid = admin.getUuid();
        long now = System.currentTimeMillis();
        if (movingUntil.getOrDefault(adminUuid, 0L) > now) {
            return; // a move is already in flight
        }
        movingUntil.put(adminUuid, now + WORLD_MOVE_GRACE_MS);
        World targetWorld = Universe.get().getWorld(target.getWorldUuid());
        World adminWorld = Universe.get().getWorld(admin.getWorldUuid());
        if (targetWorld == null || adminWorld == null) {
            return;
        }
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
                Transform where = new Transform(new Vector3d(tc.getPosition()), new Rotation3f(hr.getRotation()));
                adminWorld.execute(() -> {
                    try {
                        Ref<EntityStore> adminEntity = admin.getReference();
                        if (adminEntity == null || !adminEntity.isValid()) {
                            return;
                        }
                        adminEntity.getStore().addComponent(adminEntity, Teleport.getComponentType(),
                                Teleport.createForPlayer(targetWorld, where));
                    } catch (Exception inner) {
                        Console.warning("spectate world move failed: " + inner);
                    }
                });
            } catch (Exception outer) {
                Console.warning("spectate world move read failed: " + outer);
            }
        });
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
