package dev.stoshe.antixray.manager;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.stoshe.antixray.AntiXray;
import dev.stoshe.antixray.model.AntiXrayConfig;
import dev.stoshe.antixray.util.ChatUtil;
import dev.stoshe.antixray.util.Console;
import dev.stoshe.antixray.util.PermissionUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suspicion tracking. Two heuristics feed a per-player score:
 * <ul>
 *   <li><b>Mining rate</b> — tracked-ore breaks inside a sliding time window (blatant fast mining).</li>
 *   <li><b>Honeypot hits</b> — breaking a fully-enclosed fake ore, which a legitimate player (who only sees
 *       exposed faces) has no way of knowing was there.</li>
 * </ul>
 * The score decays over time, so a player who stops behaving suspiciously drifts back down the list.
 */
public final class DetectionManager {

    private final AntiXray plugin;
    private final ConcurrentHashMap<UUID, Record> records = new ConcurrentHashMap<>();

    public DetectionManager(AntiXray plugin) {
        this.plugin = plugin;
    }

    private AntiXrayConfig.Detection cfg() {
        return plugin.getConfig().Detection;
    }

    public void forget(UUID uuid) {
        // Keep the record for the admin panel even after disconnect; nothing to drop here.
    }

    public void clear(UUID uuid) {
        records.remove(uuid);
    }

    public void clearAll() {
        records.clear();
    }

    // ------------------------------------------------------------------ recording (world thread)

    public void recordOreBreak(PlayerRef pr, String oreName) {
        if (!cfg().Enabled) {
            return;
        }
        Record r = record(pr);
        long now = System.currentTimeMillis();
        synchronized (r) {
            r.oreTimes.addLast(now);
            r.totalOres++;
            r.lastActivity = now;
            pruneWindow(r, now);
        }
        maybeFlag(pr, r, false);
    }

    public void recordHoneypotHit(PlayerRef pr, String realBlockName) {
        if (!cfg().Enabled) {
            return;
        }
        Record r = record(pr);
        long now = System.currentTimeMillis();
        synchronized (r) {
            r.honeypotHits++;
            r.lastActivity = now;
        }
        Console.warning("Honeypot hit: " + pr.getUsername() + " broke a fake ore (real=" + realBlockName + ").");
        maybeFlag(pr, r, true);
    }

    private void maybeFlag(PlayerRef pr, Record r, boolean fromHoneypot) {
        boolean nowFlagged;
        int ores;
        int hits;
        synchronized (r) {
            ores = r.oreTimes.size();
            hits = r.honeypotHits;
            nowFlagged = ores >= cfg().RateFlagThreshold || hits >= cfg().HoneypotFlagThreshold;
        }
        if (nowFlagged && !r.flagged) {
            r.flagged = true;
            if (cfg().AlertAdminsOnFlag) {
                alertAdmins(pr, ores, hits);
            }
        }
    }

    private void alertAdmins(PlayerRef suspect, int ores, int hits) {
        alertAdmins(suspect.getUsername(), ores, hits);
    }

    private void alertAdmins(String suspectName, int ores, int hits) {
        String msg = dev.stoshe.antixray.util.Tr.t("alert.possible_xray",
                "player", suspectName, "ores", ores, "honeypots", hits);
        for (PlayerRef pr : Universe.get().getPlayers()) {
            if (pr != null && PermissionUtil.isAdmin(pr.getUuid())) {
                pr.sendMessage(ChatUtil.warning(msg));
            }
        }
        Console.warning("[ALERT] Possible X-ray: " + suspectName
                + " (" + ores + " ores in window, " + hits + " honeypot hits)");
    }

    // ------------------------------------------------------------------ test hook

