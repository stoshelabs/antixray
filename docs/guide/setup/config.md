# Config Reference

AntiXray writes `config.json` to its data folder (`mods/Stoshe_AntiXray/`) on first start. Keys named `"//"` or `"//Something"` are comments and are ignored. Apply changes with **Tools → Reload config** in the panel — no restart needed.

::: tip Block ids first
Every id in this file must match your server's real block ids. If you haven't already, do the [Probe workflow](/guide/setup/block-ids) before tuning anything else.
:::

## General

```json
"General": {
  "Language": "en_us",
  "PrefixEnabled": true,
  "Prefix": "{#ff5c5c}[AntiXray] {#ffffff}",
  "EnabledWorlds": [],
  "BypassPlayers": [],
  "BypassByPermission": false
}
```

| Key | Default | Description |
| --- | --- | --- |
| `Language` | `en_us` | Language file from `lang/` (`en_us`, `pt_br`, …). English is always the fallback. See [localization](#localization). |
| `PrefixEnabled` | `true` | Whether plugin messages are prefixed. |
| `Prefix` | `[AntiXray]` | The chat prefix (supports `{#rrggbb}` colour codes). |
| `EnabledWorlds` | `[]` | Worlds where AntiXray is active. **Empty = every world.** |
| `BypassPlayers` | `[]` | Usernames exempt from obfuscation **and** detection. Explicit list — ops are **not** auto-exempt, so you can test protection as an admin. |
| `BypassByPermission` | `false` | Also honour the `antixray.bypass` permission. Off by default, because a wildcard/op permission would otherwise silently disable protection for every admin. |

## Obfuscation

Packet-level protection — the [primary defense](/guide/protection/obfuscation).

```json
"Obfuscation": {
  "Enabled": true,
  "SendTimeMode": false,
  "ChunkRadius": 6,
  "VerticalRadius": 32,
  "MaxChunksPerTick": 8,
  "MaxY": 128,
  "CoverDepth": 2,
  "RevealRadius": 2,
  "HoneypotHostPrefixes": ["Rock_"],
  "FakeOreDensity": 0.03,
  "MaxTrapsPerPlayer": 40000,   // deprecated, ignored
  "RadiusBlocks": 24,
  "FakeOrePalette": ["Ore_Copper_*", "Ore_Iron_*"],
  "TrapOrePalette": ["Ore_Gold_*", "Ore_Silver_*", "Ore_Cobalt_*", "Ore_Mithril_*",
                     "Ore_Adamantite_*", "Ore_Thorium_*", "Ore_Onyxium_*", "Ore_Prisma"],
  "TrapChancePerSection": 0.08,
  "FallbackHideBlock": "Rock_Stone",
  "HideRealOreAs": "Rock_Stone"
}
```

| Key | Default | Description |
| --- | --- | --- |
| `Enabled` | `true` | Master switch for obfuscation. |
| `SendTimeMode` | `false` | **Experimental.** Delivery mode. `false` = the classic per-player `ServerSetBlock` scan. `true` = **send-time**: each chunk is rewritten *as it is sent* so it arrives already-obfuscated — no correction packets, no brief real-ore flash, and the obfuscated chunk is cached per section and shared across players (CPU scales with unique chunks, not player count). See [Send-time mode](/guide/protection/obfuscation#send-time-mode-experimental). |
| `ChunkRadius` | `6` | Radius **in chunks** around each player whose nearby chunks get obfuscated. **Lower this first for performance.** |
| `VerticalRadius` | `32` | Blocks above **and** below the player to obfuscate — a slab that follows them, not the whole column. |
| `MaxChunksPerTick` | `8` | Max new chunks obfuscated per player per second (spreads load as they move). |
| `MaxY` | `128` | Absolute ceiling — never obfuscate above this Y, so the surface/sky stay untouched. |
| `CoverDepth` | `2` | Solid blocks required between a fake ore and any air, along each axis. `1` = just off a visible face; `2+` = buried deeper. |
| `RevealRadius` | `2` | On breaking a block, re-send real blocks within this radius (a safety buffer) so mining never uncovers a fake. Keep close to `CoverDepth`. |
| `HoneypotHostPrefixes` | `["Rock_"]` | Fake ores only replace blocks whose id starts with one of these (real rock layers), so they never land in dirt/gravel/sand. Empty = any hidden block. |
| `FakeOreDensity` | `0.03` | Fraction (0–1) of hidden rock turned into a fake ore — how thick the honeypot field is (evenly, stably distributed). This controls **only how many honeypots exist**; real ores are always hidden by `HideRealOreAs` either way. Sane range `0.01`–`0.05`. See the warning below. |
| `MaxTrapsPerPlayer` | `40000` | **Deprecated and ignored** (kept so older config files still load). Honeypot positions are no longer stored per player — they are recomputed from the position — so there is nothing left to cap. |
| `RadiusBlocks` | `24` | Block radius used **only** by the debug Test-flash / X-ray-audit views. |
| `FakeOrePalette` | common metals | The **camouflage** field. Entries are exact ids **or `"Prefix*"` wildcards**. Keep it to common ores: real valuables are masked as rock, so a common-only field is what makes every visible valuable a trap. Check the resolved count in the **Status** tab. |
| `TrapOrePalette` | valuable ores | The **bait**. Same id/wildcard syntax; keep it disjoint from `FakeOrePalette`. Traps are never revealed on approach — breaking one is the honeypot hit. Empty = traps off. |
| `TrapChancePerSection` | `0.08` | Chance a 32×32×32 section holds ONE trap. The knob that trades detection speed against false positives. |
| `FallbackHideBlock` | `Rock_Stone` | Used only if none of the palette ids resolve. |
| `HideRealOreAs` | `Rock_Stone` | Real hidden ores are shown as this (plain rock), so X-ray sees nothing at their true location. |

::: warning Keep `FakeOreDensity` low
Each fake ore is one packet in classic mode, and an X-ray user's client has to *render* every one of them as custom-model geometry. `0.2` means roughly 10 000 fakes per chunk — that stutters the server on chunk load and **freezes or crashes** any client running a transparency pack, which also tips the cheater off that the server is protected. `0.03` is about 1–1.5 k per chunk and is plenty: one honeypot hit is all you need. If you want a genuinely thick field, use [`SendTimeMode`](/guide/protection/obfuscation#send-time-mode-experimental), where density costs almost nothing on the wire.
:::

### Protected blocks

A second layer for valuable **non-ore** blocks — see [Protected Blocks](/guide/protection/protected-blocks).

```json
"ProtectedBlocksEnabled": true,
"ProtectedBlocks": [],
"HideProtectedAs": "Rock_Stone",
"ProtectedDecoyPalette": ["Furniture_Crude_Chest_Small"],
"ProtectedDecoyChunkChance": 0.01
```

| Key | Default | Description |
| --- | --- | --- |
| `ProtectedBlocksEnabled` | `true` | Master switch for the protected-block layer. |
| `ProtectedBlocks` | `[]` | Ids **or id-prefixes** of valuable non-container blocks to hide when fully enclosed. **Chests and other containers cannot go here** — they carry a block-entity whose model is replicated on a separate channel, so masking the block id leaves the chest poking through the rock. Block-entity blocks are auto-skipped with a console warning; protect chests with decoys instead. |
| `HideProtectedAs` | `Rock_Stone` | What real protected blocks are masked as. |
| `ProtectedDecoyPalette` | `["Furniture_Crude_Chest_Small"]` | Decoy blocks buried in rock as honeypots. A cheater sees "buried loot", digs to it, and trips the trap exactly like a fake ore. Empty = hide only, no decoys. |
| `ProtectedDecoyChunkChance` | `0.01` | Chance (0–1) that an obfuscated **32×32×32 section** contains **exactly one** buried decoy — never more than one per section. At the default radius that's a handful of chests in the whole area around a player: a rare find, not a field. *(The key name says "Chunk" for config compatibility; the unit is a section.)* |

## Detection

Suspicion tracking — the [honeypot + rate heuristics](/guide/protection/detection).

```json
"Detection": {
  "Enabled": true,
  "TrackedOres": ["Ore_Gold_Stone", "Ore_Mithril_Stone", "..."],
  "RateWindowSeconds": 120,
  "RateFlagThreshold": 40,
  "HoneypotHitWeight": 25.0,
  "HoneypotWindowSeconds": 1800,
  "HoneypotFlagThreshold": 3,
  "ScoreDecayPerMinute": 0.15,
  "AlertAdminsOnFlag": true
}
```

| Key | Default | Description |
| --- | --- | --- |
| `Enabled` | `true` | Master switch for detection. |
| `TrackedOres` | *(ore list)* | Valuable ores counted by the mining-rate heuristic (all host-rock variants). [Probe](/guide/setup/block-ids) them. |
| `RateWindowSeconds` | `120` | Sliding window for the mining-rate count. |
| `RateFlagThreshold` | `40` | Tracked-ore breaks within the window at/above this flag the player. |
| `HoneypotHitWeight` | `25.0` | Suspicion added per honeypot hit. |
| `HoneypotWindowSeconds` | `1800` | Sliding window over which honeypot hits count, like the ore-break rate. Stops the rare accidental hit from piling up over days into a flag. |
| `HoneypotFlagThreshold` | `3` | Honeypot hits **within that window** at/above this flag the player on their own. |
| `ScoreDecayPerMinute` | `0.15` | How fast the suspicion score fades over time. |
| `AlertAdminsOnFlag` | `true` | Notify online admins when a player is flagged. |

## Spectate

The live follow-camera used by the panel's [Spectate button](/guide/protection/spectate).

```json
"Spectate": {
  "CameraDistance": 4.0,
  "LerpSpeed": 0.2,
  "AllowPitchControls": true,
  "FirstPerson": false,
  "FirstPersonForward": 0.45,
  "FollowYOffset": -18.0
}
```

| Key | Default | Description |
| --- | --- | --- |
| `CameraDistance` | `4.0` | How far behind the target the follow-camera trails (third person). |
| `LerpSpeed` | `0.2` | Camera smoothing — a **per-frame interpolation factor between 0 and 1**, matching the value vanilla uses. Higher is snappier. Values above `1` overshoot every frame and make the camera spin, so they're clamped. |
| `AllowPitchControls` | `true` | Whether you can look around while spectating. Forced off in first person. |
| `FirstPerson` | `false` | Start the camera in first person (through the suspect's eyes) instead of third. Flip it live in-game with **Tools → Camera view**. |
| `FirstPersonForward` | `0.45` | First person only: how far in front of the suspect's eyes the camera sits, in blocks. `0` puts it inside their head. |
| `FollowYOffset` | `-18.0` | Your body is teleported along with the suspect (your client needs it there to receive their chunks and entity). This parks it this many blocks **below** them so you never see your own character in frame — there is no way to hide your own model. Chunks stream per column, so a vertical offset is free. Set `0` to stand right on them. |

## Localization

AntiXray exports `lang/en_us.json` and `lang/pt_br.json` to the data folder on first run. Set `General.Language` to the file you want; **English is always the fallback** for any missing key. Edit the files in the data folder to customize every message, then **Reload config**.

## Performance quick-reference

On busy servers, in order of impact:

1. Lower `Obfuscation.ChunkRadius`.
2. Lower `Obfuscation.VerticalRadius` and/or `MaxY`.
3. Lower `Obfuscation.FakeOreDensity` (fewer honeypot packets) — this is also the first thing to check if X-ray users report crashes.
4. Lower `Obfuscation.MaxChunksPerTick` to spread the work more thinly.

Real ores stay hidden at every one of these settings — you're only trading away honeypot coverage and how far ahead of a player the protection runs.
