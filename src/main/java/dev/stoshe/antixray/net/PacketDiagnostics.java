package dev.stoshe.antixray.net;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.CachedPacket;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.world.SetChunk;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import dev.stoshe.antixray.AntiXray;
import dev.stoshe.antixray.manager.BlockCatalog;
import dev.stoshe.antixray.util.Console;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Send-time interception <em>probe</em> and codec <em>self-test</em> — the spike for the Orebfuscator-style
 * "obfuscate the chunk as it is sent" model (see {@code OREBFUSCATOR-NOTES.md}). <strong>Off by default</strong>;
 * enable with {@code -Dantixray.sendtime.probe=true}.
 *
 * <p>When on it registers an outbound observe-only {@link PlayerPacketWatcher} through {@link PacketAdapters}
 * (which fires on every outbound packet via {@code PacketHandler -> PacketAdapters.__handleOutbound}) and, for
 * the first outbound {@link SetChunk} packets, does three things:
 * <ol>
 *   <li><b>Logs the shape</b> — concrete class ({@link SetChunk} vs {@link CachedPacket}), size, shared-ness.</li>
 *   <li><b>Dumps samples</b> — the raw wire bytes + the {@code SetChunk.data} block payload to
 *       {@code <dataDir>/send-time-samples.txt}.</li>
 *   <li><b>Runs the codec self-test</b> (on the world thread) that decides whether the whole send-time rewrite
 *       is viable: it takes the live section, calls the server's own {@link BlockSection#serializeForPacket()}
 *       and checks it reproduces the packet's {@code data} byte-for-byte (the linchpin — if it matches, a
 *       replacement chunk built the same way is byte-identical to vanilla and the client must accept it), then
 *       clones the chunk, hides the real ores in the clone via {@link BlockSection#set}, re-serializes, and
 *       verifies the obfuscated bytes differ while the real world section is left untouched.</li>
 * </ol>
 *
 * <p>It never drops or mutates an outbound packet, so it is safe to ship.
 */
public final class PacketDiagnostics {

    /** JVM flag that turns the probe on: {@code -Dantixray.sendtime.probe=true}. */
    public static final String FLAG = "antixray.sendtime.probe";
    private static final int MAX_LOGS = 6;
    private static final int MAX_SAMPLES = 5;
    private static final int MAX_SELFTESTS = 3;
    private static final int MAX_DUMP_BYTES = 4 * 1024 * 1024;

    private AntiXray plugin;
    private PacketFilter handle;
    private File sampleFile;
    private final Object fileLock = new Object();
    private final AtomicInteger logged = new AtomicInteger();
    private final AtomicInteger samples = new AtomicInteger();
    private final AtomicInteger selfTests = new AtomicInteger();
    private final ConcurrentHashMap<Integer, Set<UUID>> seenBy = new ConcurrentHashMap<>();

    public static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    public void register(File dataDir, AntiXray plugin) {
        if (!enabled() || handle != null) {
            return;
        }
        this.plugin = plugin;
        this.sampleFile = new File(dataDir, "send-time-samples.txt");
        try {
            writeHeader();
            PlayerPacketWatcher watcher = this::observe;
            handle = PacketAdapters.registerOutbound(watcher);
            Console.warning("[send-time probe] ENABLED via -D" + FLAG + " — dumping " + MAX_SAMPLES
                    + " samples to " + sampleFile.getAbsolutePath() + " and running " + MAX_SELFTESTS
                    + " codec self-tests. Turn OFF in production.");
        } catch (Throwable t) {
            Console.warning("[send-time probe] failed to register: " + t);
        }
    }

    public void unregister() {
        if (handle != null) {
            try {
                PacketAdapters.deregisterOutbound(handle);
            } catch (Throwable ignored) {
                // shutting down anyway
            }
            handle = null;
        }
        seenBy.clear();
    }

    private void observe(PlayerRef player, Packet packet) {
        try {
            boolean isChunk = packet instanceof SetChunk
                    || (packet instanceof CachedPacket<?> cp && cp.getPacketType() == SetChunk.class);
            if (!isChunk) {
                return;
            }
            // Recover a SetChunk (with x/y/z + data). Direct when mutable; else deserialize the wire bytes.
            SetChunk sc = packet instanceof SetChunk s ? s : null;
            byte[] raw = sc != null ? null : serializeToBytes(packet);
            if (sc == null && raw != null) {
                sc = tryDeserialize(raw);
            }

            if (logged.get() < MAX_LOGS && logged.incrementAndGet() <= MAX_LOGS) {
                String shape = packet instanceof SetChunk
                        ? "SetChunk (mutable)"
                        : "CachedPacket<SetChunk>" + (isShared(packet, player) ? " (shared)" : "");
                Console.info("[send-time probe] chunk " + shape
                        + (sc != null ? " @ (" + sc.x + "," + sc.y + "," + sc.z + ") dataLen="
                                + (sc.data != null ? sc.data.length : -1) : " <undecoded>")
                        + " to=" + player.getUsername());
            }

            if (samples.get() < MAX_SAMPLES && samples.incrementAndGet() <= MAX_SAMPLES) {
                dumpSample(samples.get(), player, packet, sc, raw);
            }
            if (sc != null && sc.data != null && selfTests.get() < MAX_SELFTESTS
                    && selfTests.incrementAndGet() <= MAX_SELFTESTS) {
                scheduleSelfTest(selfTests.get(), player, sc.x, sc.y, sc.z, sc.data.clone());
            }
        } catch (Throwable ignored) {
            // a diagnostic must never throw on the network write path
        }
    }

    // ---------------------------------------------------------------- codec self-test (world thread)

    private void scheduleSelfTest(int n, PlayerRef player, int cx, int sy, int cz, byte[] vanillaData) {
        World world;
        try {
            world = Universe.get().getWorld(player.getWorldUuid());
        } catch (Throwable t) {
            return;
        }
        if (world == null) {
            return;
        }
        world.execute(() -> runSelfTest(n, world, cx, sy, cz, vanillaData));
    }

    private void runSelfTest(int n, World world, int cx, int sy, int cz, byte[] vanillaData) {
        String tag = "[send-time self-test " + n + "] section(" + cx + "," + sy + "," + cz + ") ";
        StringBuilder rec = new StringBuilder("\n## SELF-TEST ").append(n)
                .append(" section(").append(cx).append(',').append(sy).append(',').append(cz).append(")\n");
        try {
            WorldChunk wc = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(cx * ChunkUtil.SIZE, cz * ChunkUtil.SIZE));
            if (wc == null) {
                logBoth(tag + "chunk not loaded — skipped.", rec);
                return;
            }
            BlockChunk bc = wc.getBlockChunk();
            if (bc == null || sy < 0 || sy >= bc.getSectionCount()) {
                logBoth(tag + "no section — skipped.", rec);
                return;
            }
            BlockSection real = bc.getSectionAtIndex(sy);

            // LINCHPIN: does the server's own serializer reproduce the exact wire bytes we intercepted?
            byte[] reSer = real.serializeForPacket();
            boolean parity = Arrays.equals(reSer, vanillaData);
            int firstDiff = parity ? -1 : firstDiff(reSer, vanillaData);
            logBoth(tag + "serializeForPacket==wire? " + parity
                    + " (server=" + reSer.length + "B, wire=" + vanillaData.length + "B"
                    + (parity ? "" : ", firstDiffAt=" + firstDiff) + ")", rec);

            BlockCatalog catalog = plugin != null ? plugin.getBlockCatalog() : null;
            if (catalog == null || !catalog.hasFakeOres()) {
                logBoth(tag + "catalog not ready (no ore ids resolved) — edit test skipped.", rec);
                flushRecord(rec);
                return;
            }

            // DEEP COPY the section via its own storage codec so edits are fully isolated from the world.
            // (BlockChunk.cloneSerializable shares the section palette arrays — editing it corrupts the world.)
            BlockSection copy = new BlockSection();
            copy.deserialize(real.serialize(com.hypixel.hytale.codec.EmptyExtraInfo.EMPTY),
                    com.hypixel.hytale.codec.EmptyExtraInfo.EMPTY);

            int hideId = catalog.hideOreId();
            int hidden = 0;
            int size = ChunkUtil.SIZE;
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        if (catalog.isKnownOreId(copy.get(x, y, z))) {
                            copy.set(x, y, z, hideId);
                            hidden++;
                        }
                    }
                }
            }
            byte[] obf = copy.serializeForPacket();
            boolean changed = !Arrays.equals(obf, vanillaData);
            boolean worldUntouched = Arrays.equals(real.serializeForPacket(), reSer);
            logBoth(tag + "deep-copy: hid " + hidden + " ore blocks -> obf=" + obf.length
                    + "B, differsFromVanilla=" + changed + ", worldUntouched=" + worldUntouched, rec);
            if (parity && (hidden == 0 || changed) && worldUntouched) {
                logBoth(tag + "PASS — send-time obfuscation via serializeForPacket is viable.", rec);
            } else {
                logBoth(tag + "CHECK — parity=" + parity + " changed=" + changed
                        + " worldUntouched=" + worldUntouched + " (see OREBFUSCATOR-NOTES.md).", rec);
            }
        } catch (Throwable t) {
            logBoth(tag + "threw: " + t, rec);
        } finally {
            flushRecord(rec);
        }
    }

    private static int firstDiff(byte[] a, byte[] b) {
        int m = Math.min(a.length, b.length);
        for (int i = 0; i < m; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return m;
    }

    private void logBoth(String msg, StringBuilder rec) {
        Console.warning(msg);
        rec.append(msg).append('\n');
    }

    private void flushRecord(StringBuilder rec) {
        synchronized (fileLock) {
            try {
                Files.writeString(sampleFile.toPath(), rec.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // best-effort
            }
        }
    }

    // ---------------------------------------------------------------- sample dump

    private boolean isShared(Packet packet, PlayerRef player) {
        Set<UUID> players = seenBy.computeIfAbsent(System.identityHashCode(packet),
                k -> ConcurrentHashMap.newKeySet());
        players.add(player.getUuid());
        return players.size() > 1;
    }

    private void dumpSample(int n, PlayerRef player, Packet packet, SetChunk sc, byte[] rawMaybe) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("\n=== SAMPLE ").append(n).append(" ===\n");
        sb.append("class=").append(packet.getClass().getName())
                .append(" getId=").append(safeInt(packet::getId))
                .append(" to=").append(player.getUsername()).append('\n');
        byte[] raw = rawMaybe != null ? rawMaybe : serializeToBytes(packet);
        if (raw != null) {
            sb.append("rawLen=").append(raw.length).append('\n');
            if (raw.length <= MAX_DUMP_BYTES) {
                sb.append("RAW ").append(Base64.getEncoder().encodeToString(raw)).append('\n');
            }
        }
        if (sc != null) {
            sb.append("x=").append(sc.x).append(" y=").append(sc.y).append(" z=").append(sc.z).append('\n');
            appendField(sb, "DATA", sc.data);
            appendField(sb, "LLIGHT", sc.localLight);
            appendField(sb, "GLIGHT", sc.globalLight);
        }
        synchronized (fileLock) {
            try {
                Files.writeString(sampleFile.toPath(), sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable t) {
                Console.warning("[send-time probe] failed to write sample " + n + ": " + t);
            }
        }
    }

    private SetChunk tryDeserialize(byte[] raw) {
        for (int off : new int[] {0, 2, 4}) {
            ByteBuf buf = Unpooled.wrappedBuffer(raw);
            try {
                SetChunk sc = SetChunk.deserialize(buf, off);
                if (sc != null && sc.data != null) {
                    return sc;
                }
            } catch (Throwable ignored) {
                // wrong offset — try the next
            } finally {
                buf.release();
            }
        }
        return null;
    }

    private static byte[] serializeToBytes(Packet packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            packet.serialize(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), out);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            buf.release();
        }
    }

    private static void appendField(StringBuilder sb, String name, byte[] value) {
        if (value == null) {
            sb.append(name).append(" <null>\n");
        } else if (value.length > MAX_DUMP_BYTES) {
            sb.append(name).append(" <skipped: ").append(value.length).append(" bytes>\n");
        } else {
            sb.append(name).append('=').append(value.length).append(' ')
                    .append(Base64.getEncoder().encodeToString(value)).append('\n');
        }
    }

    private static int safeInt(java.util.function.IntSupplier s) {
        try {
            return s.getAsInt();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void writeHeader() throws Exception {
        String header = "# AntiXray send-time sample dump\n"
                + "# SetChunk.PACKET_ID=" + SetChunk.PACKET_ID + " IS_COMPRESSED=" + SetChunk.IS_COMPRESSED + "\n"
                + "# Per sample: RAW = base64 of packet.serialize(); DATA = base64 of SetChunk.data (block\n"
                + "# payload); LLIGHT/GLIGHT = light. See console for the codec self-test result.\n";
        synchronized (fileLock) {
            Files.writeString(sampleFile.toPath(), header, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}
