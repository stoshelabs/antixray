package dev.stoshe.antixray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.stoshe.antixray.command.AntiXrayCommand;
import dev.stoshe.antixray.manager.BlockCatalog;
import dev.stoshe.antixray.manager.DetectionManager;
import dev.stoshe.antixray.manager.ObfuscationManager;
import dev.stoshe.antixray.manager.SpectateManager;
import dev.stoshe.antixray.model.AntiXrayConfig;
import dev.stoshe.antixray.system.OreBreakSystem;
import dev.stoshe.antixray.util.Console;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AntiXray — packet-level ore obfuscation (a random fake-ore field that masks real ores from X-ray),
 * a fake-ore honeypot + mining-rate detection heuristic, an admin suspect panel, and live spectate.
 * Built on the same Hytale plugin conventions as AeroWars.
 */
public class AntiXray extends JavaPlugin {

    private static final String VERSION = "1.0.0";
    private static AntiXray instance;

    private File dataDir;
    private AntiXrayConfig config;

    private dev.stoshe.antixray.manager.TranslationManager translationManager;
    private BlockCatalog blockCatalog;
    private ObfuscationManager obfuscationManager;
    private dev.stoshe.antixray.net.SendTimeObfuscator sendTimeObfuscator;
    private DetectionManager detectionManager;
    private SpectateManager spectateManager;
    private dev.stoshe.antixray.manager.SuspectInventory suspectInventory;
    private final dev.stoshe.antixray.net.PacketDiagnostics packetDiagnostics =
            new dev.stoshe.antixray.net.PacketDiagnostics();

    private final Set<UUID> probers = ConcurrentHashMap.newKeySet();

    public AntiXray(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        super.setup();
        Console.banner(196, "AntiXray v" + VERSION, "Packet-level anti-xray + honeypot detection");

        this.dataDir = getDataDirectory().toFile();
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        loadConfig();
        this.translationManager = new dev.stoshe.antixray.manager.TranslationManager(dataDir, config.General.Language);

        this.blockCatalog = new BlockCatalog(config);
        this.obfuscationManager = new ObfuscationManager(this, blockCatalog);
        this.sendTimeObfuscator = new dev.stoshe.antixray.net.SendTimeObfuscator(this, blockCatalog);
        this.obfuscationManager.setSendTime(sendTimeObfuscator);
        this.detectionManager = new DetectionManager(this);
        this.spectateManager = new SpectateManager(this);
        this.suspectInventory = new dev.stoshe.antixray.manager.SuspectInventory(spectateManager.getVault());

        registerSystems();
        registerCommands();
        registerListeners();

        Console.success("AntiXray enabled.");
    }

    @Override
    protected void start() {
        super.start();
        obfuscationManager.start();
        spectateManager.start();
        // Send-time obfuscation (opt-in via Obfuscation.SendTimeMode). The filter is always registered but is a
        // no-op unless the mode is on, so /antixray reload can flip it without a restart.
        sendTimeObfuscator.register();
        // Send-time obfuscation spike: an off-by-default outbound-packet probe (-Dantixray.sendtime.probe=true).
        packetDiagnostics.register(dataDir, this);
    }

    @Override
    protected void shutdown() {
        if (packetDiagnostics != null) {
            packetDiagnostics.unregister();
        }
        if (sendTimeObfuscator != null) {
            sendTimeObfuscator.unregister();
        }
        if (obfuscationManager != null) {
            obfuscationManager.shutdown();
        }
        if (spectateManager != null) {
            spectateManager.shutdown();
        }
        Console.info("AntiXray disabled.");
        super.shutdown();
    }

    private void registerSystems() {
        getEntityStoreRegistry().registerSystem(new OreBreakSystem(this));
    }

    private void registerCommands() {
        AntiXrayCommand command = new AntiXrayCommand(this);
        command.addAliases("ax");
        getCommandRegistry().registerCommand(command);
    }