    /**
     * TEST HOOK (admin panel &rarr; Tools &rarr; "Simulate suspect"). Injects a synthetic suspect so the whole
     * detection path &mdash; flag &rarr; admin chat alert &rarr; Suspects list &mdash; can be exercised on a
     * single-account local server, without a second client. The record is keyed by a deterministic UUID derived
     * from {@code name}, so repeated calls accumulate on the same fake entry. Fires the admin alert exactly like
     * a real flag when the counts cross a threshold.
     *
     * <p>Note: spectating this suspect will report "offline" &mdash; it has no in-world entity to attach the
     * camera to. That part still needs a real target (spectate yourself or a real second player).
     *
     * @return the synthetic suspect's uuid.
     */
    public UUID simulate(String name, int oreBreaks, int honeypotHits) {
        UUID uuid = UUID.nameUUIDFromBytes(
                ("antixray-test:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Record r = records.computeIfAbsent(uuid, u -> new Record(u, name));
        long now = System.currentTimeMillis();
        int ores;
        int hits;
        synchronized (r) {
            r.name = name;
            for (int i = 0; i < Math.max(0, oreBreaks); i++) {
                r.oreTimes.addLast(now);
            }
            r.totalOres += Math.max(0, oreBreaks);
            r.honeypotHits += Math.max(0, honeypotHits);
            r.lastActivity = now;
            pruneWindow(r, now);
            ores = r.oreTimes.size();
            hits = r.honeypotHits;
        }
        Console.warning("[TEST] Simulated suspect '" + name + "' (+" + oreBreaks + " ores, +"
                + honeypotHits + " honeypot hits).");
        boolean flagged = ores >= cfg().RateFlagThreshold || hits >= cfg().HoneypotFlagThreshold;
        if (flagged && !r.flagged) {
            r.flagged = true;
            if (cfg().AlertAdminsOnFlag) {
                alertAdmins(name, ores, hits);
            }
        }
        return uuid;
    }

    /**
     * TEST HOOK (admin panel &rarr; Tools &rarr; "Flag online players"). Pushes every online player over the flag
     * thresholds using their <em>real</em> uuid, so each suspect row has a live in-world entity and Spectate
     * actually attaches the camera &mdash; unlike {@link #simulate}, whose synthetic suspect has no entity.
     * Ignores {@code Detection.Enabled} so it works even with detection turned off. Undo with Tools &rarr; Clear.
     *
     * @return how many players were flagged.
     */
    public int flagOnlinePlayers() {
        int n = 0;
        for (PlayerRef pr : Universe.get().getPlayers()) {
            if (pr == null || pr.getUuid() == null) {
                continue;
            }
            forceFlag(pr);
            n++;
        }
        return n;
    }

    /** Forces one real player over both flag thresholds (real uuid, spectatable). See {@link #flagOnlinePlayers}. */
    public void forceFlag(PlayerRef pr) {
        Record r = record(pr);
        long now = System.currentTimeMillis();
        synchronized (r) {
            while (r.oreTimes.size() < cfg().RateFlagThreshold) {
                r.oreTimes.addLast(now);
                r.totalOres++;
            }
            r.honeypotHits = Math.max(r.honeypotHits, cfg().HoneypotFlagThreshold);
            r.lastActivity = now;
        }
        Console.warning("[TEST] Force-flagged real player '" + pr.getUsername() + "' as a suspect.");
        maybeFlag(pr, r, false);
    }

    // ------------------------------------------------------------------ queries

    public Snapshot get(UUID uuid) {
        Record r = records.get(uuid);
        if (r == null) {
            return null;
        }
        return snapshot(r);
    }

    /** All tracked players, most suspicious first. */
    public List<Snapshot> suspects() {
        long now = System.currentTimeMillis();
        List<Snapshot> out = new ArrayList<>();
        for (Record r : records.values()) {
            synchronized (r) {
                pruneWindow(r, now);
            }
            out.add(snapshot(r));
        }
        out.sort(Comparator.comparingDouble((Snapshot s) -> s.score).reversed());
        return out;
    }

    private Snapshot snapshot(Record r) {
        long now = System.currentTimeMillis();
        synchronized (r) {
            pruneWindow(r, now);
            int ores = r.oreTimes.size();
            double raw = r.honeypotHits * cfg().HoneypotHitWeight + ores;
            double decayed = applyDecay(raw, r.lastActivity, now);
            boolean flagged = ores >= cfg().RateFlagThreshold || r.honeypotHits >= cfg().HoneypotFlagThreshold;
            return new Snapshot(r.uuid, r.name, ores, r.honeypotHits, r.totalOres, decayed, flagged, r.lastActivity);
        }
    }

    private double applyDecay(double raw, long lastActivity, long now) {
        double minutesIdle = Math.max(0, (now - lastActivity) / 60000.0);
        double factor = Math.max(0.0, 1.0 - cfg().ScoreDecayPerMinute * minutesIdle);
        return raw * factor;
    }

    private void pruneWindow(Record r, long now) {
        long cutoff = now - cfg().RateWindowSeconds * 1000L;
        Deque<Long> q = r.oreTimes;
        while (!q.isEmpty() && q.peekFirst() < cutoff) {
            q.pollFirst();
        }
    }

    private Record record(PlayerRef pr) {
        Record r = records.computeIfAbsent(pr.getUuid(), u -> new Record(u, pr.getUsername()));
        r.name = pr.getUsername();
        return r;
    }

    // ------------------------------------------------------------------ types

    private static final class Record {
        final UUID uuid;
        volatile String name;
        final Deque<Long> oreTimes = new ArrayDeque<>();
        int honeypotHits;
        long totalOres;
        volatile long lastActivity;
        volatile boolean flagged;

        Record(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    /** An immutable read-model for commands/UI. */
    public record Snapshot(UUID uuid, String name, int oresInWindow, int honeypotHits, long totalOres,
                           double score, boolean flagged, long lastActivity) {
    }
}
