<p align="center">
  <img src="https://github.com/stoshelabs/antixray/blob/main/antixray.png?raw=true" alt="AntiXray" width="720">
</p>

<p align="center">
  <b>Packet-level X-ray protection for Hytale.</b><br>
  Per-player ore obfuscation, a fake-ore honeypot that detects and catches cheaters, a suspect admin panel, and live spectate.
</p>

<p align="center">
  <a href="https://stoshelabs.github.io/antixray/"><img src="https://img.shields.io/badge/docs-online-6B2BEB?style=for-the-badge" alt="Documentation"></a>
  <img src="https://img.shields.io/badge/version-1.1.0-2ea44f?style=for-the-badge" alt="version 1.1.0">
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

AntiXray defeats **X-ray** — the cheats that let a player see through solid blocks to your ores — on two fronts
at once. It **hides** your ores so X-ray has nothing to reveal, and it **baits** cheaters with fake ores only an
X-ray user could find, so you can catch them. Everything runs server-side from a single JAR: no client mod, and
honest players never see or feel a thing.

- **Per-player packet obfuscation** — enclosed real ores are shown as plain stone in each client's view only; the server world is **never** modified
- **Fake-ore honeypots** — decoy ores buried in real rock, which only X-ray can find and every one of them a trap
- **Protected blocks** — the same for buried non-ore valuables, plus rare decoy chests
- **Detection** — honeypot hits + mining rate feed a decaying suspicion score, with quiet admin alerts and nothing ever auto-punished
- **Admin panel & live spectate** — suspects worst-first, a one-click server follow-camera, and a suspect-inventory view to confiscate the evidence
- **Drop-in** — wildcard block ids auto-discover your server's ores; per-world toggles; English &amp; Portuguese

---

## Quick start

1. Drop `AntiXray-1.1.0.jar` into your server's `mods/` folder.
2. Start the server once to generate `config.json` and the language files.
3. Run `/antixray` (alias `/ax`) and check **Status → Fake-ore ids resolved** is greater than `0`.

That's it — protection is automatic from there. The **[Getting Started](https://stoshelabs.github.io/antixray/guide/intro/getting-started)** guide covers this in full, including what to do if that count is zero.

---

## Documentation

Everything lives on the docs site — mechanics, every config key, and the full command reference:

| | |
| --- | --- |
| 🚀 [Getting Started](https://stoshelabs.github.io/antixray/guide/intro/getting-started) | Install to first protected server |
| 🛡️ [Packet Obfuscation](https://stoshelabs.github.io/antixray/guide/protection/obfuscation) · [Protected Blocks](https://stoshelabs.github.io/antixray/guide/protection/protected-blocks) · [Honeypots &amp; Detection](https://stoshelabs.github.io/antixray/guide/protection/detection) · [Admin Panel &amp; Spectate](https://stoshelabs.github.io/antixray/guide/protection/spectate) | How it protects |
| ⚙️ [Block IDs &amp; Probe](https://stoshelabs.github.io/antixray/guide/setup/block-ids) · [Config Reference](https://stoshelabs.github.io/antixray/guide/setup/config) | Configuration |
| 📖 [Commands &amp; Panel](https://stoshelabs.github.io/antixray/guide/reference/commands) · [Permissions](https://stoshelabs.github.io/antixray/guide/reference/permissions) | Reference |
| 📝 [Changelog](https://stoshelabs.github.io/antixray/guide/changelog) | What changed in each release |

---

## Building from source

```sh
./gradlew jar        # → build/libs/AntiXray-<version>.jar
```

The build locates `HytaleServer.jar` from your Hytale install (or `libs/`). The docs live in [`docs/`](docs) (VitePress) and deploy to GitHub Pages automatically on push to `main`.

---

<sub>Built for Hytale by <a href="https://github.com/gitgusilva">Gustavo Will</a> · Stoshe Labs · <a href="LICENSE">MIT</a></sub>
