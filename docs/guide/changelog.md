# Changelog

All notable changes to AntiXray. Dates are in UTC. The docs always describe the **current** release.

## Server compatibility

Each release declares the range of Hytale server versions it supports in its `manifest.json`. The server enforces that range itself: outside it the plugin is **refused at load** with an incompatibility message, rather than half-working against an API it wasn't built for.

| AntiXray | Declared range | Loads on |
| --- | --- | --- |
| **{{PLUGIN_VERSION}}** (current) | `{{SERVER_VERSION}}` | `0.5.6` and any later `0.5.x` |
| 1.1.0, 1.0.0 | `*` | **any** server version — no check at all |

::: warning Server 0.6 needs AntiXray {{PLUGIN_VERSION}} or newer
`0.6.0` introduces **native spectate**, which replaces the custom follow-camera AntiXray implements on 0.5.x. The current range deliberately stops before it — see [Live spectate](/guide/protection/spectate#live-spectate) for what changes.

**If you're still on 1.0.0 or 1.1.0, upgrade before you update the server.** Those releases declare `"*"`, so they will load on 0.6 and misbehave instead of refusing. From {{PLUGIN_VERSION}} on, a server the plugin doesn't support is caught at startup.

Anything in these docs that 0.6 changes carries a <Badge type="warning" text="changes in 0.6" /> badge.
:::

## {{PLUGIN_VERSION}}

The honeypot works now. Three separate bugs meant that on a normal world, protection covered a fraction of what it claimed and detection could barely fire at all.

**Fixed: obfuscation stopped a few seconds after a player joined**

`MaxTrapsPerPlayer` capped a per-player list of every fake position at 40,000. At the default reach one player's field is roughly **100,000** positions, so the cap was hit almost immediately and new chunks stopped being obfuscated — resuming only in scraps as the client unloaded chunks. This is what "fake ores show up in random chunks, not the ones I'm standing in" was.

The field is no longer stored. Which rock becomes a fake, which ore is masked, where a section's bait sits — all of it is a pure function of the block position and the surrounding terrain, identical for every player, so the break handler **recomputes** the answer instead of looking it up. Nothing per-player is kept but which sections a client has already been sent. `MaxTrapsPerPlayer` is now unused and <Badge type="danger" text="deprecated" />; it stays in the config only so existing files keep parsing.

**Fixed: `MaxY` left the surface unprotected**

The old default of `128` sits *below* the surface on ordinary worlds. A player standing at y140 had everything at and above eye level untouched. It is now `256`. To be clear about what this knob is: it's a CPU limiter, **not** what keeps the surface clean — every fake requires a fully-buried block, so exposed terrain is skipped regardless. Lower it only on a strictly-underground server.

**Fixed: honeypots were nearly impossible to trip**

The break handler checked whether the block was still fully enclosed. It cannot be — to break a block you must be able to see its face, so the check rejected essentially every genuine hit. Traps are now recognised by position alone.

**New: the ore field has two tiers**

The single `FakeOrePalette` split in two, and that split is what makes detection trustworthy:

| | Camouflage | Trap |
| --- | --- | --- |
| Config | `FakeOrePalette`, `FakeOreDensity` | `TrapOrePalette`, `TrapChancePerSection` |
| Ores | **common** metals (copper, iron) | **valuable** ores (gold, mithril, …) |
| Density | hundreds per chunk | ~one per 12 sections |
| Mining near it | **revealed** before you reach it | **never revealed** |
| Breaking it | nothing | **honeypot hit** |

Real valuables are masked as plain rock and the camouflage field is common-only, so **every valuable ore an X-ray user can see is a trap.** They cannot tell bait from anything else, because there is nothing else. See [Honeypots & Detection](/guide/protection/detection).

**Changed: honeypot hits are a rate, not a lifetime tally**

Hits used to accumulate forever, so an honest miner who tunnelled blind into a trap once a month would eventually flag themselves. Hits are now counted inside `HoneypotWindowSeconds` (default 30 min), like the mining-rate heuristic already was. What separates a cheater isn't the total — it's how many they find in a short time. `HoneypotFlagThreshold` drops from `4` to `3` to suit the window.

**Also in this release**

- **Pinned server compatibility.** The manifest declares `{{SERVER_VERSION}}` instead of `"*"`, so AntiXray is refused at load on a server it wasn't built for — most immediately Hytale `0.6.0`, whose native spectate replaces this plugin's follow-camera.
- **One source of truth for the version.** The banner, the panel title and the GitHub update comparison all read the version the server parsed from `manifest.json`, filled in by the build from `gradle.properties`. A constant in the source could previously drift from the built jar and make the update check compare the wrong number.
- **Status tab** reports camouflage and trap ids separately (`Ore ids resolved: 12 camouflage, 8 trap`) — a non-zero camouflage count with zero traps means detection can't fire.
- `MaxChunksPerTick` raised from `8` to `16`, so the field keeps up with a player walking.
- The debug **Nearest traps** tool reads the recomputed field instead of the removed position list.

## 1.1.0

- **Update check.** On startup AntiXray asks GitHub whether a newer release exists. If there is one, a banner is printed at the **end** of the boot log — where it's actually visible rather than buried mid-startup — and admins are told in chat a few seconds after they join.
- **"What's new" popup.** Admins get the release notes for the version they're running, shown once per release, with **Close** (see it again next join) and **Don't show until next version** (persisted per admin in `changelog_seen.json`). Re-open it any time from **Tools → What's new**.
- Both lookups are asynchronous with a 5-second timeout and fail silently, so a server with no outbound internet — or one GitHub rate-limits — sees nothing at all: no errors, no startup delay, and no popup.

::: tip Upgrading from 1.0.0
The update check ships *in* this release, so 1.0.0 servers can't announce it — 1.1.0 is the first version that will tell you about future releases.
:::

## 1.0.0

Initial release.

**Protection**
- Per-player packet **obfuscation**: fully-enclosed real ores are shown as plain rock in each client's view only; the server world is never modified.
- **Fake-ore honeypot field**: decoy ores scattered through hidden host rock at a configurable density, buried `CoverDepth` deep so they're never visible to a legitimate player.
- **Wildcard block ids**: `FakeOrePalette` and `TrackedOres` accept `"Prefix*"` entries, auto-discovering the ore variants actually registered in your world — no hand-maintained id list, and no "the ids don't match my build" problem.
- **Protected blocks**: a second layer for valuable non-ore blocks — hide the buried real ones, and scatter rare buried **decoy chests** as honeypots. Container blocks are auto-excluded from hiding, since their model replicates on a channel that can't be intercepted. See [Protected Blocks](/guide/protection/protected-blocks).
- **Send-time obfuscation (experimental):** `Obfuscation.SendTimeMode` rewrites each chunk as it is sent, so it arrives already-obfuscated — no correction packets, no brief real-ore flash — using the server's own encoder, cached per section and shared across players. Off by default; falls back to the vanilla chunk on any error so terrain can't break. See [Send-time mode](/guide/protection/obfuscation#send-time-mode-experimental).
- Safety rules: exposed faces are never touched, and breaking a block re-sends the true neighbours within `RevealRadius` so mining never uncovers a fake — or leaves a real ore masked.

**Detection**
- **Honeypot-hit** signal: breaking a fully-enclosed fake ore is recorded as a high-confidence X-ray indicator.
- **Mining-rate** heuristic: tracked-ore breaks within a sliding window.
- Combined, time-decaying **suspicion score** with admin alerts on flag — nothing is ever auto-punished.

**Admin tools**
- Single command `/antixray` (alias `/ax`) opening a tabbed panel: **Suspects** (worst-first, per-row spectate), **Tools**, **Status**.
- **Live spectate**: a real server follow-camera attached to a suspect, with first/third person, a spectator HUD, cross-world following, and your own gear stashed and restored automatically.
- **Suspect inventory**: read what a suspect is carrying and confiscate or destroy individual stacks, with a re-check that refuses the action if the stack moved first.
- **Probe** tool to discover your server's exact block ids.
- Optional **debug tools** behind `-Dantixray.debug=true`, including an **X-ray audit** view that proves the fake field reached your client without needing an X-ray resource pack.

**Performance**
- The obfuscation scan reads each chunk's slab into a single local snapshot and runs its occlusion checks against it, instead of many per-block world reads.
- Work is tracked per 32-block **section** rather than per column, so moving vertically never re-scans chunks the client already has.
- Packets are batched into a single flush per chunk instead of one flush per block.

**General**
- Per-world toggles, explicit bypass list plus optional permission bypass (ops not auto-exempt).
- English + Portuguese localization; every message editable in the data folder.
