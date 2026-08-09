# TinyTube

An app for watching YouTube videos for kids. Android ships; iOS is being built
alongside it, and the rule is that a feature lands on both or it is not
finished — see **Platform differences** for the short list of things that
genuinely cannot.

## What this is

**TinyTube** is a kid-friendly viewer for a **parent-curated** set of YouTube
channels. Instead
of the full YouTube interface — endless recommendations, comments, search, and
ads for everything under the sun — a child sees a grid of big poster tiles
holding recent uploads from the channels an adult approved, and nothing else.

There is no search box, no menu, no text entry, and no way to navigate out of
the app. Tapping a tile plays that video, and when it ends the next one starts —
from the same list that was on screen when they tapped. Open a channel first and
the whole session stays inside it.

## How it fits together

```
approved channels        Cloudflare Worker        the grid
  (SQLite, on device) ──►  /uploads          ──►   (SQLite, on device)
                            └─► YouTube

GitHub release             Cloudflare Worker
  android-latest    ────►   (tinytube.*.workers.dev) ────► /app  ──► self-update
  ios-latest        ────►                                  /ios  ──► sideload by hand
```

| Piece | What it does |
| --- | --- |
| `ChannelStore` | The approved-channel list, on the device. **This is the parental control.** |
| `worker.js` | Serves the app's release assets, and answers what an approved channel has posted. |
| `android/` | The app: a grid, a locked-down player, parent mode, a self-updater. |
| `.github/workflows/android.yml` | Builds, signs, and publishes every push to `main`. |

Curation never leaves the phone. There is no server-side list of who may watch
what and nothing to deploy when you approve something — the approved channels
are SQLite on the device, and so is the grid built from them.

The Worker does three jobs. It re-serves the app's release assets at fixed,
stable paths — that path holds a read-only GitHub token and its routes are
fixed, so the credential cannot be pointed anywhere. Those routes were added
while this repository was **private**, when a device with no credential would
have got a 404 and could never have found an update. The repository is public
now, so the credential is no longer what makes them reachable; what keeps them
is that `/app/version.json` and `/app/app-release.apk` are compiled into every
installed Android app, and renaming or removing either strands every phone. It answers `/uploads` —
what has this channel posted — so the phone doesn't download two megabytes of
YouTube's web app per channel and parse it. And it answers `/channel` — which
channel is this page for — so approving one doesn't download a channel page just
to read an id out of it.

**All of the YouTube parsing is here, and none of it is on either phone.**

The two routes that take anything from the caller are kept away from the
credential: neither reads the token, every input is matched against a fixed
pattern, and every URL either fetches is built from a validated value.
`/channel` **does** accept a URL — the app sends the page it is standing on,
because reading YouTube, including reading its addresses, is the Worker's job —
and that is not the exception it looks like: the caller's string is never
fetched. `channelTargetFromUrl` parses it, demands a host YouTube serves channel
pages from and a path of exactly `/channel/UC…` or `/@handle`, and **rebuilds**
the address from what it extracted. The most a caller can name is a different
YouTube channel. Nothing a caller sends reaches `fetch()`.

## Approving a channel

Tap **Parent** in the grid's status bar and unlock with your fingerprint, face
or PIN. Browse to a channel and tap **+**. You can sign in to Google inside
that browser, so your own subscriptions are a place to approve from.

The **+** is live only on an actual channel page — a URL starting `/@handle`
or `/channel/`. On a channel you have already approved it becomes **−**, which
removes it. Every approved channel is on a screen of its own, opened by the
list button in parent mode's bar — not inside Settings, which is the gear beside
it. Tap a row to go and look at that channel again, or **✕** to remove it, and
select several to gather them into a named group. **The approved channels**
below has the whole of it.

Removing a channel drops its videos from the grid immediately, its watch
history with it, and its avatar and thumbnails off the disk.

That list's **sort** control cycles three orders, and the label beside it says
which one is in force:

- **Recently added** — the default, and what the list always did.
- **A–Z**.
- **Most watched** — over the last 7 days, or the last 30 if nothing was watched
  in the week, or the last year if nothing in the month, and A–Z if the device
  has no history at all. Narrowing first is the point: a channel watched daily
  this week should lead one watched more often a year ago, and a list sorted
  over all time would freeze into whatever was popular when the phone was new.
  A "most watched" that found no history at all says so beside the control,
  because otherwise it quietly means A–Z and a list that looks unsorted is
  indistinguishable from a broken one.

Which order you pick applies to the child's Channels tab too. It is one list.

That history is one row per play, in the same database as everything else. It is
never uploaded — the Worker is never told what was played — rows older than the
widest window are deleted, and removing a channel removes its rows. It exists
for that sort and nothing else.

