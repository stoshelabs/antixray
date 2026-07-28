---
layout: home

hero:
  image:
    src: /logo.png
    alt: AntiXray — X-ray Protection for Hytale
  tagline: "Packet-level X-ray protection for Hytale. Hides your ores from wall-hacks, then baits and catches the cheaters digging for them."
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
    title: Camouflage & Traps
    details: The fake ores come in two tiers — a dense field of common metals that drowns out the real layout, and rare valuable ores that are pure bait. Since real valuables are masked as rock, every valuable ore an X-ray user can see is a trap.
  - icon: 📈
    title: Suspicion Scoring
    details: Trap hits and abnormal mining rate feed a decaying suspicion score, both counted over sliding windows so an unlucky miner's rare accident never adds up. Cross the threshold and every online admin gets a quiet alert — nothing is ever auto-punished.
  - icon: 🧰
    title: Protected Blocks
    details: Buried non-ore valuables get the same masking, and rare decoy chests bait a cheater into tunnelling through solid rock for loot that was never there.
  - icon: 👁️
    title: Admin Panel & Spectate
    details: One command opens a tabbed panel listing suspects worst-first, each with a one-click live follow-camera — first or third person — plus a suspect-inventory view to confiscate the evidence.
  - icon: ⚙️
    title: Tuned & Safe
    details: Exposed faces are never touched, and the camouflage field steps aside as you dig — breaking a block re-shows the true neighbours, so honest mining never uncovers a fake. Only the rare traps stay put, which is what makes a hit mean something.
  - icon: 🌍
    title: Drop-in & Localized
    details: One JAR, sensible defaults, per-world toggles, and wildcard block ids that auto-discover your server's ores. English + Portuguese out of the box, and fully configurable.
---

<style>
:root {
  --vp-home-hero-image-filter: drop-shadow(0 12px 40px rgba(18, 179, 166, 0.32));
}
</style>
