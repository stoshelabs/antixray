package dev.stoshe.antixray.net;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import dev.stoshe.antixray.manager.BlockCatalog;
import dev.stoshe.antixray.model.AntiXrayConfig;
import dev.stoshe.antixray.model.BlockKey;

/**
 * Builds an obfuscated copy of one 32&times;32&times;32 chunk section for the SEND-TIME path, reusing the
 * server's own encoder so the output is a byte-valid {@code SetChunk.data}. The section is deep-copied (via its
 * storage codec) so the live world is never mutated, then real ores are hidden as rock and honeypot fakes are
 * scattered into buried host rock — exactly the same rules as the classic scan, but written into the chunk
 * itself instead of sent as per-player {@code ServerSetBlock} corrections.
 *
 * <p>Must run on the world thread (it reads {@link World#getBlock}). Interior occlusion reads come from the
 * copy (free); only section-boundary neighbours touch the world. Returns {@code null} on any failure so the
 * caller can fall back to sending the untouched vanilla chunk.
 *
 * <p>The linchpin — {@code realSection.serializeForPacket()} reproducing the exact wire bytes — was verified
 * live (see {@code OREBFUSCATOR-NOTES.md}); a rebuilt section is therefore byte-identical to vanilla when
 * unedited, so the client always accepts the replacement.
 */
public final class SectionObfuscator {

    /** Result of obfuscating a section. */
    public static final class Result {
        /** {@code realSection.serializeForPacket()} at compute time — a freshness snapshot for the filter. */
        public final byte[] vanilla;
        /** The bytes to send in place of vanilla (== vanilla when nothing was hidden). */
        public final byte[] obfuscated;

        Result(byte[] vanilla, byte[] obfuscated) {
            this.vanilla = vanilla;
            this.obfuscated = obfuscated;
        }
    }

    private SectionObfuscator() {
    }

    public static Result obfuscate(World world, BlockChunk bc, int cx, int sy, int cz,
                                   BlockCatalog catalog, AntiXrayConfig.Obfuscation cfg) {
        try {
            int sectionCount = bc.getSectionCount();
            if (sy < 0 || sy >= sectionCount) {
                return null;
            }
            BlockSection real = bc.getSectionAtIndex(sy);
            byte[] vanilla = real.serializeForPacket();

            BlockSection copy = new BlockSection();
            copy.deserialize(real.serialize(EmptyExtraInfo.EMPTY), EmptyExtraInfo.EMPTY);

            int size = ChunkUtil.SIZE;
            int baseX = cx * size;
            int baseY = sy * size;
            int baseZ = cz * size;
            int depth = Math.max(1, cfg.CoverDepth);
            int maxY = cfg.MaxY;
            int hideId = catalog.hideOreId();

            boolean edited = false;
            // Decoys are per-SECTION here (send-time serializes one section at a time): at most one buried
            // chest per section, and only in a small share of sections. Its slot inside the section comes from
            // ObfuscationManager.decoyLocalIndex, the same derivation the classic path and the break handler
            // use, so all three agree on where a decoy is without anyone recording it.
            int trapLocal = dev.stoshe.antixray.manager.ObfuscationManager.trapLocalIndex(cx, sy, cz);
            boolean placeTrap = catalog.hasTrapOres()
                    && sectionGateSelected(cx, sy, cz, cfg.TrapChancePerSection, 0x14057B7EF767814FL);
            int decoyLocal = dev.stoshe.antixray.manager.ObfuscationManager.decoyLocalIndex(cx, sy, cz);
            boolean placeDecoy = catalog.hasDecoys()
                    && sectionGateSelected(cx, sy, cz, cfg.ProtectedDecoyChunkChance, 0x27D4EB2F165667C5L);

            for (int lx = 0; lx < size; lx++) {
                for (int lz = 0; lz < size; lz++) {
                    for (int ly = 0; ly < size; ly++) {
                        int wy = baseY + ly;
                        if (wy > maxY) {
                            continue;
                        }
                        int wx = baseX + lx;
                        int wz = baseZ + lz;
                        int rid = copy.get(lx, ly, lz);
                        if (catalog.isAir(rid)) {
                            continue;
                        }
                        // Same test order as the classic path (see ObfuscationManager.obfuscateChunk): classify
                        // first (one array read), and gate the honeypot field on density — pure arithmetic —
                        // before any occlusion walk. It matters more here: an occlusion read that crosses the
                        // section border falls through to world.getBlock.
                        int target;
                        if (catalog.isKnownOreId(rid)) {
                            if (isExposed(world, copy, baseX, baseY, baseZ, wx, wy, wz, catalog)) {
                                continue; // a legit player can see this ore — leave it alone
                            }
                            target = hideId;
                        } else if (catalog.isProtectedBlock(rid)) {
                            if (isExposed(world, copy, baseX, baseY, baseZ, wx, wy, wz, catalog)) {
                                continue;
                            }
                            // Real chest/valuable buried in terrain (enclosed): mask it as rock.
                            target = catalog.hideProtectedId();
                        } else {
                            if (!catalog.isHostRock(rid)) {
                                continue;
                            }
                            // This section's baits (trap ore / decoy chest) win over the camouflage field.
                            boolean trap = placeTrap && dev.stoshe.antixray.manager.ObfuscationManager
                                    .isSectionSlot(trapLocal, wx, wy, wz);
                            boolean decoy = !trap && placeDecoy && dev.stoshe.antixray.manager.ObfuscationManager
                                    .isSectionSlot(decoyLocal, wx, wy, wz);
                            if (!trap && !decoy
                                    && !fieldSelected(wx, wy, wz, cfg.FakeOreDensity, 0x5DEECE66DL)) {
                                continue;
                            }
                            // isBuried with depth >= 1 already covers the 6-neighbour exposure test.
                            if (!isBuried(world, copy, baseX, baseY, baseZ, wx, wy, wz, depth, catalog)) {
                                continue;
                            }
                            BlockKey here = new BlockKey(wx, wy, wz);
                            target = trap ? catalog.trapOreFor(here)
                                    : decoy ? catalog.decoyFor(here)
                                    : catalog.fakeOreFor(here);
                        }
                        if (target == rid || catalog.isAir(target)) {
                            continue;
                        }
                        // NB: BlockSection has NO set(x,y,z,block) 4-arg overload. The 4-arg
                        // set(int,int,int,int) is set(position,block,rotation,filler) — a positional
                        // setter. Calling set(lx,ly,lz,target) compiled but bound to that, writing
                        // garbage block ids (a Y coord) and shoving the fake into the filler slot,
                        // which crashed the client decoding packet 131 (index out of bounds). Use the
                        // 6-arg coordinate setter and keep the block's own rotation + filler.
                        int rot = copy.getRotationIndex(lx, ly, lz);
                        int filler = copy.getFiller(lx, ly, lz);
                        copy.set(lx, ly, lz, target, rot, filler);
                        edited = true;
                    }
                }
            }

            byte[] obf = edited ? copy.serializeForPacket() : vanilla;
            return new Result(vanilla, obf);
        } catch (Throwable t) {
            return null; // caller sends vanilla untouched
        }
    }

