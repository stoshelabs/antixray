---
layout: home

hero:
  image:
    src: /logo.png
    alt: AntiXray — X-ray Protection for Hytale
  tagline: "Stop X-ray cheating on Hytale. AntiXray hides your ores from wall-hacks with per-player packet obfuscation, then lays a field of fake-ore honeypots that quietly catch the cheaters who dig for them — with an admin suspect panel and live spectate to confirm the catch."
  actions:
    - theme: brand
      text: What is AntiXray?
      link: /guide/intro/what-is-antixray
    - theme: alt
      text: Getting Started
      link: /guide/intro/getting-started
    - theme: alt
      text: How it protects
      link: /guide/protection/obfuscation

features:
  - icon: 🛡️
    title: Packet Obfuscation
    details: Every buried ore is replaced — in each player's client only — so X-ray sees plain stone where the real ore is. The server world is never changed, so honest players notice nothing.
  - icon: 🪤
    title: Fake-Ore Honeypots
    details: A field of fake ores is scattered through hidden rock. A legit player can never see enclosed blocks, so digging straight to one is a dead giveaway of X-ray.
  - icon: 📈
    title: Suspicion Scoring
    details: Honeypot hits and abnormal mining rate feed a decaying suspicion score. Cross the threshold and every online admin gets a quiet alert — no false-positive bans.
  - icon: 🧰
    title: Protected Blocks
    details: Buried non-ore valuables get the same masking, and rare decoy chests bait a cheater into tunnelling through solid rock for loot that was never there.
  - icon: 👁️
    title: Admin Panel & Spectate
    details: One command opens a tabbed panel listing suspects worst-first, each with a one-click live follow-camera — first or third person — plus a suspect-inventory view to confiscate the evidence.
  - icon: ⚙️
    title: Tuned & Safe
    details: Exposed faces are never touched, the area you're mining is always revealed as real, and breaking a block re-shows the true neighbours — so mining never uncovers a fake.
  - icon: 🌍
    title: Drop-in & Localized
    details: One JAR, sensible defaults, per-world toggles, and wildcard block ids that auto-discover your server's ores. English + Portuguese out of the box, and fully configurable.
---

<style>
:root {
  --vp-home-hero-image-filter: drop-shadow(0 12px 40px rgba(18, 179, 166, 0.32));
}
</style>
