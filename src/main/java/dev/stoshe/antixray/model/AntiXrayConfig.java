package dev.stoshe.antixray.model;

import java.util.List;

/**
 * AntiXray configuration model (Gson). Each nested section is a {@code public static final class}
 * with PascalCase fields initialised to safe defaults, so a missing JSON key falls back sanely.
 * Mirrors the AeroWars config conventions ({@link #getDefault()}, {@link #normalize()},
 * {@link #applyFrom(AntiXrayConfig)}).
 */
public final class AntiXrayConfig {

    public General General = new General();
    public Obfuscation Obfuscation = new Obfuscation();
    public Detection Detection = new Detection();
    public Spectate Spectate = new Spectate();

    public static final class General {
        /** Language file from lang/ (en_us, pt_br, ...). English is the default and always the fallback. */
        public String Language = "en_us";
        public boolean PrefixEnabled = true;
        public String Prefix = "{#ff5c5c}[AntiXray] {#ffffff}";
        /** Worlds where AntiXray is active. Empty = every world. */
        public List<String> EnabledWorlds = List.of();
        /** Usernames exempt from obfuscation/detection. Explicit — so ops are NOT auto-exempt. */
        public List<String> BypassPlayers = List.of();
        /**
         * Also honour the {@code antixray.bypass} permission node. Off by default because op/wildcard
         * permissions ({@code *}) would otherwise silently exempt every admin from the protection.
         */
        public boolean BypassByPermission = false;
    }

