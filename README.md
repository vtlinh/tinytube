# yt_kids

An Android app for watching YouTube videos for kids.

## What this is

A kid-friendly viewer for a **parent-curated** set of YouTube videos. Instead of
the full YouTube interface — endless recommendations, comments, search, and ads
for everything under the sun — a child sees a grid of big poster tiles holding
exactly the videos an adult approved, and nothing else.

There is no search box, no menu, no text entry, and no way to navigate out of
the app. Tapping a tile plays that video; when it ends the player closes and the
child is back at the grid.

## How it fits together

```
catalog.json ──┐
               ├──► Cloudflare Worker ──► Android app
GitHub release ┘     (yt-kids.*.workers.dev)
  android-latest
```

| Piece | What it does |
| --- | --- |
| `catalog.json` | The approved-video list. **This file is the parental control.** |
| `worker.js` | Serves the catalog and the app's own release assets, publicly. |
| `android/` | The app: a grid, a locked-down player, and a self-updater. |
| `.github/workflows/android.yml` | Builds, signs, and publishes every push to `main`. |

The repository is private, so a device has no credential and GitHub answers 404
to it for both the catalog and the release assets. The Worker exists to close
that gap: it holds a read-only GitHub token and re-serves the few files an
installed copy needs. Its routes are public but fixed — none of them takes a
URL, repo, or path from the caller, so the credential never leaves the Worker.

## Approving a video

1. Open the video on YouTube and copy the 11-character id from its URL:
   `https://www.youtube.com/watch?v=`**`dQw4w9WgXcQ`**
2. Add an entry to `catalog.json`:
   ```json
   { "videos": [ { "id": "dQw4w9WgXcQ", "title": "Counting Song" } ] }
   ```
3. Push to `main`. The git-connected Cloudflare build redeploys the Worker, and
   every device picks the change up the next time the grid is opened.

**Check each id by opening it yourself first.** Nothing downstream verifies that
an id points at what you meant — only that it is well-formed. A typo is a real
way to put the wrong video in front of a child.

Entries with a malformed id, and duplicates, are dropped by the app rather than
shown.

## How the safety boundary works

Two files, both covered by plain JVM unit tests that run in CI before anything
is published:

- **`Catalog.kt`** decides which ids exist at all. Ids must be exactly 11
  URL-safe characters, anchored — so nothing carrying a `/`, `?`, `&`, or a
  quote can reach the player, where it would otherwise address a different
  video or break out of the JS string it is interpolated into.
- **`Player.kt`** decides where the player's WebView may navigate. Matching is
  on the parsed host against an allowlist, which is what makes
  `youtube.com.attacker.example` and `https://www.youtube.com@attacker.example/`
  refusals rather than matches. Non-`http(s)` schemes — `intent:`,
  `javascript:`, `file:` — have no host and are refused outright.

On top of that the player disables the long-press context menu, popups, file and
content access, and closes itself the moment the video ends, before the
end-screen grid of related videos can be tapped.

This is a lock on the front door, not a guarantee about YouTube itself. The
videos are served by YouTube and carry YouTube's ads.

## Automated, signed updates

Every push to `main` that touches `android/` builds a release APK and publishes
it to a fixed `android-latest` release.

- **Signing**: one keystore, committed at `android/signing.p12`, signs every
  build. Android only installs an update over an existing app when the
  signatures match, and CI runners would otherwise mint a random key per run.
  The password is in `build.gradle.kts` and provides no secrecy — the file is
  committed, so anyone with the repo has the key. It buys signature *stability*,
  not security. Treat it as public, and see the note below.
- **Versioning**: `versionCode` is the workflow run number, which is what the
  updater compares. `versionName` is a display-only `<year>.<week>.<patch>`.
- **Publish order**: the APK is uploaded first, `version.json` second and in its
  own call. Uploaded together they race, and the tiny manifest goes live long
  before the APK finishes — a window on every release where apps are told about
  a build they then fail to download.
- **Installing**: the app checks on every foreground, pre-downloads a newer APK,
  and offers it in a notification. The install itself always waits for a tap,
  because it restarts the app. From the first self-performed update onward the
  app is its own installer of record, so Android 12+ applies later ones with no
  further confirmation.

### About that committed key

This mirrors how `vtlinh/novels` does it, and it is a deliberate trade: anyone
with repo access can sign an APK that Android will install over this one as an
update. That is acceptable while the repo is private and the app is
family-scale. If this app is ever distributed more widely, move the keystore to
an encrypted GitHub secret and have the workflow write it out at build time.

## Setting it up

The Android build needs nothing but the repo — CI provisions the JDK, Gradle,
and the SDK. The Worker needs one secret, set once:

```bash
npx wrangler deploy                  # if the git-connected build isn't on
npx wrangler secret put GH_TOKEN     # fine-grained token, Contents:read, this repo only
```

The Worker's name in `wrangler.toml` decides its hostname, and the app has that
hostname compiled in (`Endpoints.kt`). Renaming it orphans every installed copy:
they keep asking the old hostname, and the only thing that could tell them
otherwise is an update they can no longer find.

## Building locally

```bash
gradle -p android testReleaseUnitTest   # the catalog and player tests
gradle -p android assembleRelease       # → android/app/build/outputs/apk/release/
```

Local builds get `versionCode 1` and `versionName "dev"`, since both come from
CI environment variables.
