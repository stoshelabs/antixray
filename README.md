<p align="center">
  <img src="https://github.com/stoshelabs/antixray/blob/main/antixray.png?raw=true" alt="AntiXray" width="720">
</p>

<p align="center">
  <b>Packet-level X-ray protection for Hytale.</b><br>
  Per-player ore obfuscation, a fake-ore honeypot that detects and catches cheaters, a suspect admin panel, and live spectate.
</p>

<p align="center">
  <a href="https://stoshelabs.github.io/antixray/"><img src="https://img.shields.io/badge/docs-online-6B2BEB?style=for-the-badge" alt="Documentation"></a>
  <img src="https://img.shields.io/badge/version-1.0.0-2ea44f?style=for-the-badge" alt="version 1.0.0">
  <img src="https://img.shields.io/badge/Hytale-server%20plugin-12B3A6?style=for-the-badge" alt="Hytale server plugin">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/obfuscation-per--player-1E90FF?style=flat-square" alt="per-player obfuscation">
  <img src="https://img.shields.io/badge/detection-honeypot%20%2B%20rate-1E90FF?style=flat-square" alt="honeypot + rate detection">
  <img src="https://img.shields.io/badge/i18n-en__us%20%7C%20pt__br-1E90FF?style=flat-square" alt="i18n en_us | pt_br">
</p>

<p align="center">
  📖 <b><a href="https://stoshelabs.github.io/antixray/">Read the full documentation →</a></b>
</p>

---

## Overview

AntiXray defeats **X-ray** — the cheats that let a player see through solid blocks to your ores — on two fronts at once. It **hides** your ores so X-ray has nothing to reveal, and it **baits** cheaters with fake ores only an X-ray user could find, so you can catch them.

Everything runs server-side from a single JAR. There's no client mod, and honest players never see or feel a thing.

- **Per-player packet obfuscation** — fully-enclosed real ores are shown as plain stone in each client's view only; the server world is **never** modified.
- **Fake-ore honeypots** — a field of decoy ores buried in real rock. A legit player can't see enclosed blocks, so digging to one is a giveaway of X-ray.
- **Protected blocks** — the same for buried non-ore valuables, plus rare **decoy chests** that bait a cheater into tunnelling for loot that was never there.
- **Detection** — honeypot hits + abnormal mining rate feed a decaying **suspicion score**; online admins get a quiet alert on flag. Nothing is ever auto-punished.
- **Admin panel & live spectate** — one command opens a tabbed panel listing suspects worst-first, each with a one-click server follow-camera (first or third person), a spectator HUD, and a suspect-inventory view to confiscate the evidence.
- **Safe by design** — exposed faces are never touched, the area you're mining is always revealed as real, and breaking a block re-shows the true neighbours.
- **Drop-in** — wildcard block ids auto-discover your server's ores, per-world toggles, and English + Portuguese localization.

---

## Quick start

1. Drop `AntiXray-1.0.0.jar` into your server's `mods/` folder.
2. Start the server once to generate `config.json` and the language files.
3. Confirm it resolved your world's ore ids:

```text
/antixray            # open the panel (alias /ax)
# Status → "Fake-ore ids resolved" should be > 0
```

That's it — protection is automatic from there. Ore ids aren't universal, but the default `FakeOrePalette` of `["Ore_*"]` is a wildcard that discovers whatever your world actually registered. If the count is `0`, use **Tools → Probe: ON**, break a few ores to read their real names from chat, and put those in `config.json`.

The **[Getting Started](https://stoshelabs.github.io/antixray/guide/intro/getting-started)** guide covers this in full.

---

## How it protects

| | |
| --- | --- |
| 🛡️ **Obfuscation** | Real ores are shown as plain rock in each client's view; X-ray sees stone where your ores are. |
| 🪤 **Honeypots** | A field of fake ores fills the hidden rock — the only ones X-ray can see, and all of them are traps. |
| 🧰 **Protected blocks** | Buried valuables masked as rock, and rare decoy chests that only an X-ray user would ever dig to. |
| 📈 **Detection** | Honeypot hits + mining rate → a decaying suspicion score → quiet admin alerts. |
| 👁️ **Spectate** | Watch a flagged suspect mine in real time with a server follow-camera, then confiscate the evidence from their inventory. |

Read the mechanics in depth, starting with **[Packet Obfuscation](https://stoshelabs.github.io/antixray/guide/protection/obfuscation)**.

---

## Documentation

Everything lives on the docs site:

| | |
| --- | --- |
| 🚀 [Getting Started](https://stoshelabs.github.io/antixray/guide/intro/getting-started) | Install to first protected server |
| 🛡️ [Packet Obfuscation](https://stoshelabs.github.io/antixray/guide/protection/obfuscation) · [Protected Blocks](https://stoshelabs.github.io/antixray/guide/protection/protected-blocks) · [Honeypots & Detection](https://stoshelabs.github.io/antixray/guide/protection/detection) · [Admin Panel & Spectate](https://stoshelabs.github.io/antixray/guide/protection/spectate) | How it protects |
| ⚙️ [Block IDs & Probe](https://stoshelabs.github.io/antixray/guide/setup/block-ids) · [Config Reference](https://stoshelabs.github.io/antixray/guide/setup/config) | Configuration |
| 📖 [Commands & Panel](https://stoshelabs.github.io/antixray/guide/reference/commands) · [Permissions](https://stoshelabs.github.io/antixray/guide/reference/permissions) | Reference |
| 📝 [Changelog](https://stoshelabs.github.io/antixray/guide/changelog) | What changed in each release |

---

## Language

AntiXray ships English and Brazilian Portuguese. Set the language in `config.json`:

```json
"General": { "Language": "pt_br" }
```

English is always loaded as the fallback, so a missing key can never leave a blank string. Both files are exported to `mods/Stoshe_AntiXray/lang/` on first run — edit them to customize any message, then **Tools → Reload config**. To add a language, drop a new `<code>.json` in that folder and point `Language` at it.

---

## Building from source

```sh
./gradlew jar        # → build/libs/AntiXray-<version>.jar
```

The build locates `HytaleServer.jar` from your Hytale install (or `libs/`). The docs live in [`docs/`](docs) (VitePress) and deploy to GitHub Pages automatically on push to `main`.

---

<sub>Built for Hytale by <a href="https://github.com/gitgusilva">Gustavo Will</a> · Stoshe Labs.</sub>
