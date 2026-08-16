/* Tests for the parsing half of the Worker.
 *
 * This code used to live in the app, in Feed.kt, where the Kotlin unit tests
 * covered it. Moving it here to save the phone two megabytes per channel moved
 * it out from under those tests — and it is the piece most likely to break
 * without anyone touching it, because it reads a rendering of YouTube's own
 * web app rather than anything published for consumers.
 *
 * So the same fixture follows it: three entries lifted verbatim from a live
 * uploads playlist page. When YouTube renames the shape, this is what says so.
 *
 *   node --test
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  CHANNEL_ID,
  VIDEO_ID,
  HANDLE,
  longFormPlaylistId,
  parseUploadsPage,
  parseClockDuration,
  parseFeed,
  jsonUnescape,
  xmlUnescape,
  datePositions,
  thumbnailUrl,
  parseChannelId,
  parseChannelTitle,
  parseChannelAvatar,
  parseChannelSearch,
  searchQueryFrom,
  channelSearchUrl,
  channelTargetFromUrl,
  RELEASE_ASSETS,
  contentType,
} from "./worker.js";

const OK_CHANNEL = "UC" + "a".repeat(22);

/* The shapes YouTube serves, in the nesting the real page uses. */
const lockup = (id, title, clock) => `{"lockupViewModel":{"contentId":"${id}",
  "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":
  {"videoId":"${id}"}}}}},
  "metadata":{"lockupMetadataViewModel":{"title":{"content":"${title}"}}}` +
  (clock
    ? `,"contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"${clock}","badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"}}]}}]}}`
    : "") +
  `}}`;

const renderer = (id, title, clock) =>
  `{"playlistVideoRenderer":{"videoId":"${id}","title":{"runs":[{"text":"${title}"}]}` +
  (clock ? `,"lengthText":{"simpleText":"${clock}"}` : "") +
  `}}`;

const page = (...entries) =>
  `<!doctype html><script>var ytInitialData = {"contents":[${entries.join(",")}]};</script>`;

const feed = (...entries) =>
  `<?xml version="1.0"?><feed>${entries.join("")}</feed>`;

const feedEntry = (id, title, published, seconds) =>
  `<entry><yt:videoId>${id}</yt:videoId><title>${title}</title>` +
  (published ? `<published>${published}</published>` : "") +
  (seconds != null ? `<yt:duration seconds="${seconds}"/>` : "") +
  `</entry>`;

/* ---- the playlist id ---- */

/* The whole of how Shorts stay off a child's screen. UU here instead of UULF
 * puts them back, silently, with nothing else to notice. */
test("names the long-form playlist, never the plain uploads one", () => {
  assert.equal(longFormPlaylistId(OK_CHANNEL), "UULF" + "a".repeat(22));
  assert.ok(longFormPlaylistId(OK_CHANNEL).startsWith("UULF"));
});

test("the id patterns are anchored", () => {
  assert.ok(CHANNEL_ID.test(OK_CHANNEL));
  for (const bad of ["", "UC", "nope", "UC" + "a".repeat(21), "UC" + "a".repeat(23),
                     " UC" + "a".repeat(22), "UC" + "a".repeat(22) + "&x=1"]) {
    assert.ok(!CHANNEL_ID.test(bad), `should have refused ${JSON.stringify(bad)}`);
  }
  assert.ok(VIDEO_ID.test("aaaaaaaaaaa"));
  for (const bad of ["", "short", "aaaaaaaaaa/", "aaaaaaaaaaaa", "../../etcpas"]) {
    assert.ok(!VIDEO_ID.test(bad), `should have refused ${JSON.stringify(bad)}`);
  }
});

/* ---- the page ---- */

test("reads the current page shape, in page order", () => {
  const v = parseUploadsPage(page(lockup("aaaaaaaaaaa", "First"), lockup("bbbbbbbbbbb", "Second")));
  assert.deepEqual(v.map(x => x.id), ["aaaaaaaaaaa", "bbbbbbbbbbb"]);
  assert.deepEqual(v.map(x => x.title), ["First", "Second"]);
});

test("reads the clock on the thumbnail badge", () => {
  const v = parseUploadsPage(page(
    lockup("aaaaaaaaaaa", "Short", "0:36"),
    lockup("bbbbbbbbbbb", "Long", "1:02:15"),
  ));
  assert.deepEqual(v.map(x => x.duration), [36, 3735]);
});

test("reads the older page shape", () => {
  const v = parseUploadsPage(page(renderer("aaaaaaaaaaa", "First")));
  assert.deepEqual(v.map(x => x.id), ["aaaaaaaaaaa"]);
  assert.equal(v[0].title, "First");
});

test("reads the older lengthText clock", () => {
  const v = parseUploadsPage(page(renderer("aaaaaaaaaaa", "First", "10:32")));
  assert.equal(v[0].duration, 632);
});