> **Approving a channel approves its future uploads.** The grid shows whatever
> that channel posts next, and no adult will have seen it first. That is what
> channel-level approval means rather than a gap in the implementation — so
> choose channels you'd trust unattended, and check back on them.

The grid holds each channel's **latest 100 uploads**, ordered by upload time,
newest first, across every approved channel together.

The phone doesn't fetch those from YouTube. It asks the Worker, and it sends
along the ids it already has — so the reply carries full details only for videos
that are new, and bare ids for everything it already knows.

Measured against a live channel: a refresh that finds nothing new is **1.4 KB**,
and a channel seen for the first time is **19 KB**. Before this the phone pulled
about 300 KB gzipped per channel per refresh and parsed two megabytes of markup
to do it.

**At most once a day per channel.** A channel-approval app is not a news feed:
learning about an upload eleven hours late means it appears tomorrow, and asking
on every app open costs somebody's data allowance. A newly approved channel has
never been fetched, so it fills immediately.

Videos, titles, upload times and poster URLs live in SQLite next to the approved
channels. A reply **replaces** a channel's list rather than merging into it —
the Worker's answer is what the channel has *now*, so a video missing from it
was deleted, made private, or pushed past the hundred. Merging would slowly fill
the grid with tiles that play nothing. A reply that parses to nothing changes
nothing: a stale grid beats an empty one, which is also what a phone with no
signal gets.

On the Worker's side, two requests make that answer. The **uploads playlist
page** lists exactly a hundred videos in upload order and carries no dates; the
**Atom feed** for the same playlist carries the newest fifteen with real
timestamps. Videos the feed dates keep those dates; everything below is placed
one second apart in the page's order — a sort key that preserves what is
actually known, not a claim about when something was posted. If the page comes
back empty the feed is the whole answer, at fifteen videos.

**No Shorts, ever.** Both requests name the channel's `UULF` playlist — its
uploads with Shorts removed, by YouTube's own classification — rather than the
plain `UU` one. Nothing in this app decides what a Short is, because nothing in
the data says: the page reports every entry as a video either way, and duration
is not the rule. YouTube sorts by aspect ratio, so filtering by length would
drop exactly the short, wide videos a children's channel posts while keeping
three-minute vertical Shorts. Measured against a live channel: `UU` held 100
videos of which 48 were on the channel's Shorts tab; `UULF` held 100 with an
intersection of zero.

The gate is the device's own lock, so this app never invents or stores a secret
of its own — it only learns whether the platform's check passed. On a device
with **no** lock set up there is nothing to authenticate against, and it falls
back to arithmetic: given `X + Y` and `X − Y`, name both `X` and `Y`, with the
numbers re-rolled on every wrong answer so guesses can't converge. That
fallback is a speed bump rather than a lock — if it is what your device gets,
setting a screen lock is the fix.

## The approved channels

The list button sits second-from-last in parent mode's bar, and opens the
approved channels on a screen of their own. The bar reads **← Kids mode**, then
a gap, then **+/−**, the list, and the gear last — the same on both platforms,
so a parent moving between the two apps finds the same controls in the same
places.

Tapping a channel goes back to it in parent mode. The **✕** removes it, and
removing takes everything that channel put on the device with it: its videos,
its watch history, and its cached pictures.

**Channels can be grouped.** Long-press to start selecting, pick two or more,
and tap **Group**; the name is asked for on the way in, and a name that is blank
or already taken disables the button with the reason under the field rather than
failing after the tap. Selecting everything in one group offers **Ungroup**
instead — which takes them out of the group without un-approving anything.

A group always has at least two channels. One that drops to a single member
dissolves, and that member becomes an ordinary loose channel again; there is no
such thing as a group of one, so nothing has to explain what that would mean.

Groups lead the list, A–Z among themselves, whatever order you picked for the
channels — a group is not "recently added" and you want it where it was last
time. The order you picked arranges the channels themselves: inside each group,
and in the loose list below.

The child's Channels tab shows the same arrangement, read-only. Tapping a group
narrows the grid to everything in it; each of its channels is still listed
individually, so reaching one of them never depends on understanding what a
group is.

## Settings

The gear is the last control in parent mode's bar, and holds the parent's
choices, the version number, and the update controls where there are any. (iOS
has no update controls to place — see **Platform differences**.)

**Every explanation is behind the ? beside its heading.** They used to sit under
the headings in grey, permanently, which meant a parent scrolling for the hold
slider read three paragraphs about things they already understood to reach it.
The words are unchanged — they are just no longer between you and the controls.
Android shows a small popup anchored to the **?**; iOS shows an alert, because
SwiftUI keeps a popover a popover on an iPhone only from 16.4 and the app
targets 16.0.

**When a video ends** — what plays next.

- **Play the next one down the list** — walks the grid in order and stops at the
  end of it. A session has an edge.
