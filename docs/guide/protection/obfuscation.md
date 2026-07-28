# Packet Obfuscation

Obfuscation is AntiXray's primary defense. It makes X-ray useless by controlling exactly which blocks each client is *told about* — without ever changing the world on the server.

## The core idea

When a player is near buried blocks, AntiXray rewrites what **that one client** sees using per-player [`ServerSetBlock`](/guide/reference/commands) packets:

- A **real ore** that is fully enclosed is shown as **plain rock**. X-ray now sees ordinary stone where your diamonds actually are.
- A share of the surrounding **hidden rock** is shown as a **fake ore** (a honeypot). To X-ray these look like real prizes; to a normal player they're invisible, because they're fully enclosed.

The net effect for an X-ray user: the map is a field of decoy ores, and **none of them are real**. There is no signal left to dig toward.

::: info The world is never modified
AntiXray only changes the block *packets* sent to a client — never `world.setBlock`. Two players in the same tunnel can be shown different blocks, other players see nothing, and uninstalling the plugin instantly restores every client's real view.
:::

## The rules that keep it invisible to honest players

Obfuscation is deliberately constrained so a legitimate player never notices anything wrong:

| Rule | Why it matters |
| --- | --- |
| **Exposed faces are never touched.** A block with any air neighbour is left exactly as-is. | Nothing fake ever appears on a surface a player can actually see — no floating ore in an open cave. |
| **Only fully-enclosed blocks are rewritten.** | A block you could only see *through a wall* is, by definition, only visible to X-ray. That's the only thing worth hiding. |
| **Fakes are buried `CoverDepth` blocks deep.** | A honeypot never sits right behind a block you can see — you'd have to tunnel blindly to reach one. |
| **The area you're mining is revealed as real.** On breaking a block, the true neighbours within `RevealRadius` are re-sent. | Mining always uncovers the truth. You can never break into a fake ore. |

Together these mean: **an honest player only ever sees real blocks; an X-ray user only ever sees fakes.**

## How a scan works

For each protected player, AntiXray periodically looks at the blocks around them and sends a batch of corrections:

1. It considers a **slab** that follows the player — `VerticalRadius` blocks above and below — across the chunks within `ChunkRadius`, and never above the absolute `MaxY` ceiling.
2. For every solid, non-air block in range:
   - **Air, or exposed to air?** Skip it — a player can see it.
   - **A known real ore, fully enclosed?** Replace it (in this client) with plain rock (`HideRealOreAs`).
   - **Plain host rock (e.g. `Rock_*`), buried at least `CoverDepth` deep?** If it is the section's rare [trap](/guide/protection/detection) slot, turn it into a valuable ore from `TrapOrePalette`; otherwise, if the density gate picks it, turn it into a common ore from `FakeOrePalette` — camouflage.
3. The batch is sent to that one player. Work is throttled to `MaxChunksPerTick` new chunks per second, so it spreads out as the player moves and never spikes.

Each new area is done once per client; when the player re-loads the chunk (relog, teleport), it's re-applied.

## The fake-ore field

Two knobs shape what X-ray sees:

- **`FakeOrePalette`** — the ores the camouflage field is drawn from. Common metals by default, so that every *valuable* ore an X-ray user sees is one of the rare [traps](/guide/protection/detection) instead of noise.
- **`FakeOreDensity`** — the fraction of hidden host rock turned into a honeypot (evenly distributed, and *stable per position* so there's no flicker). Real ores are **always** hidden regardless of density; density only controls how thick the decoy field is.

::: warning Denser is not better
It's tempting to crank the density for a more convincing field. Don't: in classic mode every fake is a packet, and an X-ray user's client must *render* each one as custom-model geometry. Past a few percent you stutter the server on chunk load and outright **crash** anyone running a transparency pack — which tells them the server is protected, so they turn X-ray off and stop tripping your traps. The default `0.03` already puts over a thousand traps in every chunk. One hit is all you need.
:::

::: tip Check your ids resolved
Open **Status → Fake-ore ids resolved** and confirm it's greater than zero. If it isn't, nothing is being scattered — see [Block IDs & Probe](/guide/setup/block-ids).
:::

Ores aren't the only thing worth hiding. The same machinery protects buried chests and other valuables — see [Protected Blocks](/guide/protection/protected-blocks).

## Performance

The scan is bounded on every axis: a chunk radius, a vertical slab (not the whole column), a hard `MaxY` ceiling, a per-cycle packet cap, and a per-player "done" record so nothing is re-sent while the client still holds it. Work is tracked per **32-block section**, which is world-aligned — so moving up and down a mineshaft never re-scans chunks the client already has.

It runs on the world thread, and each chunk's slab is read into a local array **once** per scan so the occlusion checks hit memory instead of the world block-by-block (a large saving at Hytale's 32×32-column chunks). Packets are batched into one flush per chunk rather than one per block.

On busy servers the cheapest wins are lowering `ChunkRadius`, `VerticalRadius`, or `MaxY`. See the [Config Reference](/guide/setup/config#obfuscation) for every knob.

## Send-time mode (experimental)

By default AntiXray masks each client with per-player `ServerSetBlock` corrections sent *after* the real chunk. Setting `Obfuscation.SendTimeMode: true` switches to a different delivery model: the chunk's block data is rewritten **as it is sent to the player**, so it arrives already-obfuscated.

- **No correction packets** and **no brief window** where the real ores are visible before the masks land.
- The obfuscated chunk is built with the server's own encoder and **cached per 32×32×32 section, shared across players** — so the cost scales with *unique chunks sent*, not with player count.
- Same protection rules (exposed faces untouched, honeypots buried, mining reveals the truth) and the same detection.
- **Safety:** if anything is stale or fails, the untouched vanilla chunk is sent — terrain can never break.

It's opt-in and experimental: enable it, restart (or relog after a reload), and confirm your world renders normally. Leave it `false` if you prefer the well-worn `ServerSetBlock` path.

## What's next

The blocks you *hide* protect your ores; the fakes you *scatter* also **catch** cheaters. That's the job of [Honeypots & Detection](/guide/protection/detection).