    public static final class Obfuscation {
        /** Master switch for the packet-level ore obfuscation / fake-ore field. */
        public boolean Enabled = true;
        /**
         * Delivery mode. {@code false} (default) = the classic per-player {@code ServerSetBlock} scan.
         * {@code true} = SEND-TIME: rewrite each chunk's block data as it is sent to a player (the chunk
         * arrives already-obfuscated, no correction packets, no brief real-ore flash), by cloning the section,
         * hiding ores / scattering honeypots, and re-serializing with the server's own encoder. The obfuscated
         * chunk is cached per section and shared across players, so cost scales with unique chunks, not player
         * count. Requires the obfuscation tick to precompute the cache on the world thread.
         */
        public boolean SendTimeMode = false;
        /** Radius, in CHUNKS, around each player whose nearby chunks get obfuscated. */
        public int ChunkRadius = 6;
        /** Blocks above AND below the player to obfuscate (a slab that follows them, not the whole column). */
        public int VerticalRadius = 32;
        /** Max new chunks obfuscated per player per second (spreads the load as they move). */
        public int MaxChunksPerTick = 16;
        /**
         * Absolute ceiling on the Y this ever obfuscates. This is only a CPU limiter, NOT what keeps the surface
         * clean: every fake requires a fully-buried block (see CoverDepth), so exposed surface and sky blocks
         * are skipped no matter how high this is. Set it above the tallest terrain a player will stand on —
         * 128 was BELOW the surface on ordinary worlds, so a player walking around at, say, y140 had everything
         * at and above their eye level left unprotected (the field only reached the rock well below them),
         * which reads exactly like "the plugin only works in some chunks". Lower it only to save CPU on a
         * server where play is strictly underground.
         */
        public int MaxY = 256;
        /**
         * How many solid blocks must sit between a fake ore and any air (along each axis). 1 = just not on a
         * visible face; 2+ = buried deeper so a fake never shows up right behind something you can see.
         */
        public int CoverDepth = 2;
        /**
         * On breaking a block, re-send the REAL blocks within this radius (a safety buffer) so mining never
         * uncovers a fake at an angle. Should be >= 1; keep it close to CoverDepth.
         */
        public int RevealRadius = 2;
        /**
         * Fake ores are only placed in blocks whose id starts with one of these prefixes (real rock layers),
         * so honeypots never land in dirt/gravel/sand. Empty = any hidden block.
         */
        public List<String> HoneypotHostPrefixes = List.of("Rock_");
        /**
         * @deprecated No longer used, and kept only so existing config files still parse. Honeypot positions
         *     used to be remembered per player and this capped that list — but one player's field is far larger
         *     than any sane cap, so obfuscation stopped dead a few seconds after they joined and only the
         *     chunks scanned before that had fakes. The field is now recomputed from the position when a break
         *     needs it (it is a pure function of the block and its surroundings), so nothing is stored and
         *     nothing has to be capped.
         */
        @Deprecated
        public int MaxTrapsPerPlayer = 40000;
        /** Radius (blocks) used only by the /antixray test and /antixray xray view modes. */
        public int RadiusBlocks = 24;
        /**
         * Fraction (0..1) of enclosed non-ore blocks turned into a fake ore — i.e. how dense the random
         * fake-ore field is. Deterministic per position (no flicker).
         *
         * <p>This does NOT control how well real ores are hidden: those are always masked with
         * {@code HideRealOreAs} regardless. It only sets how many honeypots exist, and a handful per chunk is
         * already enough to catch an X-ray user — so keep it low. The cost is real and scales linearly:
         * one {@link com.hypixel.hytale.protocol.packets.world.ServerSetBlock} per fake, and a client running
         * an X-ray pack must build render geometry for every one of them (ores are custom models, not plain
         * cubes). At 0.2 a chunk slab yielded ~10k fakes — enough to hit {@code MAX_PACKETS_PER_CHUNK} and, at
         * {@code MaxChunksPerTick} chunks/s, tens of thousands of packets per second per player: a packet
         * burst everyone pays for and a freeze/crash for anyone with a transparency pack.
         */
        public double FakeOreDensity = 0.03;
        /**
         * The CAMOUFLAGE palette: block names for the dense fake-ore field that fills hidden rock, so X-ray sees
         * a plausible-but-worthless ore layout instead of the real one. Entries are exact ids OR "Prefix*"
         * wildcards.
         *
         * <p>Default is the COMMON metals only. That is deliberate and is what makes the honeypot work: real
         * valuable ores are masked as rock, so once the camouflage is common-only, <em>every valuable ore an
         * X-ray user can see is a trap</em> ({@link #TrapOrePalette}). Putting valuables in here as well would
         * bury the traps under a hundred thousand identical-looking baits and detection would never fire.
         */
        public List<String> FakeOrePalette = List.of("Ore_Copper_*", "Ore_Iron_*");
        /**
         * The BAIT palette: valuable ores used for the rare honeypot traps. Entries are exact ids OR "Prefix*"
         * wildcards; keep it disjoint from {@link #FakeOrePalette} and aligned with Detection.TrackedOres.
         *
         * <p>Unlike the camouflage field, a trap is never revealed when mining approaches it — it is the one
         * thing a cheater can actually break, and breaking it is the honeypot hit. Empty = traps disabled.
         */
        public List<String> TrapOrePalette = List.of(
                "Ore_Gold_*", "Ore_Silver_*", "Ore_Cobalt_*", "Ore_Mithril_*",
                "Ore_Adamantite_*", "Ore_Thorium_*", "Ore_Onyxium_*", "Ore_Prisma");
        /**
         * Chance (0..1) that an obfuscated 32&times;32&times;32 SECTION contains ONE honeypot trap — the whole
         * trap count, deliberately tiny compared with the camouflage field.
         *
         * <p>This is the single knob that trades detection speed against false positives, because a trap is
         * never revealed: an X-ray user sees every trap through the rock and beelines to them, while an honest
         * miner can only find one by tunnelling into it blind. At the default ~0.08 (one trap per ~12 sections)
         * a player digging 3000 blocks has roughly a 1-in-8 chance of stumbling on a single trap, and
         * Detection.HoneypotFlagThreshold hits inside Detection.HoneypotWindowSeconds are needed to flag.
         * Raise it for faster detection on a server you watch closely; lower it if honest miners get flagged.
         */
        public double TrapChancePerSection = 0.08;
        /** Block name a hidden block is masked with if the fake-ore palette can't be resolved. */
        public String FallbackHideBlock = "Rock_Stone";
        /**
         * Block that hidden REAL ores are replaced with to hide them from X-ray. Plain rock, so a cheater
         * sees nothing where the valuable ores actually are (the honeypot fakes are the only ores they see).
         */
        public String HideRealOreAs = "Rock_Stone";

        // ---------------------------------------------------------------- protected blocks (chests, valuables)