- **Play a random one** — picks another video from the same list, never the one
  that just played.

Either way "the list" is whatever was on screen when the child tapped: every
approved channel's uploads, or one channel's if they opened it from the Channels
tab. A video started inside a channel cannot lead out of it. That falls out of
how the player is started rather than from a rule inside it — it is handed the
list it should be walking, so there is nothing for a bug to get wrong.

**Hold to unlock the player** — a slider, one to five seconds, defaulting to
one. One rather than Android's own half-second long-press, which is short
enough for a resting thumb to complete. Five is the ceiling because a hold
nobody will sit through is not a stronger lock, it is a control an adult gives
up on; what keeps a child out is that the corner is invisible and somewhere
nothing else is.

The app's name and version lead the screen, and the update controls close it.
Both used to be an About screen of their own, opened by long-pressing the grid's
title — a parent-facing screen on the child's side of the app, behind a gesture
nobody would guess was there.

## How the safety boundary works

Every decision that can put the wrong thing in front of a child lives in a file
with no Android in it, so a plain JVM can test it — and CI runs those tests
before anything is published:

| File | Decides |
| --- | --- |
| `VideoId.kt` | which video ids are usable at all |
| `Player.kt` | where the player's WebView may navigate |
| `YouTubeUrls.kt` | channel ids, which playlist is fetched, where parent mode may browse |
| `Uploads.kt` | what the app accepts from the Worker's reply |
| `Challenge.kt` | the arithmetic fallback gate |
| `Schema.kt` | the SQL behind the approved list |
| `Library.kt` | how uploads become the grid, and its order |
| `Playlist.kt` | what plays after a video ends |
| `ChannelSort.kt` | what order the approved channels are listed in |
| `ChannelGroups.kt` | how channels gather into groups, and when one dissolves |
| `Chrome.kt` | where YouTube's seek bar is, from the pixels |

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

`worker.js` parses both sources with regex on purpose. Nothing in either is
trusted, and every id goes through a pattern there and through `VideoId` again
on the phone before it can become a tile — so the worst a malformed or hostile
response can do is yield *fewer* videos, never a bad one. A DOM parser would add
an XXE surface for correctness that isn't needed, and a JSON parser aimed at two
megabytes of someone else's app state would buy about as much.

The phone re-checks what its own server sends, which is not paranoia about the
server: an id ends up in a URL *and* in a JS string literal inside the player,
and a stored thumbnail URL is later fetched and drawn. "Our own Worker said so"
is not the same kind of assurance as a pattern match at the point of use.

The playlist page is the one place anything here reads something YouTube
publishes for its own web app rather than for consumers, so it is deliberately
the *optional* half: the shape it looks for can be renamed without warning, and
when it is, every channel quietly drops back to the Atom feed and 15 videos
instead of breaking. `worker.test.mjs` pins the parser against three entries
lifted verbatim from a live page and runs in CI, so a rename shows up as a
failing check rather than as an empty grid.

On top of that the player disables the long-press context menu, popups, file and
content access, and acts the moment a video ends — loading the next one, or
closing if there is none — before the end-screen grid of related videos can be
tapped.

`Chrome.kt` is the odd one on that list, and worth a word because of how it gets
its input. A native overlay covers the player so none of YouTube's controls can
be touched; holding the top-right corner lifts it for an adult, for as long as
the settings say. The corner is invisible, and glows for a second when a video
starts and each time the overlay comes back — so an adult can see where it is
without a coloured wedge sitting over the picture while a video plays.

The first glow waits for playback rather than firing when the screen opens: a
web view shows a black rectangle while it loads, and a glow spent against that
is one nobody sees.

Lifting the overlay also reveals the **way out** — a back control, top-left,
clear of the corner that summoned it. It is an adult's control and it appears
with the other adult controls rather than sitting over every video a child
watches. On Android the system back button has always done this; what is new is
that something on screen says so, and that iOS has it at all, having had no
system back to fall back on.

**The overlay does not drop back while a video is paused.** Pausing is what an
adult does to read something on screen, to look at where the scrubber is, or to
hand the phone over — none of which produce touches, and all of which would
otherwise end with the overlay returning mid-sentence. So the countdown follows
playback: it runs while the video does, and every touch restarts it. A player
left paused and revealed stays that way until the screen is left.
A strip along the bottom stays blocked even then, so a scrub that slides off the
seek bar lands on nothing. Knowing how tall that strip should be means knowing
where the seek bar is — and the player is a cross-origin iframe, so it cannot be
asked. It can be looked at: the app copies the bottom of the player's own
on-screen pixels into an in-memory bitmap and finds the bar's red played portion
in them. A margin under the bar is left reachable, because the drawn line is
under 4dp and a thumb aiming at it lands around it.

