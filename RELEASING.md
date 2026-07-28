# Releasing AntiXray

Three things in this list are load-bearing for features that only break *after* release — the in-game
update check and the "what's new" popup both depend on the tag matching what the jar reports.

## Invariants

- **`gradle.properties` is the only place the plugin version is written.** It flows into the jar name,
  the jar manifest, `manifest.json` (via `processResources` token replacement), and back into the
  running plugin through `AntiXray.getVersion()`, which reads the manifest the server parsed. The docs
  read the same file at build time (`{{PLUGIN_VERSION}}`). Never hardcode a version anywhere else.
- **Tags are `v`-prefixed: `v1.2.0`, not `1.2.0`.** `UpdateChecker.fetchReleaseForVersion` looks up
  `/releases/tags/v{version}`. A tag without the `v` makes the "what's new" popup silently fall back to
  the *previous* release's notes.
- **The tag must point at the commit that bumped the version.** Tagging an earlier commit ships a jar
  reporting the old version, which then compares itself against the new release and shows a permanent
  "update available" banner.
- **`ServerVersion` in `src/main/resources/manifest.json` is a real semver range**, enforced by the
  server at load. Bump it deliberately when targeting a new Hytale version; a bare `"1.2.3"` fails to
  parse (use `=1.2.3` or `^1.2.3`).

## Steps

1. Set `version=x.y.z` in `gradle.properties`.
2. Confirm `ServerVersion` in `src/main/resources/manifest.json` still matches the server you built and
   tested against. If the range moved, update the compatibility table in `docs/guide/changelog.md` and
   the Hytale-server badge in `README.md` — the README is the one file with no token substitution, so
   it is the only place a range is written by hand. (Its version badge is dynamic and needs no touch.)
3. Add the release section to `docs/guide/changelog.md`.
4. `./gradlew jar` — verify `build/libs/AntiXray-x.y.z.jar` and that its bundled `manifest.json` shows
   the right `Version` and `ServerVersion`:
   ```sh
   unzip -p build/libs/AntiXray-x.y.z.jar manifest.json
   ```
5. Commit, then tag **that commit**: `git tag vx.y.z && git push origin main --tags`.
6. Publish the GitHub release on tag `vx.y.z` **with notes in the body** — the body is what the in-game
   popup shows. An empty body means the popup never appears (`ChangelogManager.isReady()` is false).
7. Sanity-check the round trip: a server running the new jar should log
   `You are running the latest version (x.y.z).` — not an update banner.

Until step 6 is done, servers running the new jar show the *previous* release's notes in the "what's
new" popup. That's the intended fallback, not a bug, but it's a reason not to leave a tag unpublished.
