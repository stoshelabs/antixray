package dev.stoshe.antixray.net;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.CachedPacket;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.world.SetChunk;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import dev.stoshe.antixray.AntiXray;
import dev.stoshe.antixray.manager.BlockCatalog;
import dev.stoshe.antixray.model.AntiXrayConfig;
import dev.stoshe.antixray.util.Console;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SEND-TIME obfuscation path (opt-in via {@code Obfuscation.SendTimeMode}). Instead of correcting each
 * client with per-player {@code ServerSetBlock} packets after the real chunk already went out, this rewrites
 * the chunk's block data <em>as it is sent</em>: an outbound {@link PlayerPacketFilter} intercepts the vanilla
 * {@code CachedPacket<SetChunk>}, swaps in a pre-obfuscated {@link SetChunk} for that section, and drops the
 * original. The obfuscated bytes are produced by {@link SectionObfuscator} (the server's own encoder), cached
 * once per section and shared across players.
 *
 * <p>Two threads cooperate:
 * <ul>
 *   <li><b>World thread</b> — {@link #precompute} builds and caches a section's obfuscated bytes plus a
 *       snapshot of its vanilla bytes. Driven proactively by the obfuscation tick, and reactively on a filter
 *       cache miss.</li>
 *   <li><b>Network thread</b> — {@link #onOutbound} looks the section up; on a hit whose vanilla snapshot still
 *       matches the outgoing data (i.e. the chunk hasn't changed since precompute) it sends the obfuscated
 *       replacement and drops the vanilla. Any miss / mismatch / error passes the vanilla chunk through
 *       untouched, so terrain can never break.</li>
 * </ul>
 */
public final class SendTimeObfuscator {

    /** Max obfuscated sections cached per world (LRU). Bounds memory — obf byte arrays are tens of KB each. */
    private static final int MAX_SECTIONS_PER_WORLD = 1024;

    private final AntiXray plugin;
    private final BlockCatalog catalog;
    private volatile PacketFilter handle;
    /** worldName -> LRU(sectionKey -> entry). */
    private final ConcurrentHashMap<String, Map<Long, Cached>> caches = new ConcurrentHashMap<>();
    /** Guards against re-entering the filter for our own resent packet. */
    private final ThreadLocal<Boolean> inSwap = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public SendTimeObfuscator(AntiXray plugin, BlockCatalog catalog) {
        this.plugin = plugin;
        this.catalog = catalog;
    }

    private static final class Cached {
        /** 64-bit hash of the vanilla bytes at compute time — cheap freshness check without storing them. */
        final long vanillaHash;
        final byte[] obf;

        Cached(long vanillaHash, byte[] obf) {
            this.vanillaHash = vanillaHash;
            this.obf = obf;
        }
    }

    /** FNV-1a 64-bit over the byte payload — collisions negligible; a rare one just sends stale-but-valid obf. */
    private static long hash(byte[] b) {
        long h = 0xcbf29ce484222325L;
        for (byte value : b) {
            h = (h ^ (value & 0xff)) * 0x100000001b3L;
        }
        return h;
    }

    private AntiXrayConfig.Obfuscation cfg() {
        return plugin.getConfig().Obfuscation;
    }

    public boolean active() {
        return cfg().Enabled && cfg().SendTimeMode;
    }

    public void register() {
        if (handle != null) {
            return;
        }
        try {
            PlayerPacketFilter filter = this::onOutbound;
            handle = PacketAdapters.registerOutbound(filter);
            Console.info("AntiXray: send-time obfuscation filter registered.");
        } catch (Throwable t) {
            Console.warning("AntiXray: could not register send-time filter: " + t);
        }
    }

    public void unregister() {
        if (handle != null) {
            try {
                PacketAdapters.deregisterOutbound(handle);
            } catch (Throwable ignored) {
                // shutting down
            }
            handle = null;
        }
        caches.clear();
    }

    // ------------------------------------------------------------------ precompute (world thread)

    private static long sectionKey(int cx, int sy, int cz) {
        return (((long) cx & 0x1FFFFF) << 43) | (((long) cz & 0x1FFFFF) << 22) | ((long) sy & 0x3FFFFF);
    }

    private Map<Long, Cached> cacheFor(String worldName) {
        return caches.computeIfAbsent(worldName, k -> java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<Long, Cached>(256, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, Cached> eldest) {
                        return size() > MAX_SECTIONS_PER_WORLD;
                    }
                }));
    }

    /** Builds and caches the obfuscated bytes for one section. Must run on the world thread. */
    public void precompute(World world, int cx, int sy, int cz) {
        if (!active() || !catalog.hasFakeOres()) {
            return;
        }
        try {
            WorldChunk wc = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(cx * ChunkUtil.SIZE, cz * ChunkUtil.SIZE));
            if (wc == null) {
                return;
            }
            BlockChunk bc = wc.getBlockChunk();
            if (bc == null) {
                return;
            }
            SectionObfuscator.Result r = SectionObfuscator.obfuscate(world, bc, cx, sy, cz, catalog, cfg());
            if (r == null) {
                return;
            }
            cacheFor(world.getName()).put(sectionKey(cx, sy, cz), new Cached(hash(r.vanilla), r.obfuscated));
        } catch (Throwable t) {
            // best-effort; the filter falls back to vanilla on a miss
        }
    }

    // ------------------------------------------------------------------ outbound filter (network thread)

    // PacketAdapters outbound filter semantics (verified in bytecode): test() == true DROPS the packet,
    // false KEEPS/sends it. We KEEP everything except the vanilla chunk we successfully replace.
    private static final boolean KEEP = false;
    private static final boolean DROP = true;

    /** @return {@link #DROP} to drop the packet (we sent our replacement), else {@link #KEEP}. Never throws. */
    private boolean onOutbound(PlayerRef pr, Packet packet) {
        // Fast keep (runs for every outbound packet): only the vanilla chunk is a CachedPacket<SetChunk>.
        // Every other packet — and our own raw-SetChunk resends — is kept with a single instanceof.
        if (!(packet instanceof CachedPacket<?> cp) || cp.getPacketType() != SetChunk.class) {
            return KEEP;
        }
        if (Boolean.TRUE.equals(inSwap.get()) || !active()) {
            return KEEP;
        }
        try {
            World world = Universe.get().getWorld(pr.getWorldUuid());
            if (world == null) {
                return KEEP;
            }
            Map<Long, Cached> cache = caches.get(world.getName());
            // Nothing cached for this world yet (e.g. during the initial-connect chunk burst): keep instantly.
            if (cache == null || cache.isEmpty()) {
                return KEEP;
            }
            if (plugin.isBypassed(pr) || !plugin.isWorldEnabled(world.getName())) {
                return KEEP;
            }
            byte[] raw = serializeToBytes(packet);
            if (raw == null || raw.length < 13) {
                return KEEP;
            }
            // x,y,z are little-endian int32 at offsets 1/5/9 (byte 0 is a header) — read them cheaply so a
            // miss never has to deserialize the whole chunk.
            int cx = leInt(raw, 1);
            int sy = leInt(raw, 5);
            int cz = leInt(raw, 9);
            Cached e = cache.get(sectionKey(cx, sy, cz));
            if (e == null) {
                return KEEP; // not obfuscated (the tick precomputes proactively) — keep vanilla
            }
            SetChunk sc = deserialize(raw);
            if (sc == null || sc.data == null) {
                return KEEP;
            }
            if (e.vanillaHash != hash(sc.data)) {
                cache.remove(sectionKey(cx, sy, cz)); // chunk changed since precompute — keep vanilla, drop stale
                return KEEP;
            }
            SetChunk repl = new SetChunk(sc.x, sc.y, sc.z, e.obf, sc.localLight, sc.globalLight);
            var ph = pr.getPacketHandler();
            if (ph == null) {
                return KEEP;
            }
            // Send our obfuscated chunk, then drop the vanilla one. inSwap makes our raw-SetChunk resend skip
            // the filter (it isn't a CachedPacket anyway, but this is belt-and-suspenders).
            inSwap.set(Boolean.TRUE);
            try {
                ph.writeNoCache(repl);
            } finally {
                inSwap.set(Boolean.FALSE);
            }
            // Nothing to arm per player: honeypot positions and masks are recomputed from the position when a
            // break needs them (see ObfuscationManager.isArmedFake), so both paths share one source of truth.
            return DROP;
        } catch (Throwable t) {
            return KEEP; // never break chunk delivery
        }
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static byte[] serializeToBytes(Packet packet) {
        ByteBuf out = Unpooled.buffer();
        try {
            packet.serialize(out);
            byte[] raw = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), raw);
            return raw;
        } catch (Throwable t) {
            return null;
        } finally {
            out.release();
        }
    }

    private SetChunk deserialize(byte[] raw) {
        ByteBuf in = Unpooled.wrappedBuffer(raw);
        try {
            return SetChunk.deserialize(in, 0);
        } catch (Throwable t) {
            return null;
        } finally {
            in.release();
        }
    }
}