    private void registerListeners() {
        try {
            getEventRegistry().register(PlayerDisconnectEvent.class, this::onDisconnect);
        } catch (Exception e) {
            Console.warning("Failed to register disconnect listener: " + e.getMessage());
        }
        try {
            getEventRegistry().register(
                    com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent.class,
                    this::onMouseButton);
        } catch (Exception e) {
            Console.warning("Failed to register mouse listener: " + e.getMessage());
        }
    }

    /**
     * Spectator hotbar tools. Hytale sends the server no keyboard input, so the shortcut is "select the tool
     * with the number keys, then click": this raw mouse event carries the held item, and its id picks the
     * action (see {@code SpectateManager.handleToolClick}). The click is cancelled so the admin's own body
     * never hits or places anything while attached.
     */
    private void onMouseButton(com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent event) {
        try {
            var button = event.getMouseButton();
            if (button == null
                    || button.state != com.hypixel.hytale.protocol.MouseButtonState.Pressed) {
                return; // one action per click — ignore the paired Released
            }
            PlayerRef pr = event.getPlayerRefComponent();
            if (pr == null || spectateManager == null) {
                return;
            }
            // The action comes from the TOOL in hand, not the button: pick the tool with the number keys and
            // click anywhere (even at open sky — this raw mouse event fires regardless of what is aimed at).
            var item = event.getItemInHand();
            String itemId = item == null ? null : item.getId();
            if (spectateManager.handleToolClick(pr, itemId)) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            Console.warning("Mouse shortcut handling failed: " + e.getMessage());
        }
    }

    private void onDisconnect(PlayerDisconnectEvent event) {
        PlayerRef pr = event.getPlayerRef();
        if (pr == null) {
            return;
        }
        UUID uuid = pr.getUuid();
        probers.remove(uuid);
        if (obfuscationManager != null) {
            obfuscationManager.forget(uuid);
        }
        if (spectateManager != null) {
            spectateManager.handleDisconnect(pr);
        }
    }

    // ------------------------------------------------------------------ reload / config

    public boolean reload() {
        try {
            reloadConfig();
            if (translationManager != null) {
                translationManager.reload(config.General.Language);
            }
            blockCatalog.rebuild();
            Console.success("AntiXray reloaded (config + language + block ids).");
            return true;
        } catch (Exception e) {
            Console.error("Reload failed: " + e.getMessage());
            return false;
        }
    }

    private void loadConfig() {
        File configFile = new File(dataDir, "config.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (!configFile.exists()) {
            // Prefer the documented resource copy; fall back to serialising defaults.
            if (!copyBundledConfig(configFile)) {
                this.config = AntiXrayConfig.getDefault();
                try (Writer writer = new FileWriter(configFile)) {
                    gson.toJson(config, writer);
                } catch (Exception e) {
                    Console.error("Failed to write default config: " + e.getMessage());
                }
            }
        }
        try (Reader reader = new FileReader(configFile)) {
            this.config = gson.fromJson(reader, AntiXrayConfig.class);
        } catch (Exception e) {
            Console.error("Failed to read config, using defaults: " + e.getMessage());
            this.config = AntiXrayConfig.getDefault();
        }
        if (config == null) {
            config = AntiXrayConfig.getDefault();
        }
        config.normalize();
    }

