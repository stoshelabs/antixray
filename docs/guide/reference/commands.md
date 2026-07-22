# Commands & Panel

AntiXray has a deliberately tiny surface: **one command** that opens an admin panel. Every feature lives in the panel's tabs, so there's nothing else to memorize.

## The command

| Command | Alias | Description |
| --- | --- | --- |
| `/antixray` | `/ax` | Open the AntiXray admin panel. |

Requires the `antixray.admin` permission (see [Permissions](/guide/reference/permissions)). There are no player-facing commands — protection is automatic and invisible.

## Panel tabs

### Suspects

Tracked players, most-suspicious first. Each row shows score, tracked-ore count, and honeypot hits, with a `[!]` on flagged players.

| Action | Description |
| --- | --- |
| **Spectate** (per row) | Attach a live follow-camera to that suspect. See [Spectate](/guide/protection/spectate#live-spectate). |
| **Refresh** | Re-sort and re-read current suspicion data. |

### Tools

| Tool | Description |
| --- | --- |
| **Probe: ON/OFF** | Toggle probe mode — prints the id of every block **you** break, to learn ore ids. See [Block IDs & Probe](/guide/setup/block-ids). |
| **Reload config** | Re-read `config.json` and re-resolve block ids without a restart. |
| **Clear all data** | Reset every player's suspicion data. |
| **Camera view** | Flip the spectate camera between first and third person. Applies live. |
| **Stop spectating** | Detach your follow-camera and return to normal. |

Four extra **debug** buttons (Test flash, Flag online players, Nearest traps, X-ray audit) appear only when the server is started with `-Dantixray.debug=true`. See [Debug tools](/guide/protection/spectate#debug-tools).

### Status

Read-only health overview: obfuscation/detection on-off, **fake-ore ids resolved** (must be > 0), resolved protected-block and decoy ids, players tracked, active worlds, your probe mode, and who you're spectating.

## Spectator hotbar tools

Hytale sends the server no keyboard input, so while spectating your shortcuts are four items placed in your hotbar. Select one with its number key and click anywhere.

| Key | Tool | Description |
| --- | --- | --- |
| **1** | Next suspect | Jump to the next player in the suspects list. |
| **2** | Camera view | Flip between first and third person. |
| **3** | Their inventory | Open the suspect's inventory — [take (confiscate) or drop](/guide/protection/spectate#suspect-inventory) each stack. |
| **9** | Stop spectating | Detach and return to where you were. |

Your own inventory is stashed while spectating and restored when it ends, including on disconnect.

::: tip First run
After installing, your only required actions are all in the panel: **Probe** to learn ids → edit `config.json` → **Reload config** → check **Status**. Full walkthrough in [Getting Started](/guide/intro/getting-started).
:::
