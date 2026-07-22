import { defineConfig } from 'vitepress'

// AntiXray documentation — plain VitePress. The docs always describe the current release; a
// hand-maintained Changelog page (guide/changelog.md) records what changed per version.
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
