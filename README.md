# yt_kids

An Android app for watching YouTube videos for kids.

## What this is

A kid-friendly viewer for a **parent-curated** set of YouTube channels. Instead
of the full YouTube interface — endless recommendations, comments, search, and
ads for everything under the sun — a child sees a grid of big poster tiles
holding recent uploads from the channels an adult approved, and nothing else.

There is no search box, no menu, no text entry, and no way to navigate out of
the app. Tapping a tile plays that video; when it ends the player closes and the
child is back at the grid.

## How it fits together

```
approved channels          YouTube per-channel
  (SQLite, on device) ────►  Atom feeds  ────► the grid

GitHub release             Cloudflare Worker
  android-latest    ────►   (yt-kids.*.workers.dev) ────► self-update
```

| Piece | What it does |
| --- | --- |
| `ChannelStore` | The approved-channel list, on the device. **This is the parental control.** |
| `worker.js` | Serves the app's own release assets, publicly. Nothing else. |
| `android/` | The app: a grid, a locked-down player, parent mode, a self-updater. |
| `.github/workflows/android.yml` | Builds, signs, and publishes every push to `main`. |

Curation never leaves the phone. There is no server-side list and nothing to
deploy when you approve something — the app reads YouTube's per-channel feeds
directly, which need no API key and no quota.

The Worker exists for one reason: the repository is private, so a device with
no credential gets a 404 from the release assets and could never find an
update. It holds a read-only GitHub token and re-serves them. Its routes are
public but fixed — none takes a URL, repo, or path from the caller, so the
credential never leaves the Worker.

## Approving a channel

Tap **Parent** in the grid's status bar. Answer the arithmetic. Browse to a
channel in the WebView that opens and tap **Approve channel** — from the
channel's own page, or from any of its videos, which approves the uploader.

**Approved** in the same bar lists what you've approved and removes any of it.
Removing a channel drops its videos from the grid immediately.

> **Approving a channel approves its future uploads.** The grid shows whatever
> that channel posts next, and no adult will have seen it first. That is what
> channel-level approval means rather than a gap in the implementation — so
> choose channels you'd trust unattended, and check back on them.

Uploads come from the channel's Atom feed, which carries roughly the latest 15
and nothing older.

The gate is a speed bump, not a lock. Given `X + Y` and `X − Y` it asks for
both `X` and `Y`, and re-rolls the numbers on every wrong answer so guesses
can't converge. It stops a young child tapping through; an older one who can do
the algebra will get past it. If you need a real barrier it wants a PIN
instead, which is a small change.

## How the safety boundary works

Two files carry it, both covered by plain JVM unit tests that run in CI before
anything is published:

- **`VideoId.kt`** decides which ids are usable at all. Ids must be exactly 11
  URL-safe characters, anchored — so nothing carrying a `/`, `?`, `&`, or a
  quote can reach the player, where it would otherwise address a different
  video or break out of the JS string it is interpolated into. Every id off a
  channel feed goes through it.
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
gradle -p android testReleaseUnitTest   # id, player, gate, feed and schema tests
gradle -p android assembleRelease       # → android/app/build/outputs/apk/release/
```

Local builds get `versionCode 1` and `versionName "dev"`, since both come from
CI environment variables.
