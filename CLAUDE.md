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
.github/workflows/claude-autofix.yml  fix a PR whose android run went red
android/                         the app
  signing.p12                    committed keystore; see README for why
  app/src/main/java/dev/vtlinh/ytkids/
    VideoId.kt      pure: which video ids are valid   (unit-tested)
    Player.kt       pure: page + navigation allowlist (unit-tested)
    Challenge.kt    pure: the arithmetic fallback gate (unit-tested)
    YouTubeUrls.kt  pure: channel ids, parent allowlist (unit-tested)
    Feed.kt         pure: channel upload feed         (unit-tested)
    Schema.kt       pure: the SQL                     (unit-tested)
    Library.kt      pure: collate uploads into the grid (unit-tested)
    Chrome.kt       pure: find the seek bar's track     (unit-tested)
    ChannelStore.kt approved channels, SQLite on the device — the parental control
    ChannelFeeds.kt per-channel uploads + cache
    BlockHeightStore.kt the measured player inset, per display
    MainActivity.kt the grid + the read-only Channels tab
    BottomTabs.kt   the two-tab bottom bar, shared by the child's screens
    PlayerActivity.kt the locked-down WebView
    ParentActivity.kt YouTube in a WebView, behind ChallengeActivity
    ApprovedChannelsActivity.kt the approved list, with open and remove
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
- The publish it dispatches afterwards IS filtered, by hand, to the same paths
  the `push` trigger uses. `workflow_dispatch` ignores path filters, so every
  merge used to publish — a docs-only PR shipped a new `versionCode` to every
  installed device for an update containing nothing. If the file list can't be
  read it publishes anyway: the wrong direction to be wrong in is the one where
  an app change silently never ships.

`claude-autofix.yml` does the reverse: a red `android` run on a PR gets read,
fixed and pushed, so the retry goes green and auto-merge takes it. It has the
same runs-from-main property. It stops after three attempts on a branch, treats
an empty diff as a valid outcome, and pushes but labels `no-auto-merge` if the
fix touched `.github/workflows/**` — a model editing the workflow that judges
it can switch off the judging. `no-autofix` on a PR opts out entirely.

Autofix **may** change tests, and the mechanical guard that used to hold those
PRs for review was removed on purpose: a fix that changes behaviour has to
change the test that pinned the old behaviour, and holding every one of those
stopped the loop on the ordinary case rather than the dangerous one. What is
left is the instruction in the workflow's prompt, and it is now the only thing
standing between a deleted assertion and every installed device. The line it
draws: updating an expected value because the behaviour legitimately changed is
fine; relaxing a matcher, deleting a case, or widening an input set until the
failure stops is not. Green bought that way is worse than red — red merely
stops, green publishes.

## Conventions

- **The pure files must stay free of Android imports.** `VideoId`, `Player`,
  `Challenge`, `YouTubeUrls`, `Feed`, `Schema`, `Library` and `Chrome` are the app's
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
  link that leaves the app. The status bar holds exactly one control, the
  Parent button, and everything behind it is gated by `ChallengeActivity`.
  `AboutActivity` stays on the long-press: it is parent-facing but harmless.
- **The Channels tab is read-only, and must stay that way.** It shows the
  approved list and narrows the grid to one channel. It cannot remove a channel
  and it cannot open YouTube — `ChannelStore` is the parental control and
  editing it lives behind the gate, in `ApprovedChannelsActivity`. The two
  screens share `item_approved_channel.xml`; the child's copy hides the remove
  button. Don't give this one an action that changes what is approved.
- **`ParentActivity` must never be reachable without the gate.** It is real
  YouTube. It is not exported, and the only thing that starts it is a
  `RESULT_OK` from `ChallengeActivity`. Don't add another caller.
- **The gate is the device lock**, with the arithmetic only as a fallback for
  a device that has none. Don't reverse that: the arithmetic is beatable by any
  child who can do algebra, and it exists so parent mode still has a door on a
  phone with no screen lock.
- **Sign-in hosts are parent-mode only.** `Player`'s allowlist is separate and
  narrower on purpose; a signed-in Google page must never be reachable from the
  child's screen.
- **Approving a channel approves its future uploads**, which no adult has seen.
  That is the deal the app now makes, and it is the weakest point in it.
  Anything that widens it further — auto-approving related channels, following
  playlists, surfacing recommendations — is a bigger change than it looks.
- **The player's frame capture stays a measurement, never a picture.**
  `PlayerActivity.measureBlockHeight` PixelCopies the bottom strip of the
  window to find the seek bar. Only that strip is ever copied, the bitmap is
  recycled in the callback that received it, and nothing is written, passed on
  or sent. A build that saved it to a PNG and offered it on the share sheet
  existed for one round of debugging and was removed once the measurement
  worked; if it is ever needed again it is in the history, with the backup
  exclusions and the FileProvider that made it safe. Don't widen the source
  rectangle, don't keep the bitmap, and don't add a caller that wants the
  image rather than the number.
- **A failed measurement must never be stored.** `Chrome.blockHeightOrNull`
  returns null for "could not tell" precisely so the Activity can distinguish
  it from a real answer that happens to equal the fallback. Latching on the
  number instead meant one blank frame was persisted as the answer and nothing
  ever looked again — the bug that made the whole feature silently do nothing
  on a real phone. `WebView.draw(Canvas)` was the cause of the blank frames;
  the player is composited in hardware and does not appear on a software
  canvas. Don't go back to it.
- **The player's geometry is measured, never written down.** Where YouTube's
  seek bar is, how thick it is, how much room to leave under it and what counts
  as an implausible answer all come out of the captured pixels. `Chrome` takes
  its scale from the bar's own drawn thickness — about 3dp — so every figure in
  it is a RATIO, holding at any resolution without anyone converting dp to
  pixels or knowing the density. Two earlier versions put those numbers in
  code, first as fractions of the screen and then as dp in `dimens.xml`; both
  were tuned to a single screenshot and both were wrong elsewhere. Don't
  reintroduce either. `ChromeTest` builds the same layout at seven device
  geometries and asserts doubling the resolution doubles the answer; add to
  that list rather than tuning to a screenshot.
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