The copy is taken while the controls are actually up — a few hundred
milliseconds after playback starts, and again after any touch while the overlay
is lifted — retrying until a frame has a bar in it. A frame without one stores
nothing and asks for another; only a successful measurement is kept. While the
overlay is lifted the blocked strip tints faintly, so an adult who has just
unlocked the controls can see where the live area ends rather than finding the
bottom of the screen mysteriously dead.

Nothing about that is written down in the app. The bar's own drawn thickness —
about 3dp — is the scale, measured from the same pixels, so how much room to
leave under it and what counts as an implausible answer are expressed as
multiples of it. Ratios hold at any resolution, which is why no part of this
converts dp to pixels or asks the device its density. The tests lay the same
layout out at seven device geometries and check that doubling the resolution
doubles the answer.

The result is saved, keyed to the display it was measured on, so it survives a
restart instead of reverting to the fallback until someone pauses a video. A
foldable opening, a change to Android's display-size setting, or a restore onto
a different phone all change that key, and the app measures again.

That capture is a measurement, not a picture. Only that bottom strip is ever
drawn, so the part of the screen with the video in it is never captured at all;
the bitmap is read into an array and recycled inside the method that made it; it
is never written to storage, handed to another component, or sent anywhere. It
happens once per process, and every failure falls back to the compiled-in
constant.

This is a lock on the front door, not a guarantee about YouTube itself. The
videos are served by YouTube.

### Signed in — what actually controls it

Two separate things were confused here for a while, so be precise about them:

- **The wrapper** is the little HTML page the app builds and hands to the web
  view. It runs on `www.youtube-nocookie.com`.
- **The player** is a YouTube `/embed/` iframe inside that page, and it has
  always been on `www.youtube.com`. The IFrame API only points at the nocookie
  domain if you pass it `host:`, and neither platform ever has.

So the player is **third-party** to the wrapper, and whether it carries your
Google session — and therefore whether YouTube Premium applies — is decided by
**cookie policy**, not by the wrapper's origin.

That was learned the hard way. The wrapper was moved to `www.youtube.com` to
pick up the session, which **broke playback on both platforms**: every video
came up *"Video unavailable"*, because a synthetic document cannot prove it has
that origin and YouTube's embed declines to serve a player to one that claims
it. That change is reverted and pinned by a unit test on both platforms.

**Android now opts into third-party cookies for the player**, which is the
lever that was there all along. If your account has Premium, the player should
play without ads. It is deliberately a probe rather than a settled feature: the
cost — third-party cookies on the child's screen — is unconditional, and if the
benefit does not materialise the line comes back out.

**iOS cannot do the same thing.** WKWebView's tracking prevention blocks
third-party cookies for a domain like `youtube.com`, and no public API turns
that off outside an app with browser entitlements. Getting Premium there means
giving the wrapper a real origin — serving that page from the Worker as a URL
rather than building it in the app — or navigating straight at
`https://www.youtube.com/embed/<id>`, which is first-party but gives up the
error signal the player uses to skip dead videos and makes every link YouTube
draws a top-level navigation, so the allowlist would have to match on path as
well as host.

Either way: **what a child watches is only attached to a Google account when
the player is signed in.** The app's own watch history is device-only and never
uploaded, under every one of these arrangements.

## Automated, signed updates

Every push to `main` that touches `android/` builds a release APK and publishes
it to a fixed `android-latest` release.

- **Signing**: one keystore signs every build. Android only installs an update
  over an existing app when the signatures match, and CI runners would otherwise
  mint a random key per run — so what this buys is signature *stability*, and
  losing the key is unrecoverable. It is held as two repository secrets and
  written out at build time. It used to be committed, and it was replaced rather
  than merely hidden when this went public; the note below says why, and what
  that cost.
- **Versioning**: `versionCode` is the workflow run number, which is what the
  updater compares. `versionName` is a display-only `<year>.<week>.<patch>`.
- **Publish order**: the APK is uploaded first, `version.json` second and in its
  own call. Uploaded together they race, and the tiny manifest goes live long
  before the APK finishes — a window on every release where apps are told about
  a build they then fail to download.
- **Build reuse**: publishing a merge normally reuses the APK the pull request's
  own run built minutes earlier, rather than compiling the same code twice — a
  publish drops from about two and a half minutes to about thirty seconds. Each
  build records the SHA of the `android/` tree it came from — git's own content
  hash, nothing to commit and nothing to exclude — and the publish reuses it
  only if that is what main now holds. Only `android/`, because that is
  everything the APK is made of, so a docs-only merge in between costs nothing.
  If the hashes differ the publish builds *and re-runs the tests*, because at
  that point no run has tested the code that is about to ship.
- **Installing**: the app checks on every foreground, pre-downloads a newer APK,
  and offers it in a notification. The install itself always waits for a tap,
  because it restarts the app — it is never applied mid-video. From the first
  self-performed update onward the app is its own installer of record, so
  Android 12+ applies later ones with no further confirmation.