test("a missing clock is null, and is not borrowed from the next entry", () => {
  const v = parseUploadsPage(page(
    lockup("aaaaaaaaaaa", "No clock"),
    lockup("bbbbbbbbbbb", "Has one", "3:00"),
  ));
  assert.deepEqual(v.map(x => x.duration), [null, 180]);
});

/* The page is not trusted. An id goes into a URL and, on the phone, into a JS
 * string literal — the app revalidates every one of these on the way in. */
test("drops entries whose id is not a valid video id", () => {
  const v = parseUploadsPage(page(
    lockup("aaaaaaaaaaa", "Good"),
    lockup("short", "Bad"),
    lockup("bbbbbbbbbbb", "Good"),
  ));
  assert.deepEqual(v.map(x => x.id), ["aaaaaaaaaaa", "bbbbbbbbbbb"]);
});

test("drops a repeated id", () => {
  const v = parseUploadsPage(page(lockup("aaaaaaaaaaa", "One"), lockup("aaaaaaaaaaa", "Two")));
  assert.equal(v.length, 1);
  assert.equal(v[0].title, "One");
});

test("stops at the limit", () => {
  const many = Array.from({ length: 150 }, (_, i) => lockup(idAt(i), `Video ${i}`));
  assert.equal(parseUploadsPage(page(...many)).length, 100);
  assert.equal(parseUploadsPage(page(...many), 7).length, 7);
});

/* The only way this parser can produce a WRONG tile rather than a missing one. */
test("does not borrow the next entry's title", () => {
  const untitled = '{"lockupViewModel":{"contentId":"aaaaaaaaaaa"}}';
  const v = parseUploadsPage(page(untitled, lockup("bbbbbbbbbbb", "Not mine")));
  assert.deepEqual(v.map(x => x.title), ["aaaaaaaaaaa", "Not mine"]);
});

test("unescapes json escapes in titles", () => {
  const title = s => parseUploadsPage(page(lockup("aaaaaaaaaaa", s)))[0].title;
  assert.equal(title("Ten \\u0026 Two"), "Ten & Two");
  assert.equal(title('\\"Quoted\\"'), '"Quoted"');
  assert.equal(title("a\\/b"), "a/b");
  assert.equal(title("caf\\u00e9"), "café");
});

test("parseClockDuration reads M:SS and H:MM:SS, and refuses the rest", () => {
  assert.equal(parseClockDuration("0:36"), 36);
  assert.equal(parseClockDuration("10:32"), 632);
  assert.equal(parseClockDuration("1:02:15"), 3735);
  assert.equal(parseClockDuration("LIVE"), null);
  assert.equal(parseClockDuration("NEW"), null);
  assert.equal(parseClockDuration(""), null);
  assert.equal(parseClockDuration(undefined), null);
});

test("leaves an unknown escape alone", () => {
  assert.equal(jsonUnescape("a\\qb"), "a\\qb");
  assert.equal(jsonUnescape("trailing\\"), "trailing\\");
  /* The one that used to be dropped: a \u with fewer than four hex digits does
   * not match the four-hex branch, so "u" alone is the escape. Testing the
   * first character instead of the length turned it into parseInt("") -> NaN
   * -> a U+0000 spliced into a title a child's tile then drew. */
  assert.equal(jsonUnescape("a\\u12"), "a\\u12");
  assert.equal(jsonUnescape("a\\u12b"), "a\\u12b");
  assert.ok(!jsonUnescape("a\\u12").includes(String.fromCharCode(0)), "no NUL spliced in");
});

/* Every one of these is a real response: an error, a consent interstitial, the
 * mobile page after a redirect, a shape we no longer recognise. Each has to
 * come back empty so the caller falls through to the feed. */
test("returns empty rather than throwing on a page with nothing in it", () => {
  for (const junk of ["", "   ", "<html>Before you continue to YouTube</html>",
                      '{"lockupViewModel":', '{"lockupViewModel":{}}',
                      '<html>{"videoId":"aaaaaaaaaaa"}</html>']) {
    assert.equal(parseUploadsPage(junk).length, 0, JSON.stringify(junk.slice(0, 30)));
  }
});

/* ---- the feed ---- */

test("reads ids, titles and published times", () => {
  const d = parseFeed(feed(
    feedEntry("aaaaaaaaaaa", "First", "2026-07-29T15:58:06+00:00"),
    feedEntry("bbbbbbbbbbb", "Second", "2026-07-28T00:00:00+00:00"),
  ));
  assert.equal(d.get("aaaaaaaaaaa").published, 1785340686);
  assert.equal(d.get("aaaaaaaaaaa").title, "First");
  assert.equal(d.get("bbbbbbbbbbb").published, 1785196800);
});

