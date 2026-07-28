package dev.stoshe.antixray.manager;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.world.ServerSetBlock;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.stoshe.antixray.AntiXray;
import dev.stoshe.antixray.model.AntiXrayConfig;
import dev.stoshe.antixray.model.BlockKey;
import dev.stoshe.antixray.util.ChatUtil;
import dev.stoshe.antixray.util.Console;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The packet-level anti-xray core. For every eligible player it obfuscates, one chunk at a time, the vertical
 * slab ({@code VerticalRadius} above/below, up to {@code MaxY}) of every loaded chunk within
 * {@code ChunkRadius} of them — hiding each fully-enclosed real ore as plain rock and scattering honeypot fake
 * ores through hidden rock. Everything is sent to that <em>one</em> player via {@link ServerSetBlock}; the world
 * is never changed and no other player sees any of it. Exposed faces are never touched, so a legitimate player
 * never sees a fake, and breaking a block re-sends the real neighbours so mining always uncovers the truth.
 *
 * <p>The field is <em>derived, never recorded</em>: which rock becomes a fake ore and which ore is masked is a
 * pure function of the block position and the terrain around it, so the break handler recomputes the answer
 * instead of consulting a per-player list of positions. Only which chunk sections a client has already been
 * sent is tracked per player.
 *
 * <p>Each chunk's slab is read into a padded {@link Slab} array <em>once</em> per scan, so occlusion checks
 * hit memory instead of making ~13 {@code world.getBlock} calls per candidate block — the dominant cost of
 * the scan at Hytale's 32&times;32-column chunks. All world access and per-player state mutation happen on the
 * world thread ({@code world.execute}).
 */
public final class ObfuscationManager {

    private final AntiXray plugin;
    private final BlockCatalog catalog;

    private final ConcurrentHashMap<UUID, PlayerView> views = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    /** Optional send-time path; when active the tick precomputes obfuscated chunks instead of sending ServerSetBlock. */
    private dev.stoshe.antixray.net.SendTimeObfuscator sendTime;

    /** Safety cap on set-block packets emitted for a single chunk column (high enough to not truncate). */
    private static final int MAX_PACKETS_PER_CHUNK = 16000;
    /**
     * Point at which a chunk stops scattering NEW honeypots and spends what's left of the packet budget on
     * masking real ores. Hitting the hard cap used to truncate the scan wherever it happened to be, leaving the
     * rest of the chunk unmasked — a hole in the actual protection to pay for decoration. Honeypots are the
     * expendable half, so they yield first.
     */
    private static final int HONEYPOT_BUDGET_PER_CHUNK = (MAX_PACKETS_PER_CHUNK * 4) / 5;
    /** Batches at least this large are queued and flushed once instead of flushed per packet. */
    private static final int QUEUE_THRESHOLD = 16;

    private volatile long lastCatalogRebuildMs;
    private static final long CATALOG_REBUILD_INTERVAL_MS = 15000;

    /** Send-time: don't precompute until a player has been in-world this long, so the initial world-load
     *  (which is world-thread heavy) finishes without competing for the world thread. */
    private static final long SEND_TIME_GRACE_MS = 15000;
    /** Send-time: chunks precomputed per player per tick — kept low so precompute never starves chunk delivery. */
    private static final int SEND_TIME_CHUNKS_PER_TICK = 2;
    /** Sections in a chunk column, i.e. how many bits of the per-chunk done-mask are meaningful. */
    private static final int SECTIONS_PER_CHUNK = 16;

    public ObfuscationManager(AntiXray plugin, BlockCatalog catalog) {
        this.plugin = plugin;
        this.catalog = catalog;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AntiXray-Obfuscation");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 2, 1, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        views.clear();
    }

    public void forget(UUID uuid) {
        views.remove(uuid);
    }

    /** Wires the optional send-time path so the tick precomputes obfuscated chunks instead of ServerSetBlock. */
    public void setSendTime(dev.stoshe.antixray.net.SendTimeObfuscator sendTime) {
        this.sendTime = sendTime;
    }

    private AntiXrayConfig.Obfuscation cfg() {
        return plugin.getConfig().Obfuscation;
    }

    private boolean worldEnabled(String worldName) {
        List<String> enabled = plugin.getConfig().General.EnabledWorlds;
        return enabled == null || enabled.isEmpty() || enabled.contains(worldName);
    }

    // ------------------------------------------------------------------ chunk-key packing

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    // ------------------------------------------------------------------ scheduling

