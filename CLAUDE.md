# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

`yt_kids` is an Android app for watching a parent-curated set of YouTube videos.
A child sees a grid of approved videos and can reach nothing else. See
`README.md` for the architecture and the approval workflow.

## Layout

```
catalog.json                     the approved-video list — the parental control
worker.js / wrangler.toml        Cloudflare Worker: catalog + release assets
.github/workflows/android.yml    build, sign, publish to the android-latest release
android/                         the app
  signing.p12                    committed keystore; see README for why
  app/src/main/java/dev/vtlinh/ytkids/
    Catalog.kt      pure: which ids are valid       (unit-tested)
    Player.kt       pure: page + navigation allowlist (unit-tested)
    CatalogStore.kt fetch + on-disk cache
    MainActivity.kt the grid
    PlayerActivity.kt the locked-down WebView
    Updater.kt      self-update against android-latest
  app/src/test/                  plain JVM tests, no emulator
```

## Commands

```bash
gradle -p android testReleaseUnitTest   # runs in CI before anything is published
gradle -p android assembleRelease
npx wrangler deploy --dry-run           # validates the worker bundles
```

An Android SDK (platform 34, build-tools 34.0.0) is required for both Gradle
commands; CI provisions it.

## Conventions

- **`Catalog.kt` and `Player.kt` must stay free of Android imports.** They are
  the app's safety boundary and they are testable precisely because a plain JVM
  can run them. Anything needing a `Context` belongs in the Activity or Store
  that calls them.
- **Validate video ids at every hop.** `Catalog` refuses malformed ids and
  `Player.pageFor` refuses them again rather than trusting its caller. An id is
  interpolated into both a URL and a JS string literal, so a partially-checked
  one is how the wrong video gets played.
- **The player's WebView may not navigate off the allowlist.** Match on the
  parsed host, never a substring of the URL. If you add a host, add the
  lookalike test cases for it too.
- **Never widen the child-facing surface.** No search, no free text entry, no
  link that leaves the app. Parent-only functionality goes behind the
  long-press on the grid header, in `AboutActivity`.
- **`version.json` is published last, in its own upload.** It is what tells an
  app a new build exists; landing it before the APK advertises a version that
  can't be downloaded.
- **Don't rename the Worker.** Its hostname is compiled into `Endpoints.kt`, and
  installed copies can only learn a new one via an update they'd fetch from the
  old one.
- Never commit API keys or tokens. The Worker's `GH_TOKEN` is a wrangler secret;
  `signing.p12` is committed on purpose and is not a secret (see README).
