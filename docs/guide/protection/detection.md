# Honeypots & Detection

Obfuscation makes X-ray *useless*. Detection makes it *dangerous*. The same fake-ore field that hides your real ores is also a trap: the only way to find and dig a fully-enclosed fake is to see through walls.

Detection is a **best-effort, secondary** layer — it never punishes anyone automatically. It builds a suspicion picture and alerts you; the judgement stays human.

## The honeypot

A **honeypot** is a fake ore placed in fully-enclosed host rock, buried `CoverDepth` blocks deep. The important property:

> A legitimate player physically cannot see a fully-enclosed block. So tunnelling straight to one, and breaking it, is something only X-ray makes possible.

When a tracked player breaks a block that was a honeypot **for them**, AntiXray records a **honeypot hit** — the strongest, lowest-false-positive signal it has. (Because the trap is per-player and only placed where the player couldn't legitimately see it, an honest miner essentially never trips one by chance.)

## The two heuristics

AntiXray combines two independent signals into one score:

### 1. Honeypot hits
Breaking fully-enclosed fake ores. Each hit adds `HoneypotHitWeight` to the player's suspicion score. Reaching `HoneypotFlagThreshold` hits flags the player on its own — this is the primary, high-confidence signal.

### 2. Mining rate
How many **tracked valuable ores** (`TrackedOres`) a player breaks within a sliding window of `RateWindowSeconds`. Digging an implausible number of valuable ores in a short window — the classic X-ray fingerprint — pushes the score up, and crossing `RateFlagThreshold` in the window flags the player. This catches cheats that avoid the honeypots but still beeline real ore.

## The suspicion score

Every tracked player carries a single **suspicion score** that:

- **Rises** on honeypot hits (weighted heavily) and abnormal mining rate.
- **Decays** over time at `ScoreDecayPerMinute`, so a brief spike from bad luck fades and only sustained behaviour keeps someone flagged.

When the score crosses the flag threshold and `AlertAdminsOnFlag` is on, every **online admin** gets a quiet chat alert:

```
[AntiXray] Possible X-ray: Steve (52 ores/window, 6 honeypots).
```

Nothing is kicked, banned, or rolled back. The alert is an invitation to look — ideally with [live spectate](/guide/protection/spectate).

## Reading suspects in the panel

The **Suspects** tab of the admin panel lists tracked players **most-suspicious first**, each row showing their score, tracked-ore count, and honeypot hits, with a `[!]` marker on flagged players and a one-click **Spectate** button:

```
[!] Steve   score 78  |  ores 52  |  honeypots 6     [ Spectate ]
    Alex    score 12  |  ores  9  |  honeypots 0     [ Spectate ]
```

## Tuning

Detection defaults are conservative to avoid false positives. Adjust in `config.json` under `Detection` (see the [Config Reference](/guide/setup/config#detection)):

- **Too many alerts?** Raise `HoneypotFlagThreshold` / `RateFlagThreshold`, or raise `ScoreDecayPerMinute` so scores fade faster.
- **Want it stricter?** Lower those thresholds, or raise `HoneypotHitWeight` so a single trap counts for more.
- **Honeypots not triggering?** Raise `Obfuscation.FakeOreDensity` for a thicker field, and make sure your `FakeOrePalette` ids [resolve](/guide/setup/block-ids).

::: warning Detection is a signal, not a verdict
Treat flags as leads. Confirm with spectate before acting — a flagged score means "worth a look," not "guilty."
:::

## What's next

When someone's flagged, watch them mine in real time: [Admin Panel & Spectate](/guide/protection/spectate).
