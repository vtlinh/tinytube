# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

**TinyTube** is an Android app for watching a parent-curated set of YouTube
videos. A child sees a grid of approved videos and can reach nothing else. See
`README.md` for the architecture and the approval workflow.

The app's NAME is TinyTube and so is the Kotlin package, `dev.vtlinh.tinytube`.
Its ADDRESSES are not: `applicationId` stays `dev.vtlinh.ytkids` and the Worker
stays `yt-kids`. Renaming the applicationId would install a second app beside
the first on every phone that already has this one; renaming the Worker would
strand installed copies on a hostname they can only be told about by an update
they would fetch from it.

Two consequences of the package move, both load-bearing:

- `MainActivity` keeps its OLD class name as an `activity-alias`, and the alias
  carries the launcher filter. A home-screen icon is a pinned component name, so
  without it every already-placed icon would point at a class that no longer
  exists. Don't collapse it into `.MainActivity`.
- `Updater.ACTION_INSTALL_UPDATE` stays `dev.vtlinh.ytkids.INSTALL_UPDATE`. A
  notification from the previous build holds a `PendingIntent` naming it.

## Layout

```
worker.js / wrangler.toml        Cloudflare Worker: release assets + /uploads
worker.test.mjs                  its parsers, under `node --test` (CI runs it)
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
    Uploads.kt      pure: reading the Worker's reply  (unit-tested)
    Schema.kt       pure: the SQL                     (unit-tested)
    Library.kt      pure: collate + date + order the grid (unit-tested)
    Playlist.kt     pure: what plays next             (unit-tested)
    HoldTime.kt     pure: the hold-to-unlock duration (unit-tested)
    ChannelSort.kt  pure: the approved list's order   (unit-tested)
    Chrome.kt       pure: find the seek bar's track     (unit-tested)
    ChannelStore.kt approved channels, SQLite on the device — the parental control
    SettingsStore.kt the parent's choices; SettingsActivity edits them + About, in parent mode
    ChannelFeeds.kt asks the Worker, once a day per channel
    VideoStore.kt   the grid, in SQLite
    WatchStore.kt   what was played, on the device only — feeds the sort
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
node --test                             # the Worker's parsers; CI runs this too
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

The publish run doesn't usually run them again, but not by skipping them: it
usually doesn't build at all, republishing the APK the PR's own run made. When
it does build — because the code moved and the reuse was refused — it tests,
because that is precisely the case where nothing has tested what is shipping.

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

The publish `android.yml` runs after that merge reuses the pull request's own
APK when `git rev-parse HEAD:android` — the SHA of the `android/` subtree — is
what that build recorded. Three things follow. The hash covers `android/` and
only `android/`, so anything the APK depends on has to live there; a build input
put anywhere else would be invisible to the check and could change without
forcing a rebuild. Every build must keep writing `dist/version.json` and
`dist/build-tree.txt` beside the APK — the reuse check reads them, and a build
that stops emitting them silently disables the fast path. And the tests run on
*every* build that happens, including a publish: reaching the compiler on a
publish means the reuse was refused, which means no run has tested this tree.
Don't "optimise" the tests back out of that path.

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
  `Challenge`, `YouTubeUrls`, `Uploads`, `Schema`, `Library`, `Playlist`,
  `ChannelSort`, `HoldTime` and `Chrome` are the app's safety boundary and they are testable precisely
  because a plain JVM can run them. Anything needing a `Context` belongs in the
  Activity or Store that calls them.
- **Validate video ids at every hop.** The Worker refuses malformed ids off the
  page and off the feed, `Uploads.parse` refuses them again off the reply, and
  `Player.pageFor` refuses them a third time rather than trusting its caller. An
  id is interpolated into both a URL and a JS string literal, so a
  partially-checked one is how the wrong video gets played.
- **The phone does not parse YouTube any more; the Worker does.** `/uploads`
  fetches the playlist page and the Atom feed and answers with the list. The
  phone sends the ids it already has and gets details back only for what is
  new, so an ordinary refresh is about a kilobyte instead of two megabytes.
  Don't reintroduce a direct-to-YouTube uploads fetch on the device without
  saying what it is for — the data cost is the whole reason it moved.
- **Refresh is at most once a day per channel.** `channels.uploads_at` is the
  clock and NULL means never, which is why a newly approved channel fetches at
  once. Mark it only after a fetch that produced something: marking a failure
  buys the outage a full day.
- **The uploads page is the optional half of the answer.** 100 videos come from
  YouTube's uploads-playlist page, which is a rendering of their own web app and
  can be renamed under us; 15 come from the Atom feed, which is published for
  the purpose and is also the only source carrying an upload TIME. So
  `parseUploadsPage` returning empty must fall through to the feed in
  `worker.js`, and every parser there must return empty rather than throw on
  anything it does not recognise. Fifteen videos is a thinner grid; a crash or
  an empty one is a broken app.
- **Shorts are excluded by naming the `UULF` playlist, and by nothing else.**
  Both URLs in `worker.js` are built from `longFormPlaylistId`, which is the
  channel id with `UC` replaced by `UULF`: YouTube's own uploads list with
  Shorts removed. Do not classify videos anywhere. The page reports every entry
  as `LOCKUP_CONTENT_TYPE_VIDEO` whether it is a Short or not, and duration is
  not the rule — YouTube sorts by aspect ratio, so filtering by length would
  drop exactly the short, wide videos a children's channel posts while keeping
  three-minute vertical Shorts. A `UU` playlist id there puts Shorts back
  silently.
- **The grid is ordered by upload time, newest first, across all channels.**
  The page gives order and no dates, the feed gives dates and no depth; the
  Worker's `datePositions` reconciles them by keeping every date the feed knows
  and placing everything below it one second apart in page order. That number
  is a SORT KEY, not a claim about when something was posted — don't display it
  as a date, and don't let anything downstream treat it as one.
- **Re-validate everything the Worker sends.** `Uploads.parse` puts every id
  through `VideoId.isValid` and every thumbnail URL through a host check, even
  though the Worker checked both. An id reaches a URL and a JS string literal,
  and a stored thumbnail URL is later fetched and drawn; "our own server said
  so" is not a reason to skip the check that stops the wrong video playing.
- **The Worker's reply REPLACES a channel's videos.** It is the answer to what
  the channel has now, so a video missing from it is one that was deleted, made
  private, or pushed past the hundred. Merging would fill the grid with tiles
  that play nothing. A reply that parses to nothing changes nothing — a stale
  grid beats an empty one.
- **What plays next comes from the list the child tapped on.** `PlayerActivity`
  is handed the whole visible list and an index, so a video started from a
  channel-filtered grid cannot lead out of that channel — and there is no rule
  in the player saying so, which is the point. Don't give the player its own
  idea of what is playable; the screen that was on is the authority.
- **The child's status bar holds exactly one control**, the Parent button, and
  everything behind it is gated by `ChallengeActivity`. Settings had a second
  button there for one build; it lives in parent mode now, next to the approved
  list, where every other parent control already is. Anything reachable from
  that bar without the gate does not belong on it. Nothing on the child's
  screen has a hidden action either: About used to open on a long-press of the
  title and is part of Settings now.
- **A notification may not lead past the gate.** It sits in the shade and on
  the lock screen, where a child can reach it, so `Updater`'s content intent
  opens the grid rather than Settings — where the hold duration now lives. The
  Install action still does the useful thing in one tap.
- **Watch history never leaves the device, and stays small.** It exists for one
  feature — ordering the approved list by what is actually being watched — and
  is one row per play in the same SQLite file. Nothing uploads it, the Worker is
  never told what was played, removing a channel removes its rows, and
  `WatchStore` prunes past the widest rung of `ChannelSort`'s ladder. Don't grow
  it into analytics, and don't send it anywhere.
- **"Most watched" is a ladder, not a window.** 7 days, then 30, then 365, then
  A-Z — each rung used only when every narrower one found nothing at all. A
  window of zeroes counts as empty; otherwise one stale row pins the ladder to
  its rung forever. The final fallback is A-Z rather than last-added on purpose:
  an absent answer must not masquerade as the default one having agreed with it.
  And the list has to SAY which rung applied, or "most watched" over an empty
  history is indistinguishable from a broken sort.
- **Nothing inside parent mode needs its own gate.** `ParentActivity` is only
  reachable through a `RESULT_OK` from `ChallengeActivity`, so the approved
  list and the settings open straight from its bar. Adding a second challenge
  there would be asking the same question twice; adding an entry point to
  either from the child's side would be skipping it once.
- **Curation is channel-level and on the device.** There is no hand-listed
  video catalog and no server-side list; `ChannelStore` is the parental
  control. Don't reintroduce a remote source of approvals without saying what
  happens when it disagrees with the device.
- **The player's WebView may not navigate off the allowlist.** Match on the
  parsed host, never a substring of the URL. If you add a host, add the
  lookalike test cases for it too.
- **Never widen the child-facing surface.** No search, no free text entry, no
  link that leaves the app. The status bar holds one control — see the gating
  rule below.
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
- **Bump `BlockHeightStore.VERSION` whenever the measurement changes.** A
  preference file survives an app update, so a wrong answer written by one
  build is read back by every build after it — which is how fixing the latching
  bug below changed nothing on any device that had already run the broken one.
  The version key is what makes a fix actually reach a device, and it has been
  bumped once per wrong answer that got persisted: 2 when a failed capture
  could be stored as a result, 3 when the measurement moved from the red to the
  track and every number it had ever written became wrong.
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
- **Nothing on `/uploads` may reach the GitHub token.** The release routes hold
  a credential and are fixed — none takes a URL, repo or path from the caller,
  which is what makes them safe unauthenticated. `/uploads` is the one route
  that does take input, and it earns that by being separated from the
  credential: it never reads `env.GH_TOKEN`, `env` is not even passed to it,
  the only caller input is a channel id matched against
  `^UC[A-Za-z0-9_-]{22}$` and a bounded list of `^[A-Za-z0-9_-]{11}$` ids, and
  every URL it fetches is BUILT from a validated id. Don't let a caller-supplied
  URL, host or path reach `fetch()` there, and don't put the token within reach
  of it. A third route that takes input has to make the same argument or it
  doesn't belong.
- **The Worker's parsing is tested by `worker.test.mjs`, run in CI.** It reads a
  rendering of YouTube's own web app, so it breaks without anyone touching it —
  it is pinned against three entries lifted verbatim from a live page. The
  `node --test` step lives in `android.yml` on purpose: auto-merge waits only
  for the `android` run, so a check anywhere else would not gate a merge.
- Never commit API keys or tokens. The Worker's `GH_TOKEN` is a wrangler secret;
  `signing.p12` is committed on purpose and is not a secret (see README).