        /**
         * Second protection layer for valuable NON-ORE blocks (chests, custom/craft blocks). When enabled, a
         * fully-enclosed protected block is hidden from X-ray (masked as {@link #HideProtectedAs}), and decoy
         * copies are scattered into buried rock as honeypots — breaking one flags the player, same as a fake ore.
         */
        public boolean ProtectedBlocksEnabled = true;
        /**
         * Ids OR id-PREFIXES of valuable NON-CONTAINER blocks to hide from X-ray when fully enclosed (exact id
         * or startsWith match). IMPORTANT: chests/containers can NOT go here — they carry a block-entity whose
         * model is replicated on a separate channel, so swapping the block id leaves the chest visibly poking
         * through the rock. Such block-entity blocks are auto-skipped (with a console warning); protect chests
         * via {@link #ProtectedDecoyPalette} instead. Put plain valuable blocks here (e.g. metal/gem storage
         * blocks) once confirmed with /antixray probe. Empty by default.
         */
        public List<String> ProtectedBlocks = List.of(
                // e.g. once confirmed with the probe: "Block_Metal_Gold", "Block_Metal_Mithril", "Gem_Block_Diamond"
        );
        /** Real protected blocks are masked with THIS (plain rock) so X-ray sees nothing where they are. */
        public String HideProtectedAs = "Rock_Stone";
        /**
         * Decoy blocks scattered into buried rock as honeypots (exact ids — verify with the probe). A cheater
         * using X-ray sees "buried loot" and digs to it, tripping the honeypot. Empty = no decoys (hide only).
         */
        public List<String> ProtectedDecoyPalette = List.of("Furniture_Crude_Chest_Small");
        /**
         * Chance (0..1) that an obfuscated 32&times;32&times;32 SECTION contains ONE buried decoy chest (never
         * more than one per section). This controls the ACTUAL number of chests, unlike a per-block density
         * (there is far too much rock for that).
         *
         * <p>The name says "Chunk" for config compatibility, but the unit is a section: both obfuscation paths
         * now work section by section. With ChunkRadius 6 and VerticalRadius 32 that is ~113 chunks &times; ~2.5
         * sections around a player, so 0.01 ≈ a few buried chests in the whole surrounding area — a rare "loot
         * find", not a field. One is enough to catch an X-ray user, who beelines to anything valuable.
         */
        public double ProtectedDecoyChunkChance = 0.01;
    }

    public static final class Detection {
        /** Master switch for suspicion tracking. */
        public boolean Enabled = true;
        /**
         * Blocks treated as "valuable ores" for the mining-rate heuristic — a real break of one counts toward
         * the rate window. Entries are exact ids OR "Prefix*" wildcards. Defaults auto-discover every VALUABLE
         * ore variant registered in your world (Copper/Iron are intentionally left out so normal mining of the
         * common metals doesn't false-flag). Add or narrow as you like.
         */
        public List<String> TrackedOres = List.of(
                "Ore_Gold_*", "Ore_Silver_*", "Ore_Cobalt_*", "Ore_Mithril_*",
                "Ore_Adamantite_*", "Ore_Thorium_*", "Ore_Onyxium_*", "Ore_Prisma");
        /** Sliding window (seconds) over which ore breaks are counted for the rate heuristic. */
        public int RateWindowSeconds = 120;
        /** Tracked-ore breaks within the window at/above this count flag the player as a suspect. */
        public int RateFlagThreshold = 40;
        /** Weight of a honeypot hit (breaking one of the rare trap ores) in the suspicion score. */
        public double HoneypotHitWeight = 25.0;
        /**
         * Sliding window (seconds) over which honeypot hits are counted, exactly like the ore-break rate.
         *
         * <p>Honeypot hits used to accumulate for the lifetime of the session, so the rare accident — an honest
         * miner tunnelling blind into a trap — added up over hours or days until it crossed the threshold on its
         * own. What separates a cheater from an unlucky miner is not the total, it is how many they find in a
         * short time: an X-ray user walks from trap to trap.
         */
        public int HoneypotWindowSeconds = 1800;
        /** Honeypot hits within {@link #HoneypotWindowSeconds} at/above this count flag the player on their own. */
        public int HoneypotFlagThreshold = 3;
        /** Suspicion score decays by this fraction per minute of inactivity. */
        public double ScoreDecayPerMinute = 0.15;
        /** Broadcast an alert to online admins the first time a player crosses a flag threshold. */
        public boolean AlertAdminsOnFlag = true;
    }

