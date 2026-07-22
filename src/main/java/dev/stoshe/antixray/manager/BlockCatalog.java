package dev.stoshe.antixray.manager;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import dev.stoshe.antixray.model.AntiXrayConfig;
import dev.stoshe.antixray.model.BlockKey;
import dev.stoshe.antixray.util.Console;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ObjIntConsumer;

/**
 * Resolves the configured block <em>names</em> into the numeric runtime block ids the packet layer
 * ({@link com.hypixel.hytale.protocol.packets.world.ServerSetBlock}) and {@code World.getBlock(...)}
 * speak in, and classifies blocks (air / ore). Rebuilt on {@code /antixray reload}.
 */
public final class BlockCatalog {

    private final AntiXrayConfig config;

    private volatile int emptyId = BlockType.EMPTY_ID;
    private int fallbackHideId = BlockType.EMPTY_ID;
    private volatile int hideOreId = BlockType.EMPTY_ID;
    private volatile int[] fakeOreIds = new int[0];
    /** How many REAL fake-ore ids resolved (excludes the invisible fallback). 0 = fake field effectively off. */
    private volatile int realFakeCount = 0;

    /** Membership table for the valuable NON-ORE blocks (chests, custom) we hide from X-ray. */
    private volatile IdTable protectedIds = IdTable.EMPTY;
    /** Block a hidden REAL protected block is masked with (plain rock). */
    private volatile int hideProtectedId = BlockType.EMPTY_ID;
    /** Decoy blocks (chests) scattered as honeypots. */
    private volatile int[] decoyIds = new int[0];

    /** Names of ores counted by the mining-rate heuristic. */
    private volatile Set<String> trackedOreNames = Set.of();
    /** Every block we consider an "ore" (palette ∪ tracked) — used for honeypot classification. */
    private volatile IdTable knownOreIds = IdTable.EMPTY;
    /** The base rock blocks honeypots may be placed in (from HoneypotHostPrefixes). */
    private volatile IdTable hostRockIds = IdTable.EMPTY;

    private int lastLoggedFakeCount = -1;

    /**
     * An immutable id&rarr;membership bitmap, looked up millions of times per second by the obfuscation loop.
     *
     * <p>Two reasons this isn't a {@code HashSet<Integer>}: every {@code contains(int)} on one boxes an
     * {@code Integer} (an allocation per block scanned), and — more importantly — {@link BlockCatalog#rebuild()}
     * runs on the scheduler thread while world threads are reading. Mutating a shared {@code HashSet} under a
     * concurrent read can return a wrong answer or spin inside a resize. A table is built off to the side and
     * published by a single volatile reference write, so a reader sees either the whole old table or the whole
     * new one.
     */
    private static final class IdTable {
        static final IdTable EMPTY = new IdTable(new boolean[0], 0);

        private final boolean[] flags;
        private final int count;

        private IdTable(boolean[] flags, int count) {
            this.flags = flags;
            this.count = count;
        }

        boolean contains(int id) {
            return id >= 0 && id < flags.length && flags[id];
        }

        int size() {
            return count;
        }

        boolean isEmpty() {
            return count == 0;
        }

        /** Mutable accumulator; call {@link Builder#build()} once and publish the result. */
        static final class Builder {
            private boolean[] flags = new boolean[512];
            private int count;

            void add(int id) {
                if (id < 0) {
                    return;
                }
                if (id >= flags.length) {
                    flags = java.util.Arrays.copyOf(flags, Math.max(id + 1, flags.length * 2));
                }
                if (!flags[id]) {
                    flags[id] = true;
                    count++;
                }
            }

            IdTable build() {
                return count == 0 ? EMPTY : new IdTable(flags, count);
            }
        }
    }

    public BlockCatalog(AntiXrayConfig config) {
        this.config = config;
        rebuild();
    }

