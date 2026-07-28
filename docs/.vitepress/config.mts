import { readFileSync } from 'node:fs'
import { defineConfig } from 'vitepress'

// AntiXray documentation — plain VitePress. The docs always describe the current release; a
// hand-maintained Changelog page (guide/changelog.md) records what changed per version.
//
// Versions are NOT written by hand in the markdown. They are read here from the same files the build
// uses and substituted at build time, so a release bump never leaves a stale number behind:
//
//   {{PLUGIN_VERSION}}   -> gradle.properties `version`        (e.g. AntiXray-{{PLUGIN_VERSION}}.jar)
//   {{SERVER_VERSION}}   -> manifest.json `ServerVersion`      (the supported Hytale range)
//
// See the markdown.config hook at the bottom for the substitution itself.
//
// Badge convention for anything that won't survive the next server release. <Badge> is a VitePress
// global component, so no import is needed. Put it on the section heading and follow it with a
// container that says what replaces the feature and when:
//
//   ## Live spectate <Badge type="warning" text="changes in 0.6" />   -- still works, goes away later
//   ## Old thing <Badge type="danger" text="deprecated" />            -- works today, do not build on it
//   ## Gone thing <Badge type="danger" text="removed in 0.6" />       -- no longer functional
//
// The version in the badge text is the *server* version (manifest ServerVersion), not the plugin's.
// When the plugin's ServerVersion range moves past it, drop the badge and rewrite the section.

const repoFile = (path: string) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8')

const pluginVersion = /^\s*version\s*=\s*(.+)$/m.exec(repoFile('gradle.properties'))?.[1]?.trim()
if (!pluginVersion) {
    throw new Error("Could not read 'version' from gradle.properties — docs versions would be wrong.")
}

const serverVersion = JSON.parse(repoFile('src/main/resources/manifest.json')).ServerVersion
if (!serverVersion) {
    throw new Error("Could not read 'ServerVersion' from src/main/resources/manifest.json.")
}

const VERSION_TOKENS: Record<string, string> = {
    '{{PLUGIN_VERSION}}': pluginVersion,
    '{{SERVER_VERSION}}': serverVersion,
}

export default defineConfig({
    title: 'AntiXray',
    description: 'Packet-level X-ray protection for Hytale — ore obfuscation, honeypot detection, and live spectate.',
    base: '/antixray/', // GitHub Pages: https://stoshelabs.github.io/antixray/
    lang: 'en-US',
    cleanUrls: true,
    lastUpdated: true,
    head: [
        ['link', { rel: 'icon', href: '/antixray/icon.png' }],
        ['meta', { name: 'theme-color', content: '#12b3a6' }],
        ['meta', { property: 'og:title', content: 'AntiXray — X-ray Protection for Hytale' }],
        ['meta', { property: 'og:description', content: 'Stop X-ray cheating on Hytale: per-player packet ore obfuscation, a fake-ore honeypot that detects and catches cheaters, an admin suspect panel, and live spectate.' }],
        ['meta', { property: 'og:image', content: '/antixray/logo.png' }],
    ],
    markdown: {
        // Substitute the version tokens in the raw markdown, before parsing — so they work everywhere,
        // including inside inline code and fenced blocks (where Vue interpolation would not).
        config: (md) => {
            md.core.ruler.before('normalize', 'antixray-versions', (state) => {
                for (const [token, value] of Object.entries(VERSION_TOKENS)) {
                    state.src = state.src.split(token).join(value)
                }
            })
        },
    },
    themeConfig: {
        logo: '/icon.png',
        nav: [
            { text: 'Home', link: '/' },
            { text: 'Guide', link: '/guide/intro/what-is-antixray' },
            { text: 'How it protects', link: '/guide/protection/obfuscation' },
            { text: 'Commands', link: '/guide/reference/commands' },
            { text: 'Changelog', link: '/guide/changelog' },
        ],
        sidebar: [
            {
                text: 'Introduction',
                items: [
                    { text: 'What is AntiXray?', link: '/guide/intro/what-is-antixray' },
                    { text: 'Getting Started', link: '/guide/intro/getting-started' },
                ],
            },
            {
                text: 'How it protects',
                items: [
                    { text: 'Packet Obfuscation', link: '/guide/protection/obfuscation' },
                    { text: 'Protected Blocks', link: '/guide/protection/protected-blocks' },
                    { text: 'Honeypots & Detection', link: '/guide/protection/detection' },
                    { text: 'Admin Panel & Spectate', link: '/guide/protection/spectate' },
                ],
            },
            {
                text: 'Configuration',
                items: [
                    { text: 'Block IDs & Probe', link: '/guide/setup/block-ids' },
                    { text: 'Config Reference', link: '/guide/setup/config' },
                ],
            },
            {
                text: 'Reference',
                items: [
                    { text: 'Commands & Panel', link: '/guide/reference/commands' },
                    { text: 'Permissions', link: '/guide/reference/permissions' },
                ],
            },
            {
                text: 'Releases',
                items: [
                    { text: 'Changelog', link: '/guide/changelog' },
                ],
            },
        ],
        socialLinks: [
            { icon: 'github', link: 'https://github.com/stoshelabs/antixray' },
        ],
        search: {
            provider: 'local',
        },
        editLink: {
            pattern: 'https://github.com/stoshelabs/antixray/edit/main/docs/:path',
            text: 'Edit this page on GitHub',
        },
        lastUpdated: {
            text: 'Last updated',
            formatOptions: { dateStyle: 'short', timeStyle: 'short' },
        },
        footer: {
            message: 'Released under the MIT License.',
            copyright: 'Copyright © 2026-present Stoshe Labs',
        },
    },
})
