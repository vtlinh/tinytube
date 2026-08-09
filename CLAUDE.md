# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

**TinyTube** is an app for watching a parent-curated set of YouTube videos. A
child sees a grid of approved videos and can reach nothing else. Android ships;
iOS is being built alongside it under the rule below. See `README.md` for the
architecture and the approval workflow.

**Development of the Android and iOS apps is PAUSED (2026-08).** Write no new
code or tests under `android/` or `ios/`, and dispatch no APK/IPA publish. The
active front is the WEB APP under `web/`, copied from
[pathikrit/TinyTube](https://github.com/pathikrit/TinyTube) and published to
this repository's GitHub Pages by `pages.yml`. It is a separate, self-contained
npm project — see `web/AGENTS.md` — and the rules below about the two apps
(including **Both platforms, always**) still bind whatever touches them, but do
not require app-side counterparts for web work while the pause holds. The
installed base still exists: the Worker keeps serving, and nothing here may
break an installed phone.

Everything is TinyTube: the label, the Kotlin package, the `applicationId`
(`dev.vtlinh.tinytube`) and the Worker (`tinytube.vtlinh87.workers.dev`). The
last two were renamed once, together, and that pairing was the only way it could
be done — see README's **Naming**. It cost every installed phone its approved
channels. Don't do it again:

- The `applicationId` is how Android identifies an install. Change it and the
  build cannot update what is on a phone; it installs beside it with its own
  data directory, and the approved channels and watch history are gone.
- The Worker's hostname is compiled into `Endpoints.kt`, so a renamed Worker
  cannot tell an installed app where it went. Worse than expected in practice:
  Cloudflare's git build RENAMES the service rather than adding one, so
  the previous hostname went to 404 the moment the new name deployed
  and every installed app lost its uploads and updates at once. There was no
  bridge and no way to build one after the fact.

## Both platforms, always

**A change to one platform must not publish the other.** `android.yml`'s push
trigger names `android/**` and nothing else, and auto-merge's publish filter is
narrower still — it mirrors each workflow's own "does this change the artifact"
list, so a test-only change ships nothing either. An `ios/**` change ships no
APK — and the Swift tests live in `ios.yml` rather than inside `android.yml` for
exactly that reason: a CI tweak for iOS sitting in that file would have shipped
a new `versionCode` to every Android device for a change that cannot affect
them. When an iOS release pipeline exists it owes Android the same courtesy.

A feature lands on Android AND iOS, or it is not finished. If something can
only exist on one — self-update is the standing example, and there is no iOS
equivalent — the difference goes in README's **Platform differences** table
with the reason. Silence there means the two apps agree, and that has to stay
true or the table is worse than nothing.

The shared logic is duplicated, not shared: `android/.../*.kt` and
`ios/TinyTubeCore/Sources/` are line-for-line counterparts, and so are their
tests. Change one, change the other, in the same commit. The Swift tests run on
Linux under `swift test`, so CI checks both.

One language difference bit already and will again: **Kotlin wraps on integer
overflow, Swift traps.** A line that produces a wrong answer on Android is a
crash on iOS. Bound before you add, not after. A second: **Swift's sort is not
stable and Kotlin's is**, so anything that leans on tie order has to buy it
explicitly — sort by an explicit tiebreaker rather than trusting the order in.

Two answers already bought with a spike, so don't re-derive them:

- **`Chrome.swift` is fed by ReplayKit on iOS, and by nothing else.**
  `takeSnapshot` is software-painted, `CALayer.render(in:)` can't see an
  out-of-process layer, and `drawHierarchy` returns black over video on devices
  *while working in the simulator* — don't "confirm" that one on a simulator.
  ReplayKit reads the real screen, which is what Android's `PixelCopy` does.
  That it hands over a frame is NOT a reason to refuse it: `PixelCopy` hands
  over a bitmap too, and the rule below is about retention, not about pixels
  existing. What is different on iOS is the **consent alert** — once per app
  process, again after 8 minutes backgrounded — which a child can be the one
  looking at. So it captures **once per install**, stores the answer per
  display, and gives up after three fruitless launches rather than prompting
  forever. A failure stores nothing. See `ScreenMeasurement`.
- **Google sign-in inside a webview works only by user-agent evasion**, on both
  platforms — Android removes `; wv`, iOS adds `Version/… Safari/…`. It is
  expected to break. `SFSafariViewController` is not the fallback: the app
  cannot read its URL, which is what parent mode's approve button needs, and
  its cookies don't reach `WKWebView` anyway. The real fallback is
  signed-out browsing, which needs no code.

## Layout

```
worker.js / wrangler.toml        Cloudflare Worker: release assets, /uploads
                                 and /channel — ALL the YouTube parsing —
                                 /sync/*, the web app's per-account state in
                                 D1, and /videos, the SHARED per-channel video
                                 cache (one YouTube fetch a day per channel
                                 serves every account; the Worker alone writes
                                 it)
worker.test.mjs                  its parsers, under `node --test worker.test.mjs`
                                 (CI runs it; named because bare `node --test`
                                 would sweep up web/tests too)
web/                             the WEB APP, copied verbatim from
                                 pathikrit/TinyTube @ cbab6b8; a self-contained
                                 npm project with its own AGENTS.md, tests and
                                 .gitignore. Published to GitHub Pages
                                 (vtlinh.github.io/tinytube) by pages.yml
ios/project.yml                  the Xcode project, as a readable file; the
                                 .xcodeproj is GENERATED, never committed
art/app-icon.png                 the launcher icon, as supplied artwork
art/make-icons.py                turns it into every size BOTH platforms need;
                                 re-run and commit after replacing the master
ios/TinyTube/                    the app target
  Database.swift        the one SQLite file; RUNS Schema's ladder
  ChannelStore.swift    approved channels — the parental control
  VideoStore.swift      the grid; a reply REPLACES a channel's videos
  WatchStore.swift      what was played, device-only, pruned
  SettingsStore.swift   the parent's choices, in UserDefaults
  Endpoints.swift       the Worker's hostname — don't change it
  ChannelFeeds.swift    asks the Worker, once a day per channel
  ChannelResolver.swift which channel a page is for; asks the Worker
  MainView.swift        the grid + the read-only Channels tab
  PlayerView.swift      overlay, reveal corner, blocker, what plays next
  PlayerWebView.swift   the locked-down WKWebView + the Bridge shim
  ParentView.swift      real YouTube, only ever reached through the gate
  SettingsView.swift    the parent's choices + About; no updates on iOS
  ApprovedChannelsView.swift  the approved list, with groups, open and remove
  Gate.swift            LocalAuthentication; arithmetic only with no lock
  ChallengeView.swift   that arithmetic fallback
  PlayerChrome.swift    the blocker's height: measured, else 16pt
  PlayerStage.swift     touch observation + the landscape lock, in UIKit
  BlockHeightStore.swift  it, remembered — per display, per version
  ScreenMeasurement.swift ReplayKit capture; feeds Chrome, once ever
  BrowserUserAgent.swift  the Safari suffix that lets Google sign in
  ImageStore.swift      avatars + posters on disk, PERMANENTLY — what the
                        removing-a-channel rule below turns on
  CachedImage.swift     the view that reads it; REPLACED AsyncImage
  ImageCache.swift      a forwarder to ImageStore, kept for one call site
  TinyTubeApp.swift     the @main entry point
  Assets.xcassets/      the launcher icon; GENERATED, see art/ above
ios/TinyTubeTests/               app-target tests; run on a simulator in CI
ios/TinyTubeCore/                the shared logic in Swift, mirroring the pure
                                 Kotlin files; `swift test` runs it on Linux
  Sources/TinyTubeCore/          VideoId Player Uploads Library Playlist
                                 ChannelSort ChannelGroups HoldTime Challenge
                                 YouTubeUrls Schema Chrome — the whole pure layer
  Tests/                         the Kotlin tests, ported alongside
.github/workflows/android.yml    build, sign, publish to the android-latest release
.github/workflows/ios.yml        ios-core on Linux; ios-app builds the IPA on
                                 macOS; publish puts it on ios-latest, from
                                 ubuntu — see the reuse note below
.github/workflows/auto-merge.yml merge a PR once both platforms pass
.github/workflows/claude-autofix.yml  fix a PR whose android run went red
.github/workflows/pages.yml      build web/ and publish it to GitHub Pages —
                                 dispatched by auto-merge for merges touching
                                 web/** (GITHUB_TOKEN merges fire no push
                                 events), plus hand-pushes to main, weekly (to
                                 refresh videos.json) and on dispatch; never on
                                 pull requests, so it cannot hold auto-merge
android/                         the app
  signing.p12                    NOT in the repo — a secret, and gitignored
                                 here; CI writes it out. See README for why
  app/src/main/java/dev/vtlinh/tinytube/
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
    SettingsStore.kt the parent's choices; SettingsActivity edits them +
                    About, in parent mode
    ChannelGroups.kt pure: channels gathered into named groups (unit-tested)
    ApprovedChannelsActivity.kt the approved list, with groups, open and remove
    Tooltip.kt      the ? beside each settings heading
    ChannelFeeds.kt asks the Worker, once a day per channel
    ChannelResolver.kt which channel a page is for; asks the Worker
    VideoStore.kt   the grid, in SQLite
    WatchStore.kt   what was played, on the device only — feeds the sort
    Thumbnails.kt   avatars + posters on disk, PERMANENTLY — what the
                    removing-a-channel rule below turns on
    BlockHeightStore.kt the measured player inset, per display
    MainActivity.kt the grid + the read-only Channels tab
    BottomTabs.kt   the two-tab bottom bar, shared by the child's screens
    PlayerActivity.kt the locked-down WebView
    ParentActivity.kt YouTube in a WebView, behind ChallengeActivity
    ParentSession.kt is a parent looking at this NOW — what lets the
                    update notification skip a gate already passed
    Updater.kt      self-update against android-latest
  app/src/test/                  plain JVM tests, no emulator
```

## Commands

```bash
gradle -p android testReleaseUnitTest   # runs in CI before anything is published
gradle -p android assembleRelease
node --test worker.test.mjs             # the Worker's parsers; CI runs this too
                                        # (named: bare `node --test` sweeps up
                                        # web/tests, which are vitest files)
(cd web && npm ci && npm test)          # the web app's suite; pages.yml runs it
swift test --package-path ios/TinyTubeCore   # the iOS half; CI runs this too
npx wrangler deploy --dry-run           # validates the worker bundles
```

An Android SDK (platform 34, build-tools 34.0.0) is required for both Gradle
commands; CI provisions it.

**The iOS APP cannot be built here, and saying otherwise is the failure mode to
avoid.** `swift test` covers `TinyTubeCore` and nothing else — that package is
Linux-buildable on purpose. Everything under `ios/TinyTube/` needs Xcode, so the
`ios-app` job on a macOS runner is the first thing that ever compiles it. Don't
claim a screen is verified because the core tests are green.

One thing you CAN do here, and should before batching up a lot of app code:

```bash
for f in ios/TinyTube/*.swift ios/TinyTubeTests/*.swift; do swiftc -parse "$f"; done
```

`-parse` stops before type checking, so it never loads UIKit or ReplayKit and
runs on Linux. It catches typos and malformed declarations; it CANNOT tell you
an API call is wrong. `ios-core` runs the same check, so a syntax error costs a
Linux run rather than a macOS one — but a batched PR full of app code is still
mostly unverified until `ios-app` runs. Say so when reporting it.

If you need the project locally on a Mac:

```bash
brew install xcodegen && (cd ios && xcodegen generate)   # writes TinyTube.xcodeproj
```

## Workflow

Finish everything that was asked before opening a pull request. Commit as you
go and push the branch — that part is free — but open the PR only once every
feature in the request is implemented on both platforms. Then let it merge.

Free because a push to a branch with NO OPEN PULL REQUEST triggers nothing:
`android.yml` and `ios.yml` both filter their `push` trigger to `main`, so the
only thing that builds a branch is the `pull_request` event. Open the PR early
and every subsequent push is a full run of both platforms; open it at the end
and the whole change costs one.

The reason used to be money: macOS runners bill at ten times the rate and a
free-plan PRIVATE repository gets about 200 macOS minutes a month. **That
premise is gone** — this repository is public, and GitHub-hosted minutes on the
standard `macos-15` label are not billed on public repositories. The rule stays
anyway, for the reasons that survive: a run per push is CI noise, a green
run on a half-finished branch says nothing, and a macOS build still takes ten
real minutes of waiting whoever is paying. Where you see a minutes budget quoted
elsewhere in this file or in `ios.yml`, read it the same way.

A DRAFT pull request does not help. Drafts still fire `pull_request` events and
still build; the only thing draft status changes is that auto-merge leaves them
alone. Don't open one as a way of "saving" runs.

The cost of batching is that nothing is checked until the end, so check it here
instead. Before opening anything, run every suite that runs locally — the Gradle
unit tests, `node --test`, `swift test`, and the `swiftc -parse` sweep over the
app target — and treat a red one exactly as you would a red CI run. See
**Commands**. Note what that list does NOT include: nothing under
`ios/TinyTube/` is compiled by any of it, so a batched PR carrying app code is
still unverified in the way that matters until `ios-app` runs.

Don't leave finished work sitting on a branch with no PR either. "Everything
that was asked" is the trigger, not "everything I can think of".

**Then stop. Don't schedule a check-in to poll the pull request.** Generic
PR-watching advice says to, on the grounds that CI success is not reliably
delivered — but that advice assumes a human has to merge. Here `auto-merge.yml`
does it: it fires on `workflow_run` completion, re-checks every required check,
and merges. A failure arrives as an event, a merge arrives as an event, and a
red `android` run additionally gets `claude-autofix.yml`. Polling adds a
permission prompt and a wake-up to a loop that already closes itself. If a PR
seems stuck, read `auto-merge`'s own run log — it prints which check it is
waiting for — rather than setting a timer.

Green CI is part of "finished", not a separate step to skip — the unit tests are
what stand between a bad video id and the player, and merging past a red run
publishes a build to every installed device.

The publish run doesn't usually run them again, but not by skipping them: it
usually doesn't build at all, republishing the APK the PR's own run made. When
it does build — because the code moved and the reuse was refused — it tests,
because that is precisely the case where nothing has tested what is shipping.

`auto-merge.yml` does the merging: a PR that isn't a draft merges itself once
BOTH `android` and `ios` pass and nothing else on the commit has failed. Label a PR
`no-auto-merge` to hold it open. Two things follow from how it works:

- It runs the copy of itself on `main`, so changes to it only take effect after
  they land, and the PR that changes it must be merged by hand.
- Once a PR merges, its branch is done. Further pushes to it produce NO CI at
  all — there is no open PR to raise a `pull_request` event, and the `push`
  trigger only watches `main` — so the commits sit there looking pushed and
  never get built. Start a fresh branch from `main` and open a new PR. This has
  already stranded four commits once.
- It requires the check runs `build` and `worker` (android.yml's jobs) and
  `ios-core` and `ios-app` (ios.yml's) BY NAME. Renaming any of them silently
  stops it waiting for that half. A required check that hasn't reported counts
  as not-yet rather than as absent, so whichever workflow finishes last is the
  one that merges.
- **All four skip themselves when their half of the repository is untouched**,
  each via a cheap `changes` job and a job-level `if`. A Worker-only pull
  request spends no Gradle build and no macOS runner; a docs-only one spends
  neither and no Swift either. `worker` exists as a job at all because of this:
  `node --test` used to be a step inside `build`, so gating `build` on
  `android/**` would have silently stopped testing the Worker on exactly the
  changes that touch it.
- `ios-app` is the macOS job, and it is required despite costing 10x minutes
  because it is SLOW. Left merely informational it would report after the merge
  every time rather than occasionally, which is not a gate at all. It pays for
  itself by skipping: a job-level `if` turns it off on changes that don't touch
  `ios/**`, and a check skipped that way still REPORTS, as `skipped`, which
  counts as passing. That is the whole reason the filtering is a job `if` and
  not a path filter on the workflow — see the next bullet for what a path
  filter would do here.
- `android.yml`'s `pull_request` trigger is deliberately not path-filtered. A
  filtered run that doesn't match never reports at all, and auto-merge waits
  for it — a docs-only PR would never merge.
- The publishes it dispatches afterwards ARE filtered, by hand, to the same
  paths each `push` trigger uses — `android/**` and `ios/**`, and **no workflow
  file**. Editing CI cannot change either artifact, because the reuse hashes
  cover those directories alone, so counting a workflow edit as an app change
  only ever shipped a new build number wrapping identical code. The cost is
  that a change to a workflow merges without that workflow having built
  anything; if you change how a build is actually made, touch that platform's
  directory in the same commit or dispatch the publish by hand. `workflow_dispatch` ignores path filters, so
  every merge used to publish — a docs-only PR shipped a new `versionCode` to
  every installed device for an update containing nothing. If the file list
  can't be read it publishes anyway: the wrong direction to be wrong in is the
  one where an app change silently never ships.
- **There are two publishes now, one per platform**, and each is dispatched
  only for a merge that touched its half. It also has to hand each one the
  right RUN to reuse, which it looks up by workflow NAME rather than taking
  from the event: `github.event.workflow_run.id` is whichever workflow tripped
  auto-merge, so half the time offering it to the iOS publish would name a run
  with no IPA in it. That is not a wrong build — the tree check refuses it —
  but it is a full macOS build bought for nothing on every second merge.

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

`ios.yml` does the same thing against `git rev-parse HEAD:ios`, and the same
three consequences hold — but it makes the decision in a DIFFERENT PLACE, and
that difference is the point rather than an inconsistency. Android decides
inside `build`, which is an ordinary runner it was going to start anyway. iOS
decides in `changes`, on ubuntu, BEFORE `ios-app` is allowed to run, because
`ios-app` is macOS: starting it merely to discover there was nothing to compile
would spend about ten minutes of macOS runner time, and ten real minutes of
waiting, on every iOS merge. So the reuse path never touches a Mac at all — `publish` is an ubuntu
job that downloads the IPA and uploads it to `ios-latest`.

Two traps that placement creates, both already sprung once elsewhere in this
file. `publish` needs `always()` in its `if`, because a skipped `ios-app` — the
reuse case, the one where publishing matters most — would otherwise skip it
too. And `ios-app`'s own `if` must stay false-on-pull-requests for the reuse
half: `reuse_run_id` is a dispatch input a PR run cannot have, so PRs always
build. If that ever stops being true, `ios-app` starts reporting `skipped` on
pull requests, and auto-merge counts a skip as a pass — the iOS gate would
switch itself off silently.

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

  **`/channel` is the same trade, made later.** Working out which channel a
  page is for used to happen on the device: `ChannelResolver` downloaded a full
  desktop channel page to read one 24-character string out of it, every time a
  parent approved anything. `parseChannelId`, `parseChannelTitle`,
  `parseChannelAvatar` and `channelTargetFromUrl` live in `worker.js` now, with
  the tests that came with them. The app sends the URL it is standing on and
  gets an answer. Same rule follows: no direct-to-YouTube page fetch on the
  device without saying what it is for.

  **`/channel` answers with the VIDEOS too**, from the same `uploadsFor` the
  `/uploads` route uses. Approving needs the id, the name and the first hundred
  uploads, and asking for those separately was two round trips at the one
  moment a parent is watching a spinner. A refresh of an already-approved
  channel still uses `/uploads`, which doesn't need the name again. An empty
  `videos` means "could not tell" and must not be stored as "this channel has
  none" — `VideoStore.replace` already refuses an empty list for that reason.
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
- **A notification may not lead PAST the gate — but it may lead TO it.** It
  sits in the shade and on the lock screen where a child can reach it, so
  `Updater`'s content intent must never name `SettingsActivity`: the hold
  duration is on that screen. What it does instead is name the grid and ASK,
  with an extra; `MainActivity` runs `ChallengeActivity` exactly as the Parent
  button does, and settings opens only on a `RESULT_OK`. A failed challenge
  leaves the child on the grid. The Install action still does the useful thing
  in one tap without opening anything.

  **And it does not ask twice.** A parent already in parent mode — or in the
  settings inside it — has just passed that gate, so the notification opens
  settings directly. `ParentSession` answers "is a parent looking at this now",
  and it is a COUNT rather than a flag because Android starts the incoming
  activity before it stops the outgoing one: a boolean would read "closed"
  during the handover into settings, which is exactly when a parent would tap
  this. Started/stopped rather than created/destroyed, so backgrounding the app
  owes the gate again.

  Two more details that are load-bearing rather than tidy. The request goes
  through `ParentActivity` rather than starting settings directly, because
  closing settings should leave the parent in parent mode rather than back on
  the child's grid having passed a challenge for nothing. And the extra is consumed
  on arrival — `setIntent` keeps it for the life of the activity, so a rotation
  would otherwise re-run the gate on a screen the parent had already
  dismissed.
- **Removing a channel removes everything it put on the device**, and that is
  one call on each platform rather than a checklist at every call site. Its
  row, its videos, its watch history AND its cached pictures — the last of
  which is the one that gets forgotten, because it is not in the database: the
  database holds the URLs, and the images live in an image store. **Both stores
  keep a disk half, and both are permanent**: `Thumbnails.kt` writes into
  `filesDir` — not `cacheDir`, precisely so Android cannot reclaim it — and
  `ImageStore.swift` writes into Application Support. Neither expires anything,
  so removal is the only thing that ever reclaims a byte. Don't go looking in
  `URLCache.shared` on iOS: `AsyncImage` and the URL cache were REPLACED by
  `ImageStore` and `CachedImage`, and `ImageCache.swift` is a forwarder kept so
  the call site in `ChannelStore.remove` still compiles. Read the URLs BEFORE
  deleting the rows; afterwards there is nothing left to read and every picture
  stays.
- **The app's watch history never leaves the device, and stays small.** It
  exists for one feature — ordering the approved list by what is actually being
  watched — and is one row per play in the same SQLite file. Nothing in this app
  uploads it and the Worker is never told what was played.

  Be precise about what it promises. The player is back on the nocookie domain
  and therefore signed OUT, so this is once again close to a real claim — but
  don't overstate it either: parent mode browses real YouTube signed in, and
  the moment the player is ever made first-party again (see the Premium note
  below) YouTube sees every play. This rule is about THIS APP's history table,
  which is device-only under every one of those arrangements.
  Nothing uploads the table, removing a channel removes its rows, and
  `WatchStore` prunes past the widest rung of `ChannelSort`'s ladder. Don't grow
  it into analytics, and don't send it anywhere.
- **"Most watched" is a ladder, not a window.** 7 days, then 30, then 365, then
  A-Z — each rung used only when every narrower one found nothing at all. A
  window of zeroes counts as empty; otherwise one stale row pins the ladder to
  its rung forever. The final fallback is A-Z rather than last-added on purpose:
  an absent answer must not masquerade as the default one having agreed with it.
  The list no longer names which rung applied — "Most watched · last 7 days"
  was noise on the ordinary case — but it MUST still say when the ladder fell
  all the way through, because "most watched" over an empty history quietly
  means A-Z and a list that looks unsorted is indistinguishable from a broken
  one. That one string is the part of this rule that survives.
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
  approved list and narrows the grid to one channel, or to a whole group. It
  cannot remove a channel, it cannot open YouTube, and it cannot make, rename
  or break up a group — `ChannelStore` is the parental control and editing it
  lives behind the gate, in `ApprovedChannelsActivity`. The two screens share
  `item_approved_channel.xml` and `item_channel_group.xml`, and both build
  their rows from the same `ChannelGroups.arrange`, so they cannot drift apart;
  the child's copy hides the remove button and has no long press. Don't give
  this one an action that changes what is approved.

  **The groups are shown AND their members are shown too**, on the child's
  side. A group header filters the grid to every channel in it, and each of
  those channels is still listed individually below it. Reaching one channel of
  a group must not cost a child two taps and an idea about how grouping works.

- **A group has at least two channels, and that is enforced rather than
  assumed.** Grouping is offered only for two or more; a group that loses
  members until one is left dissolves, and its last member becomes loose.
  `ChannelGroups.dissolving` is the rule and each store's `tidy()` runs it
  after EVERY mutation rather than at the call sites somebody remembered — a
  removal, an ungroup of half a group, and a move into another group each
  strand whatever is left behind.

  Two consequences worth not rediscovering. Group headers sort A–Z among
  themselves whatever the channel sort is, because "recently added" is not a
  property a group has and a parent looking for one wants it where it was last
  time; the sort orders the channels, inside a group and in the loose list
  below. And the name a new group may take is judged against
  `ChannelGroups.namesInUse`, not against every name there is: a group whose
  every member is in the selection is emptied by the grouping and dissolves, so
  its name comes free. That is what makes "add these to Cartoons" possible at
  all — and the store then has to ABSORB that group's row rather than insert a
  second one, because the name column is UNIQUE and the tidy that removes the
  emptied group runs afterwards.
- **The settings screen explains itself through the `?` beside each heading,
  not in prose under it.** Four permanent grey paragraphs were most of that
  screen, and a parent scrolling for the hold slider read three explanations of
  things they already understood to reach it. Android gets a real anchored
  popup (`Tooltip.kt`); iOS gets an alert, because `.popover` only stays a
  popover on an iPhone from 16.4 and the deployment target is 16.0 — on 16.0 it
  takes over the whole screen, which is worse than the alert. Same words on
  both, and a new setting brings its own `?` rather than a paragraph.
- **`ParentActivity` must never be reachable without the gate.** It is real
  YouTube. It is not exported, and the only thing that starts it is a
  `RESULT_OK` from `ChallengeActivity`. Don't add another caller.
- **The gate is the device lock**, with the arithmetic only as a fallback for
  a device that has none. Don't reverse that: the arithmetic is beatable by any
  child who can do algebra, and it exists so parent mode still has a door on a
  phone with no screen lock.
- **Sign-in HOSTS are parent-mode only; the SESSION is not.** `Player`'s
  allowlist is separate and narrower on purpose — accounts.google.com and the
  rest of the sign-in chain are not on it and must not be added.

  **The WRAPPER runs on `www.youtube-nocookie.com` and must keep doing so —
  and note "wrapper", because the player itself never did.** The embed iframe
  has always been `www.youtube.com`: `YT.Player` points at the nocookie domain
  only when passed `host:`, and neither platform's page ever has. So
  `Player.ORIGIN` names the origin of the document that HOSTS the player, and
  the failed change moved that document rather than the player. It
  was moved to `www.youtube.com` on purpose — same-origin with parent mode's
  session, so a **YouTube Premium account would play without ads**, which was
  asked for explicitly and is the only way Premium could ever apply, the
  nocookie domain being unauthenticated by design.

  It broke playback on BOTH platforms: every video came up "Video unavailable".
  The player page is a SYNTHETIC DOCUMENT — `loadDataWithBaseURL` on Android,
  `loadHTMLString` on iOS — so naming `www.youtube.com` as its base claims an
  origin the document cannot prove, and YouTube's embed refuses to serve a
  player to it. The nocookie domain tolerates exactly that, because being
  embedded by pages that are not YouTube is what it is FOR.

  So `Player.ORIGIN` / `Player.origin` is not a configuration knob, and
  changing it back on its own is not a trade between ads and no ads — it is
  reintroducing the bug. `PlayerTest` pins it on both platforms now; nothing
  did before, which is how one constant reached a phone and stopped every
  video playing.

  **The Premium lever is the cookie policy, not this constant.** Because the
  iframe is `www.youtube.com` and the wrapper is not, the embed is THIRD-PARTY,
  so whether it carries a session is decided by whether third-party cookies are
  sent to it. Android opts in with `setAcceptThirdPartyCookies` — currently in
  as a probe, and to be taken back OUT if it does not deliver an ad-free player,
  because the tracking surface on the child's screen is unconditional while the
  benefit is not.

  WKWebView has no equivalent: its tracking prevention blocks third-party
  cookies for a domain like youtube.com and no public API disables that outside
  a browser-entitled app. So if the probe works, iOS needs the wrapper served
  from a REAL origin — e.g. the Worker serving the player page as a URL — at
  which point the iframe is still third-party but the document is at least
  provable. The other route is navigating straight to
  `https://www.youtube.com/embed/<id>`, first-party on both, which gives up the
  `Bridge` contract's error signal and turns every link YouTube draws into a
  top-level navigation — so the allowlist would have to match on PATH as well
  as host. Cost either before starting.
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

  **iOS cannot keep the source-rectangle half of that promise, and says so.**
  `PixelCopy` takes a rectangle; ReplayKit does not, so whole frames arrive and
  `ScreenMeasurement` reads only the strip's rows out of them. Nothing above the
  strip is ever copied into memory the app owns, nothing is written, kept or
  sent, and capture stops the moment a measurement succeeds — but the frame does
  exist briefly in a buffer the system owns. That is weaker than Android's
  guarantee, it is the closest iOS allows, and it is a row in README's Platform
  differences rather than a silent divergence. The rest of the rule binds both
  platforms unchanged: it is a measurement, never a picture.
- **Bump `BlockHeightStore.VERSION` whenever the measurement changes.** A
  preference file survives an app update, so a wrong answer written by one
  build is read back by every build after it — which is how fixing the latching
  bug below changed nothing on any device that had already run the broken one.
  The version key is what makes a fix actually reach a device, and it has been
  bumped once per wrong answer that got persisted: 2 when a failed capture
  could be stored as a result, 3 when the measurement moved from the red to the
  track and every number it had ever written became wrong.

  iOS has its own `BlockHeightStore` with its own `version`, and the same ledger
  applies: it is at **2**, bumped when the capture read the bottom rows of
  ReplayKit's BUFFER as though they were the bottom of the screen — which in the
  player's forced landscape is a band down one SIDE, so `Chrome` never found a
  seek bar on any device. The NUMBERS need not agree across platforms; the RULE
  does. On iOS the version also resets the give-up counter, and that is what made
  the bump load-bearing rather than tidy: every device that ran the broken build
  had spent all three attempts and would never have asked again.
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
  can't be downloaded. `ios.yml` follows the same order even though nothing on
  iOS polls its copy — there is no self-update — because the habit is worth
  more than the exception, and the file is read by a person asking which build
  the download link is serving.
- **Each publish writes what it published into the release notes**, and that is
  a workaround rather than decoration. GitHub's releases list prints a
  release's `published_at`, which for a FIXED TAG is the day the tag was first
  created and never moves — `android-latest` read "3 days ago" while serving an
  APK from twenty minutes earlier. It cannot be fixed at the source: the only
  thing that moves `published_at` is toggling the release to draft and
  republishing, and a draft's asset URLs 404, which is the path every installed
  phone updates through. Don't do that to buy a date. Keep the notes truthful
  instead, and read the build numbers out of `dist/version.json` rather than
  from the publishing run — on the reuse path that run compiled nothing.
- **The Worker's release routes are a fixed table of path → {tag, name}**, and
  `/app/version.json` and `/app/app-release.apk` are compiled into installed
  Android apps. Renaming either strands every phone: the update mechanism is
  the thing that would be broken, so nothing on the device could recover. The
  `/ios/…` paths carry no such weight — a browser reads them — but they are
  still spelled out one at a time rather than assembled, because that is what
  keeps `GH_TOKEN` unreachable from anything a caller sends.
- **The old name is gone from this repository, and two of the places it lived
  were not text.** Both were renamed properly rather than edited, and both
  would have broken installed phones SILENTLY if they had been edited:
  - **The database filename.** `Schema.DATABASE` is the address of the SQLite
    file on a device, so renaming it means an existing install opens a NEW,
    empty database — the approved channels, the grid and the watch history all
    still on disk under the old name and never looked for again. No crash, no
    error: a parent finds an empty grid and approves their channels again.
    That cost was accepted deliberately. A migration was written and removed on
    request, so its absence is a decision rather than an oversight, and adding
    one now would only help devices that have not yet run the renamed build.
  - **The keystore.** `storePassword` / `keyAlias` / `keyPassword` are
    `signing.p12`'s real credentials, not labels, so the KEYSTORE was re-keyed
    with `keytool -changealias` and `-storepasswd`. That altered the container,
    not the key: the certificate SHA-256 came through unchanged, which is the
    only reason installed apps still accepted an update across that rename.

  **That is history now — the key itself has since been rotated**, when this
  repository went public, and the certificate DID change. The paragraph above
  describes what a rename could achieve and is kept because the distinction it
  draws still matters; it is not a description of the current key. Every copy
  installed before the rotation is stranded and has to be reinstalled by hand,
  which was accepted deliberately. Verify the current one with
  `keytool -list -v`, and see README's **About that committed key** for why
  rotating beat scrubbing: GitHub's pull-request refs keep the old file
  reachable forever, and a history rewrite does not touch them.

  Renamed with no such care needed, because none is persisted on a device: the
  CI artifact, the Worker's outgoing `User-Agent`, `rootProject.name` and the
  `Theme.*` resources.
- **Don't rename the Worker or the `applicationId`.** The Worker's hostname is
  compiled into `Endpoints.kt` and installed copies can only learn a new one via
  an update they'd fetch from the old one; the `applicationId` is how Android
  identifies an install, so changing it installs a second app rather than
  updating the first. They were renamed once, together — that pairing is what
  made it survivable, because the app carrying the new hostname was a fresh
  install rather than an update. It cost every phone its approved channels AND
  stranded every un-migrated one: Cloudflare renames the service rather than
  adding a second, so the old hostname 404s from the moment the new one
  deploys. There is no bridge. Don't do this again.
- **Nothing that takes caller input may reach the GitHub token.** The release
  routes hold a credential and are fixed — none takes a URL, repo or path from
  the caller, which is what makes them safe unauthenticated. Two routes do take
  input, and both earn it the same way: they never read `env.GH_TOKEN`, `env`
  is not even passed to them, every caller input is matched against a FIXED
  PATTERN, and every URL they fetch is BUILT from a validated value.

  `/uploads` takes a channel id against `^UC[A-Za-z0-9_-]{22}$` and a bounded
  list of `^[A-Za-z0-9_-]{11}$` ids.

  `/channel` takes **a URL**, which looks like the exact thing this rule
  forbids and isn't — the distinction is worth being precise about, because
  getting it wrong either way costs something. The caller's string is never
  fetched. `channelTargetFromUrl` parses it, requires a host in `PAGE_HOSTS`
  and a path of exactly `/channel/UC…` or `/@handle`, and **rebuilds** the
  address from the extracted value. The most a caller can name is a different
  YouTube channel, which is the whole point of the route. Reading YouTube —
  including reading its URLs — is the Worker's job; the apps send what they
  are standing on.

  So the rule is not "never accept a URL", it is **never `fetch()` a string a
  caller supplied**. Don't let a caller-supplied host, port, scheme or path
  through, and don't put the token within reach. A route that takes input has
  to make that argument or it doesn't belong.

  **The `/sync/*` routes make that argument a third time, one binding wider.**
  They are the web app's cross-device state (settings, watch history, quota
  usage) in D1, keyed by a Google account. The router hands them `env.DB` and
  nothing else — never `env`; the only URL ever fetched is Google's JWKS
  endpoint, a constant; the identity key (email) comes out of a
  SIGNATURE-VERIFIED Google ID token, never the request body, so one account
  cannot name another's rows; and every other input is fixed-pattern matched
  (video ids, bucket keys, device id, session token) and reaches SQL only as a
  bound parameter or through `json_each` over a string the Worker itself
  re-serialized from validated values. Sessions are 90-day bearer tokens
  stored as SHA-256 hashes, so a leaked database impersonates nobody. The
  routes answer 503 until `wrangler.toml`'s D1 binding and worker.js's
  `GOOGLE_CLIENT_ID` are both filled in. Merge semantics live in
  `web/src/lib.js` (row-wise last-write-wins; usage buckets are per-device and
  summed server-side — that summing is what makes the watch quota hold across
  devices, and why a device must never fold pulled totals back into the
  buckets it pushes).

  **The `/sync/*` rows are keyed by CHILD as well as by account.** One
  household has several children and each owns their own age, quota, channels
  and watch history, so `sync_watched_v2`/`sync_usage_v2` carry a `child_id` in
  their primary key — new tables rather than an ALTER, because SQLite cannot
  widen a PRIMARY KEY in place. The pre-children tables are still there and
  their rows are copied once, idempotently, under `DEFAULT_CHILD` (`"default"`),
  which is the id the web app's own migration gives its first child. Those two
  literals have to stay equal: change one alone and every already-syncing
  account silently starts from an empty history. Settings stay ACCOUNT-wide —
  the blob carries every child — so only history and usage take the child.

  **`/videos` is the shared-vs-per-user line, drawn deliberately.** Which
  videos a channel has is a fact about the channel, so it is cached ONCE, in
  D1's `channel_cache`, keyed by channel id and shared by every account —
  which channels an account approved stays per-user in `sync_settings`. The
  cache is written ONLY by the Worker's own fetches (Data API when the
  `YOUTUBE_API_KEY` wrangler secret is set — durations, 18+ dropped — else the
  same page+feed scrape /uploads uses): a browser-writable shared cache would
  let one account put its own "videos" under a channel every other child then
  sees. The caller contributes a channel id against `CHANNEL_ID` and nothing
  else; an answer that parses to nothing is never cached (stale beats empty);
  and the web client re-validates every id and rebuilds thumbnails anyway.
- **The Worker's parsing is tested by `worker.test.mjs`, run in CI.** It reads a
  rendering of YouTube's own web app, so it breaks without anyone touching it —
  it is pinned against three entries lifted verbatim from a live page, plus the
  channel-page readers that arrived with `/channel`. It runs in the `worker`
  job of `android.yml`, which auto-merge requires BY NAME, so it gates a merge.
  What makes a check a gate is being in that REQUIRED list — not which file it
  sits in.
- Never commit API keys or tokens. The Worker's `GH_TOKEN` is a wrangler secret,
  and the signing key is two repository secrets — `ANDROID_KEYSTORE_B64` and
  `ANDROID_KEYSTORE_PASSWORD` — that `android.yml` writes out at build time.
  `signing.p12` was committed on purpose for as long as this repository was
  private. It is gitignored now; do not commit it again. It is also a DIFFERENT
  key from the one in the history, which was rotated rather than scrubbed
  because GitHub's pull-request refs keep the old file reachable forever and a
  history rewrite does not touch them. Losing the current key is unrecoverable:
  a new key updates nothing that already exists. See README's **About that
  committed key**.
