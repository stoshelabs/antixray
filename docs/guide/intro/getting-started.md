# Getting Started

AntiXray is a drop-in JAR. Install it, confirm one number in the Status tab, and you're protected — the defaults discover your server's ore ids on their own.

## 1. Install

1. Drop `AntiXray-1.1.0.jar` into your server's `mods/` folder.
2. Start the server once. AntiXray creates its data folder (`mods/Stoshe_AntiXray/`) with a `config.json` and the `lang/` files.
3. Confirm it loaded — the console prints the `AntiXray v1.1.0` banner on startup.

Protection starts immediately. Step 2 is the one check worth doing.

## 2. Check the ids resolved

Ore block ids are **not** universal — they depend on the host rock and your server build. AntiXray handles this with wildcards: the default `FakeOrePalette` of `["Ore_*"]` matches every ore your world actually registered, so there's normally nothing to configure.

Confirm it:

1. Join as an admin and open the panel with `/antixray` (alias `/ax`).
2. Go to **Status** and read **Fake-ore ids resolved**. Anything above `0` means the honeypot field is live.

If it says `0`, use **Tools → Probe: ON** and break a few ores — chat prints each block's real name (`probe: Ore_Gold_Stone (id 1234) @ 120,42,-88`) so you can put the right values in `config.json`, then **Tools → Reload config**. Full workflow in [Block IDs & Probe](/guide/setup/block-ids).

::: tip Ids appear as the world loads
Hytale fills its asset map after plugins start, and it grows as new biomes load. If the count looks low right after boot, fly around a bit and re-check.
:::

## 3. Verify it's working

**Status** should show `Obfuscation: ON`, `Detection: ON`, and a non-zero fake-ore count. That's the healthy state.

If you want to *see* the obfuscated field with your own eyes — without installing an X-ray resource pack — restart the server with `-Dantixray.debug=true` and use panel → **Tools → X-ray audit**. It makes the rock around you see-through for you only, so whatever ore blocks show through are exactly what your client received. See [Debug tools](/guide/protection/spectate#debug-tools).

::: info Nothing to see in normal play
The whole protection lives in **fully-enclosed** blocks. Walking around and seeing no change is the correct result — an honest player is never meant to notice anything.
:::

::: warning Test as a non-bypassed player
Ops are **not** auto-exempt from protection (by design, so you can test it). If you added yourself to `BypassPlayers` or enabled `BypassByPermission`, remove yourself before testing or you'll see the real world.
:::

## 4. Tune (optional)

The defaults are conservative and safe. If you want to adjust reach, honeypot density, or detection sensitivity, see the [Config Reference](/guide/setup/config). Common tweaks:

- **Bigger servers / performance** — lower `Obfuscation.ChunkRadius`, `VerticalRadius`, or `MaxY`.
- **More aggressive traps** — raise `Obfuscation.FakeOreDensity` a little. Keep it in the `0.01`–`0.05` range; high values crash X-ray users' clients and tip them off.
- **Fewer / more alerts** — adjust `Detection.HoneypotFlagThreshold` and `RateFlagThreshold`.
- **Portuguese** — set `General.Language` to `pt_br`.

## Next steps

- **[How it protects](/guide/protection/obfuscation)** — the obfuscation, honeypot, and detection mechanics in depth.
- **[Protected Blocks](/guide/protection/protected-blocks)** — hiding buried valuables and baiting with decoy chests.
- **[Commands & Panel](/guide/reference/commands)** — the single command, every panel tab, and the spectator hotbar tools.
- **[Permissions](/guide/reference/permissions)** — the two permission nodes.
