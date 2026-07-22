# What is AntiXray?

**AntiXray** is a packet-level anti-cheat plugin for Hytale servers that defeats **X-ray** — the family of cheats (texture packs, modified clients, "cave finder" tools) that let a player see through solid blocks straight to the valuable ores.

It works on two fronts at once:

1. **Prevention** — it *hides* your ores from X-ray so the cheat has nothing to reveal.
2. **Detection** — it *baits* cheaters with fake ores only an X-ray user could find, then flags and lets you catch them.

Everything runs server-side from a single JAR. There is no client mod to install and nothing your honest players ever see or feel.

## How it protects, in one picture

For every player, AntiXray looks at the blocks buried around them and rewrites what **that one client** is told:

- **Real ores that are fully enclosed** are shown as plain stone. X-ray now sees *nothing* where your diamonds and adamantite actually are.
- **Fake "honeypot" ores** are scattered through the surrounding rock. To an X-ray user these look like the real prize — but a normal player, who can only see exposed faces, has no way of knowing they exist.

The world on the server is **never modified** — AntiXray only changes the block packets sent to each client, per player. Two players standing in the same tunnel can be shown completely different things, and neither affects the actual world.

::: tip The trap springs itself
Because a legitimate player physically cannot see a fully-enclosed block, tunnelling straight to a buried fake ore is something only X-ray makes possible. When a suspect breaks one, AntiXray records a **honeypot hit** — the strongest signal there is.
:::

## Highlights

- **Per-player packet obfuscation** — real ores hidden as stone, in each client's view only; the server world is untouched.
- **Fake-ore honeypot field** — a configurable density of decoy ores buried in real rock layers, deep enough that they never sit behind a surface a player could see.
- **Two detection heuristics** — honeypot hits and abnormal mining rate, combined into a single **decaying suspicion score**.
- **Quiet admin alerts** — online admins are notified when a player crosses the flag threshold; nothing is ever auto-punished.
- **Protected blocks** — the same treatment for buried non-ore valuables, plus rare **decoy chests** that bait a cheater into digging through solid rock for loot that was never there.
- **Admin panel** — a tabbed in-game UI listing suspects worst-first, with one-click **live spectate** (a real server follow-camera, first or third person) to watch before you act, and a **suspect inventory** view to confiscate the evidence.
- **Safe by design** — exposed faces are never altered, the blocks you're actively mining are always revealed as real, and breaking a block re-sends the true neighbours.
- **Zero id maintenance** — wildcard block ids auto-discover the ores your world actually registered, with a probe tool for when you want to be specific.
- **Per-world toggles, bypass lists, and English + Portuguese** localization.

## What AntiXray is *not*

- It is **not** a movement/combat anti-cheat — it targets X-ray specifically.
- It does **not** ban or punish anyone. It surfaces suspects and gives you the tools to confirm; the decision is always yours.
- It does **not** edit your world. Remove the plugin and every client instantly sees the real blocks again.

Ready to install? Head to [Getting Started](/guide/intro/getting-started). Want the mechanics in depth? Start with [Packet Obfuscation](/guide/protection/obfuscation).
