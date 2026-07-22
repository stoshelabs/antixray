# Admin Panel & Spectate

Everything an admin needs is in one place. The command `/antixray` (alias `/ax`) opens a tabbed panel — there are no other subcommands to remember. From here you review suspects, watch them live, and run the maintenance tools.

## Opening the panel

```text
/antixray      # or /ax
```

Requires the `antixray.admin` permission (see [Permissions](/guide/reference/permissions)). The panel has three tabs.

## Suspects tab

The list of tracked players, **most-suspicious first**. Each row shows the player's suspicion score, tracked-ore count, and honeypot hits, with a `[!]` on flagged players and a per-row **Spectate** button.

- **Refresh** re-sorts and re-reads the current suspicion data.
- **Spectate** attaches your camera to that player (below).

## Live spectate

Spectate is a **real server-side follow camera**, not a teleport. When you click **Spectate** on a suspect:

- Your view detaches and locks onto the target, trailing at `Spectate.CameraDistance` blocks and easing with `LerpSpeed`. If `AllowPitchControls` is on you can look around.
- Your body is teleported along with them (your client needs it nearby to receive their chunks and entity), parked `Spectate.FollowYOffset` blocks **below** them so you never see your own character in frame. You're made invulnerable while attached, and hidden from other players.
- You watch them mine in real time — the ideal way to confirm an X-ray flag before you act, because you can see whether they tunnel straight to buried ore.
- The target isn't notified and their gameplay is unaffected.
- It follows across worlds. When it ends, you're returned to exactly where you were standing when you started.

To stop, press **`9`** on your hotbar, or open the panel → **Tools → Stop spectating**. If the suspect logs off, spectating ends on its own and tells you so.

::: tip Confirm before you act
A flag is a lead. Spectating a suspect for a minute of mining is usually enough to tell deliberate wall-hacking from a lucky vein.
:::

### First and third person

**Third person** (default) trails behind the suspect and is best for reading their movement — the tell-tale beeline through solid rock.

**First person** puts you behind their eyes, which is what you want to see *what they're aiming at* as they dig.

Flip between them live with hotbar **`2`**, or panel → **Tools → Camera view**. The starting mode is `Spectate.FirstPerson`.

## Spectator HUD & hotbar tools

While spectating you get a HUD showing who you're watching, the camera mode, and their live detection score.

Hytale sends the server no keyboard input, so the spectator "shortcuts" are four **hotbar items** placed in your bar. Select one with its number key and click anywhere to fire it:

| Key | Tool | What it does |
| --- | --- | --- |
| **1** | Next suspect | Jump to the next player in the suspects list. |
| **2** | Camera view | Flip between first and third person. |
| **3** | Their inventory | Open the suspect's inventory (below). |
| **9** | Stop spectating | Detach and go back where you were. |

::: info Your gear is safe
Your real inventory is stashed when spectating starts and restored in full when it ends — including if you disconnect mid-spectate.
:::

## Suspect inventory

Hotbar **`3`** opens a live read of what the suspect is carrying, across all six inventory sections. Each stack has two actions:

| Action | What it does |
| --- | --- |
| **Take (evidence)** | Confiscates the stack. It goes into your **stashed** inventory, so you get it when you stop spectating. |
| **Drop** | Destroys the stack outright. |

Reads and edits run on the suspect's own world thread, and each stack is re-checked immediately before it's taken. If they moved it between your seeing it and your clicking, the action is **refused** rather than taking the wrong thing — refresh and try again.

This is the fastest way to settle a case: a player two minutes into a session with a stack of mithril and no matching tunnel behind them isn't ambiguous.

## Tools tab

Maintenance actions, each a single button:

| Tool | What it does |
| --- | --- |
| **Probe: ON/OFF** | Toggles probe mode. While on, every block **you** break prints its exact id in chat — used to fill `FakeOrePalette` / `TrackedOres`. See [Block IDs & Probe](/guide/setup/block-ids). |
| **Reload config** | Re-reads `config.json` and re-resolves all block ids without a restart. |
| **Clear all data** | Resets every player's suspicion data back to zero. |
| **Camera view** | Flips the spectate camera between first and third person. Applies live. |
| **Stop spectating** | Detaches your follow camera. |

### Debug tools

Four extra diagnostic buttons exist for troubleshooting, hidden unless the server is started with `-Dantixray.debug=true`:

| Tool | What it does |
| --- | --- |
| **Test flash** | Turns the blocks around you into fake ores for a few seconds, in your view only. |
| **Flag online players** | Force-flags every online player, so alerts, the suspects list and Spectate can all be exercised on a server with one account. Undo with **Clear all data**. |
| **Nearest traps** | Lists the honeypot coordinates currently armed in *your* client view. |
| **X-ray audit (15s)** | Makes the rock around you see-through **for you only**, skipping traps and known ores. Whatever ore blocks show through are the field your client actually received — this answers "is obfuscation working?" without installing an X-ray pack. |

## Status tab

A read-only overview to confirm everything's healthy:

- **Obfuscation** / **Detection** — on or off.
- **Fake-ore ids resolved** — how many `FakeOrePalette` ids matched real server blocks. **If this is 0, your ids are wrong** — nothing will be obfuscated. Fix via [probe](/guide/setup/block-ids).
- **Protected blocks** — resolved [protected-block](/guide/protection/protected-blocks) ids and decoy ids.
- **Players tracked** and **Active worlds**.
- **Your probe mode** and **who you're spectating**.

## What's next

If **Fake-ore ids resolved** is zero, your config ids don't match your server. Fix that first: [Block IDs & Probe](/guide/setup/block-ids).