    private void tick() {
        try {
            if (!cfg().Enabled) {
                return;
            }
            long now = System.currentTimeMillis();
            // Block assets load after plugin setup() and more ore variants register as biomes load, so keep
            // re-resolving: every tick until the first ids appear, then periodically to catch new ones.
            if (!catalog.hasFakeOres() || now - lastCatalogRebuildMs >= CATALOG_REBUILD_INTERVAL_MS) {
                int fakesBefore = catalog.fakeOreCount();
                catalog.rebuild();
                lastCatalogRebuildMs = now;
                // Ore assets load a few seconds after boot. Chunks obfuscated in that window got an invisible
                // fallback field and were marked done; when the real ores first resolve, forget every player's
                // done-set so those chunks re-obfuscate with the now-visible fake ores.
                if (fakesBefore == 0 && catalog.fakeOreCount() > 0) {
                    for (PlayerView v : views.values()) {
                        v.doneChunks.clear();
                    }
                    Console.info("BlockCatalog: fake-ore field is now live — re-obfuscating loaded chunks.");
                }
                if (!catalog.hasFakeOres()) {
                    return;
                }
            }
            for (PlayerRef pr : Universe.get().getPlayers()) {
                // A player mid-(dis)connect has null uuid/world/transform for a moment — guard every step and
                // skip that player for this tick rather than letting one transient null abort the whole tick.
                try {
                    if (pr == null) {
                        continue;
                    }
                    UUID uuid = pr.getUuid();
                    UUID worldUuid = pr.getWorldUuid();
                    if (uuid == null || worldUuid == null) {
                        continue;
                    }
                    World world = Universe.get().getWorld(worldUuid);
                    if (world == null || world.getName() == null || !worldEnabled(world.getName())) {
                        continue;
                    }
                    var transform = pr.getTransform();
                    if (transform == null) {
                        continue;
                    }
                    var pos = transform.getPosition();
                    if (pos == null) {
                        continue;
                    }
                    int px = (int) Math.floor(pos.x);
                    int py = (int) Math.floor(pos.y);
                    int pz = (int) Math.floor(pos.z);

                    if (plugin.isBypassed(pr)) {
                        views.remove(uuid);
                        continue;
                    }

                    PlayerView view = views.computeIfAbsent(uuid, u -> new PlayerView());
                    if (view.busy) {
                        continue;
                    }
                    int pcx = ChunkUtil.chunkCoordinate(px);
                    int pcz = ChunkUtil.chunkCoordinate(pz);
                    view.busy = true;
                    world.execute(() -> {
                        try {
                            obfuscateAround(pr, world, view, pcx, pcz, py);
                        } catch (Exception e) {
                            Console.warning("Obfuscation failed for " + pr.getUsername() + ": " + e);
                        } finally {
                            view.busy = false;
                        }
                    });
                } catch (Exception perPlayer) {
                    // transient state during (dis)connect — skip this player this tick
                }
            }
        } catch (Exception e) {
            Console.warning("Obfuscation tick failed: " + e);
        }
    }

    // ------------------------------------------------------------------ per-chunk obfuscation (world thread)

    private void obfuscateAround(PlayerRef pr, World world, PlayerView view, int pcx, int pcz, int py) {
        if (!world.getName().equals(view.worldName)) {
            view.worldName = world.getName();
            view.doneChunks.clear();
        }
        int radius = cfg().ChunkRadius;
        int vr = cfg().VerticalRadius;
        int size = ChunkUtil.SIZE;
        // Forget chunks that left the player's radius, so they re-obfuscate if the client reloads them. Pruned
        // one ring beyond the radius: without that margin, walking back and forth over the boundary re-does the
        // same chunks forever.
        view.doneChunks.keySet().removeIf(key -> Math.abs(chunkX(key) - pcx) > radius + 1
                || Math.abs(chunkZ(key) - pcz) > radius + 1);

        var tracker = pr.getChunkTracker();

        // The unit of work is a 32-block SECTION of a chunk, and doneChunks stores one bit per section.
        //
        // The old model kept the Y at which a chunk was last done and redid the WHOLE column once the player
        // had moved a VerticalRadius away from it — so walking down a mineshaft re-scanned and re-sent the same
        // chunks over and over. A section is world-aligned, not player-relative: once it is obfuscated it stays
        // correct no matter where the player goes, so descending only costs the sections newly entering the
        // vertical window.
        int sectionLo = Math.max(0, Math.floorDiv(Math.max(ChunkUtil.MIN_Y, py - vr), size));
        int sectionHi = Math.min(SECTIONS_PER_CHUNK - 1, Math.floorDiv(Math.min(cfg().MaxY, py + vr), size));
        if (sectionHi < sectionLo) {
            return; // the player's vertical window is entirely above MaxY
        }
        long needed = sectionMask(sectionLo, sectionHi);

        // Collect chunks in range with sections still missing, nearest first.
        // The client only applies a ServerSetBlock for a chunk it has ALREADY received (vanilla's own
        // ChunkSystems$ReplicateChanges gates on exactly this). The server loads a chunk long before it streams
        // it to the player, so without this gate we blast the whole field at a client that silently drops it —
        // and then mark the chunk done forever. Chunks the client hasn't got yet are forgotten and retried.
        List<long[]> todo = new ArrayList<>();
        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                long key = chunkKey(cx, cz);
                if (tracker != null && !tracker.isLoaded(ChunkUtil.indexChunk(cx, cz))) {
                    view.doneChunks.remove(key); // not delivered (or reloading) — redo once it arrives
                    continue;
                }
                long missing = needed & ~view.doneChunks.getOrDefault(key, 0L);
                if (missing != 0L) {
                    int dist = Math.abs(cx - pcx) + Math.abs(cz - pcz);
                    todo.add(new long[] {dist, cx, cz, key, missing});
                }
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        todo.sort(Comparator.comparingLong(a -> a[0]));

        boolean useSendTime = sendTime != null && sendTime.active();
        if (useSendTime) {
            // Grace-gate: skip precompute while the player is still loading the world, so we never compete
            // with the server's own chunk generation/serialization and stall the join.
            long now = System.currentTimeMillis();
            if (view.firstSeenMs == 0L) {
                view.firstSeenMs = now;
            }
            if (now - view.firstSeenMs < SEND_TIME_GRACE_MS) {
                return;
            }
        }
        // The budget still counts CHUNKS, not sections: one unit does every section a chunk is missing, which
        // is the same slab of work the old per-chunk pass did, so MaxChunksPerTick keeps its meaning.
        int budget = useSendTime ? SEND_TIME_CHUNKS_PER_TICK : cfg().MaxChunksPerTick;
        for (long[] entry : todo) {
            if (budget <= 0) {
                break;
            }
            int cx = (int) entry[1];
            int cz = (int) entry[2];
            long missing = entry[4];
            long completed = 0L;
            for (int sy = sectionLo; sy <= sectionHi; sy++) {
                if ((missing & (1L << sy)) == 0L) {
                    continue;
                }
                int loY = Math.max(ChunkUtil.MIN_Y, sy * size);
                int hiY = Math.min(cfg().MaxY, sy * size + size - 1);
                boolean done = useSendTime ? precomputeSection(world, cx, cz, sy)
                        : obfuscateSection(pr, world, view, cx, cz, sy, loY, hiY);
                if (!done) {
                    break; // chunk vanished mid-pass — leave the rest missing so it is retried
                }
                completed |= 1L << sy;
            }
            if (completed != 0L) {
                view.doneChunks.merge(entry[3], completed, (a, b) -> a | b);
                budget--;
            }
        }
    }

