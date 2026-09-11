// Which versions of the documentation exist, and which one this build is.
//
// VitePress has no versioning of its own, so the site follows the convention the
// rest of the Vite ecosystem uses: every release line is built once, with its own
// `base`, into its own directory. The newest is also built at the root, so
// `/guide/reference/commands` is always the current release and
// `/v1.2/guide/reference/commands` is that release forever.
//
// `tools/build_docs.sh` does the building and the Pages workflow runs it.
// Cutting a release that starts a new line (1.3 -> 1.4) is these edits and a tag:
//
//   1. make sure the outgoing line's tag is **pushed first** — an archived version
//      is built from its newest `v<line>.*` tag, so listing it in `archived` before
//      the tag exists publishes a dropdown entry that 404s. It is survivable rather
//      than fatal: build_docs.sh prints "no tag for 1.3, skipping" and carries on;
//   2. move that version into `archived` below (newest first), leaving its
//      `hytale` entry alone;
//   3. set `current` to the new line, in step with gradle.properties, and add its
//      `hytale` entry — the game line the new jar is built against;
//   4. tag the new release and push it, then push the commit — the Pages workflow
//      deploys from `main`, and by then every tag it has to build from is there.
//
// A patch release (1.3.0 -> 1.3.1) touches none of this: `current` is the line,
// not the patch.
//
// The archived build comes from the tag, in a worktree, so it says what that
// release actually said rather than today's text wearing an old label.
//
// One consequence worth knowing rather than rediscovering: an archived build runs
// **its own tag's** copy of this file and of config.mts. A tag cut before this file
// existed — v1.2.0 and older — hardcodes `base: '/antixray/'` and has no banner
// and no version menu. build_docs.sh copes with the first by passing the base on
// the VitePress command line, which overrides whatever the config says, so every
// link and asset of /v1.2/ stays inside /v1.2/. The second it cannot fix: those
// builds simply have no "you are reading old docs" banner and no dropdown back to
// the latest. 1.0 and 1.1 are not listed because nobody should be running them
// (they declare `"*"` and misbehave on newer servers — see the changelog);
// 1.2 is, because it is the last release for Hytale 0.5.

/** The release line this checkout documents. Kept in step with gradle.properties. */
export const current = '1.3'

/**
 * Older release lines that are still deployed, newest first. A version leaves
 * this list when its directory is deleted, not before — a link that 404s is
 * worse than a page that says it is out of date.
 */
export const archived: string[] = ['1.2']

/**
 * The Hytale version each release line targets.
 *
 * This is the question a server owner actually asks — they know which game build
 * they run, not which plugin tag documents it — so it rides in the banner and the
 * version menu instead of living in prose someone has to go find. It is also the
 * `hytale-<line>` suffix of the jar name, which the docs write as
 * `{{HYTALE_LINE}}` (see config.mts).
 */
export const hytale: Record<string, string> = {
  '1.3': '0.6',
  '1.2': '0.5',
}

/** The version this build documents. */
export const version = process.env.ANTIXRAY_DOCS_VERSION ?? current

/**
 * What the *site* contains, which is not the same question as what this checkout
 * documents.
 *
 * An archived version is rebuilt from its own tag, and inside that worktree this
 * file is the one that shipped with the tag: its `current` is that very version,
 * and it has never heard of anything released since. Left to itself an archived
 * build would therefore believe it is the latest — no banner, and a dropdown
 * missing every newer release, which is exactly the reader this whole scheme
 * exists for.
 *
 * So build_docs.sh reads `current`, `archived` and `hytale` from the checkout it
 * was invoked in — main — and passes them down as JSON. A build with no such
 * hand-down is its own authority, which is right for a local build.
 */
type Site = { current: string; archived: string[]; hytale: Record<string, string> }

const site: Site = process.env.ANTIXRAY_DOCS_SITE
  ? JSON.parse(process.env.ANTIXRAY_DOCS_SITE)
  : { current, archived, hytale }

/** The newest release the site carries. */
export const latest = site.current

/** True while building a version that is no longer the latest. */
export const isArchived = version !== latest

/** The Hytale line this build's version targets, or undefined if nobody wrote it down. */
export const hytaleLine: string | undefined = site.hytale[version] ?? hytale[version]

/**
 * Absolute URL of the site root, when the build knows one — CI takes it from the
 * Pages configuration.
 *
 * Cross-version links have to be absolute, and that is not a preference. Every
 * internal link goes through VitePress's `withBase`, which prepends *this
 * build's* base: inside `/antixray/v1.2/`, a link written `/` comes out as
 * `/antixray/v1.2/` — the page it is already on. An absolute URL is left alone,
 * so it is the only form that can point from one version to another.
 *
 * Empty falls back to base-relative links, which is right for a local build where
 * the root is the only version there is.
 */
export const siteUrl = (process.env.ANTIXRAY_DOCS_SITE_URL ?? '').replace(/\/*$/, '/')

/** A link to another version of this site, from whichever one is building. */
export function versionLink(v?: string): string {
  const path = v ? `v${v}/` : ''
  return siteUrl ? `${siteUrl}${path}` : `/${path}`
}

/**
 * How a version is named: the plugin line and the game build it targets.
 *
 * Unlike Plots, AntiXray's lines map one-to-one onto Hytale lines (1.2 is 0.5,
 * 1.3 is 0.6), so the game version is what tells the entries apart for the
 * person picking one — it goes in the dropdown too, not only in the banner.
 */
export function label(v: string = version): string {
  const game = site.hytale[v] ?? hytale[v]
  return game ? `v${v} · Hytale ${game}` : `v${v}`
}

/** The nav dropdown: the latest release, then everything still deployed beside it. */
export function versionMenu() {
  const older = site.archived.filter((v) => v !== latest)

  return {
    text: `v${version}`,
    items: [
      { text: `${label(latest)} (latest)`, link: versionLink() },
      ...older.map((v) => ({ text: label(v), link: versionLink(v) })),
      { text: 'Release notes', link: 'https://github.com/stoshelabs/antixray/releases' },
    ],
  }
}

/**
 * What the banner at the top of an archived build says, or null on the latest.
 * It is handed to the theme through `themeConfig` rather than imported by the
 * component, because the component also runs in the browser, where `process.env`
 * does not exist.
 */
export function versionBanner() {
  if (!isArchived) {
    return null
  }

  return {
    label: label(),
    latestLabel: label(latest),
    latestLink: versionLink(),
  }
}