- **Notifications**: asked for on first launch, because the notification is the
  only thing that says an update is ready. If they're off, the settings screen
  says so and offers to turn them on — and its **Check for updates** button
  works regardless. Tapping the notification opens the **grid**, never the
  settings screen directly: a notification sits on the lock screen where a child
  can reach it, so it must not lead PAST the gate — but it may lead TO it. What
  it actually does is ask, with an extra the grid consumes on arrival: the same
  challenge the Parent button runs, and settings only on a pass. A failed answer
  leaves the child on the grid. **And it does not ask twice** — a parent already
  in parent mode, or in the settings inside it, has just passed that gate, so
  the tap goes straight through; backgrounding the app owes the gate again. Its
  **Install** action still works in one tap without opening anything.

Two one-time hurdles on the very first update: the app needs Android's "Install
unknown apps" permission, and Android shows one confirmation while the app
claims update ownership. Silent from then on.

### About that committed key

The key was committed here, at `android/signing.p12`, with its password in
`build.gradle.kts` — mirroring how `vtlinh/novels` does it. That was a stated
trade: anyone with repo access could sign an APK Android installs over this one
as an update, which was acceptable while the repo was private.

Going public ended it, and **deleting the file would not have been enough.**
GitHub keeps every pull request's refs permanently and independently of
branches: 76 of them carried the keystore, and on a public repository anyone can
fetch them. A history rewrite does not touch a single one. The scrub would have
felt complete and left the key downloadable from 76 places — which is the worst
outcome available, because it is the one you stop checking.

So the key was **rotated**, not hidden. A leaked key that signs nothing is
harmless, and that is a property no amount of scrubbing could have bought. The
old key is still in the history and is now a dead artifact.

**The cost, stated plainly: every copy installed before this must be
uninstalled and reinstalled by hand, losing its approved channels and watch
history.** Android refuses an update whose signing certificate changed, and this
one did:

| | certificate SHA-256 |
| --- | --- |
| old, now dead | `74:7B:05:92:…:46:4B:24:9F` |
| current | `D1:A7:D2:1D:…:C9:F9:06:D1` |

Self-update will not rescue those phones. The updater downloads the new APK and
the install fails on a signature mismatch, so the app sits on its last old-key
version until somebody reinstalls it.

APK Signature Scheme v3 rotation could have avoided that on Android 9+, by
carrying a lineage proving the old key authorised the new one. It was not used:
`minSdk` is 26, so the oldest devices would have been stranded regardless, and
it cannot be verified anywhere but CI.

The key now lives in `ANDROID_KEYSTORE_B64` and `ANDROID_KEYSTORE_PASSWORD`,
written out by `android.yml` at build time. **One** thing guards it, and it is
worth being exact about which:

- `android.yml` reads the certificate fingerprint back out of the APK it just
  built and fails unless it matches. The build-reuse hash covers `android/` and
  the key no longer lives there, so swapping the secret would change what ships
  without forcing a rebuild; this checks the artifact instead of inferring from
  the tree.

`build.gradle.kts` does **not** refuse to assemble an unsigned release, and this
paragraph said it did. With no key — `android/signing.p12` missing, or
`ANDROID_KEYSTORE_PASSWORD` blank — `canSign` is false, the release build type
simply gets no signing config, `assembleRelease` succeeds, and the output is
`app-release-unsigned.apk`. An unsigned build is not a broken artifact: it is a
working one that installs on none of the phones that matter, which is why the
check above is on the finished APK rather than on the build. In CI the first
thing that notices a missing key is `apksigner` failing to find
`app-release.apk`; locally you get an unsigned APK and no warning.

**Keep a copy somewhere durable.** A GitHub secret is write-only — nothing reads
it back — so the secret is not a backup. Lose the keystore and every installed
copy is stranded permanently, including by its author.

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

## Naming

Everything is **TinyTube**: the label, the Kotlin package, the `applicationId`
(`dev.vtlinh.tinytube`) and the Worker (`tinytube.vtlinh87.workers.dev`).

The last two were renamed together, once, and that pairing is the only way it
can be done. It is worth writing down why, so nobody does it again casually.

**The `applicationId` is how Android identifies an installed app.** Changing it
means this build cannot update the one already on a phone: it installs *beside*
it, with its own data directory. The approved channels, watch history and
settings of the old app are not visible to the new one. There is no migration —
the old app has to be uninstalled by hand and its channels approved again.

**The Worker's hostname is compiled into `Endpoints.kt`.** A renamed Worker
cannot tell an installed app where it went, because the only thing that could is
an update the app would have to fetch from the hostname it no longer has. That
is survivable exactly once, and only because it happened alongside the
applicationId: the app carrying the new hostname is a fresh install, not an
update to something that was pointed at the old one.

