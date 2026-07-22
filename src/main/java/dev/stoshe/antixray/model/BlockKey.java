package dev.stoshe.antixray.model;

/**
 * An immutable integer block position usable as a hash key. Kept independent of the engine vector
 * types so it can live in maps/sets. Also exposes a stable hash used to deterministically pick a
 * fake ore for a position (so the client view doesn't flicker between rescans).
 */
public record BlockKey(int x, int y, int z) {

    /** A well-mixed, position-stable hash (independent of {@link #hashCode()} which the JDK may change). */
    public long mix() {
        return mix(x, y, z);
    }

    /**
     * The same hash without allocating a key. The hot obfuscation loop tests the density gate on every
     * candidate block but keeps only a few percent of them, so it hashes the raw coordinates and builds a
     * {@code BlockKey} only for the positions it actually records.
     */
    public static long mix(int x, int y, int z) {
        long h = 1125899906842597L;
        h = 31 * h + x;
        h = 31 * h + y;
        h = 31 * h + z;
        // finaliser (splitmix64-style) so neighbouring coords don't produce neighbouring outputs
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