    /** Block id at world (wx,wy,wz): from the copy when inside this section, else from the live world. */
    private static int blockAt(World world, BlockSection copy, int baseX, int baseY, int baseZ,
                               int wx, int wy, int wz) {
        int lx = wx - baseX, ly = wy - baseY, lz = wz - baseZ;
        int size = ChunkUtil.SIZE;
        if (lx >= 0 && lx < size && ly >= 0 && ly < size && lz >= 0 && lz < size) {
            return copy.get(lx, ly, lz);
        }
        return world.getBlock(wx, wy, wz);
    }

    private static boolean isExposed(World world, BlockSection copy, int baseX, int baseY, int baseZ,
                                     int x, int y, int z, BlockCatalog cat) {
        return cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x + 1, y, z))
                || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x - 1, y, z))
                || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x, y + 1, z))
                || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x, y - 1, z))
                || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x, y, z + 1))
                || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x, y, z - 1));
    }

    private static boolean isBuried(World world, BlockSection copy, int baseX, int baseY, int baseZ,
                                    int x, int y, int z, int depth, BlockCatalog cat) {
        for (int d = 1; d <= depth; d++) {
            if (cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x + d, y, z))
                    || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x - d, y, z))
                    || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x, y + d, z))
                    || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x, y - d, z))
                    || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x, y, z + d))
                    || cat.isAir(blockAt(world, copy, baseX, baseY, baseZ, x, y, z - d))) {
                return false;
            }
        }
        return true;
    }

    /** Deterministic density gate for the honeypot scatter (stable per position, no flicker). The salt keeps
     * the ore-fake field and the decoy field independent so they don't collide on the same positions. */
    /**
     * Deterministic per-SECTION gate: does this section get one of the rare baits? The salt selects which bait
     * (trap ore / decoy chest) and matches ObfuscationManager's, so both paths place them identically.
     */
    private static boolean sectionGateSelected(int cx, int sy, int cz, double chance, long salt) {
        if (chance <= 0.0) {
            return false;
        }
        if (chance >= 1.0) {
            return true;
        }
        double frac = ((new BlockKey(cx, sy, cz).mix() ^ salt) >>> 11) * 0x1.0p-53;
        return frac < chance;
    }

    private static boolean fieldSelected(int x, int y, int z, double density, long salt) {
        if (density >= 1.0) {
            return true;
        }
        if (density <= 0.0) {
            return false;
        }
        double frac = ((BlockKey.mix(x, y, z) ^ salt) >>> 11) * 0x1.0p-53;
        return frac < density;
    }
}