The old hostname did **not** survive the rename, and this is the part that went
differently from the plan. Cloudflare's git-connected build **renames the
existing service** rather than standing a second one up beside it, so
the previous hostname began answering 404 the moment the new name
deployed. Every app already installed lost its uploads feed and its update check
in the same instant, with nothing able to tell it where to look.

Those apps still play what is already in their grid — videos come from YouTube
directly — but they will never learn about a new upload or a new build again.
Installing the new app by hand is the only recovery, which makes the migration
below mandatory rather than tidy-up.

### Migrating a phone

1. Install the new APK:
   **https://tinytube.vtlinh87.workers.dev/app/app-release.apk**
2. Uninstall the old app. Both are called TinyTube in the launcher; the old one
   is the one whose package does not read `dev.vtlinh.tinytube` under
   Android's app info.
3. Approve the channels again, in parent mode.

The old app cannot update itself to the new one — its update check points at a
hostname that no longer answers — so step 1 has to be a manual download.

## Platform differences

The two apps agree unless this table says otherwise. Silence here means they
match, so anything that can only exist on one platform belongs in it.

| | Android | iOS | why |
| --- | --- | --- | --- |
| **Self-update** | Yes — checks the Worker, downloads, installs on a tap | **No** | iOS has no `PackageInstaller` equivalent and a sideloaded app cannot install its successor. On the free Apple ID tier there is no delivery channel at all: builds are sideloaded by hand. See **Distribution** below. |
| **Update notification** | Yes | No | Nothing to notify about without self-update, and the free tier has no push entitlement either. |
| **Parent gate fallback** | Device lock, arithmetic if the device has none | Device lock; arithmetic present but nearly unreachable | `LocalAuthentication`'s `deviceOwnerAuthentication` falls back to the passcode by itself, so only a device with no lock at all reaches the arithmetic. |
| **Player bottom blocker** | Measured from the player's own pixels, silently, as often as it likes | Measured **once per install**, at the cost of one consent alert | Only ReplayKit returns the composited pixels of a playing video, and it asks the user first. So iOS captures once, stores the answer per display, and never asks again. |
| **What the capture can see** | The bottom strip only — `PixelCopy` takes a source rectangle, so the video is never captured at all | The whole frame arrives; only the strip is read out of it | ReplayKit has no source rectangle. Weaker than Android's guarantee, and the closest iOS allows. |
| **Approving from your own subscriptions** | Yes — sign in to Google inside parent mode | **Best-effort, and expected to break** | Google blocks account sign-in from embedded webviews. Android evades the check by dropping one user-agent token; the iOS equivalent is adding two. Same workaround, same fragility, and no sanctioned replacement — see the spike below. |
| **App lifetime before it stops launching** | Indefinite | **7 days** | Free-tier provisioning profiles expire after a week. Re-sideload to reset it. |
| **How many can be installed** | No limit | **3 sideloaded apps** at once, across all apps | A free Apple ID limit, not something this app can spend. |

### The two spikes that shaped the iOS app

Both were run before any app code, because either answer would have changed the
design. Neither could be settled by writing Swift: there is no Mac in the
development environment, and — for the first one — a simulator would have given
the wrong answer confidently. What follows is the evidence and what it cost.

#### Can the app read back the pixels of a playing video? **No.**

Android's blocker is measured because `PixelCopy` reads the *composited* window,
hardware video surface included. That is the whole reason `WebView.draw(Canvas)`
failed there and `PixelCopy` did not: one is a software repaint, the other is
the real screen.

iOS has no `PixelCopy`. Each candidate fails for the same reason:

- **`WKWebView.takeSnapshot(with:)`** is a software-painted snapshot. WebKit's
  Tim Horton, on the bug that introduced the API: *"a software painted snapshot,
  meaning that 3D transforms will be flattened and ugly, and video/WebGL
  may-or-may-not work."* That is `WebView.draw(Canvas)` by another name.
- **`CALayer.render(in:)`** walks the layer tree the app owns. The video is not
  in it — it is composited out of process.
- **`UIView.drawHierarchy(in:afterScreenUpdates:)`** was meant to be the one
  that captures special layers, and it returns black where the video is. **On
  devices only — it works in the simulator.** Anything "verified" on a simulator
  here is verified wrong, which is the trap this spike existed to avoid.
- **ReplayKit** genuinely captures the composited screen, and is what iOS uses.

That last one was written off at first, on the grounds that it hands over a
picture rather than a number. That reasoning was wrong and is worth correcting
rather than quietly dropping: Android's `PixelCopy` hands over a bitmap too. The
rule is about **retention** — read it, recycle it, never store or send it — and
a discarded ReplayKit frame satisfies that exactly as a discarded Android bitmap
does.