test("an offset is honoured rather than ignored", () => {
  const utc = parseFeed(feed(feedEntry("aaaaaaaaaaa", "x", "2026-07-29T15:58:06+00:00")));
  const two = parseFeed(feed(feedEntry("aaaaaaaaaaa", "x", "2026-07-29T17:58:06+02:00")));
  assert.equal(utc.get("aaaaaaaaaaa").published, two.get("aaaaaaaaaaa").published);
});

test("an entry with no usable date still parses", () => {
  const d = parseFeed(feed(feedEntry("aaaaaaaaaaa", "x", null)));
  assert.equal(d.get("aaaaaaaaaaa").published, null);
});

test("reads yt:duration seconds from the feed", () => {
  const d = parseFeed(feed(feedEntry("aaaaaaaaaaa", "x", "2026-07-29T15:58:06+00:00", 212)));
  assert.equal(d.get("aaaaaaaaaaa").duration, 212);
  const none = parseFeed(feed(feedEntry("aaaaaaaaaaa", "x", "2026-07-29T15:58:06+00:00")));
  assert.equal(none.get("aaaaaaaaaaa").duration, null);
});

test("junk in place of a feed is an empty map, not a throw", () => {
  for (const junk of ["", "not xml", "<feed></feed>", "<feed><entry></entry></feed>"]) {
    assert.equal(parseFeed(junk).size, 0);
  }
});

test("unescapes xml entities and does not double-unescape", () => {
  assert.equal(xmlUnescape("Ten &amp; Two"), "Ten & Two");
  assert.equal(xmlUnescape("caf&#233;"), "café");
  assert.equal(xmlUnescape("&amp;lt;"), "&lt;");
});

/* String.fromCodePoint THROWS above U+10FFFF, and a throw here does not stay
 * here: it escapes parseFeed, uploadsFor and the request, so one malformed
 * entity turned /uploads and /channel into a 500 and threw away a hundred-video
 * page that had already parsed. Out of range is left as written. */
test("an out-of-range numeric reference is left alone rather than thrown on", () => {
  assert.equal(xmlUnescape("&#x110000;"), "&#x110000;");
  assert.equal(xmlUnescape("&#99999999;"), "&#99999999;");
  assert.equal(xmlUnescape("&#xFFFFFFFFFFFF;"), "&#xFFFFFFFFFFFF;");
  /* still inside range, so still decoded */
  assert.equal(xmlUnescape("&#x10FFFF;"), String.fromCodePoint(0x10ffff));
});

test("a feed carrying one is a parsed entry, not a thrown request", () => {
  const d = parseFeed(feed(feedEntry("aaaaaaaaaaa", "&#x110000;", "2026-07-29T15:58:06+00:00")));
  assert.equal(d.size, 1);
  assert.equal(d.get("aaaaaaaaaaa").title, "&#x110000;");
  assert.equal(d.get("aaaaaaaaaaa").published, 1785340686);
});

/* ---- the sort keys ---- */

test("dated videos keep the date the feed gave them", () => {
  const out = datePositions(
    [{ id: "aaaaaaaaaaa" }, { id: "bbbbbbbbbbb" }],
    new Map([["aaaaaaaaaaa", { published: 500 }], ["bbbbbbbbbbb", { published: 400 }]]),
    1000,
  );
  assert.deepEqual(out.map(v => v.published), [500, 400]);
});

test("a page with no clock takes the feed's duration", () => {
  const out = datePositions(
    [{ id: "aaaaaaaaaaa" }, { id: "bbbbbbbbbbb", duration: 36 }],
    new Map([["aaaaaaaaaaa", { published: 500, duration: 212 }]]),
    1000,
  );
  assert.deepEqual(out.map(v => v.duration), [212, 36]);
});

test("undated videos are placed below the last dated one, in page order", () => {
  const out = datePositions(
    ["aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd"].map(id => ({ id })),
    new Map([["aaaaaaaaaaa", { published: 500 }], ["bbbbbbbbbbb", { published: 400 }]]),
    1000,
  );
  assert.deepEqual(out.map(v => v.published), [500, 400, 399, 398]);
});

/* The property the grid's order depends on. A feed entry claiming to be newer
 * than something above it in the page is the case that would break it. */
test("the result always descends, even when a date disagrees", () => {
  const out = datePositions(
    ["aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"].map(id => ({ id })),
    new Map([
      ["aaaaaaaaaaa", { published: 100 }],
      ["bbbbbbbbbbb", { published: 900 }],
      ["ccccccccccc", { published: 50 }],
    ]),
    1000,
  );
  const keys = out.map(v => v.published);
  for (let i = 1; i < keys.length; i++) {
    assert.ok(keys[i] < keys[i - 1], `${JSON.stringify(keys)} is not descending`);
  }
});

test("thumbnails are the stable form, on the host that serves them", () => {
  const url = thumbnailUrl("aaaaaaaaaaa");
  assert.equal(url, "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg");
  assert.ok(!url.includes("?"), "a signed variant would expire in the database");
});

/* ---- the real page ---- */

