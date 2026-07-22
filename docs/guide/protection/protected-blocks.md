# Protected Blocks

Ores aren't the only thing X-ray finds. A cheater with a transparency pack can also spot **buried chests** and any valuable non-ore block you've hidden in the world — dungeon loot, a player's stash, a custom craft block.

The protected-block layer applies the same two ideas as the ore layer, to those blocks: **hide** the real ones, and **bait** with decoys.

It's controlled by the `Obfuscation.ProtectedBlocks*` keys — see the [Config Reference](/guide/setup/config#protected-blocks).

## Hiding real blocks

Any block id listed in `ProtectedBlocks` is masked with `HideProtectedAs` (plain rock, by default) in a player's client **when it is fully enclosed** — exactly the same rule the ore layer uses. Break through to it and the real block is revealed immediately, so nothing is ever hidden from a player who legitimately dug there.

Entries can be exact ids or prefixes, so `Furniture_Crude_Chest` would cover `_Small` and `_Large`.

The list is **empty by default**, and that's deliberate — see the next section for why.

## Why chests can't be hidden

::: danger Containers leak their model
A chest in Hytale is a `DrawType: Model` block carrying a **block-entity** (`ItemContainerBlock`). That block-entity's model is replicated to the client on a **separate channel** from the block itself — entity replication, for which there is no packet AntiXray can intercept.

So masking the block id to `Rock_Stone` hides the *block* but leaves the chest **model still rendering**, poking out of the stone you replaced it with. It looks broken, and it doesn't hide anything.
:::

AntiXray handles this for you rather than letting you shoot yourself in the foot: any id you add to `ProtectedBlocks` that has a server block-entity is **automatically skipped**, with a console warning telling you how many were dropped.

That leaves `ProtectedBlocks` for **plain valuable blocks** — metal or gem storage blocks, custom craft blocks, anything that renders from its block id alone. Confirm the ids with the [Probe tool](/guide/setup/block-ids) first.

Suppressing block-entity replication per player is possible in principle, but it's a large and risky feature that AntiXray does not attempt.

## Decoy chests: the trap that does work

Chests are protected the other way round — not by hiding real ones, but by **burying fake ones**.

A decoy is just a block id with **no server block-entity**, so it renders its static model from the block channel and can't leak. On the server it's plain rock; there is no container, nothing to open, and nothing to steal. To an X-ray user it looks exactly like buried loot.

They dig to it. That's a honeypot hit, scored identically to a fake ore — see [Honeypots & Detection](/guide/protection/detection). And unlike a fake ore, digging 30 blocks through solid rock to a buried chest is almost impossible to explain away.

::: tip One is enough
`ProtectedDecoyChunkChance` defaults to `0.01` — the chance that a 32×32×32 section gets **one** buried decoy, never more. Around a player that works out to a handful of chests in the entire loaded area: a rare, tempting find rather than an obviously fake field. Bury hundreds and a cheater notices the pattern and stops digging.
:::

## Verifying it

Open the panel → **Status**. The **Protected blocks** line reports how many ids resolved and how many decoy ids resolved:

```text
Protected blocks: 0 ids, 1 decoys
```

`0 ids` is the expected default. If you added ids and the count is still 0, they either didn't match your server's assets (probe them) or were block-entity blocks and got skipped — check the console.

## What's next

- [Honeypots & Detection](/guide/protection/detection) — what happens when someone digs to a decoy.
- [Config Reference](/guide/setup/config#protected-blocks) — every key on this layer.