    /**
     * Re-resolves every configured name against the live block asset map. Safe to call repeatedly — block
     * assets aren't in the map yet during plugin {@code setup()}, so the obfuscation tick calls this until it
     * resolves. Logs only when the resolved count changes (no per-tick spam).
     */
    public void rebuild() {
        this.emptyId = BlockType.EMPTY_ID;
        // Everything is accumulated locally and published at the very end, so a world thread reading the
        // catalog mid-rebuild sees the previous, complete state instead of a half-cleared one.
        IdTable.Builder ores = new IdTable.Builder();
        Set<String> tracked = new HashSet<>();

        // Fake-ore field palette. Entries may be exact ids OR "Prefix*" wildcards (e.g. "Ore_*"), which
        // auto-discover every matching block actually registered in this world.
        LinkedHashSet<Integer> palette = new LinkedHashSet<>();
        forEachMatch(config.Obfuscation.FakeOrePalette, (name, id) -> {
            palette.add(id);
            ores.add(id);
        });

        this.fallbackHideId = resolve(config.Obfuscation.FallbackHideBlock);
        if (fallbackHideId == BlockType.UNKNOWN_ID) {
            fallbackHideId = BlockType.EMPTY_ID;
        }

        int hide = resolve(config.Obfuscation.HideRealOreAs);
        // Number of REAL fake-ore ids that resolved (before any fallback). If this is 0 the field renders as
        // the fallback rock — invisible to X-ray — so the fake-ore layer is effectively off. Tracked separately
        // so the status/logs don't misreport "1 resolved" when it's really just the fallback.
        int resolvedFakes = palette.size();
        if (palette.isEmpty() && fallbackHideId != BlockType.EMPTY_ID) {
            palette.add(fallbackHideId);
        }

        // Tracked ores (mining-rate heuristic) — same exact/wildcard matching. We store the matched NAMES so the
        // break handler (which only sees a block name) can test membership.
        forEachMatch(config.Detection.TrackedOres, (name, id) -> {
            tracked.add(name);
            ores.add(id);
        });

        this.hostRockIds = buildHostRocks();
        this.hideOreId = (hide == BlockType.UNKNOWN_ID) ? fallbackHideId : hide;
        this.fakeOreIds = palette.stream().mapToInt(Integer::intValue).toArray();
        this.realFakeCount = resolvedFakes;
        this.knownOreIds = ores.build();
        this.trackedOreNames = Set.copyOf(tracked);
        // Protected non-ore blocks (chests, valuables) + their decoy honeypots. Reads hideOreId as its
        // fallback, so it publishes last.
        rebuildProtected();

        if (realFakeCount != lastLoggedFakeCount) {
            lastLoggedFakeCount = realFakeCount;
            if (realFakeCount > 0) {
                Console.info("BlockCatalog: " + realFakeCount + " fake-ore ids, "
                        + knownOreIds.size() + " ore ids resolved.");
            } else {
                Console.warning("BlockCatalog: no fake-ore ids resolved yet "
                        + "(block assets may still be loading; will retry). If it stays 0, the fake-ore field "
                        + "is INVISIBLE (falls back to " + config.Obfuscation.FallbackHideBlock + ") — fix "
                        + "FakeOrePalette with the real ore ids from /antixray probe.");
            }
        }
    }