test("reads a real uploads page", () => {
  const html = readFileSync(
    new URL("./android/app/src/test/resources/uploads-page.html", import.meta.url),
    "utf8",
  );
  const v = parseUploadsPage(html);
  assert.equal(v.length, 3);
  assert.deepEqual(v.map(x => x.id), ["RUZjwisAnHw", "rc6W2KuTBSs", "eCQwPYARIg8"]);
  assert.equal(v[0].title, "just need a video of him taking the hint #YouTubePartner");
  assert.ok(v[1].title.includes("hydraulic press"));
  /* The ampersand in this one arrives as & — the escape that makes
     jsonUnescape necessary. */
  assert.ok(v[2].title.startsWith("@Fredagainagain"));
  assert.deepEqual(v.map(x => x.duration), [36, 14, 15]);
  for (const video of v) assert.ok(VIDEO_ID.test(video.id));
});

function idAt(n) {
  const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";
  return "vid" + alphabet[Math.floor(n / 64)] + alphabet[n % 64] + "aaaaaa";
}

/* ---- channel resolution ----
 *
 * Ported from YouTubeUrlsTest.kt, which is where these lived while the phone
 * did the fetching. They came here with the code.
 */

test("reads the channel id out of a page", () => {
  const canonical =
    `<html><head><link rel="canonical" href="https://www.youtube.com/channel/${OK_CHANNEL}"></head></html>`;
  assert.equal(parseChannelId(canonical), OK_CHANNEL);

  /* "channelId" also appears in a watch page's payload, referring to the
     uploader — which is the channel we want there too. */
  const payload = `{"header":{"channelId":"${OK_CHANNEL}","title":"x"}}`;
  assert.equal(parseChannelId(payload), OK_CHANNEL);

  assert.equal(parseChannelId("<html>nothing here</html>"), null);
  assert.equal(parseChannelId(""), null);
});

/* The canonical link is a DECLARED identity. An href carrying anything after
   the id is not one, and must not be truncated into a match. */
test("a canonical href with trailing junk is not an id", () => {
  const trailing =
    `<link rel="canonical" href="https://www.youtube.com/channel/${OK_CHANNEL}/videos">`;
  assert.equal(parseChannelId(trailing), null);
});

test("reads the channel title, falling back to the page title", () => {
  assert.equal(
    parseChannelTitle(`<meta property="og:title" content="Some Channel">`),
    "Some Channel",
  );
  assert.equal(
    parseChannelTitle("<html><head><title>Some Channel - YouTube</title></head>"),
    "Some Channel",
  );
  assert.equal(parseChannelTitle("<html>nothing</html>"), null);
  assert.equal(parseChannelTitle(""), null);
});

/* Both sources are HTML and both are escaped. The phone stores this string
 * verbatim as the channel's name, so an undecoded one sits in the approved list
 * and on the child's Channels tab until the channel is approved again. */
test("decodes entities in the channel title, from either source", () => {
  assert.equal(
    parseChannelTitle(
      `<meta property="og:title" content="Ben &amp; Holly&#39;s Little Kingdom">`,
    ),
    "Ben & Holly's Little Kingdom",
  );
  assert.equal(
    parseChannelTitle("<html><head><title>Ten &amp; Two - YouTube</title></head>"),
    "Ten & Two",
  );
  assert.equal(
    parseChannelTitle(`<meta property="og:title" content="A &quot;B&quot;">`),
    'A "B"',
  );
});

/* Cosmetic, but the phone stores it and later fetches and draws it — so an
   og:image pointing anywhere other than YouTube's avatar hosts is refused
   rather than handed over. */
test("reads the avatar, and only from youtube's image hosts", () => {
  assert.equal(
    parseChannelAvatar(`<meta property="og:image" content="https://yt3.googleusercontent.com/a/x=s900">`),
    "https://yt3.googleusercontent.com/a/x=s900",
  );
  assert.equal(
    parseChannelAvatar(`<meta property="og:image" content="https://yt3.ggpht.com/a/y">`),
    "https://yt3.ggpht.com/a/y",
  );
  for (const bad of [
    `<meta property="og:image" content="https://attacker.example/a.png">`,
    `<meta property="og:image" content="https://yt3.ggpht.com.attacker.example/a">`,
    `<meta property="og:image" content="javascript:alert(1)">`,
    `<meta property="og:title" content="not an image">`,
    "",
  ]) {
    assert.equal(parseChannelAvatar(bad), null, `should have refused: ${bad}`);
  }
});

/* The apps send the URL they are standing on; this is what makes that safe.
   The caller's string is never fetched — it is taken apart and the address is
   rebuilt from the validated pieces. */