What is genuinely different is that ReplayKit **asks**. It shows "TinyTube would
like to record your screen", once per app process and again after eight minutes
in the background, and on this app a child can be the one looking at that alert.
That is a reason to capture rarely, not never. So iOS measures **once per
install**: the answer goes to `BlockHeightStore` keyed by display and by a
version number, and after that no capture is ever started again. A device where
the capture never yields a usable frame gives up after three launches rather
than prompting on every one, and a bumped version revives it.

Two rules carry over from Android unchanged, because both were paid for there. A
failed capture **stores nothing** — `Chrome.blockHeight` returns nil for "could
not tell" precisely so a blank frame cannot be written down as an answer, which
is the bug that made the feature silently do nothing on a real phone. And the
stored value is keyed by a **version**, so a fix to the measurement actually
reaches a device that already wrote a wrong answer.

One difference remains and cannot be closed. Android passes `PixelCopy` a source
rectangle, so only the bottom strip is ever copied and the part of the screen
with the video in it is never captured at all — true by construction. ReplayKit
has no such parameter: whole frames arrive, and this reads only the strip's rows
out of them. Nothing above the strip is ever copied into memory the app owns,
and nothing is written, handed on, or sent — but the frame does exist briefly in
a buffer the system owns, and that is weaker than Android's guarantee.

#### Does Google sign-in work inside `WKWebView`? **Only by lying, same as Android.**

Google blocks account sign-in from embedded webviews — you get "This browser or
app may not be secure" instead of a login form. It has been enforced against the
OAuth endpoint since September 2021 and against ordinary account sign-in since
July 2023.

Android already lives with this. `ParentActivity` drops the `; wv` token Android
puts in its user agent, and the comment there is honest about what that is: *"a
workaround for a deliberate restriction, not a supported path"*.

iOS needs the mirror image. `WKWebView`'s default user agent is Safari's string
with `Version/… Safari/…` **missing**, and that absence is the tell. Adding it
back via `applicationNameForUserAgent` is the same evasion pointed the other
way: Android removes a token, iOS adds one.

The sanctioned alternative does not fit, and it is worth writing down why so
nobody re-proposes it:

- `SFSafariViewController` and `ASWebAuthenticationSession` are what Google tells
  you to use. Neither lets the app read the current URL — and parent mode's whole
  interaction is *"you are looking at a channel, here is a **+** to approve it"*.
  A browser the app cannot see the address of cannot drive that button.
- Signing in there does not help the webview either. `SFSafariViewController`
  shares cookies with **Safari**; `WKWebView` has its own `WKWebsiteDataStore`.
  The session does not cross, so this cannot even be used as a one-time
  sign-in step.

So: same trick as Android, same expectation that it stops working one day.
**When it does, signed-out browsing still works** — every channel is reachable
and approvable without an account. What is lost is only starting from your own
subscriptions, which is a convenience rather than the feature. That is the
fallback, and it needs no code.

## Distribution

Android publishes itself. iOS cannot, and the free Apple ID tier is what the
owner has chosen for now, so the chore is real and worth stating plainly.

CI builds an **unsigned** `.ipa`. It cannot sign: free provisioning is
interactive-Xcode-only, so there is no credential a workflow could hold even in
principle. The owner sideloads it with **AltStore** or **Sideloadly**, from a
Windows PC or a Mac.

### The two download links

Both platforms have one fixed URL that always serves the newest build, so
neither needs anyone to go hunting through Actions runs:

- **Android** — https://tinytube.vtlinh87.workers.dev/app/app-release.apk
- **iOS** — https://tinytube.vtlinh87.workers.dev/ios/TinyTube.ipa

They exist for different reasons, which is worth keeping straight. The Android
one is what the **app itself** polls to update; the iOS one is for a **person
with a browser**, because nothing on iOS self-updates. What they share is why
they go through the Worker at all — and the reason has changed. They were added
while this repository was private, when its release assets answered 404 to
anyone without a credential and the Worker held a read-only one. Public assets
need no credential, so what keeps the Android path is that it is compiled into
every installed app: the URL above is what a phone polls, and moving it strands
every copy already out there. The iOS one stays because a stable link a person
can bookmark beats hunting through Actions runs.

`/ios/version.json` says which build the iOS link is currently serving. Nothing
reads it — it is there to answer "is this the one I already installed?".

**Ignore the date GitHub prints on the releases page.** Both tags are fixed and
reused forever, so their `published_at` is the day the tag was first created and
never moves again: `android-latest` read "3 days ago" while holding an APK built
twenty minutes earlier. There is no way to correct it — the only thing that
moves `published_at` is toggling the release back to a draft and republishing,
and a draft's asset URLs 404, which is the exact path installed phones use to
fetch an update. So each publish writes the build into the release NOTES
instead: versionCode, version name, build time and commit. The header stays
wrong; the body is right.