    /** Scans the block asset map for keys matching HoneypotHostPrefixes and collects their numeric ids. */
    private IdTable buildHostRocks() {
        IdTable.Builder hostRocks = new IdTable.Builder();
        var prefixes = config.Obfuscation.HoneypotHostPrefixes;
        if (prefixes == null || prefixes.isEmpty()) {
            return IdTable.EMPTY; // empty = any hidden block is a valid host (handled by isHostRock)
        }
        try {
            var map = BlockType.getAssetMap().getAssetMap();
            for (Object k : map.keySet()) {
                String key = String.valueOf(k);
                for (String pref : prefixes) {
                    if (pref != null && !pref.isBlank() && key.startsWith(pref)) {
                        int id = BlockType.getAssetMap().getIndexOrDefault(key, BlockType.UNKNOWN_ID);
                        if (id != BlockType.UNKNOWN_ID && id != BlockType.EMPTY_ID) {
                            hostRocks.add(id);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Console.warning("Failed to build host-rock set: " + e.getMessage());
        }
        return hostRocks.build();
    }

    /** True if honeypot fakes may be placed in this block (a base rock layer). Empty prefix list = any. */
    public boolean isHostRock(int blockId) {
        IdTable t = hostRockIds; // single volatile read
        return t.isEmpty() || t.contains(blockId);
    }

    /** Number of resolved host-rock ids (diagnostic). */
    public int hostRockCount() {
        return hostRockIds.size();
    }

    /**
     * Resolves the protected-block set (by exact id OR prefix, scanning the live asset map so one entry like
     * "Furniture_Crude_Chest" covers every _Small/_Large variant), the decoy palette, and the mask block.
     */
    private void rebuildProtected() {
        IdTable.Builder protectedBlocks = new IdTable.Builder();
        var obf = config.Obfuscation;
        if (!obf.ProtectedBlocksEnabled) {
            this.protectedIds = IdTable.EMPTY;
            this.decoyIds = new int[0];
            this.hideProtectedId = BlockType.EMPTY_ID;
            return;
        }
        var patterns = obf.ProtectedBlocks;
        int skippedEntities = 0;
        if (patterns != null && !patterns.isEmpty()) {
            try {
                var map = BlockType.getAssetMap().getAssetMap();
                for (Object k : map.keySet()) {
                    String key = String.valueOf(k);
                    for (String p : patterns) {
                        if (p != null && !p.isBlank() && (key.equals(p) || key.startsWith(p))) {
                            int id = BlockType.getAssetMap().getIndexOrDefault(key, BlockType.UNKNOWN_ID);
                            if (id != BlockType.UNKNOWN_ID && id != BlockType.EMPTY_ID) {
                                // Block-entity blocks (chests/containers) CANNOT be safely hidden: the block id
                                // becomes rock but the block-entity's model is replicated on a separate channel,
                                // so the client still draws the chest poking through the rock. Skip them here —
                                // protect those via decoys instead.
                                if (isBlockEntityBlock(id)) {
                                    skippedEntities++;
                                } else {
                                    protectedBlocks.add(id);
                                }
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Console.warning("Failed to build protected-block set: " + e.getMessage());
            }
        }
        if (skippedEntities > 0) {
            Console.warning("BlockCatalog: skipped " + skippedEntities + " protected block(s) that are "
                    + "block-entities (chests/containers) — hiding them would leak their model. Use decoys "
                    + "(ProtectedDecoyPalette) for those instead.");
        }

        LinkedHashSet<Integer> decoys = new LinkedHashSet<>();
        if (obf.ProtectedDecoyPalette != null) {
            for (String name : obf.ProtectedDecoyPalette) {
                int id = resolve(name);
                if (id != BlockType.UNKNOWN_ID && id != BlockType.EMPTY_ID) {
                    decoys.add(id);
                }
            }
        }
        int hp = resolve(obf.HideProtectedAs);
        this.hideProtectedId = (hp == BlockType.UNKNOWN_ID) ? hideOreId : hp;
        this.decoyIds = decoys.stream().mapToInt(Integer::intValue).toArray();
        this.protectedIds = protectedBlocks.build();
    }

    /** True if this id is a valuable protected block (chest/custom) we mask from X-ray. */
    public boolean isProtectedBlock(int blockId) {
        return protectedIds.contains(blockId);
    }

    /**
     * True if this block carries a block-entity (chest/container/etc.). Such blocks can't be hidden by swapping
     * their id — the block-entity model is replicated separately and would still render — so we never mask them.
     * Best-effort: on any error returns false (i.e. treat as a plain block).
     */
    public boolean isBlockEntityBlock(int blockId) {
        try {
            BlockType t = BlockType.getAssetMap().getAsset(blockId);
            return t != null && t.getBlockEntity() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Block a hidden real protected block is replaced with (plain rock). */
    public int hideProtectedId() {
        return hideProtectedId;
    }

    public boolean hasDecoys() {
        return decoyIds.length > 0;
    }

    /** Number of resolved protected-block ids (chests/valuables actually found in the world's asset map). */
    public int protectedCount() {
        return protectedIds.size();
    }

    /** Number of resolved decoy-block ids. */
    public int decoyCount() {
        return decoyIds.length;
    }

    /** Deterministic decoy block for a position (stable across rescans, no flicker). */
    public int decoyFor(BlockKey key) {
        if (decoyIds.length == 0) {
            return emptyId;
        }
        int idx = Math.floorMod(key.mix() ^ 0x9E3779B97F4A7C15L, decoyIds.length);
        return decoyIds[idx];
    }

    /**
     * Expands a list of block patterns against the live asset map and invokes {@code sink(name, id)} for every
     * matched, registered block. Each entry is either an exact block id, or a {@code "Prefix*"} wildcard (a
     * trailing {@code *}) that matches every registered block id starting with the prefix — so {@code "Ore_*"}
     * auto-discovers every ore variant actually loaded in this world, with no hand-maintained id list. Exact
     * entries that don't resolve are skipped (asset may not be registered yet / not on this build).
     */
    private void forEachMatch(List<String> patterns, ObjIntConsumer<String> sink) {
        if (patterns == null || patterns.isEmpty()) {
            return;
        }
        java.util.List<String> prefixes = new java.util.ArrayList<>();
        for (String p : patterns) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (p.endsWith("*")) {
                String pref = p.substring(0, p.length() - 1);
                if (!pref.isEmpty()) {
                    prefixes.add(pref);
                }
            } else {
                int id = resolve(p);
                if (id != BlockType.UNKNOWN_ID && id != BlockType.EMPTY_ID) {
                    sink.accept(p, id);
                }
            }
        }
        if (prefixes.isEmpty()) {
            return;
        }
        try {
            var map = BlockType.getAssetMap().getAssetMap();
            for (Object k : map.keySet()) {
                String key = String.valueOf(k);
                for (String pref : prefixes) {
                    if (key.startsWith(pref)) {
                        int id = BlockType.getAssetMap().getIndexOrDefault(key, BlockType.UNKNOWN_ID);
                        if (id != BlockType.UNKNOWN_ID && id != BlockType.EMPTY_ID) {
                            sink.accept(key, id);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Console.warning("Failed to expand wildcard block patterns: " + e.getMessage());
        }
    }

    /** name -> numeric runtime id, or {@link BlockType#UNKNOWN_ID} if the asset doesn't exist. */
    private static int resolve(String name) {
        if (name == null || name.isBlank()) {
            return BlockType.UNKNOWN_ID;
        }
        try {
            return BlockType.getAssetMap().getIndexOrDefault(name, BlockType.UNKNOWN_ID);
        } catch (Exception e) {
            return BlockType.UNKNOWN_ID;
        }
    }

    public boolean hasFakeOres() {
        return fakeOreIds.length > 0;
    }

    /** Count of REAL resolved fake-ore ids (excludes the invisible fallback). 0 = fake field is off/invisible. */
    public int fakeOreCount() {
        return realFakeCount;
    }

    public int emptyId() {
        return emptyId;
    }

    /** Block a hidden real ore is replaced with to HIDE it from X-ray (plain rock). */
    public int hideOreId() {
        return hideOreId;
    }

    /** True if this numeric id is air (nothing rendered / see-through for exposure purposes). */
    public boolean isAir(int blockId) {
        return blockId == emptyId;
    }

    /** Deterministic fake ore for a position, so the client view is stable across rescans (no flicker). */
    public int fakeOreFor(BlockKey key) {
        if (fakeOreIds.length == 0) {
            return fallbackHideId;
        }
        long h = key.mix();
        int idx = Math.floorMod(h, fakeOreIds.length);
        return fakeOreIds[idx];
    }

    /** True if the numeric id is one of our known ores (so masking it is expected, not a honeypot). */
    public boolean isKnownOreId(int blockId) {
        return knownOreIds.contains(blockId);
    }

    /** True if a broken block's string id is a rate-tracked valuable ore. */
    public boolean isTrackedOreName(String blockName) {
        return blockName != null && trackedOreNames.contains(blockName);
    }

    /**
     * A transparent block for the admin-only X-ray audit view. Tries a few known glass ids and falls back to
     * air, so the audit never silently renders nothing.
     */
    public int seeThroughId() {
        for (String name : new String[] {"Glass_Block", "Glass_Block_Clear", "Glass", "Glass_Pane"}) {
            int id = resolve(name);
            if (id != BlockType.UNKNOWN_ID && id != BlockType.EMPTY_ID) {
                return id;
            }
        }
        return emptyId;
    }

    /** Best-effort name for a numeric id (diagnostics). */
    public static String nameOf(int blockId) {
        try {
            BlockType t = BlockType.getAssetMap().getAsset(blockId);
            return t == null ? "?" : t.getId();
        } catch (Exception e) {
            return "?";
        }
    }
}
