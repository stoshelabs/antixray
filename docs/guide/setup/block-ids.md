# Block IDs & Probe

AntiXray's protection is only as good as the block ids it resolves — and ore ids are **not** universal. The good news is that the defaults handle this for you; this page is about confirming that, and about the cases where you want to be specific.

## Why ids matter

Ore block ids depend on the **host rock** and your **server build**. The same metal has a different id in stone, slate, basalt, magma, and so on, and a given world only registers the variants that actually generate in it.

If an id in `FakeOrePalette` or `TrackedOres` doesn't resolve it's skipped with a console warning. If **none** of them resolve, obfuscation has nothing to draw from — the **Status** tab shows `Fake-ore ids resolved: 0` and you'll see no honeypots.

## Wildcards do this for you

You don't have to maintain an id list. Entries in `FakeOrePalette` and `TrackedOres` can be exact ids **or `"Prefix*"` wildcards**, which are matched against the blocks your server has actually registered:

```json
"FakeOrePalette": ["Ore_*"],
"TrackedOres": ["Ore_Gold_*", "Ore_Mithril_*", "Ore_Adamantite_*"]
```

`"Ore_*"` picks up every ore variant in your world, whatever it's called and whatever rock it sits in. **These are the defaults**, so on most servers block ids need no attention at all — just check the Status tab.

::: warning Asset ids aren't ready at startup
Block ids can't be resolved during plugin startup — Hytale fills its asset map *after* setup, and it keeps growing as new biomes load. AntiXray re-resolves ids automatically as they appear, so a freshly discovered ore may take a moment (or a chunk load in the right biome) to register.
:::

## Confirm it worked

Open `/antixray` → **Status**:

```text
Fake-ore ids resolved: 14
```

Anything above 0 means the field is live. If it's `0`, your palette matched nothing — go back to the wildcard defaults, or probe (below) to see what your world really has.

::: tip Prove it end to end
To actually *see* the obfuscated field in your client without installing an X-ray resource pack, start the server with `-Dantixray.debug=true` and use panel → **Tools → X-ray audit**. See [Debug tools](/guide/protection/spectate#debug-tools).
:::

## The probe workflow

Probe mode logs the exact id of every block **you** break. You need it when you want to name ids explicitly — narrowing which ores are tracked, adding [protected blocks](/guide/protection/protected-blocks), or diagnosing a `resolved: 0`.

1. Join as an admin and open the panel: `/antixray` (or `/ax`).
2. **Tools → Probe: ON.**
3. Break the blocks you care about. Each one prints:

   ```
   probe: Ore_Gold_Stone (id 1234) @ 120,42,-88
   ```
4. Collect the **names** — AntiXray resolves names to ids, so names are what go in the config.
5. **Tools → Probe: OFF** when you're done.

::: tip Don't trust the asset list
`Server/BlockTypeList/Ores.json` lists *more* ids than are actually live in any given world. Probe the ores that really appear in yours and use those.
:::

## Filling the config by hand

The keys that take block ids:

- **`Obfuscation.FakeOrePalette`** — the ores honeypots are drawn from. Wide and plausible is good.
- **`Obfuscation.HideRealOreAs`** — the plain rock real ores are hidden as (default `Rock_Stone`). Make sure this one resolves.
- **`Obfuscation.HoneypotHostPrefixes`** — id prefixes that count as "real rock" a honeypot may replace (default `["Rock_"]`), so fakes never land in dirt, gravel, or sand.
- **`Detection.TrackedOres`** — the valuable ores the mining-rate heuristic counts. Copper and iron are left out by default so normal mining of the common metals doesn't false-flag.
- **`Obfuscation.ProtectedBlocks` / `ProtectedDecoyPalette`** — see [Protected Blocks](/guide/protection/protected-blocks).

Apply without a restart via **Tools → Reload config**.

## Copy-paste checklist

```text
[ ] /antixray → Status → Fake-ore ids resolved > 0     ← usually you're done here
[ ] If 0: Tools → Probe: ON, break a few ores, read the names from chat
[ ] Put those names (or "Prefix*") into FakeOrePalette + TrackedOres
[ ] Check HideRealOreAs + HoneypotHostPrefixes exist on your server
[ ] Tools → Reload config, re-check Status
```

## What's next

With ids resolved, tune the rest in the [Config Reference](/guide/setup/config).
