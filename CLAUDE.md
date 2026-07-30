# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

`yt_kids` is an Android app for watching a parent-curated set of YouTube videos.
A child sees a grid of approved videos and can reach nothing else. See
`README.md` for the architecture and the approval workflow.

## Layout

```
worker.js / wrangler.toml        Cloudflare Worker: release assets only
.github/workflows/android.yml    build, sign, publish to the android-latest release
.github/workflows/auto-merge.yml merge a PR once android passes
android/                         the app
  signing.p12                    committed keystore; see README for why
  app/src/main/java/dev/vtlinh/ytkids/
    VideoId.kt      pure: which video ids are valid   (unit-tested)
    Player.kt       pure: page + navigation allowlist (unit-tested)
    Challenge.kt    pure: the parent-mode gate        (unit-tested)
    YouTubeUrls.kt  pure: channel ids, parent allowlist (unit-tested)
    Feed.kt         pure: channel upload feed         (unit-tested)
    Schema.kt       pure: the SQL                     (unit-tested)
    Library.kt      pure: collate uploads into the grid (unit-tested)
    ChannelStore.kt approved channels, SQLite on the device — the parental control
    ChannelFeeds.kt per-channel uploads + cache
    MainActivity.kt the grid
    PlayerActivity.kt the locked-down WebView
    ParentActivity.kt YouTube in a WebView, behind ChallengeActivity
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

## Workflow

Finish each feature: commit it, push the branch, open the pull request, and
merge it once CI is green. Don't leave completed work sitting on a branch or a
PR waiting to be asked about.

Green CI is part of "finished", not a separate step to skip — the unit tests are
what stand between a bad video id and the player, and merging past a red run
publishes a build to every installed device.

`auto-merge.yml` does the merging: a PR that isn't a draft merges itself once
`android` passes and nothing else on the commit has failed. Label a PR
`no-auto-merge` to hold it open. Two things follow from how it works:

- It runs the copy of itself on `main`, so changes to it only take effect after
  they land, and the PR that changes it must be merged by hand.
- `android.yml`'s `pull_request` trigger is deliberately not path-filtered. A
  filtered run that doesn't match never reports at all, and auto-merge waits
  for it — a docs-only PR would never merge.

## Conventions

- **The pure files must stay free of Android imports.** `VideoId`, `Player`,
  `Challenge`, `YouTubeUrls`, `Feed`, `Schema` and `Library` are the app's
  safety boundary and they are testable precisely because a plain JVM can run
  them. Anything needing a `Context` belongs in the Activity or Store that
  calls them.
- **Validate video ids at every hop.** `Feed` refuses malformed ids coming off
  a channel's feed and `Player.pageFor` refuses them again rather than trusting
  its caller. An id is interpolated into both a URL and a JS string literal, so
  a partially-checked one is how the wrong video gets played.
- **Curation is channel-level and on the device.** There is no hand-listed
  video catalog and no server-side list; `ChannelStore` is the parental
  control. Don't reintroduce a remote source of approvals without saying what
  happens when it disagrees with the device.
- **The player's WebView may not navigate off the allowlist.** Match on the
  parsed host, never a substring of the URL. If you add a host, add the
  lookalike test cases for it too.
- **Never widen the child-facing surface.** No search, no free text entry, no
  link that leaves the app. The grid's status bar holds exactly one control,
  the Parent button, and everything behind it is gated by `ChallengeActivity`.
  `AboutActivity` stays on the long-press: it is parent-facing but harmless.
- **`ParentActivity` must never be reachable without the gate.** It is real
  YouTube. It is not exported, and the only thing that starts it is a
  `RESULT_OK` from `ChallengeActivity`. Don't add another caller.
- **Approving a channel approves its future uploads**, which no adult has seen.
  That is the deal the app now makes, and it is the weakest point in it.
  Anything that widens it further — auto-approving related channels, following
  playlists, surfacing recommendations — is a bigger change than it looks.
- **`version.json` is published last, in its own upload.** It is what tells an
  app a new build exists; landing it before the APK advertises a version that
  can't be downloaded.
- **Don't rename the Worker.** Its hostname is compiled into `Endpoints.kt`, and
  installed copies can only learn a new one via an update they'd fetch from the
  old one.
- **The Worker serves release assets only.** It holds a GitHub token; every
  route is fixed and takes nothing from the caller. Adding a route that does is
  how that token becomes everyone's.
- Never commit API keys or tokens. The Worker's `GH_TOKEN` is a wrangler secret;
  `signing.p12` is committed on purpose and is not a secret (see README).