    /** Bits {@code lo..hi} set — the sections a player's vertical window covers. */
    private static long sectionMask(int lo, int hi) {
        long mask = 0L;
        for (int sy = lo; sy <= hi; sy++) {
            mask |= 1L << sy;
        }
        return mask;
    }

    /**
     * Send-time path: precompute + cache the obfuscated bytes for one section. Returns false if the chunk
     * isn't loaded yet (so it's retried).
     */
    private boolean precomputeSection(World world, int cx, int cz, int sy) {
        int size = ChunkUtil.SIZE;
        if (world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(cx * size, cz * size)) == null) {
            return false;
        }
        sendTime.precompute(world, cx, sy, cz);
        return true;
    }

    /**
     * Obfuscates the {@code [minY..maxY]} band of one chunk (a single 32-block section) for this player.
     * Returns false if the chunk isn't loaded yet.
     *
     * <p>The band (plus a {@code CoverDepth}-block border) is read into a local array <em>once</em> and every
     * occlusion check reads from it, instead of the ~13 {@code world.getBlock} calls per candidate the naive
     * scan would make. Because this runs on the world thread (which owns the blocks exclusively for the
     * duration of the call), the snapshot is identical to reading the live world block-by-block — same result,
     * a fraction of the reads.
     */
    private boolean obfuscateSection(PlayerRef pr, World world, PlayerView view, int cx, int cz, int sy,
            int minY, int maxY) {
        int size = ChunkUtil.SIZE;
        int ox = cx * size;
        int oz = cz * size;
        if (world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(ox, oz)) == null) {
            return false;
        }
        PacketHandler ph = pr.getPacketHandler();
        if (ph == null) {
            return true;
        }
        int depth = Math.max(1, cfg().CoverDepth);
        // Pad by `depth`: isExposed needs the 1-block ring, isBuried needs up to `depth` blocks out.
        Slab slab = Slab.read(world, ox - depth, minY - depth, oz - depth,
                size + 2 * depth, (maxY - minY + 1) + 2 * depth, size + 2 * depth, catalog.emptyId());

        // The two BAITS are per-SECTION, not per-block: at most one of each per section, in a small share of
        // sections (there is far too much rock for a per-block density to give a believable count). Their slot
        // inside the section is derived from (cx,sy,cz) too, so — like every other part of the field — they are
        // a pure function of the position and can be recomputed later instead of remembered.
        final int trapLocal = trapLocalIndex(cx, sy, cz);
        final boolean placeTrap = catalog.hasTrapOres() && sectionTrapSelected(cx, sy, cz);
        final int decoyLocal = decoyLocalIndex(cx, sy, cz);
        final boolean placeDecoy = catalog.hasDecoys() && sectionDecoySelected(cx, sy, cz);

        // Hoisted out of the loop: these are read once per candidate block otherwise, and the config lookup
        // walks plugin -> config -> Obfuscation every time.
        final double density = cfg().FakeOreDensity;
        final int hideOre = catalog.hideOreId();
        final int hideProtected = catalog.hideProtectedId();

        // Test order matters — this loop runs ~66k times per chunk per player. Cheapest, most selective test
        // first: classification is one array read (BlockCatalog.IdTable), while occlusion costs 6 (isExposed)
        // to 6*CoverDepth (isBuried) slab reads. So classify first, and in the honeypot branch run the density
        // gate — pure arithmetic, no memory — BEFORE the occlusion walk, since at the default density ~97% of
        // candidates are rejected there and never need it.
        List<ToClientPacket> batch = new ArrayList<>();
        scan:
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                for (int y = minY; y <= maxY; y++) {
                    if (batch.size() >= MAX_PACKETS_PER_CHUNK) {
                        // Abandon the whole chunk, not just this column: the old plain `break` left the two
                        // outer loops spinning over 32x32 columns to break again immediately. Whatever is left
                        // of this chunk stays unobfuscated, so say so instead of truncating in silence.
                        if (!view.truncWarned) {
                            view.truncWarned = true;
                            Console.warning("Chunk packet cap (" + MAX_PACKETS_PER_CHUNK + ") hit for "
                                    + pr.getUsername() + " at chunk " + cx + "," + cz + " — the rest of that "
                                    + "chunk is left unprotected. Lower FakeOreDensity or VerticalRadius.");
                        }
                        break scan;
                    }
                    int real = slab.get(x, y, z);
                    if (catalog.isAir(real)) {
                        continue;
                    }
                    int target;
                    if (catalog.isKnownOreId(real)) {
                        if (isExposed(slab, x, y, z)) {
                            continue; // visible to a legit player — leave it alone
                        }
                        // Real ore: HIDE it as plain rock so X-ray sees nothing at its true location.
                        target = hideOre;
                    } else if (catalog.isProtectedBlock(real)) {
                        if (isExposed(slab, x, y, z)) {
                            continue;
                        }
                        // Real chest/valuable buried in terrain (enclosed): mask it as rock so X-ray can't
                        // spot it.
                        target = hideProtected;
                    } else {
                        if (!catalog.isHostRock(real)) {
                            continue;
                        }
                        // This section's baits (trap ore / decoy chest, at most one each) win over the
                        // camouflage field; everything else is decided by the density gate. The baits ignore
                        // the packet budget: there are two of them per section and they are the only thing
                        // detection can actually fire on.
                        boolean trap = placeTrap && isSectionSlot(trapLocal, x, y, z);
                        boolean decoy = !trap && placeDecoy && isDecoyLocal(decoyLocal, x, y, z);
                        if (!trap && !decoy
                                && (batch.size() >= HONEYPOT_BUDGET_PER_CHUNK
                                    || !fieldSelected(x, y, z, density))) {
                            continue; // budget spent, or simply not part of the field — no occlusion read
                        }
                        // Honeypot host rock: buried deep enough it's never behind something visible. For
                        // CoverDepth >= 1 this subsumes isExposed (d=1 IS the 6-neighbour ring), so the
                        // exposure test above would be redundant work here.
                        if (!isBuried(slab, x, y, z, depth)) {
                            continue;
                        }
                        BlockKey here = new BlockKey(x, y, z);
                        target = trap ? catalog.trapOreFor(here)
                                : decoy ? catalog.decoyFor(here)
                                : catalog.fakeOreFor(here);
                    }
                    if (target == real) {
                        continue;
                    }
                    batch.add(new ServerSetBlock(x, y, z, target, (short) 0, (byte) 0));
                }
            }
        }
        flush(ph, batch);
        return true;
    }

    // ------------------------------------------------------------------ the field as a pure function
    //
    // Nothing about the obfuscated field is remembered per player: every part of it — which rock becomes a fake
    // ore, which ore is masked, where a section's decoy sits — is a deterministic function of the block position
    // and the world's own contents, identical for every player. So "is there a fake at (x,y,z)?" is RECOMPUTED
    // when a break needs the answer, instead of being looked up in a per-player set of positions.
    //
    // That set used to be the hard limit on how much of the world could be protected at all: at the default
    // ChunkRadius/VerticalRadius/FakeOreDensity one player's field is ~100k positions against a 40k cap, so
    // obfuscation stopped a few seconds after joining and only resumed in scraps as the client unloaded chunks —
    // which is exactly what "fake ores show up in random chunks, not the ones I'm standing in" looks like.

    /**
     * TRAP (honeypot bait): the one rare position per section that is deliberately <em>never</em> revealed, so
     * it is the single thing a player can actually break. Breaking one is the honeypot hit.
     *
     * <p>Note what is NOT tested here: whether the block is still buried. It cannot be — to break a block you
     * must be able to see its face, so by the time anyone hits a trap they have already opened it up. That is
     * exactly why the old "is it still enclosed?" test made honeypots impossible to trip. What separates a
     * cheater from an unlucky miner is not one hit but the RATE of them, which is why the trap is rare
     * ({@code TrapChancePerSection}) and the flag needs several hits inside a window.
     */
    private boolean isArmedTrap(World world, int x, int y, int z) {
        if (y < ChunkUtil.MIN_Y || y > cfg().MaxY) {
            return false;
        }
        try {
            // The world still holds plain rock here (we only ever changed what the client was shown), so a
            // block that is no longer host rock cannot have been carrying a bait.
            int real = world.getBlock(x, y, z);
            if (catalog.isAir(real) || !catalog.isHostRock(real)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return isTrapPosition(x, y, z);
    }

    /** True if this position is its section's trap-ore slot or decoy-chest slot (both are bait). */
    private boolean isTrapPosition(int x, int y, int z) {
        int size = ChunkUtil.SIZE;
        int cx = ChunkUtil.chunkCoordinate(x);
        int cz = ChunkUtil.chunkCoordinate(z);
        int sy = Math.floorDiv(y, size);
        if (catalog.hasTrapOres() && sectionTrapSelected(cx, sy, cz)
                && isSectionSlot(trapLocalIndex(cx, sy, cz), x, y, z)) {
            return true;
        }
        return catalog.hasDecoys() && sectionDecoySelected(cx, sy, cz)
                && isDecoyLocal(decoyLocalIndex(cx, sy, cz), x, y, z);
    }

    /**
     * CAMOUFLAGE: this block is host rock the dense field would have drawn as a common fake ore. Used only when
     * REVEALING — the occlusion test is deliberately not applied, because the break we are reacting to is
     * precisely what un-buried the neighbours, so testing it would skip the blocks that most need putting back.
     *
     * <p>Traps are excluded: restoring the bait would put us straight back to a honeypot that can never be hit.
     */
    private boolean isFieldCandidate(int blockId, int x, int y, int z) {
        if (catalog.isAir(blockId) || catalog.isKnownOreId(blockId) || catalog.isProtectedBlock(blockId)
                || !catalog.isHostRock(blockId)) {
            return false;
        }
        return fieldSelected(x, y, z, cfg().FakeOreDensity) && !isTrapPosition(x, y, z);
    }

    /** True if this player's client was actually sent the obfuscated form of the section holding (x,y,z). */
    private boolean wasObfuscatedFor(PlayerView view, int x, int y, int z) {
        if (sendTime != null && sendTime.active()) {
            return true; // send-time rewrites every chunk on its way out, so there is no per-player done-set
        }
        if (view == null) {
            return false;
        }
        int sy = Math.floorDiv(y, ChunkUtil.SIZE);
        if (sy < 0 || sy >= SECTIONS_PER_CHUNK) {
            return false;
        }
        long done = view.doneChunks.getOrDefault(
                chunkKey(ChunkUtil.chunkCoordinate(x), ChunkUtil.chunkCoordinate(z)), 0L);
        return (done & (1L << sy)) != 0L;
    }

    /** Same as {@link #isBuried} but reading the live world — for the one-off checks outside a chunk scan. */
    private boolean isBuriedInWorld(World world, int x, int y, int z, int depth) {
        try {
            for (int d = 1; d <= depth; d++) {
                if (catalog.isAir(world.getBlock(x + d, y, z))
                        || catalog.isAir(world.getBlock(x - d, y, z))
                        || catalog.isAir(world.getBlock(x, y + d, z))
                        || catalog.isAir(world.getBlock(x, y - d, z))
                        || catalog.isAir(world.getBlock(x, y, z + d))
                        || catalog.isAir(world.getBlock(x, y, z - d))) {
                    return false;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /** A block is exposed if any of its 6 orthogonal neighbours is air (i.e. a legit player could see it). */
    private boolean isExposed(Slab slab, int x, int y, int z) {
        return catalog.isAir(slab.get(x + 1, y, z))
                || catalog.isAir(slab.get(x - 1, y, z))
                || catalog.isAir(slab.get(x, y + 1, z))
                || catalog.isAir(slab.get(x, y - 1, z))
                || catalog.isAir(slab.get(x, y, z + 1))
                || catalog.isAir(slab.get(x, y, z - 1));
    }

    /**
     * True if there's no air within {@code depth} solid blocks along any axis — i.e. the block is buried
     * deep enough that the player can't see it along any straight line of sight, only through walls (X-ray).
     */
    private boolean isBuried(Slab slab, int x, int y, int z, int depth) {
        for (int d = 1; d <= depth; d++) {
            if (catalog.isAir(slab.get(x + d, y, z))
                    || catalog.isAir(slab.get(x - d, y, z))
                    || catalog.isAir(slab.get(x, y + d, z))
                    || catalog.isAir(slab.get(x, y - d, z))
                    || catalog.isAir(slab.get(x, y, z + d))
                    || catalog.isAir(slab.get(x, y, z - d))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deterministic density gate for the honeypot scatter (stable per position, no flicker). Takes raw
     * coordinates and a hoisted density so the hot loop can test a candidate without allocating a key or
     * re-reading the config.
     */
    private static boolean fieldSelected(int x, int y, int z, double density) {
        if (density >= 1.0) {
            return true;
        }
        if (density <= 0.0) {
            return false;
        }
        double frac = ((BlockKey.mix(x, y, z) ^ 0x5DEECE66DL) >>> 11) * 0x1.0p-53;
        return frac < density;
    }

    /** Deterministic per-SECTION gate: does this section get its one honeypot trap? Stable across rescans. */
    private boolean sectionTrapSelected(int cx, int sy, int cz) {
        double c = cfg().TrapChancePerSection;
        if (c <= 0.0) {
            return false;
        }
        if (c >= 1.0) {
            return true;
        }
        double frac = ((BlockKey.mix(cx, sy, cz) ^ 0x14057B7EF767814FL) >>> 11) * 0x1.0p-53;
        return frac < c;
    }

    /** The slot a section's trap ore occupies, packed like {@link #decoyLocalIndex}. */
    public static int trapLocalIndex(int cx, int sy, int cz) {
        return (int) ((BlockKey.mix(cx, sy, cz) ^ 0xA0761D6478BD642FL) >>> 23) & 0x7FFF;
    }

    /** Whether a world position is the local slot a packed index points at. */
    public static boolean isSectionSlot(int packed, int x, int y, int z) {
        int mask = ChunkUtil.SIZE - 1;
        return (x & mask) == ((packed >>> 10) & mask)
                && (y & mask) == ((packed >>> 5) & mask)
                && (z & mask) == (packed & mask);
    }

    /** Deterministic per-SECTION gate: does this section get its one buried decoy chest? Stable across rescans. */
    private boolean sectionDecoySelected(int cx, int sy, int cz) {
        double c = cfg().ProtectedDecoyChunkChance;
        if (c <= 0.0) {
            return false;
        }
        if (c >= 1.0) {
            return true;
        }
        // Same salt and same (cx,sy,cz) key the send-time path uses, so a world obfuscated by either path
        // scatters its decoys in exactly the same places.
        double frac = ((BlockKey.mix(cx, sy, cz) ^ 0x27D4EB2F165667C5L) >>> 11) * 0x1.0p-53;
        return frac < c;
    }

    /**
     * The one position inside a section its decoy may occupy, packed as {@code lx<<10 | ly<<5 | lz}.
     *
     * <p>Derived from the section coordinates rather than "the first buried rock the scan happens to reach", so
     * the decoy — like the rest of the field — can be recognised later from its position alone. If the drawn
     * spot isn't buried host rock, that section simply gets no decoy (which just makes the effective chest rate
     * a little lower than {@code ProtectedDecoyChunkChance}).
     */
    public static int decoyLocalIndex(int cx, int sy, int cz) {
        return (int) ((BlockKey.mix(cx, sy, cz) ^ 0x9E3779B97F4A7C15L) >>> 23) & 0x7FFF;
    }

    /** Whether a world position is the local slot {@link #decoyLocalIndex} drew for its section. */
    public static boolean isDecoyLocal(int packed, int x, int y, int z) {
        return isSectionSlot(packed, x, y, z);
    }

    /** Recomputes whether (x,y,z) is its section's decoy slot — the reverse of the scan's placement test. */
    private boolean isDecoyPosition(int x, int y, int z) {
        if (!catalog.hasDecoys()) {
            return false;
        }
        int size = ChunkUtil.SIZE;
        int cx = ChunkUtil.chunkCoordinate(x);
        int cz = ChunkUtil.chunkCoordinate(z);
        int sy = Math.floorDiv(y, size);
        return sectionDecoySelected(cx, sy, cz) && isDecoyLocal(decoyLocalIndex(cx, sy, cz), x, y, z);
    }

    // ------------------------------------------------------------------ reveal on break (world thread)

    /**
     * Called from the break handler on the world thread. Re-sends the REAL block for every position within
     * {@code RevealRadius} of the broken block (a safety buffer, so mining never uncovers a fake at an angle),
     * and reports whether the broken block itself was a honeypot for this player.
     */
    public boolean revealAround(PlayerRef pr, World world, int bx, int by, int bz) {
        PlayerView view = views.get(pr.getUuid());
        // Honeypot hit: the block being broken is one of this section's rare baits, and this client was
        // actually sent the obfuscated form of that section (so it really was showing them an ore).
        boolean wasTrap = wasObfuscatedFor(view, bx, by, bz) && isArmedTrap(world, bx, by, bz);

        PacketHandler ph = pr.getPacketHandler();
        if (ph != null) {
            // Re-send only the block classes we ever touch: a masked real ore/valuable, or host rock the field
            // would have drawn as a fake. Everything else — furniture, dirt, connected blocks — we never
            // changed, so its client value already matches the world; re-pushing it via a plain ServerSetBlock
            // (no rotation/state) is what duplicated/desynced nearby furniture & connected blocks.
            //
            // Re-sending a position we did NOT actually change (an ore that was exposed anyway, rock outside a
            // scanned section) is a no-op on the client: it is the block the client already has.
            //
            // Revealing the masks is what makes mining work at all: the block a player just uncovered stops
            // being enclosed, the rescan then skips it (it only touches enclosed blocks), so this is the ONLY
            // chance to put the real ore back on their screen.
            int r = Math.max(1, cfg().RevealRadius);
            List<ToClientPacket> batch = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int x = bx + dx;
                        int y = by + dy;
                        int z = bz + dz;
                        try {
                            int realN = world.getBlock(x, y, z);
                            if (catalog.isAir(realN)) {
                                continue;
                            }
                            boolean ours = catalog.isKnownOreId(realN) || catalog.isProtectedBlock(realN)
                                    || isFieldCandidate(realN, x, y, z);
                            if (ours) {
                                addRealBlock(batch, world, x, y, z, realN);
                            }
                        } catch (Exception ignored) {
                            // leaving the client value is harmless; a rescan will correct it
                        }
                    }
                }
            }
            flush(ph, batch);
        }
        return wasTrap;
    }

    /**
     * Queues the TRUE block at a position, carrying its real rotation and filler exactly like vanilla's own
     * {@code ChunkSystems$ReplicateChanges} does. Sending 0/0 instead is only harmless for a block we ourselves
     * had already flattened; a reveal also re-sends blocks we may never have touched, and resetting a rotated
     * rock's orientation on the client would be a visible desync.
     */
    private void addRealBlock(List<ToClientPacket> batch, World world, int x, int y, int z, int blockId) {
        short filler = 0;
        byte rotation = 0;
        try {
            var chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
            if (chunk != null) {
                filler = (short) chunk.getFiller(x, y, z);
                rotation = (byte) chunk.getRotationIndex(x, y, z);
            }
        } catch (Exception ignored) {
            // fall back to the neutral state rather than skipping the block entirely
        }
        batch.add(new ServerSetBlock(x, y, z, blockId, filler, rotation));
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * DIAGNOSTIC (admin panel &rarr; Tools &rarr; "Nearest traps"). The obfuscated field lives entirely in
     * <em>buried</em> blocks, so a normal client renders nothing at all — which is indistinguishable from "the
     * plugin isn't working". This lists the honeypots actually armed in <em>this</em> player's client view,
     * nearest first, with the fake ore each position now shows. Fly to one and dig two blocks: you should find
     * that fake ore, and breaking it should fire a honeypot alert.
     *
     * <p>Recomputed by scanning the blocks around the player (the field is a pure function of position — see
     * {@link #isArmedFake}), so it reports what the client is actually being shown rather than a bookkeeping
     * side-table that could disagree with it.
     */
    public void reportNearestTraps(PlayerRef pr, int radius, int limit) {
        World world = Universe.get().getWorld(pr.getWorldUuid());
        if (world == null) {
            return;
        }
        int r = Math.max(4, Math.min(48, radius));
        PlayerView view = views.get(pr.getUuid());
        world.execute(() -> {
            var pos = pr.getTransform().getPosition();
            int px = (int) Math.floor(pos.x);
            int py = (int) Math.floor(pos.y);
            int pz = (int) Math.floor(pos.z);
            List<BlockKey> found = new ArrayList<>();
            for (int x = px - r; x <= px + r; x++) {
                for (int y = py - r; y <= py + r; y++) {
                    for (int z = pz - r; z <= pz + r; z++) {
                        if (wasObfuscatedFor(view, x, y, z) && isArmedTrap(world, x, y, z)) {
                            found.add(new BlockKey(x, y, z));
                        }
                    }
                }
            }
            found.sort(Comparator.comparingLong(k -> {
                long dx = k.x() - px;
                long dy = k.y() - py;
                long dz = k.z() - pz;
                return dx * dx + dy * dy + dz * dz;
            }));
            pr.sendMessage(ChatUtil.info(dev.stoshe.antixray.util.Tr.t("msg.traps_header", "n", found.size())));
            for (int i = 0; i < Math.min(limit, found.size()); i++) {
                BlockKey k = found.get(i);
                double dist = Math.sqrt(Math.pow(k.x() - px, 2) + Math.pow(k.y() - py, 2)
                        + Math.pow(k.z() - pz, 2));
                pr.sendMessage(ChatUtil.info(k + "  (" + Math.round(dist) + "m)  → "
                        + BlockCatalog.nameOf(catalog.fakeOreFor(k))));
            }
        });
    }

    /**
     * ADMIN AUDIT (panel &rarr; Tools &rarr; "X-ray audit"). Turns the plain rock around the admin see-through
     * <em>for that admin only</em>, for a few seconds, WITHOUT touching anything the obfuscator placed. So
     * whatever ore blocks show through the glass are exactly what this client currently believes is down there:
     * if the field is live you see the scattered fake ores (and no real ones, since those are masked); if you
     * see the real ore layout instead, the field never reached the client. It answers "is it working?" without
     * needing an X-ray resource pack.
     *
     * <p>Skips honeypot positions and known ores so the view itself can neither reveal nor destroy the field.
     */
    public void xrayAudit(PlayerRef pr, int radius, int seconds) {
        World world = Universe.get().getWorld(pr.getWorldUuid());
        if (world == null) {
            return;
        }
        int r = Math.max(4, Math.min(40, radius));
        int secs = Math.max(2, Math.min(60, seconds));
        world.execute(() -> {
            PacketHandler ph = pr.getPacketHandler();
            if (ph == null) {
                return;
            }
            int glass = catalog.seeThroughId();
            var pos = pr.getTransform().getPosition();
            int px = (int) Math.floor(pos.x);
            int py = (int) Math.floor(pos.y);
            int pz = (int) Math.floor(pos.z);
            int depth = Math.max(1, cfg().CoverDepth);
            Slab slab = Slab.read(world, px - r - depth, py - r - depth, pz - r - depth,
                    2 * r + 1 + 2 * depth, 2 * r + 1 + 2 * depth, 2 * r + 1 + 2 * depth, catalog.emptyId());
            List<ToClientPacket> batch = new ArrayList<>();
            List<int[]> touched = new ArrayList<>();
            for (int x = px - r; x <= px + r; x++) {
                for (int y = py - r; y <= py + r; y++) {
                    for (int z = pz - r; z <= pz + r; z++) {
                        if (batch.size() >= MAX_PACKETS_PER_CHUNK) {
                            break;
                        }
                        int real = slab.get(x, y, z);
                        if (catalog.isAir(real) || real == glass) {
                            continue;
                        }
                        // Never touch what the obfuscator owns: its honeypots, or a masked real ore.
                        if (catalog.isKnownOreId(real) || catalog.isProtectedBlock(real)
                                || isFieldCandidate(real, x, y, z) || isTrapPosition(x, y, z)) {
                            continue;
                        }
                        batch.add(new ServerSetBlock(x, y, z, glass, (short) 0, (byte) 0));
                        touched.add(new int[] {x, y, z});
                    }
                }
            }
            flush(ph, batch);
            pr.sendMessage(ChatUtil.success(dev.stoshe.antixray.util.Tr.t("msg.audit_on",
                    "n", touched.size(), "secs", secs)));
            if (scheduler != null && !touched.isEmpty()) {
                scheduler.schedule(() -> world.execute(() -> {
                    PacketHandler h = pr.getPacketHandler();
                    if (h == null) {
                        return;
                    }
                    List<ToClientPacket> revert = new ArrayList<>();
                    for (int[] p : touched) {
                        addRealBlock(revert, world, p[0], p[1], p[2], world.getBlock(p[0], p[1], p[2]));
                    }
                    flush(h, revert);
                    pr.sendMessage(ChatUtil.info(dev.stoshe.antixray.util.Tr.t("msg.audit_off")));
                }), secs, TimeUnit.SECONDS);
            }
        });
    }

    // ------------------------------------------------------------------ visual self-test

    /**
     * Demonstration/self-test: turns every solid block in a small cube around {@code pr} into a random fake
     * ore — visible to {@code pr} ONLY — then reverts to the real blocks after {@code seconds}. Deliberately
     * includes exposed blocks so the admin can confirm the per-player packet swap works.
     */
    public void testFlash(PlayerRef pr, int radius, int seconds) {
        World world = Universe.get().getWorld(pr.getWorldUuid());
        if (world == null) {
            return;
        }
        int r = Math.max(1, Math.min(10, radius));
        int secs = Math.max(2, Math.min(60, seconds));
        world.execute(() -> {
            PacketHandler ph = pr.getPacketHandler();
            if (ph == null) {
                return;
            }
            var pos = pr.getTransform().getPosition();
            int px = (int) Math.floor(pos.x);
            int py = (int) Math.floor(pos.y);
            int pz = (int) Math.floor(pos.z);
            List<ToClientPacket> fake = new ArrayList<>();
            List<int[]> touched = new ArrayList<>();
            for (int x = px - r; x <= px + r; x++) {
                for (int z = pz - r; z <= pz + r; z++) {
                    if (world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z)) == null) {
                        continue;
                    }
                    for (int y = py - r; y <= py + r; y++) {
                        int real = world.getBlock(x, y, z);
                        if (catalog.isAir(real)) {
                            continue;
                        }
                        int f = catalog.fakeOreFor(new BlockKey(x, y, z));
                        fake.add(new ServerSetBlock(x, y, z, f, (short) 0, (byte) 0));
                        touched.add(new int[] {x, y, z});
                    }
                }
            }
            flush(ph, fake);
            pr.sendMessage(ChatUtil.success(dev.stoshe.antixray.util.Tr.t("msg.test_flash",
                    "n", touched.size(), "secs", secs)));
            if (scheduler != null && !touched.isEmpty()) {
                scheduler.schedule(() -> world.execute(() -> {
                    PacketHandler h = pr.getPacketHandler();
                    if (h == null) {
                        return;
                    }
                    List<ToClientPacket> revert = new ArrayList<>();
                    for (int[] p : touched) {
                        addRealBlock(revert, world, p[0], p[1], p[2], world.getBlock(p[0], p[1], p[2]));
                    }
                    flush(h, revert);
                    pr.sendMessage(ChatUtil.info(dev.stoshe.antixray.util.Tr.t("msg.test_reverted")));
                }), secs, TimeUnit.SECONDS);
            }
        });
    }

    private void flush(PacketHandler ph, List<ToClientPacket> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            // writeNoCache, like vanilla's block replication: these packets are unique per player, so the
            // shared cached-packet path is both wasteful and (for per-player fakes) wrong — PacketHandler.write
            // wraps everything it is handed in a CachedPacket.
            //
            // writeNoCache on its own, though, does a channel writeAndFlush PER PACKET (PacketHandler.writePacket
            // takes the writeAndFlush branch whenever queuePackets is false) — that is one flush per block. For a
            // batch we queue and flush once at the end instead. The flag only controls WHEN the bytes leave, not
            // their order or delivery, so the worst case if another subsystem toggles it concurrently is a missed
            // batching opportunity — never a lost or reordered packet.
            boolean queue = batch.size() >= QUEUE_THRESHOLD;
            if (queue) {
                ph.setQueuePackets(true);
            }
            try {
                for (ToClientPacket p : batch) {
                    ph.writeNoCache(p);
                }
            } finally {
                if (queue) {
                    ph.setQueuePackets(false);
                    ph.tryFlush();
                }
            }
        } catch (Exception e) {
            Console.warning("Failed to send " + batch.size() + " anti-xray packets: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ block snapshot

    /**
     * A one-shot, world-coordinate cuboid snapshot of block ids, so the obfuscation scan's occlusion checks read
     * an array instead of hammering {@code world.getBlock}. Reads outside the captured region (never expected —
     * the region is padded to cover every access) return air.
     *
     * <p>Filled column by column, resolving the chunk ONCE per (x,z) instead of once per block:
     * {@code world.getBlock} is {@code indexChunkFromBlock} + a chunk-map lookup + the chunk's own read, and a
     * ~70-block column repeats that lookup for every single block. One chunk handle per column turns ~89k map
     * lookups per chunk into ~1.3k.
     *
     * <p>An unloaded chunk reads as air, which the scan treats as "exposed" — the conservative direction: we
     * skip obfuscating next to terrain we can't see, rather than placing a fake that might end up visible.
     */
    private static final class Slab {
        private final int x0, y0, z0, sx, sy, sz;
        private final int[] data;
        private final int air;

        private Slab(int x0, int y0, int z0, int sx, int sy, int sz, int[] data, int air) {
            this.x0 = x0; this.y0 = y0; this.z0 = z0;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.data = data; this.air = air;
        }

        static Slab read(World world, int x0, int y0, int z0, int sx, int sy, int sz, int air) {
            int[] data = new int[sx * sy * sz];
            for (int x = 0; x < sx; x++) {
                int wx = x0 + x;
                for (int z = 0; z < sz; z++) {
                    int wz = z0 + z;
                    var chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(wx, wz));
                    // Y is the contiguous axis: the column below fills it sequentially, and the occlusion
                    // checks read y±d most often.
                    int base = (x * sz + z) * sy;
                    if (chunk == null) {
                        java.util.Arrays.fill(data, base, base + sy, air);
                        continue;
                    }
                    for (int y = 0; y < sy; y++) {
                        int v;
                        try {
                            v = chunk.getBlock(wx, y0 + y, wz);
                        } catch (Exception e) {
                            v = air; // out of world (e.g. above build height) → treated as exposed
                        }
                        data[base + y] = v;
                    }
                }
            }
            return new Slab(x0, y0, z0, sx, sy, sz, data, air);
        }

        int get(int x, int y, int z) {
            int lx = x - x0, ly = y - y0, lz = z - z0;
            if (lx < 0 || lx >= sx || ly < 0 || ly >= sy || lz < 0 || lz >= sz) {
                return air;
            }
            return data[(lx * sz + lz) * sy + ly];
        }
    }

    // ------------------------------------------------------------------ per-player state

    private static final class PlayerView {
        /**
         * chunk key -> bitmask of the chunk's 32-block sections already obfuscated for this player.
         *
         * <p>A section is world-aligned, so "done" is permanent for as long as the client holds the chunk —
         * unlike the old "last obfuscated at player Y" value, which forced a full re-scan of the column every
         * time the player moved a VerticalRadius vertically.
         *
         * <p>Concurrent, not a plain HashMap: the world thread reads/writes it inside obfuscateAround while the
         * scheduler thread can clear every view's map at once when the block catalog resolves new ore ids. A
         * HashMap under that would risk a corrupt read or a spin inside a resize.
         */
        final Map<Long, Long> doneChunks = new ConcurrentHashMap<>();
        volatile String worldName = "";
        volatile boolean busy;
        /** Set once when a chunk hit the packet cap, so that warning is logged one time per player too. */
        volatile boolean truncWarned;
        /** When this player was first seen in-world (ms) — used to grace-gate send-time precompute. */
        volatile long firstSeenMs;
    }
}