    private boolean copyBundledConfig(File target) {
        try (InputStream in = getClass().getResourceAsStream("/config.json")) {
            if (in == null) {
                return false;
            }
            Files.copy(in, target.toPath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void reloadConfig() {
        File configFile = new File(dataDir, "config.json");
        if (!configFile.exists()) {
            return;
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Reader reader = new FileReader(configFile)) {
            AntiXrayConfig fresh = gson.fromJson(reader, AntiXrayConfig.class);
            if (fresh != null) {
                config.applyFrom(fresh);
                config.normalize();
            }
        } catch (Exception e) {
            Console.error("Failed to reload config: " + e.getMessage());
        }
    }

    /** Persists the in-memory config, preserving any "//" comment keys already on disk. */
    public void saveConfig() {
        File configFile = new File(dataDir, "config.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject fresh = gson.toJsonTree(config).getAsJsonObject();
        JsonObject out = fresh;
        if (configFile.exists()) {
            try (Reader reader = new FileReader(configFile)) {
                JsonElement existing = JsonParser.parseReader(reader);
                if (existing != null && existing.isJsonObject()) {
                    JsonObject disk = existing.getAsJsonObject();
                    mergeInto(disk, fresh);
                    out = disk;
                }
            } catch (Exception e) {
                Console.warning("Could not merge config.json, rewriting it: " + e.getMessage());
            }
        }
        try (Writer writer = new FileWriter(configFile)) {
            gson.toJson(out, writer);
        } catch (Exception e) {
            Console.error("Failed to save config: " + e.getMessage());
        }
    }

    private static void mergeInto(JsonObject disk, JsonObject fresh) {
        for (Map.Entry<String, JsonElement> entry : fresh.entrySet()) {
            JsonElement diskVal = disk.get(entry.getKey());
            if (diskVal != null && diskVal.isJsonObject() && entry.getValue().isJsonObject()) {
                mergeInto(diskVal.getAsJsonObject(), entry.getValue().getAsJsonObject());
            } else {
                disk.add(entry.getKey(), entry.getValue());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Case-insensitive online-player lookup by username. */
    public PlayerRef findOnline(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (PlayerRef pr : Universe.get().getPlayers()) {
            if (pr != null && name.equalsIgnoreCase(pr.getUsername())) {
                return pr;
            }
        }
        return null;
    }

    public boolean isWorldEnabled(String worldName) {
        var enabled = config.General.EnabledWorlds;
        return enabled == null || enabled.isEmpty() || enabled.contains(worldName);
    }

    /**
     * Whether a player is exempt from obfuscation/detection. Explicit only: a username in
     * {@code General.BypassPlayers}, or the {@code antixray.bypass} permission when
     * {@code General.BypassByPermission} is enabled. Ops are NOT auto-exempt (a wildcard permission would
     * otherwise silently disable the protection for every admin).
     */
    public boolean isBypassed(PlayerRef pr) {
        if (pr == null) {
            return false;
        }
        var g = config.General;
        if (g.BypassPlayers != null) {
            String name = pr.getUsername();
            for (String b : g.BypassPlayers) {
                if (b != null && b.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return g.BypassByPermission && dev.stoshe.antixray.util.PermissionUtil.isBypassed(pr.getUuid());
    }

    public boolean isProbing(UUID uuid) {
        return probers.contains(uuid);
    }

    /** Toggles probe mode for an admin; returns the new state. */
    public boolean toggleProbe(UUID uuid) {
        if (probers.contains(uuid)) {
            probers.remove(uuid);
            return false;
        }
        probers.add(uuid);
        return true;
    }

    // ------------------------------------------------------------------ accessors

    public static AntiXray getInstance() {
        return instance;
    }

    public String getVersion() {
        String v = getClass().getPackage().getImplementationVersion();
        return v != null ? v : VERSION;
    }

    public AntiXrayConfig getConfig() {
        return config;
    }

    public dev.stoshe.antixray.manager.TranslationManager getTranslationManager() {
        return translationManager;
    }

    public BlockCatalog getBlockCatalog() {
        return blockCatalog;
    }

    public ObfuscationManager getObfuscationManager() {
        return obfuscationManager;
    }

    public DetectionManager getDetectionManager() {
        return detectionManager;
    }

    public dev.stoshe.antixray.manager.SuspectInventory getSuspectInventory() {
        return suspectInventory;
    }

    public SpectateManager getSpectateManager() {
        return spectateManager;
    }
}
