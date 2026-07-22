# Changelog

All notable changes to AntiXray. Dates are in UTC. The docs always describe the **current** release.

## Unreleased

- **Update check & release notes.** On startup AntiXray asks GitHub whether a newer release exists, printing a banner at the end of the boot log and telling admins in chat shortly after they join. Admins also get a **"What's new"** popup with the current release's notes, shown once per release, with *Close* and *Don't show until next version*. Re-open it any time from **Tools → What's new**. Every lookup is async with a 5s timeout and fails silently, so an offline server sees nothing at all.

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
