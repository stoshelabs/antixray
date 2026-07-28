# Honeypots & Detection

Obfuscation makes X-ray *useless*. Detection makes it *dangerous*: scattered through the fake-ore field are rare **traps** — valuable ores that only exist on a cheater's screen.

Detection is a **best-effort, secondary** layer — it never punishes anyone automatically. It builds a suspicion picture and alerts you; the judgement stays human.

## The honeypot

The fake ores you see through an X-ray pack come in **two tiers**, and only one of them is a trap.

| | Camouflage field | Trap |
|---|---|---|
| Config | `FakeOrePalette`, `FakeOreDensity` | `TrapOrePalette`, `TrapChancePerSection` |
| Ores used | **common** metals (copper, iron) | **valuable** ores (gold, mithril, …) |
| How many | hundreds per chunk | ~one per 12 chunk sections |
| Mining near it | **revealed** — turns back into real rock before you can reach it | **never revealed** |
| Breaking it | nothing | **honeypot hit** |

The camouflage field's job is to drown the real ore layout in plausible noise, and it steps out of the way as soon as anyone mines close to it — an honest player never even learns it was there.

The trap's job is to be the one thing a cheater can actually take. Because real valuables are masked as plain rock and the camouflage field is common metals only:

> **Every valuable ore an X-ray user can see is fake.** They cannot tell a trap from anything else, because there is nothing else.

An honest miner *can* still tunnel blind into a trap — that is the honest limit of any honeypot. It is made rare on purpose, and the flag is a **rate**, not a lifetime tally: several hits inside `HoneypotWindowSeconds`. A cheater walks from trap to trap; unlucky miners find one an hour.

## The two heuristics

AntiXray combines two independent signals into one score:

### 1. Honeypot hits
Breaking trap ores. Each hit adds `HoneypotHitWeight` to the player's suspicion score, and reaching `HoneypotFlagThreshold` hits **within `HoneypotWindowSeconds`** flags the player on its own — the primary signal.

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

- **Too many alerts?** Lower `Obfuscation.TrapChancePerSection` (fewer traps to stumble on), raise `HoneypotFlagThreshold` / `RateFlagThreshold`, or shorten `HoneypotWindowSeconds`.
- **Want it stricter?** Raise `TrapChancePerSection` so traps are denser, lower the thresholds, or raise `HoneypotHitWeight`.
- **Honeypots never triggering?** Check the **Status** tab: if `TrapOrePalette` resolved to 0 ids there are no traps at all. Also make sure `FakeOrePalette` (camouflage) and `TrapOrePalette` (bait) stay **disjoint** — putting valuables in the camouflage field buries the traps among a hundred thousand identical baits.

::: warning Detection is a signal, not a verdict
Treat flags as leads. Confirm with spectate before acting — a flagged score means "worth a look," not "guilty."
:::

## What's next

When someone's flagged, watch them mine in real time: [Admin Panel & Spectate](/guide/protection/spectate).