test("a channel url is taken apart and rebuilt", () => {
  assert.deepEqual(channelTargetFromUrl("https://m.youtube.com/@Mrwhosetheboss"), {
    handle: "Mrwhosetheboss",
    url: "https://www.youtube.com/@Mrwhosetheboss",
  });
  assert.deepEqual(channelTargetFromUrl(`https://www.youtube.com/channel/${OK_CHANNEL}`), {
    id: OK_CHANNEL,
    url: `https://www.youtube.com/channel/${OK_CHANNEL}`,
  });
  /* Trailing path is dropped rather than carried into the fetch. */
  assert.equal(
    channelTargetFromUrl("https://m.youtube.com/@some.channel/videos").url,
    "https://www.youtube.com/@some.channel",
  );
});

test("anything that is not a channel page is refused", () => {
  for (const bad of [
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",   // a watch page is not a channel
    "https://www.youtube.com/results?search_query=x",
    "https://www.youtube.com/",
    "https://www.youtube.com/feed/subscriptions",
    "https://www.youtube.com/x/@handle",             // handle must be the FIRST segment
    "https://www.youtube.com/channel/short",
    "https://attacker.example/@evil",                // wrong host
    "https://www.youtube.com.attacker.example/@evil", // lookalike host
    "https://yt3.ggpht.com/@evil",                   // an image host is not a page host
    "javascript:alert(1)",
    "file:///etc/passwd",
    "",
  ]) {
    assert.equal(channelTargetFromUrl(bad), null, `should have refused: ${JSON.stringify(bad)}`);
  }
});

/* Whatever a caller sends, the fetched address is one this Worker built. */
test("the rebuilt url is always youtube's own, however odd the input", () => {
  for (const url of [
    "https://m.youtube.com/@Mrwhosetheboss?si=tracking#frag",
    "https://youtube.com/@Mrwhosetheboss/",
    "https://WWW.YOUTUBE.COM/@Mrwhosetheboss",
  ]) {
    const t = channelTargetFromUrl(url);
    assert.ok(t, `should have accepted: ${url}`);
    assert.equal(t.url, "https://www.youtube.com/@Mrwhosetheboss");
  }
});

/* ---- channel search (no API key) ----
 *
 * Lifted from a live results page (Type=Channel filter) on 2026-08-15: two
 * channelRenderer objects, one with an https avatar and one protocol-relative.
 * When YouTube renames this shape, this is what says so. */

const searchEntry = (id, title, avatar) =>
  `{"channelRenderer":{"channelId":"${id}","title":{"simpleText":"${title}"},` +
  `"thumbnail":{"thumbnails":[{"url":"${avatar}"}]}}}`;

const searchPage = (...entries) =>
  `<!doctype html><script>var ytInitialData = {"contents":[${entries.join(",")}]};</script>`;

const COCO = "UCbCmjCuTUZos6Inko4u57UQ";
const CODY = "UCuekY-Lxc6H9NEFO3EzDNdA";
const HTTPS_AVATAR =
  "https://yt3.googleusercontent.com/yiljI_XQcX8X0p_E2S0APGrUs7tHBMukf5_3dhVzhbH-z5uhzpt88tUopQa7ngVwO1nGAAnr6A=s88-c-k-c0x00ffffff-no-rj-mo";
const REL_AVATAR =
  "//yt3.ggpht.com/yiljI_XQcX8X0p_E2S0APGrUs7tHBMukf5_3dhVzhbH-z5uhzpt88tUopQa7ngVwO1nGAAnr6A=s88-c-k-c0x00ffffff-no-rj-mo";

test("parses channel search results, including protocol-relative avatars", () => {
  const found = parseChannelSearch(
    searchPage(
      searchEntry(COCO, "Cocomelon - Nursery Rhymes", HTTPS_AVATAR),
      searchEntry(CODY, "CoComelon - Cody Time", REL_AVATAR),
    ),
  );
  assert.deepEqual(found, [
    { id: COCO, title: "Cocomelon - Nursery Rhymes", avatarUrl: HTTPS_AVATAR },
    { id: CODY, title: "CoComelon - Cody Time", avatarUrl: "https:" + REL_AVATAR },
  ]);
});

test("a search page that is not channels yields nothing, never a throw", () => {
  assert.deepEqual(parseChannelSearch(""), []);
  assert.deepEqual(parseChannelSearch("<html>nothing</html>"), []);
  assert.deepEqual(
    parseChannelSearch(`{"videoRenderer":{"videoId":"aaaaaaaaaaa","title":{"simpleText":"Nope"}}}`),
    [],
  );
});

test("a missing title falls back to the id; a hostile avatar is dropped", () => {
  const noTitle = `{"channelRenderer":{"channelId":"${COCO}","thumbnail":{"thumbnails":[{"url":"${HTTPS_AVATAR}"}]}}}`;
  assert.equal(parseChannelSearch(noTitle)[0].title, COCO);

  const badAvatar = searchEntry(COCO, "Coco", "https://attacker.example/a.png");
  assert.equal(parseChannelSearch(searchPage(badAvatar))[0].avatarUrl, null);
});

