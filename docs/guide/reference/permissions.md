# Permissions

AntiXray has just two permission nodes. Protection itself needs no permission — it applies automatically to every non-bypassed player.

| Node | Default | Grants |
| --- | --- | --- |
| `antixray.admin` | denied | Open the `/antixray` panel and use every tool (spectate, probe, reload, clear). Receive [suspect alerts](/guide/protection/detection#the-suspicion-score). |
| `antixray.bypass` | denied | Exempt the player from **obfuscation and detection** — for staff and builders who should see the real world. **Only honoured when `General.BypassByPermission` is `true`.** |

## How bypass works

A player is exempt from protection if **either**:

- their username is in `General.BypassPlayers`, **or**
- `General.BypassByPermission` is `true` **and** they hold `antixray.bypass`.

::: warning Ops are not auto-exempt
By design, operators are **not** automatically bypassed — so you can verify the protection as an admin and actually see the obfuscation your players see. `BypassByPermission` is off by default because a wildcard/op permission (`*`) would otherwise grant `antixray.bypass` to every admin and silently disable protection for them.
:::

## Recommended setup

- Grant `antixray.admin` to your staff role.
- To exempt builders on a creative world, either list them in `BypassPlayers`, or set `BypassByPermission: true` and grant `antixray.bypass` to your builder role.
- Leave ops non-bypassed while you test, then add trusted staff to the bypass list once you've confirmed protection works.

See the [Config Reference](/guide/setup/config#general) for the related `General` keys.
