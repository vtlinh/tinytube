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

Tap **Parent** in the grid's status bar and unlock with your fingerprint, face
or PIN. Browse to a channel and tap **+**. You can sign in to Google inside
that browser, so your own subscriptions are a place to approve from.

The **+** is live only on an actual channel page — a URL starting `/@handle`
or `/channel/`. On a channel you have already approved it becomes **−**, which
removes it. The list button opens every approved channel: tap one to go and
look at it again, or **✕** to remove it.

Removing a channel drops its videos from the grid immediately.

> **Approving a channel approves its future uploads.** The grid shows whatever
> that channel posts next, and no adult will have seen it first. That is what
> channel-level approval means rather than a gap in the implementation — so
> choose channels you'd trust unattended, and check back on them.

Uploads come from the channel's Atom feed, which carries roughly the latest 15
and nothing older.

The gate is the device's own lock, so this app never invents or stores a secret
of its own — it only learns whether the platform's check passed. On a device
with **no** lock set up there is nothing to authenticate against, and it falls
back to arithmetic: given `X + Y` and `X − Y`, name both `X` and `Y`, with the
numbers re-rolled on every wrong answer so guesses can't converge. That
fallback is a speed bump rather than a lock — if it is what your device gets,
setting a screen lock is the fix.

## How the safety boundary works

Every decision that can put the wrong thing in front of a child lives in a file
with no Android in it, so a plain JVM can test it — and CI runs those tests
before anything is published:

| File | Decides |
| --- | --- |
| `VideoId.kt` | which video ids are usable at all |
| `Player.kt` | where the player's WebView may navigate |
| `YouTubeUrls.kt` | channel ids, and where parent mode may browse |
| `Feed.kt` | what comes out of a channel's upload feed |
| `Challenge.kt` | the arithmetic fallback gate |
| `Schema.kt` | the SQL behind the approved list |
| `Library.kt` | how feeds become the grid |

Two of them matter most:

- **`VideoId.kt`** — ids must be exactly 11 URL-safe characters, anchored, so
  nothing carrying a `/`, `?`, `&`, or a quote can reach the player, where it
  would otherwise address a different video or break out of the JS string it is
  interpolated into. Every id off a channel feed goes through it, and
  `Player.pageFor` checks it again rather than trusting its caller.
- **`Player.kt`** — navigation is matched on the *parsed host* against an
  allowlist, which is what makes `youtube.com.attacker.example` and
  `https://www.youtube.com@attacker.example/` refusals rather than matches.
  Non-`http(s)` schemes — `intent:`, `javascript:`, `file:` — have no host and
  are refused outright.

`Feed.kt` parses the Atom feed with regex on purpose. Nothing in a feed is
trusted, and every id still goes through `VideoId` before it can become a tile,
so the worst a malformed or hostile feed can do is yield *fewer* videos, never
a bad one — while a DOM parser would add an XXE surface for correctness that
isn't needed.

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
  because it restarts the app — it is never applied mid-video. From the first
  self-performed update onward the app is its own installer of record, so
  Android 12+ applies later ones with no further confirmation.
- **Notifications**: asked for on first launch, because the notification is the
  only thing that says an update is ready. If they're off, the About screen
  says so and offers to turn them on — and its **Check for updates** button
  works regardless.

Two one-time hurdles on the very first update: the app needs Android's "Install
unknown apps" permission, and Android shows one confirmation while the app
claims update ownership. Silent from then on.

### About that committed key

This mirrors how `vtlinh/novels` does it, and it is a deliberate trade: anyone
with repo access can sign an APK that Android will install over this one as an
update. That is acceptable while the repo is private and the app is
family-scale. If this app is ever distributed more widely, move the keystore to
an encrypted GitHub secret and have the workflow write it out at build time.

## Appearance

The app is dark, not dark-when-the-phone-is: nearly all of its screen time is a
video on black, and a grid that alternates with the system setting flashes on
every return from the player. The theme is still `DayNight` and the light
palette is kept in step, so `MODE_NIGHT_FOLLOW_SYSTEM` in `App.kt` hands the
choice back to the phone without a re-theme.

## Setting it up

Already configured for this repo; this is what it took, and what to redo if it
is ever rebuilt elsewhere.

The Android build needs nothing but the repo — CI provisions the JDK, Gradle,
and the SDK. The Worker is deployed by a git-connected Cloudflare build on
every push to `main`, and needs one secret, set once:

```bash
npx wrangler deploy                  # if the git-connected build isn't on
npx wrangler secret put GH_TOKEN     # fine-grained token, Contents:read, this repo only
```

Cloudflare's preview deployments for non-`main` branches get their own
hostnames and cannot affect installed apps, which only ever talk to production.

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

Merging is automated: `auto-merge.yml` merges a pull request once `android`
passes and nothing else on the commit has failed. Label a PR `no-auto-merge` to
hold it open.

---

By Linh Vu.