test("a search query is rebuilt into a youtube results url, never fetched as-is", () => {
  assert.equal(searchQueryFrom("  coco  melon "), "coco melon");
  const url = channelSearchUrl("cocomelon");
  assert.ok(url.startsWith("https://www.youtube.com/results?"));
  assert.ok(url.includes("search_query=cocomelon"));
  assert.ok(url.includes("sp=EgIQAg"));
  assert.equal(channelSearchUrl("https://evil.example/"), null);
  assert.equal(channelSearchUrl("http://youtube.com/results?q=x"), null);
  assert.equal(channelSearchUrl(""), null);
  assert.equal(channelSearchUrl("x"), null); // too short
  assert.equal(channelSearchUrl("a".repeat(81)), null);
  assert.equal(channelSearchUrl("foo<script>"), null);
});

/* The shape a handle has to have. It is what stands between /channel and a
   path a caller chose. */
test("handles are a fixed pattern, not a path", () => {
  for (const ok of ["SomeChannel", "a.b-c_d", "abc", "a".repeat(30)]) {
    assert.ok(HANDLE.test(ok), `should have allowed ${ok}`);
  }
  for (const bad of [
    "ab",                    // too short
    "a".repeat(31),          // too long
    "has space",
    "slash/es",
    "../../etc/passwd",
    "@leadingAt",
    "query?x=1",
    "a\nb",
    "",
  ]) {
    assert.ok(!HANDLE.test(bad), `should have refused: ${JSON.stringify(bad)}`);
  }
});

/* ---- the release routes ---- */

/* THE /app PATHS ARE COMPILED INTO INSTALLED ANDROID APPS. An app that cannot
   find its update cannot be told where the update went — the update mechanism
   being the thing that broke — so these two strings are as good as permanent.
   This is here because the table grew a second release under it and changed
   shape to do so; renaming a key would be silent otherwise. */
test("the paths installed apps ask for are exactly these", () => {
  assert.deepEqual(
    Object.keys(RELEASE_ASSETS).filter(p => p.startsWith("/app/")).sort(),
    ["/app/app-release.apk", "/app/version.json"],
  );
  assert.deepEqual(RELEASE_ASSETS["/app/app-release.apk"],
    { tag: "android-latest", name: "app-release.apk" });
  assert.deepEqual(RELEASE_ASSETS["/app/version.json"],
    { tag: "android-latest", name: "version.json" });
});

/* Each platform reads its own release. Pointing an iOS path at android-latest
   would serve an APK to someone trying to sideload, and pointing an Android
   path at ios-latest would strand every installed phone. */
test("every release path names its own platform's release", () => {
  for (const [path, { tag, name }] of Object.entries(RELEASE_ASSETS)) {
    assert.ok(name, `${path} names no asset`);
    assert.equal(tag, path.startsWith("/ios/") ? "ios-latest" : "android-latest");
  }
});

/* Decided by the fixed table's own extension, never by anything a caller
   sends — an IPA served as an APK is a download Windows and iOS both refuse to
   do anything useful with. */
test("content types come off the asset name", () => {
  assert.equal(contentType("version.json"), "application/json; charset=utf-8");
  assert.equal(contentType("TinyTube-unsigned.ipa"), "application/octet-stream");
  assert.equal(contentType("app-release.apk"), "application/vnd.android.package-archive");
});

/* --------------------------------------------------------------------------
 * SYNC: the verification and validation half that a plain node test can pin.
 * The signature check itself needs Google's live keys and a real token, so
 * what is tested here is everything AROUND it: claim checks, input patterns,
 * and the validators that stand between a caller's body and the SQL.
 * ------------------------------------------------------------------------ */

import {
  JWT,
  SESSION_TOKEN,
  DEVICE_ID,
  DAY_KEY,
  HOUR_KEY,
  b64urlToBytes,
  bytesToB64url,
  decodeJwt,
  claimsEmail,
  sha256hex,
  validWatchedRows,
  validUsageBuckets,
} from "./worker.js";

const b64url = obj => bytesToB64url(new TextEncoder().encode(JSON.stringify(obj)));
const fakeJwt = (header, payload) => `${b64url(header)}.${b64url(payload)}.${bytesToB64url(new Uint8Array(4))}`;

test("base64url round-trips", () => {
  const bytes = new Uint8Array([0, 1, 2, 250, 251, 252, 253, 254, 255]);
  assert.deepEqual(b64urlToBytes(bytesToB64url(bytes)), bytes);
  assert.ok(!bytesToB64url(bytes).match(/[+/=]/));
});

test("session tokens from 32 bytes match the pattern the routes demand", () => {
  assert.ok(SESSION_TOKEN.test(bytesToB64url(new Uint8Array(32).fill(7))));
  assert.ok(!SESSION_TOKEN.test("short"));
  assert.ok(!SESSION_TOKEN.test("x".repeat(44)));
});