    public static final class Spectate {
        /** Third-person orbit distance for the follow camera (0 ≈ first-person). */
        public float CameraDistance = 4.0f;
        /** Camera smoothing: a PER-FRAME interpolation factor in (0..1]. Vanilla's camera commands use 0.2.
         *  Values above 1 overshoot every frame and make the camera spin/flip; clamped at send time. */
        public float LerpSpeed = 0.2f;
        /** Let the spectating admin look around with the mouse while attached (third person only). */
        public boolean AllowPitchControls = true;
        /** Start spectating in first person (through the suspect's eyes). Toggle in-game: Tools → Camera view. */
        public boolean FirstPerson = false;
        /** First person: how far FORWARD of the suspect's eyes to sit, so you aren't inside their head. */
        public float FirstPersonForward = 0.45f;
        /** Blocks BELOW the suspect the admin's (invisible-to-others) body is parked, to keep it out of shot. */
        public double FollowYOffset = -18.0;
    }

    // ------------------------------------------------------------------ helpers

    public static AntiXrayConfig getDefault() {
        return new AntiXrayConfig();
    }

    /** Null-fills every section so callers never NPE after a sparse config file. */
    public void normalize() {
        if (General == null) {
            General = new General();
        }
        if (Obfuscation == null) {
            Obfuscation = new Obfuscation();
        }
        if (Detection == null) {
            Detection = new Detection();
        }
        if (Spectate == null) {
            Spectate = new Spectate();
        }
        if (General.EnabledWorlds == null) {
            General.EnabledWorlds = List.of();
        }
        if (General.BypassPlayers == null) {
            General.BypassPlayers = List.of();
        }
        if (Obfuscation.FakeOrePalette == null || Obfuscation.FakeOrePalette.isEmpty()) {
            Obfuscation.FakeOrePalette = List.of("Stone");
        }
        if (Obfuscation.FallbackHideBlock == null || Obfuscation.FallbackHideBlock.isBlank()) {
            Obfuscation.FallbackHideBlock = "Stone";
        }
        if (Detection.TrackedOres == null) {
            Detection.TrackedOres = List.of();
        }
        Obfuscation.FakeOreDensity = Math.max(0.0, Math.min(1.0, Obfuscation.FakeOreDensity));
        Obfuscation.ChunkRadius = Math.max(1, Math.min(24, Obfuscation.ChunkRadius));
        Obfuscation.VerticalRadius = Math.max(4, Obfuscation.VerticalRadius);
        Obfuscation.CoverDepth = Math.max(1, Math.min(6, Obfuscation.CoverDepth));
        Obfuscation.RevealRadius = Math.max(1, Math.min(5, Obfuscation.RevealRadius));
        if (Obfuscation.HoneypotHostPrefixes == null) {
            Obfuscation.HoneypotHostPrefixes = List.of();
        }
        if (Obfuscation.ProtectedBlocks == null) {
            Obfuscation.ProtectedBlocks = List.of();
        }
        if (Obfuscation.ProtectedDecoyPalette == null) {
            Obfuscation.ProtectedDecoyPalette = List.of();
        }
        if (Obfuscation.HideProtectedAs == null || Obfuscation.HideProtectedAs.isBlank()) {
            Obfuscation.HideProtectedAs = Obfuscation.HideRealOreAs;
        }
        Obfuscation.ProtectedDecoyChunkChance = Math.max(0.0, Math.min(1.0, Obfuscation.ProtectedDecoyChunkChance));
        Obfuscation.MaxChunksPerTick = Math.max(1, Obfuscation.MaxChunksPerTick);
        Obfuscation.RadiusBlocks = Math.max(4, Obfuscation.RadiusBlocks);
        Detection.RateWindowSeconds = Math.max(10, Detection.RateWindowSeconds);
    }

    /** Copies section references in for a live {@code /antixray reload} (managers hold the shared instance). */
    public void applyFrom(AntiXrayConfig other) {
        if (other == null) {
            return;
        }
        if (other.General != null) {
            this.General = other.General;
        }
        if (other.Obfuscation != null) {
            this.Obfuscation = other.Obfuscation;
        }
        if (other.Detection != null) {
            this.Detection = other.Detection;
        }
        if (other.Spectate != null) {
            this.Spectate = other.Spectate;
        }
    }
}
