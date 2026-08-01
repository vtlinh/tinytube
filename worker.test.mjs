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
  parseFeed,
  jsonUnescape,
  xmlUnescape,
  datePositions,
  thumbnailUrl,
  parseChannelId,
  parseChannelTitle,
  parseChannelAvatar,
} from "./worker.js";

const OK_CHANNEL = "UC" + "a".repeat(22);

/* The shapes YouTube serves, in the nesting the real page uses. */
const lockup = (id, title) => `{"lockupViewModel":{"contentId":"${id}",
  "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":
  {"videoId":"${id}"}}}}},
  "metadata":{"lockupMetadataViewModel":{"title":{"content":"${title}"}}}}}`;

const renderer = (id, title) =>
  `{"playlistVideoRenderer":{"videoId":"${id}","title":{"runs":[{"text":"${title}"}]}}}`;

const page = (...entries) =>
  `<!doctype html><script>var ytInitialData = {"contents":[${entries.join(",")}]};</script>`;

const feed = (...entries) =>
  `<?xml version="1.0"?><feed>${entries.join("")}</feed>`;

const feedEntry = (id, title, published) =>
  `<entry><yt:videoId>${id}</yt:videoId><title>${title}</title>` +
  (published ? `<published>${published}</published>` : "") +
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

test("reads the older page shape", () => {
  const v = parseUploadsPage(page(renderer("aaaaaaaaaaa", "First")));
  assert.deepEqual(v.map(x => x.id), ["aaaaaaaaaaa"]);
  assert.equal(v[0].title, "First");
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

test("leaves an unknown escape alone", () => {
  assert.equal(jsonUnescape("a\\qb"), "a\\qb");
  assert.equal(jsonUnescape("trailing\\"), "trailing\\");
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

/* ---- the sort keys ---- */

test("dated videos keep the date the feed gave them", () => {
  const out = datePositions(
    [{ id: "aaaaaaaaaaa" }, { id: "bbbbbbbbbbb" }],
    new Map([["aaaaaaaaaaa", { published: 500 }], ["bbbbbbbbbbb", { published: 400 }]]),
    1000,
  );
  assert.deepEqual(out.map(v => v.published), [500, 400]);
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

/* The third and last shape of caller input this Worker accepts. It is what
   stands between /channel and a caller-supplied URL reaching fetch(). */
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