test("sha256hex is the reference SHA-256", async () => {
  // openssl: echo -n abc | openssl dgst -sha256
  assert.equal(await sha256hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
});

test("decodeJwt reads header and payload and refuses garbage", () => {
  const decoded = decodeJwt(fakeJwt({ alg: "RS256", kid: "k1" }, { iss: "accounts.google.com" }));
  assert.equal(decoded.header.kid, "k1");
  assert.equal(decoded.payload.iss, "accounts.google.com");
  assert.equal(decodeJwt("not-a-jwt"), null);
  assert.equal(decodeJwt(`${b64url({})}.${"%%%"}.x`), null);
  assert.equal(decodeJwt("a.b"), null);
  assert.ok(JWT.test(fakeJwt({}, {})));
});

test("claimsEmail accepts exactly a live, verified Google token for OUR client", () => {
  const now = 1_700_000_000_000;
  const good = {
    iss: "https://accounts.google.com",
    aud: "client-1",
    exp: now / 1000 + 60,
    email: "Parent@Example.com",
    email_verified: true,
  };
  assert.equal(claimsEmail(good, "client-1", now), "parent@example.com");
  assert.equal(claimsEmail({ ...good, iss: "accounts.google.com" }, "client-1", now), "parent@example.com");
  // every single deviation refuses
  assert.equal(claimsEmail({ ...good, iss: "evil.example" }, "client-1", now), null);
  assert.equal(claimsEmail({ ...good, aud: "client-2" }, "client-1", now), null, "someone else's token");
  assert.equal(claimsEmail({ ...good, exp: now / 1000 - 1 }, "client-1", now), null, "expired");
  assert.equal(claimsEmail({ ...good, email_verified: false }, "client-1", now), null);
  assert.equal(claimsEmail({ ...good, email: undefined }, "client-1", now), null);
  assert.equal(claimsEmail(good, "", now), null, "no client id configured means nothing validates");
  assert.equal(claimsEmail(null, "client-1", now), null);
});

test("validWatchedRows normalizes good rows and refuses each bad shape whole", () => {
  const rows = validWatchedRows([
    { id: "dQw4w9WgXcQ", pos: 10.5, dur: 212, completed: true, updatedAt: 123 },
    { id: "aqz-KE-bpKQ", pos: 0, dur: 0, completed: false, updatedAt: 456 },
  ]);
  assert.deepEqual(rows, [
    { id: "dQw4w9WgXcQ", pos: 10.5, dur: 212, completed: 1, updatedAt: 123 },
    { id: "aqz-KE-bpKQ", pos: 0, dur: 0, completed: 0, updatedAt: 456 },
  ]);
  assert.deepEqual(validWatchedRows([]), []);
  const good = { id: "dQw4w9WgXcQ", pos: 1, dur: 2, completed: false, updatedAt: 3 };
  assert.equal(validWatchedRows([{ ...good, id: "short" }]), null);
  assert.equal(validWatchedRows([{ ...good, id: "x".repeat(11) + "!" }]), null);
  assert.equal(validWatchedRows([{ ...good, pos: -1 }]), null);
  assert.equal(validWatchedRows([{ ...good, pos: Infinity }]), null);
  assert.equal(validWatchedRows([{ ...good, updatedAt: 1.5 }]), null);
  assert.equal(validWatchedRows([{ ...good, updatedAt: 0 }]), null);
  assert.equal(validWatchedRows("nope"), null);
  assert.equal(validWatchedRows(Array.from({ length: 501 }, () => good)), null, "over the row cap");
});

test("validUsageBuckets demands a device id and fixed-pattern bucket keys", () => {
  const deviceId = "1b671a64-40d5-491e-99b0-da01ff1f3341";
  const usage = validUsageBuckets({
    deviceId,
    days: { "2026-08-09": 3600 },
    hours: { "496728": 1800 },
  });
  assert.deepEqual(usage, { deviceId, days: { "2026-08-09": 3600 }, hours: { 496728: 1800 } });
  assert.deepEqual(validUsageBuckets({ deviceId }), { deviceId, days: {}, hours: {} }, "absent halves are empty");
  assert.equal(validUsageBuckets({ deviceId: "not-a-uuid", days: {} }), null);
  assert.equal(validUsageBuckets({ deviceId, days: { "08/09/2026": 1 } }), null, "day key pattern");
  assert.equal(validUsageBuckets({ deviceId, hours: { abc: 1 } }), null, "hour key pattern");
  assert.equal(validUsageBuckets({ deviceId, days: { "2026-08-09": -1 } }), null);
  assert.equal(validUsageBuckets({ deviceId, days: { "2026-08-09": 90_000 } }), null, "more than a day of seconds");
  assert.equal(validUsageBuckets({ deviceId, hours: { "496728": 3_700 } }), null, "more than an hour of seconds");
  assert.equal(validUsageBuckets({ deviceId, days: [] }), null);
  assert.equal(validUsageBuckets(null), null);
  assert.ok(DEVICE_ID.test(deviceId) && DAY_KEY.test("2026-08-09") && HOUR_KEY.test("496728"));
});

/* /videos: the pure mapping between Data API replies and the cached shape.
   Ids re-validated, thumbnails rebuilt from the id, 18+ dropped — the cache is
   shared across every account, so nothing unvalidated may enter it. */

import { parseIsoDuration, apiVideoList } from "./worker.js";

test("parseIsoDuration reads ISO8601 and refuses placeholders", () => {
  assert.equal(parseIsoDuration("PT1H2M3S"), 3723);
  assert.equal(parseIsoDuration("PT2M"), 120);
  assert.equal(parseIsoDuration("PT45S"), 45);
  assert.equal(parseIsoDuration("P0D"), null);
  assert.equal(parseIsoDuration(undefined), null);
});

test("apiVideoList validates ids, attaches durations, drops 18+ and builds thumbnails", () => {
  const items = [
    { contentDetails: { videoId: "dQw4w9WgXcQ" }, snippet: { title: "Good" } },
    { contentDetails: { videoId: "aqz-KE-bpKQ" }, snippet: { title: "Adult" } },
    { contentDetails: { videoId: "short!" }, snippet: { title: "Bad id" } },
    { contentDetails: {}, snippet: { title: "No id" } },
  ];
  const details = [
    { id: "dQw4w9WgXcQ", contentDetails: { duration: "PT3M32S" } },
    { id: "aqz-KE-bpKQ", contentDetails: { duration: "PT1M", contentRating: { ytRating: "ytAgeRestricted" } } },
  ];
  assert.deepEqual(apiVideoList(items, details), [
    { id: "dQw4w9WgXcQ", title: "Good", duration: 212, thumbnail: thumbnailUrl("dQw4w9WgXcQ") },
  ]);
  assert.deepEqual(apiVideoList([], []), []);
  assert.deepEqual(apiVideoList(undefined, undefined), []);
});

/* One account, several children: history and usage are keyed by child, so the
   id has to be validated like every other caller input — and the migrated
   first child's id is FIXED, because the web app's own migration must land on
   the same rows. */

import { CHILD_ID, DEFAULT_CHILD } from "./worker.js";

test("CHILD_ID accepts uuids and the migration id, and refuses anything that could reach SQL oddly", () => {
  assert.ok(CHILD_ID.test(DEFAULT_CHILD));
  assert.ok(CHILD_ID.test("1b671a64-40d5-491e-99b0-da01ff1f3341"));
  assert.ok(!CHILD_ID.test(""));
  assert.ok(!CHILD_ID.test("a'; DROP TABLE sync_watched_v2; --"));
  assert.ok(!CHILD_ID.test("has space"));
  assert.ok(!CHILD_ID.test("x".repeat(65)));
});

/* The web app migrates its pre-children settings to exactly this id, and the
   Worker copies pre-children rows under it. If either side changes it alone,
   an account that was already syncing silently starts from an empty history. */
test("the migration child id is the literal both sides agree on", () => {
  assert.equal(DEFAULT_CHILD, "default");
});

/* The cache outlived its own shape once: its first version stored videos and
   no title, and serving one of those rows inside its day would show a bare
   channel id where a name belongs. Caught on a live preview, not in a test —
   so here is the test. */

import { usableRecord } from "./worker.js";

test("a cached row without a title is not a usable record", () => {
  assert.equal(usableRecord({ channel_id: "UC", videos: [{ id: "x" }] }), false);
  assert.equal(usableRecord({ channel_id: "UC", title: "Chan", videos: [] }), true);
  assert.equal(usableRecord({ channel_id: "UC", title: null, videos: [] }), true, "known-to-be-nameless is an answer");
  assert.equal(usableRecord(null), false);
});

test("a scrape that stored no lengths is stale, once", () => {
  assert.equal(usableRecord({ title: "Chan", videos: [{ id: "x", duration: null }] }), false);
  assert.equal(usableRecord({ title: "Chan", videos: [{ id: "x", duration: 36 }] }), true);
  assert.equal(usableRecord({ title: "Chan", videos: [{ id: "x", duration: null }], lengths: true }), true);
});

/* The refresh floor: 23 hours, and 23 rather than 24 on purpose — a daily
   cron at a fixed minute would never find a row fetched just after the
   previous run "due" at a 24-hour threshold, and it would sit for another
   whole day. */
import { MIN_REFRESH_MS } from "./worker.js";

test("nothing is refetched inside 23 hours, and the floor clears a daily cron", () => {
  assert.equal(MIN_REFRESH_MS, 23 * 3600_000);
  assert.ok(MIN_REFRESH_MS < 24 * 3600_000, "a 24h floor and a daily cron never line up");
});