**Weekly**, because free-tier profiles expire after seven days and the app then
refuses to launch until it is re-signed. Three sideloaded apps is the ceiling on
the whole device, not just this one.

If that weekly chore stops being worth it, the $99/year Apple Developer Program
changes the shape of it rather than merely easing it: CI could sign and upload
to TestFlight, builds would arrive over the air with no computer involved at
all, and the cycle would go from 7 days to 90. That is the upgrade path, and
nothing in the app has to change to take it.

## Building locally

```bash
gradle -p android testReleaseUnitTest   # id, player, gate, uploads and schema tests
gradle -p android assembleRelease       # → android/app/build/outputs/apk/release/
swift test --package-path ios/TinyTubeCore   # the same tests, in Swift
node --test worker.test.mjs             # the Worker's parsers
npx wrangler deploy --dry-run           # checks the Worker still bundles
```

All four run in CI before anything is published, and all four run without a Mac
— which is the point of `TinyTubeCore` being a package rather than a folder in
the app target.

The iOS **app** is the exception, and needs Xcode:

```bash
brew install xcodegen && (cd ios && xcodegen generate)
open ios/TinyTube.xcodeproj
```

`TinyTube.xcodeproj` is generated and not committed — a `pbxproj` is thousands
of lines of generated identifiers, unreviewable in a diff and conflicting on
every branch that adds a file. `ios/project.yml` is the same thing in a form a
person can read, and CI regenerates the project before it builds.

You do not need any of this to get a build. That is what the `ios-app` job is
for; see **Distribution**.

Local builds get `versionCode 1` and `versionName "dev"`, since both come from
CI environment variables.

Merging is automated: `auto-merge.yml` merges a pull request once `build`,
`worker`, `ios-core` and `ios-app` have all passed and nothing else on the
commit has failed. Label a PR `no-auto-merge` to hold it open.

It then dispatches the publish for whichever platform the PR actually touched —
a merge with no `ios/**` in it publishes no IPA, and one with no `android/**`
ships no `versionCode`. Each publish re-serves the artifact the pull request
already built when the tree has not moved since, so an ordinary merge costs a
release upload rather than a second build. On iOS that check happens on an
ubuntu runner **before** the macOS one starts, because a macOS minute bills at
ten.

## Pull requests that fix themselves

`claude-autofix.yml` closes the other half of the loop. When `android` fails on
a pull request it reads the failing job's log, runs Claude Code over the
checkout, verifies the fix with the same Gradle commands CI uses, and pushes.
The new run then goes green and `auto-merge.yml` merges it — so a red PR
resolves itself without anyone watching for it.

Two secrets:

| secret | what happens without it |
| --- | --- |
| `ANTHROPIC_API_KEY` **or** `CLAUDE_CODE_OAUTH_TOKEN` | the workflow warns and skips; nothing is fixed |
| `AUTOFIX_TOKEN` — a PAT with `repo` scope | the fix is still pushed, but see below |

`AUTOFIX_TOKEN` matters more than it looks. A push made with the default
`GITHUB_TOKEN` deliberately does not trigger further workflow runs, so a fix
pushed with it lands on the branch and `android` never re-runs: the PR keeps
the red check it already had, and `auto-merge.yml`, which waits for a passing
run, waits forever. With a personal access token the push looks like a
person's and CI starts again on its own. Without one, the fix is written and
somebody has to push an empty commit to set it going.

Three things stop it running away:

- **Three attempts per branch.** Counted from the `Auto-fix CI` commits on the
  branch itself, so it survives across runs. After that it comments and stops.
- **An empty diff is a valid outcome.** Told to change nothing when the cause
  isn't clear or the failure looks infrastructural, it says so on the PR rather
  than inventing a change.
- **A fix that touches `.github/workflows/**` is pushed but labelled
  `no-auto-merge`.** A model editing the workflow that judges it can switch off
  the judging, and nothing downstream would notice. `no-autofix` on a PR opts
  out of the whole thing.

Tests are **not** on that list. They were, and the guard was removed
deliberately: a fix that changes behaviour has to change the test that pinned
the old behaviour, so holding every such PR stopped the loop on the ordinary
case rather than the dangerous one. What remains is an instruction in the
workflow's prompt — updating an expected value because the behaviour
legitimately changed is fine; relaxing a matcher, deleting a case, or widening
an input set until the failure stops is not.

Worth being plain about the consequence, because it is the one place this
repository trades safety for convenience: a run that quietly weakens an
assertion will now merge itself, and the unit tests are what stand between a
bad video id and the player. Red merely stops; green publishes to every
installed device. If that trade stops looking worth it, the guard is four lines
in `claude-autofix.yml`.

---

By Linh Vu.
