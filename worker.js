/* The Worker behind both apps.
 *
 * Three jobs, and the credential is kept apart from two of them on purpose.
 *
 * RELEASE ASSETS. These re-serve the release assets under stable, spellable
 * paths. They were added while the repository was PRIVATE, when its assets
 * answered 404 to anyone without a credential and an installed app could never
 * have discovered an update — nothing on the device could recover from that,
 * the update mechanism being the thing that broke. The repository is public
 * now, so the credential is no longer what makes them reachable; what keeps
 * them here is that `/app/version.json` and `/app/app-release.apk` are compiled
 * into every installed Android app, so removing or renaming them strands every
 * phone. The token stays because these routes still call the GitHub API, and
 * they remain fixed: none takes a URL, a repo or a path from the caller, so the
 * credential cannot be pointed anywhere.
 *
 * The iOS ones serve a DIFFERENT purpose from the Android ones, and it is worth
 * not confusing them. /app/* exists because an installed app has to be able to
 * find its own update; /ios/* exists so the owner can hand themselves a stable
 * link to the latest IPA without going hunting through Actions runs for it.
 * Nothing on iOS self-updates and nothing reads /ios/version.json to decide
 * anything — it is there so a person can see what the link is currently
 * serving.
 *
 * UPLOADS. /uploads answers "what has this channel posted" so the phone does
 * not have to download two megabytes of YouTube's web app and parse it. It
 * takes a channel id and a list of video ids the caller already has, and
 * answers with the current list — full details for what is new, bare ids for
 * what the caller already knows. Measured against a live channel: 1.4 KB when
 * the caller is up to date, 19 KB for a channel it has never seen, against two
 * megabytes of markup before. This is the route called daily per channel, and
 * the known-id list is the whole reason it is cheap.
 *
 * CHANNEL. /channel answers "which channel is this page, and what has it
 * posted" — the id, the name, the avatar and the first hundred uploads, in one
 * reply. Reading a channel page used to happen on the device, which meant every
 * approval downloaded a full page to read one 24-character string out of it.
 * It sends no known-id list because nothing is ever known at approval time.
 *
 * ALL THE YOUTUBE PARSING IS HERE. Neither app reads a byte of YouTube's markup.
 *
 * The two YouTube routes DO take input from the caller, which the release
 * routes deliberately do not, so it is worth being exact about what that means:
 *
 *   - Neither reads env.GH_TOKEN. `env` is not even passed to them; the
 *     credential is in the release path and nothing on these can reach it.
 *   - /uploads takes a channel id matched against ^UC[A-Za-z0-9_-]{22}$ and a
 *     bounded list of ids matched against ^[A-Za-z0-9_-]{11}$.
 *   - /channel takes a URL, and that is NOT the exception it looks like. The
 *     caller's string is never fetched: channelTargetFromUrl parses it, demands
 *     a host in PAGE_HOSTS and a path of exactly /channel/UC… or /@handle, and
 *     REBUILDS the address from what it extracted. The most a caller can name
 *     is a different YouTube channel, which is the point of the route.
 *   - So every URL fetched here is BUILT from a validated value. The rule is
 *     not "never accept a URL", it is never fetch() a string a caller supplied.
 *   - The known-id list is capped, and it only ever makes the response
 *     smaller. A caller that lies about what it has gets fewer details, not
 *     someone else's.
 *
 * Curation is still not served from here. Which channels a child may watch
 * lives in SQLite on the device and nothing about these routes can change it —
 * they answer questions about channels, not about who may watch them.
 *
 * One-time setup, from this directory:
 *   npx wrangler deploy                  # if the git-connected build isn't on
 *   npx wrangler secret put GH_TOKEN     # fine-grained, Contents:read, this repo only
 */

/* The repository was renamed to tinytube along with everything else.
 * GitHub 301-redirects the old name and fetch() follows redirects, so this kept
 * working across the gap — but a redirect is somebody else's promise, and the
 * one thing this Worker must never fail at is telling an app where its update
 * is. Name it directly. */
const RELEASE_REPO = "vtlinh/tinytube";

/* path -> the release it lives in and the asset name published there.
 *
 * TWO RELEASES, one per platform, because the two publish independently:
 * android.yml writes android-latest and ios.yml writes ios-latest, and neither
 * merge should disturb the other's assets.
 *
 * The /app/… paths are COMPILED INTO INSTALLED ANDROID APPS (see Endpoints.kt)
 * and can never change — an app that cannot find its update is an app that can
 * never be fixed remotely. The /ios/… paths carry no such weight: nothing on
 * iOS self-updates, so they are read by a person with a browser and could be
 * renamed tomorrow. They are still spelled out one path at a time here rather
 * than assembled from anything the caller sends, which is what keeps the
 * credential unreachable. */
export const RELEASE_ASSETS = {
  "/app/version.json": { tag: "android-latest", name: "version.json" },
  "/app/app-release.apk": { tag: "android-latest", name: "app-release.apk" },
  "/ios/TinyTube.ipa": { tag: "ios-latest", name: "TinyTube-unsigned.ipa" },
  "/ios/version.json": { tag: "ios-latest", name: "version.json" },
};

/* GitHub hands an asset download off to a signed URL on another host; these are
 * the only ones we follow it to. */
const GH_HOSTS = /^https:\/\/([a-z0-9-]+\.)*(githubusercontent\.com|github\.com)\//i;

const MAX_HOPS = 5;

/* Decided by the asset's own extension, from the fixed table above — never from
 * anything a caller sends. */
export function contentType(name) {
  if (name.endsWith(".json")) return "application/json; charset=utf-8";
  if (name.endsWith(".ipa")) return "application/octet-stream";
  return "application/vnd.android.package-archive";
}

async function releaseAsset(env, { tag, name }) {
  if (!env.GH_TOKEN) return new Response("GH_TOKEN is not set\n", { status: 500 });
  const api = {
    "User-Agent": "tinytube-worker",
    "Authorization": `Bearer ${env.GH_TOKEN}`,
    "X-GitHub-Api-Version": "2022-11-28",
  };
  const rel = await fetch(
    `https://api.github.com/repos/${RELEASE_REPO}/releases/tags/${tag}`,
    { headers: { ...api, Accept: "application/vnd.github+json" } },
  );
  /* No such release is a 404, not a 502. They are different facts and only one
   * of them is our fault: "nothing has been published to ios-latest yet" is the
   * answer somebody gets for clicking the iOS link before the first build, and
   * a Bad Gateway sends them looking for an outage that isn't there. Anything
   * else from GitHub — rate limit, a dead token, an actual outage — stays a 502,
   * because then something IS wrong at this end. */
  if (rel.status === 404) {
    return new Response(`nothing published to ${tag} yet\n`, { status: 404 });
  }
  if (!rel.ok) return new Response(`release lookup failed: ${rel.status}\n`, { status: 502 });
  const asset = ((await rel.json()).assets || []).find(a => a.name === name);
  if (!asset) return new Response(`no asset named ${name}\n`, { status: 404 });

  /* The asset endpoint answers with a redirect to a signed URL. Follow it by
   * hand so our credential is not replayed to whatever host it names, and so a
   * redirect somewhere unexpected is refused rather than proxied. */
  let at = `https://api.github.com/repos/${RELEASE_REPO}/releases/assets/${asset.id}`;
  let carry = { ...api, Accept: "application/octet-stream" };
  let r;
  for (let hop = 0; ; hop++) {
    r = await fetch(at, { headers: carry, redirect: "manual" });
    if (r.status < 300 || r.status > 399) break;
    const loc = r.headers.get("location");
    if (!loc) break;
    if (hop >= MAX_HOPS) return new Response("too many redirects\n", { status: 508 });
    at = new URL(loc, at).toString();
    if (!GH_HOSTS.test(at)) return new Response("asset redirected off GitHub\n", { status: 502 });
    /* the signed URL carries its own credential and rejects ours */
    carry = { "User-Agent": "tinytube-worker" };
  }
  if (!r.ok) return new Response(`asset fetch failed: ${r.status}\n`, { status: 502 });

  const headers = new Headers();
  headers.set("content-type", contentType(name));
  /* The IPA is fetched by a person in a browser rather than by an app, and
   * without this Chrome and Edge save it under the asset's own name — which is
   * TinyTube-unsigned.ipa, and looks enough like a mistake that somebody will
   * wonder whether they got the right file. */
  if (name.endsWith(".ipa")) {
    headers.set("content-disposition", 'attachment; filename="TinyTube.ipa"');
  }
  /* Short, because the updater's whole job is noticing a new build. The APK is
   * immutable per build but is republished under the same name, so it cannot be
   * cached for longer than the manifest that points at it. */
  headers.set("cache-control", "public, max-age=300");
  headers.set("access-control-allow-origin", "*");
  return new Response(r.body, { status: 200, headers });
}

/* ---- uploads ---- */

export const CHANNEL_ID = /^UC[A-Za-z0-9_-]{22}$/;
export const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

/* A YouTube @handle, without the @. The third and last shape of caller input
 * this Worker accepts, and like the other two it is a FIXED PATTERN rather
 * than anything resembling a URL — see the note above /channel. */
export const HANDLE = /^[A-Za-z0-9._-]{3,30}$/;

/* How many ids a caller may say it already has. A hundred is the whole list;
 * the slack is for a phone that kept a few extra across a channel's edits.
 * Past that the list is truncated rather than refused — the only effect of a
 * missing id is that its details are sent again. */
const MAX_KNOWN = 500;

/* What one request answers with, and also what the first payload of a playlist
 * page happens to hold. */
const MAX_VIDEOS = 100;

/* YouTube redirects a mobile user agent to m.youtube.com, whose page lists
 * twenty videos and hides the rest behind a continuation. */
const DESKTOP_UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
  "Chrome/120.0.0.0 Safari/537.36";

/* UULF, not UU: the channel's uploads with SHORTS TAKEN OUT, by YouTube's own
 * classification. Nothing here decides what a Short is, because nothing in the
 * page says — every entry is reported as a video either way, and length is not
 * the rule (YouTube sorts by aspect ratio, so a duration filter would drop the
 * short, wide videos a children's channel posts and keep three-minute vertical
 * Shorts). Changing this to UU puts Shorts on a child's screen. */
export function longFormPlaylistId(channelId) {
  return "UULF" + channelId.slice(2);
}

/* Where one video's entry begins in the page's embedded state. Two of them,
 * because YouTube renamed this mid-2025 and old shapes still turn up. */
const ENTRY_MARKERS = ['{"lockupViewModel":', '{"playlistVideoRenderer":'];
const PAGE_ID = /"(?:contentId|videoId)":"([A-Za-z0-9_-]{11})"/;
const PAGE_TITLES = [
  /"lockupMetadataViewModel":\{"title":\{"content":"((?:[^"\\]|\\.)*)"/,
  /"title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"/,
  /"title":\{[^{}]*"simpleText":"((?:[^"\\]|\\.)*)"/,
];

/* Each entry ends where the next begins, and that bound comes first: without
 * it an entry missing its own title would take the NEXT entry's, which is the
 * one failure here that produces a wrong answer rather than a missing one. The
 * window bounds the last entry, which has nothing after it. */
const ENTRY_WINDOW = 20000;

export function parseUploadsPage(html, limit = MAX_VIDEOS) {
  const out = [];
  const seen = new Set();
  const marker = ENTRY_MARKERS.find(m => html.includes(m));
  if (!marker) return out;

  let from = html.indexOf(marker);
  while (from >= 0 && out.length < limit) {
    const next = html.indexOf(marker, from + marker.length);
    const end = Math.min(next >= 0 ? next : html.length, from + ENTRY_WINDOW);
    const chunk = html.slice(from + marker.length, end);
    const id = (PAGE_ID.exec(chunk) || [])[1];
    if (id && VIDEO_ID.test(id) && !seen.has(id)) {
      seen.add(id);
      let title = null;
      for (const re of PAGE_TITLES) {
        const m = re.exec(chunk);
        if (m) { title = jsonUnescape(m[1]).trim(); break; }
      }
      out.push({ id, title: title || id });
    }
    from = next;
  }
  return out;
}

/* JSON's string escapes, which is what a title inside the page's state wears.
 * An unrecognised escape is left alone rather than dropped: a title is
 * cosmetic and a mangled one beats a missing one. */
export function jsonUnescape(s) {
  return s.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (whole, esc) => {
    /* Length, not first character: a short \u12 does not match the four-hex
     * branch, so `.` matches the bare "u" and esc is "u". Testing esc[0] made
     * that parseInt("") -> NaN -> a U+0000 spliced into the title, which is
     * the opposite of leaving an unrecognised escape alone. */
    if (esc.length === 5) return String.fromCharCode(parseInt(esc.slice(1), 16));
    switch (esc) {
      case '"': case "\\": case "/": return esc;
      case "n": return "\n";
      case "r": return "\r";
      case "t": return "\t";
      case "b": return "\b";
      case "f": return "\f";
      default: return whole;
    }
  });
}

/* The same playlist's Atom feed: fifteen entries, ten kilobytes, and the only
 * source that carries an upload TIME. The page has the order and the depth;
 * this has the dates. */
export function parseFeed(xml) {
  const out = new Map();
  const entries = xml.match(/<entry>[\s\S]*?<\/entry>/g) || [];
  for (const entry of entries) {
    const id = (/<yt:videoId>\s*([^<\s]+)\s*<\/yt:videoId>/.exec(entry) || [])[1];
    if (!id || !VIDEO_ID.test(id)) continue;
    const at = (/<published>\s*([^<\s]+)\s*<\/published>/.exec(entry) || [])[1];
    const title = (/<title[^>]*>([\s\S]*?)<\/title>/.exec(entry) || [])[1];
    const seconds = at ? Math.floor(Date.parse(at) / 1000) : NaN;
    out.set(id, {
      published: Number.isFinite(seconds) ? seconds : null,
      title: title ? xmlUnescape(title).trim() : null,
    });
  }
  return out;
}

/* A numeric reference above U+10FFFF (or an unparseable one) is left as it was
 * written rather than decoded. String.fromCodePoint THROWS on those, and a
 * throw here escapes parseFeed, uploadsFor and the request itself — one
 * malformed entity in an Atom feed would turn /uploads and /channel into a 500
 * and discard a perfectly good uploads page along with it. Every parser in this
 * file returns what it could read rather than throwing. */
function codePoint(digits, radix, whole) {
  const n = parseInt(digits, radix);
  if (!Number.isFinite(n) || n < 0 || n > 0x10ffff) return whole;
  return String.fromCodePoint(n);
}

export function xmlUnescape(s) {
  return s
    .replace(/&#x([0-9A-Fa-f]+);/g, (whole, h) => codePoint(h, 16, whole))
    .replace(/&#([0-9]+);/g, (whole, d) => codePoint(d, 10, whole))
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&"); /* last, so "&amp;lt;" stays "&lt;" */
}

/* Every video gets a sort key.
 *
 * The feed dates the newest fifteen; everything below is placed one second
 * before whatever preceded it in the page's order. That is not a guess at when
 * something was posted and is not presented as one — it is a key that
 * preserves the two things actually known: the page's order is upload order,
 * and its tail is older than the feed's oldest entry. */
export function datePositions(ordered, dated, now) {
  let previous = now;
  return ordered.map(v => {
    const known = dated.get(v.id)?.published ?? null;
    const at = known !== null && known <= previous ? known : previous - 1;
    previous = at;
    return { ...v, published: at };
  });
}

/* i.ytimg.com serves a thumbnail for any public video with no key and no
 * cookie. hqdefault exists for every video; the sized variants in the page
 * carry expiring signatures, so the stable form is what gets stored. */
export function thumbnailUrl(id) {
  return `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;
}

async function fetchText(url, headers) {
  /* Cached at the edge, so a hundred phones asking for the same channel in the
   * same hour cost YouTube one page rather than a hundred. Shorter than the
   * app's own once-a-day rule so a channel that just posted is not stale for
   * long once somebody does ask. */
  /* A rejected fetch is a null, exactly like a non-ok one. fetch() rejects on a
   * reset connection, a DNS failure or a subrequest limit, and uploadsFor awaits
   * both halves with Promise.all — so an unguarded rejection on the feed would
   * throw away a page that had already come back with a hundred videos, and take
   * the whole request out with it. One source failing degrades the answer; it
   * does not destroy it. */
  try {
    const r = await fetch(url, {
      headers,
      cf: { cacheTtl: 1800, cacheEverything: true },
    });
    return r.ok ? await r.text() : null;
  } catch (e) {
    return null;
  }
}

/* ---- channel resolution ---- */

/* Which channel a page is FOR.
 *
 * A /channel/UC… URL carries the id outright, but most of YouTube doesn't —
 * it uses an @handle, and a handle cannot be turned into an id without asking
 * YouTube. That asking used to happen ON THE PHONE, in ChannelResolver, which
 * meant every approval downloaded a full channel page to read one string out
 * of it. It happens here now, for the same reason the uploads parsing moved:
 * the device gets an answer instead of a document.
 *
 * Every channel page carries its id in several places; these are the two that
 * have been stable and unambiguous. The canonical link is preferred because it
 * is a declared identity rather than an incidental mention — "channelId" also
 * appears in a watch page's payload referring to the uploader, which is in fact
 * what we want there too.
 *
 * Returns null rather than throwing on anything unrecognised, exactly like the
 * uploads parsers: the worst a hostile or renamed page can do is yield no
 * answer, never a wrong one. */
export function parseChannelId(html) {
  const canonical = html.match(
    /<link[^>]+rel="canonical"[^>]+href="https:\/\/www\.youtube\.com\/channel\/(UC[A-Za-z0-9_-]{22})"/,
  );
  if (canonical && CHANNEL_ID.test(canonical[1])) return canonical[1];

  const payload = html.match(/"channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})"/);
  if (payload && CHANNEL_ID.test(payload[1])) return payload[1];

  return null;
}

/* A human name, so the approved list reads as names rather than 24-character
 * ids. Only ever cosmetic — nothing depends on it — so any failure falls back
 * to the id rather than blocking an approval.
 *
 * Both sources are HTML and both are entity-escaped, so both are decoded on the
 * way out. "Ben &amp; Holly&#39;s Little Kingdom" is what an og:title attribute
 * actually carries, and the phone stores whatever comes back verbatim — an
 * undecoded name sits in the approved list until the channel is approved again.
 * The other two title paths already decode (parseUploadsPage through
 * jsonUnescape, parseFeed through xmlUnescape); this is the same step. */
export function parseChannelTitle(html) {
  const og = html.match(/<meta[^>]+property="og:title"[^>]+content="([^"]{1,120})"/);
  if (og) {
    const t = xmlUnescape(og[1]).trim();
    if (t) return t;
  }
  const title = html.match(/<title>([^<]{1,120})<\/title>/);
  if (title) {
    /* the page title is "Name - YouTube" */
    const t = xmlUnescape(title[1]).replace(/ - YouTube$/, "").trim();
    if (t) return t;
  }
  return null;
}

/* Hosts YouTube serves channel avatars from. Checked before the URL is ever
 * handed back, because the phone stores whatever it is given and later fetches
 * and draws it: an og:image tag is page-controlled, and "some URL a page told
 * us about" is not something to put in a child's device database.
 *
 * The phone checks this again on arrival. Same reasoning as video ids — "our
 * own server said so" is not the same assurance as a check at the point of
 * use. */
const AVATAR_HOSTS = new Set(["yt3.ggpht.com", "yt3.googleusercontent.com"]);

export function parseChannelAvatar(html) {
  const m = html.match(/<meta[^>]+property="og:image"[^>]+content="([^"]{1,500})"/);
  if (!m) return null;
  let host;
  try {
    host = new URL(m[1]).hostname.toLowerCase();
  } catch (e) {
    return null;
  }
  if (AVATAR_HOSTS.has(host) || host.endsWith(".googleusercontent.com")) return m[1];
  return null;
}

/* A channel URL, taken apart and REBUILT.
 *
 * ⚠️ THIS IS WHAT LETS /channel ACCEPT A URL AT ALL. The caller's string is
 * never fetched. It is parsed, its host is checked against the pages YouTube
 * serves channels from, its path must be exactly /channel/UC… or /@handle, and
 * the address that actually gets fetched is BUILT here from the extracted
 * value. A caller cannot name a host, a port, a scheme or a path — the most it
 * can do is name a different YouTube channel, which is the entire point of the
 * route.
 *
 * The apps used to do this splitting themselves and send the pieces. They send
 * the URL now: reading YouTube is this Worker's job, and a URL is something to
 * read. What did NOT move is the checking — both apps still validate the id
 * that comes back, because it becomes a database key and a request parameter.
 *
 * Returns { id } or { handle }, plus the built target. Null for anything else:
 * a watch page, a search, the home feed. */
export function channelTargetFromUrl(url) {
  let parsed;
  try {
    parsed = new URL(String(url).trim());
  } catch (e) {
    return null;
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null;

  const host = parsed.hostname.toLowerCase();
  if (!PAGE_HOSTS.has(host)) return null;

  const parts = parsed.pathname.split("/").filter(Boolean);

  /* /channel/UC… — the id is right there. */
  if (parts.length >= 2 && parts[0] === "channel" && CHANNEL_ID.test(parts[1])) {
    return { id: parts[1], url: `https://www.youtube.com/channel/${parts[1]}` };
  }

  /* /@handle — anchored at the FIRST segment. A watch page mentions its
   * uploader and a search result lists a dozen channels, but neither IS a
   * channel, and approving from one would be a guess. */
  if (parts.length >= 1 && parts[0].startsWith("@")) {
    const handle = parts[0].slice(1);
    if (HANDLE.test(handle)) {
      return { handle, url: `https://www.youtube.com/@${handle}` };
    }
  }

  return null;
}

/* Hosts that serve channel pages. Narrower than everything else YouTube
 * serves — an image or media host is never somewhere a channel is approved
 * from. Mirrors YouTubeUrls.PAGE_HOSTS on both apps. */
const PAGE_HOSTS = new Set(["www.youtube.com", "m.youtube.com", "youtube.com"]);

/* What channel is the parent looking at, and what has it posted?
 *
 * ⚠️ THIS IS THE THIRD ROUTE THAT TAKES CALLER INPUT AND IT MAKES THE SAME
 * ARGUMENT THE OTHER TWO DO. It does not read env.GH_TOKEN, `env` is not even
 * passed to it, and although it now accepts a URL, that URL is never fetched:
 * channelTargetFromUrl above takes it apart and builds the address from the
 * validated pieces.
 *
 * The reply carries the VIDEOS TOO. Approving a channel needs its id, its name
 * and its first hundred uploads, and asking for those separately meant two
 * round trips and two waits at the one moment a parent is watching a spinner.
 * One request now. A refresh of an already-approved channel still uses
 * /uploads, which does not need the name again. */
async function channel(request) {
  let body;
  try {
    body = await request.json();
  } catch (e) {
    return json({ error: "bad json" }, 400);
  }

  /* The app sends the URL it is standing on. It does NOT send HTML, and it
   * does not have to pick the id or handle out of the address first — reading
   * YouTube is this Worker's job, and that includes reading its URLs. */
  const target = channelTargetFromUrl(
    typeof body?.url === "string" ? body.url : "",
  );
  if (!target) return json({ error: "not a channel url" }, 400);

  const html = await fetchText(target.url, {
    /* Desktop on purpose: the mobile pages carry the canonical link less
     * reliably, and this is the one thing being read. */
    "User-Agent": DESKTOP_UA,
    "Accept-Language": "en-US,en;q=0.9",
  });

  /* An id that came out of the URL needs no page to be usable. A handle does —
   * there is nothing else that turns one into an id. */
  const resolved = (html && parseChannelId(html)) || target.id || null;
  if (!resolved) {
    return json({ error: html ? "no channel here" : "could not resolve" }, html ? 404 : 502);
  }

  /* The uploads come back with it: one round trip for the whole approval,
   * rather than resolve-then-fetch with the parent watching.
   *
   * Nothing is ever "already known" here, so this route does not accept a
   * `known` list. A channel being approved has nothing stored, and removing one
   * deletes its videos — so a re-approval has nothing stored either. It read
   * such a list briefly and no caller ever sent one; an input shape nothing
   * uses is surface to justify for nothing, which is the same reason /channel
   * doesn't take a video id.
   *
   * The known-id optimisation is /uploads' and stays there: that is the route
   * called every day per channel, where it turns a 19 KB reply into 1.4 KB. */
  const videos = await uploadsFor(resolved, EMPTY);

  return json({
    id: resolved,
    title: html ? parseChannelTitle(html) : null,
    avatarUrl: html ? parseChannelAvatar(html) : null,
    /* null, not [] — "we could not tell" and "this channel has nothing" must
     * not look alike to a caller that REPLACES its stored list with this. */
    videos,
  });
}

/* A channel's uploads, as the reply's `videos` array — shared by /uploads and
 * /channel so approving a channel and refreshing one go down the same path.
 * Returns null when nothing upstream answered, which both callers turn into
 * "changed nothing" rather than "empty". */
async function uploadsFor(channelId, known) {
  const playlist = longFormPlaylistId(channelId);
  const [html, xml] = await Promise.all([
    fetchText(`https://www.youtube.com/playlist?list=${playlist}&hl=en`, {
      "User-Agent": DESKTOP_UA,
      "Accept-Language": "en-US,en;q=0.9",
    }),
    fetchText(`https://www.youtube.com/feeds/videos.xml?playlist_id=${playlist}`, {}),
  ]);

  const dated = xml ? parseFeed(xml) : new Map();
  let ordered = html ? parseUploadsPage(html) : [];

  /* The page is a rendering of YouTube's own web app and its shape can be
   * renamed without warning. When that happens the feed is the whole answer,
   * at fifteen videos rather than none — and being a UULF feed, still with no
   * Shorts in it. */
  if (ordered.length === 0) {
    ordered = [...dated.entries()].map(([id, d]) => ({ id, title: d.title || id }));
  }
  if (ordered.length === 0) return null;

  return datePositions(ordered, dated, Math.floor(Date.now() / 1000)).map(v =>
    known.has(v.id)
      ? v.id
      : { id: v.id, title: v.title, published: v.published, thumb: thumbnailUrl(v.id) },
  );
}

/* Nothing is known. Named rather than inlined so the /channel path reads as a
 * deliberate choice instead of an oversight. */
const EMPTY = new Set();

/* The ids a caller says it already has. Only ever shrinks the response: a
 * caller that lies about what it has gets fewer details, never someone
 * else's. */
function knownIds(body) {
  return new Set(
    (Array.isArray(body?.known) ? body.known : [])
      .slice(0, MAX_KNOWN)
      .filter(v => typeof v === "string" && VIDEO_ID.test(v)),
  );
}

async function uploads(request) {
  let body;
  try {
    body = await request.json();
  } catch (e) {
    return json({ error: "bad json" }, 400);
  }

  const channel = typeof body?.channel === "string" ? body.channel : "";
  if (!CHANNEL_ID.test(channel)) return json({ error: "bad channel" }, 400);

  const videos = await uploadsFor(channel, knownIds(body));
  if (videos === null) return json({ error: "nothing upstream" }, 502);

  return json({ channel, videos });
}

/* ------------------------------------------------------------------------- *
 * SYNC. Per-account state for the WEB APP (web/), in D1: settings, watch
 * history and quota usage, keyed by a Google account's email. The phone apps
 * know nothing of these routes.
 *
 * These routes take caller input, so they make the same argument /uploads and
 * /channel make — and it has to hold, or they don't belong in this Worker:
 *
 *   - None reads env.GH_TOKEN. `env` is not passed to any of them; the router
 *     hands over env.DB (the D1 binding) and nothing else.
 *   - The only fetch() in this section is JWKS_URL, a constant. The caller's
 *     token is never fetched, never forwarded, never echoed — it is VERIFIED,
 *     against Google's published keys, with WebCrypto.
 *   - Every other input is matched against a fixed pattern before use: video
 *     ids against VIDEO_ID, bucket keys against DAY_KEY/HOUR_KEY, the device
 *     id against DEVICE_ID, the session token against SESSION_TOKEN. Row
 *     counts and byte sizes are capped. Everything reaches SQL as a bound
 *     parameter or through json_each over a string this code re-serialized
 *     from validated values — caller bytes never meet SQL text.
 *   - The identity key (email) comes out of the VERIFIED token, never from the
 *     request body, so one account cannot name another account's rows.
 *
 * Sessions: /sync/login trades a valid Google ID token for a bearer token this
 * Worker mints. Only the SHA-256 of that token is stored, so a leaked database
 * cannot impersonate anyone. Google ID tokens expire hourly; the session lasts
 * 90 days, which is what makes background pushes possible without re-prompting
 * a signed-in parent.
 *
 * Merging is last-write-wins PER ROW (per video, per settings blob), decided
 * by the client's updatedAt clock — fine for a family's devices. Usage buckets
 * are per DEVICE and only ever grow, so the upsert takes the larger value;
 * /sync/pull sums across devices, which is what lets the watch quota hold when
 * a child switches devices.
 * ------------------------------------------------------------------------- */

/* The web app's OAuth client id (a PUBLIC identifier, compiled into the page
   too — keep the two copies equal, the other is web/src/lib.js). Empty
   disables /sync/* with a 503. */
export const GOOGLE_CLIENT_ID = "559900350228-kamkqhee408lf7nh0kg5p9njgo71qjtt.apps.googleusercontent.com";

const JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);
export const JWT = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
export const SESSION_TOKEN = /^[A-Za-z0-9_-]{43}$/; // base64url of 32 random bytes
export const DEVICE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/* One account, several children: history and quota usage are per CHILD. The
   web app sends a UUID, or the literal "default" for the child a pre-children
   install was migrated into — hence a pattern rather than DEVICE_ID's UUID. */
export const CHILD_ID = /^[A-Za-z0-9_-]{1,64}$/;
export const DEFAULT_CHILD = "default";
export const DAY_KEY = /^\d{4}-\d{2}-\d{2}$/;
export const HOUR_KEY = /^\d{1,10}$/; // epoch hours; 10 digits reaches year 116k
const SESSION_TTL_MS = 90 * 86_400_000;
const MAX_BODY_BYTES = 256 * 1024;
const MAX_SETTINGS_BYTES = 64 * 1024;
const MAX_WATCHED_ROWS = 500; // the web app's own LRU cap
const MAX_DAY_BUCKETS = 366; // the web app prunes past these horizons
const MAX_HOUR_BUCKETS = 48;

export function b64urlToBytes(s) {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

export function bytesToB64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** header and payload of a JWT, WITHOUT verifying it — the caller must. Null on garbage. */
export function decodeJwt(token) {
  if (typeof token !== "string" || token.length > 4096 || !JWT.test(token)) return null;
  try {
    const [h, p] = token.split(".");
    const parse = (part) => JSON.parse(new TextDecoder().decode(b64urlToBytes(part)));
    return { header: parse(h), payload: parse(p) };
  } catch {
    return null;
  }
}

/**
 * The claim checks, apart from the signature so a plain node test can pin
 * them: issuer is Google, audience is OUR client id, not expired, and the
 * email is present and verified. Returns the email (lowercased) or null.
 */
export function claimsEmail(payload, clientId, nowMs) {
  if (!payload || !clientId) return null;
  if (!GOOGLE_ISSUERS.has(payload.iss)) return null;
  if (payload.aud !== clientId) return null;
  if (!(Number(payload.exp) * 1000 > nowMs)) return null;
  if (payload.email_verified !== true && payload.email_verified !== "true") return null;
  const email = typeof payload.email === "string" ? payload.email.trim().toLowerCase() : "";
  if (!email || email.length > 320 || !email.includes("@")) return null;
  return email;
}

/* Google rotates these keys; an hour of cache is what their own CDN headers
   suggest. Per-isolate, like everything else cached here. */
let jwksCache = null; // { keysById: Map, fetchedAt }

async function googleKey(kid) {
  if (!jwksCache || Date.now() - jwksCache.fetchedAt > 3600_000 || !jwksCache.keysById.has(kid)) {
    const resp = await fetch(JWKS_URL);
    if (!resp.ok) throw new Error(`jwks: HTTP ${resp.status}`);
    const { keys } = await resp.json();
    const keysById = new Map();
    for (const jwk of keys ?? []) {
      if (jwk.kty === "RSA" && jwk.alg === "RS256" && jwk.kid) {
        keysById.set(
          jwk.kid,
          await crypto.subtle.importKey(
            "jwk",
            jwk,
            { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
            false,
            ["verify"],
          ),
        );
      }
    }
    jwksCache = { keysById, fetchedAt: Date.now() };
  }
  return jwksCache.keysById.get(kid) ?? null;
}

/** Signature + claims. The email, or null for anything short of fully valid. */
async function verifyGoogleToken(token, clientId) {
  const decoded = decodeJwt(token);
  if (!decoded || decoded.header.alg !== "RS256") return null;
  const key = await googleKey(decoded.header.kid);
  if (!key) return null;
  const [h, p, sig] = token.split(".");
  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    b64urlToBytes(sig),
    new TextEncoder().encode(`${h}.${p}`),
  );
  if (!ok) return null;
  return claimsEmail(decoded.payload, clientId, Date.now());
}

export async function sha256hex(s) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/* CREATE IF NOT EXISTS on first touch per isolate — no migration tooling for
   four small tables. usage rows are PER DEVICE so a device can re-push its own
   monotonically growing buckets idempotently; pull SUMs across devices. */
let schemaReady = null;

function ensureSchema(db) {
  schemaReady ??= db.batch([
    db.prepare(
      "CREATE TABLE IF NOT EXISTS sync_sessions (token_hash TEXT PRIMARY KEY, email TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)",
    ),
    db.prepare("CREATE INDEX IF NOT EXISTS sync_sessions_email ON sync_sessions (email)"),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS sync_settings (email TEXT PRIMARY KEY, data TEXT NOT NULL, updated_at INTEGER NOT NULL)",
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS sync_watched (email TEXT NOT NULL, video_id TEXT NOT NULL, pos REAL NOT NULL, dur REAL NOT NULL, completed INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (email, video_id))",
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS sync_usage (email TEXT NOT NULL, device_id TEXT NOT NULL, kind TEXT NOT NULL, bucket TEXT NOT NULL, secs INTEGER NOT NULL, PRIMARY KEY (email, device_id, kind, bucket))",
    ),
    /* PER CHILD, and new tables rather than an ALTER: SQLite cannot widen a
       PRIMARY KEY in place, and the child belongs in the key — two children
       watching the same video are two rows, not one. The pre-children tables
       above are kept and their rows copied below, once, into DEFAULT_CHILD;
       the web app migrates its own first child to that same fixed id, so an
       account that was already syncing keeps its history. */
    db.prepare(
      "CREATE TABLE IF NOT EXISTS sync_watched_v2 (email TEXT NOT NULL, child_id TEXT NOT NULL, video_id TEXT NOT NULL, pos REAL NOT NULL, dur REAL NOT NULL, completed INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (email, child_id, video_id))",
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS sync_usage_v2 (email TEXT NOT NULL, device_id TEXT NOT NULL, child_id TEXT NOT NULL, kind TEXT NOT NULL, bucket TEXT NOT NULL, secs INTEGER NOT NULL, PRIMARY KEY (email, device_id, child_id, kind, bucket))",
    ),
    /* Idempotent by the conflict clause, so running it per isolate is safe and
       a fresh database copies nothing. */
    db.prepare(
      `INSERT INTO sync_watched_v2 (email, child_id, video_id, pos, dur, completed, updated_at) ` +
        `SELECT email, '${DEFAULT_CHILD}', video_id, pos, dur, completed, updated_at FROM sync_watched WHERE true ` +
        `ON CONFLICT DO NOTHING`,
    ),
    db.prepare(
      `INSERT INTO sync_usage_v2 (email, device_id, child_id, kind, bucket, secs) ` +
        `SELECT email, device_id, '${DEFAULT_CHILD}', kind, bucket, secs FROM sync_usage WHERE true ` +
        `ON CONFLICT DO NOTHING`,
    ),
    /* The SHARED half, keyed by channel and nothing else: which videos a
       channel has is a fact about the channel, not about a user, so one fetch
       a day serves every account. WHICH channels an account approved stays in
       its own settings — per user, like everything else under sync_. */
    db.prepare(
      "CREATE TABLE IF NOT EXISTS channel_cache (channel_id TEXT PRIMARY KEY, data TEXT NOT NULL, fetched_at INTEGER NOT NULL)",
    ),
  ]);
  return schemaReady;
}

async function readBody(request) {
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** The session's email, or null. Reads `Authorization: Bearer <token>`. */
async function sessionEmail(request, db) {
  const auth = request.headers.get("authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!SESSION_TOKEN.test(token)) return null;
  const row = await db
    .prepare("SELECT email FROM sync_sessions WHERE token_hash = ?1 AND expires_at > ?2")
    .bind(await sha256hex(token), Date.now())
    .first();
  return row?.email ?? null;
}

async function syncLogin(request, db, clientId) {
  const body = await readBody(request);
  const idToken = body?.id_token;
  if (typeof idToken !== "string" || !JWT.test(idToken) || idToken.length > 4096) {
    return json({ error: "id_token required" }, 400);
  }
  let email;
  try {
    email = await verifyGoogleToken(idToken, clientId);
  } catch {
    return json({ error: "could not reach key server, try again" }, 502);
  }
  if (!email) return json({ error: "invalid token" }, 403);

  await ensureSchema(db);
  const token = bytesToB64url(crypto.getRandomValues(new Uint8Array(32)));
  const now = Date.now();
  const expiresAt = now + SESSION_TTL_MS;
  await db.batch([
    db.prepare("DELETE FROM sync_sessions WHERE email = ?1 AND expires_at <= ?2").bind(email, now),
    db.prepare("INSERT INTO sync_sessions (token_hash, email, created_at, expires_at) VALUES (?1, ?2, ?3, ?4)")
      .bind(await sha256hex(token), email, now, expiresAt),
  ]);
  return json({ token, email, expires_at: expiresAt });
}

async function syncPull(request, db) {
  await ensureSchema(db);
  const email = await sessionEmail(request, db);
  if (!email) return json({ error: "sign in again" }, 401);
  const body = await readBody(request);
  /* Settings are ACCOUNT-wide (the blob carries every child); history and
     usage are per child. An absent or malformed child means the migrated
     first one, so an older client keeps working. */
  const child = CHILD_ID.test(body?.child ?? "") ? body.child : DEFAULT_CHILD;

  const [settings, watched, usage] = await db.batch([
    db.prepare("SELECT data, updated_at FROM sync_settings WHERE email = ?1").bind(email),
    db.prepare(
      "SELECT video_id, pos, dur, completed, updated_at FROM sync_watched_v2 WHERE email = ?1 AND child_id = ?2 ORDER BY updated_at DESC LIMIT ?3",
    ).bind(email, child, MAX_WATCHED_ROWS),
    db.prepare(
      "SELECT kind, bucket, SUM(secs) AS secs FROM sync_usage_v2 WHERE email = ?1 AND child_id = ?2 GROUP BY kind, bucket",
    ).bind(email, child),
  ]);

  const settingsRow = settings.results[0];
  const days = {};
  const hours = {};
  for (const row of usage.results) (row.kind === "day" ? days : hours)[row.bucket] = row.secs;
  return json({
    settings: settingsRow ? { data: JSON.parse(settingsRow.data), updatedAt: settingsRow.updated_at } : null,
    watched: watched.results.map((r) => ({
      id: r.video_id,
      pos: r.pos,
      dur: r.dur,
      completed: !!r.completed,
      updatedAt: r.updated_at,
    })),
    usage: { days, hours },
  });
}

/** Normalized rows re-serialized from validated values, or null. Exported for the tests. */
export function validWatchedRows(watched) {
  if (!Array.isArray(watched) || watched.length > MAX_WATCHED_ROWS) return null;
  const rows = [];
  for (const w of watched) {
    if (!w || !VIDEO_ID.test(w.id ?? "")) return null;
    const pos = Number(w.pos);
    const dur = Number(w.dur);
    const updatedAt = Number(w.updatedAt);
    if (!Number.isFinite(pos) || pos < 0 || !Number.isFinite(dur) || dur < 0) return null;
    if (!Number.isInteger(updatedAt) || updatedAt <= 0) return null;
    rows.push({ id: w.id, pos, dur, completed: w.completed ? 1 : 0, updatedAt });
  }
  return rows;
}

/** {days, hours} with every key and value validated and capped, or null. Exported for the tests. */
export function validUsageBuckets(usage) {
  const buckets = (obj, keyPattern, cap, maxSecs) => {
    if (obj == null) return {};
    if (typeof obj !== "object" || Array.isArray(obj)) return null;
    const entries = Object.entries(obj);
    if (entries.length > cap) return null;
    const out = {};
    for (const [k, v] of entries) {
      const secs = Number(v);
      if (!keyPattern.test(k) || !Number.isInteger(secs) || secs < 0 || secs > maxSecs) return null;
      out[k] = secs;
    }
    return out;
  };
  if (!usage || !DEVICE_ID.test(usage.deviceId ?? "")) return null;
  const days = buckets(usage.days, DAY_KEY, MAX_DAY_BUCKETS, 86_400);
  const hours = buckets(usage.hours, HOUR_KEY, MAX_HOUR_BUCKETS, 3_600);
  if (!days || !hours) return null;
  return { deviceId: usage.deviceId, days, hours };
}

async function syncPush(request, db) {
  await ensureSchema(db);
  const email = await sessionEmail(request, db);
  if (!email) return json({ error: "sign in again" }, 401);
  const body = await readBody(request);
  if (!body) return json({ error: "bad body" }, 400);
  if (body.child != null && !CHILD_ID.test(body.child)) return json({ error: "bad child" }, 400);
  const child = body.child ?? DEFAULT_CHILD;

  const statements = [];

  if (body.settings != null) {
    const { data, updatedAt } = body.settings;
    if (typeof data !== "object" || data === null || Array.isArray(data)) return json({ error: "bad settings" }, 400);
    const text = JSON.stringify(data);
    if (text.length > MAX_SETTINGS_BYTES) return json({ error: "settings too large" }, 400);
    if (!Number.isInteger(updatedAt) || updatedAt <= 0) return json({ error: "bad settings" }, 400);
    statements.push(
      db.prepare(
        "INSERT INTO sync_settings (email, data, updated_at) VALUES (?1, ?2, ?3) " +
          "ON CONFLICT (email) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at " +
          "WHERE excluded.updated_at > sync_settings.updated_at",
      ).bind(email, text, updatedAt),
    );
  }

  if (body.watched != null) {
    const rows = validWatchedRows(body.watched);
    if (!rows) return json({ error: "bad watched" }, 400);
    if (rows.length) {
      /* One statement however many rows: json_each over a string THIS code
         serialized from the validated rows above — caller bytes never meet
         SQL text, and the 100-bound-parameter limit never comes into play. */
      statements.push(
        db.prepare(
          "INSERT INTO sync_watched_v2 (email, child_id, video_id, pos, dur, completed, updated_at) " +
            "SELECT ?1, ?2, value ->> 'id', value ->> 'pos', value ->> 'dur', value ->> 'completed', value ->> 'updatedAt' " +
            "FROM json_each(?3) WHERE true " +
            "ON CONFLICT (email, child_id, video_id) DO UPDATE SET pos = excluded.pos, dur = excluded.dur, " +
            "completed = excluded.completed, updated_at = excluded.updated_at " +
            "WHERE excluded.updated_at > sync_watched_v2.updated_at",
        ).bind(email, child, JSON.stringify(rows)),
      );
    }
  }

  if (body.usage != null) {
    const usage = validUsageBuckets(body.usage);
    if (!usage) return json({ error: "bad usage" }, 400);
    for (const [kind, buckets] of [["day", usage.days], ["hour", usage.hours]]) {
      if (!Object.keys(buckets).length) continue;
      /* A device's own bucket only ever grows, so the larger value wins —
         re-pushing is idempotent and an old device can't shrink a newer count. */
      statements.push(
        db.prepare(
          "INSERT INTO sync_usage_v2 (email, device_id, child_id, kind, bucket, secs) " +
            `SELECT ?1, ?2, ?3, '${kind}', key, value FROM json_each(?4) WHERE true ` +
            "ON CONFLICT (email, device_id, child_id, kind, bucket) DO UPDATE SET secs = excluded.secs " +
            "WHERE excluded.secs > sync_usage_v2.secs",
        ).bind(email, usage.deviceId, child, JSON.stringify(buckets)),
      );
    }
  }

  if (statements.length) await db.batch(statements);
  return json({ ok: true });
}

/* ------------------------------------------------------------------------- *
 * /videos — the SHARED per-channel metadata cache the web app reads before
 * spending anyone's API key. One YouTube fetch a day for a channel serves
 * every account; the channel LIST stays per account in sync_settings.
 *
 * Written ONLY by this Worker, never from anything a caller sends: a
 * browser-writable shared cache would let one account put its own "videos"
 * under a channel every OTHER account's child then sees. The caller
 * contributes exactly one thing — a channel id against CHANNEL_ID — and every
 * URL fetched is built from that validated id plus a fixed base (YouTube's
 * page, feed, or Data API with a key held as a Worker secret). env is not
 * passed in; the route gets the D1 binding and the optional key.
 *
 * The Data API path (set the YOUTUBE_API_KEY wrangler secret to enable it)
 * answers with real durations and drops 18+ age-restricted videos; without a
 * key it falls back to the same page+feed scrape /uploads uses, which has
 * neither. An answer that parses to NOTHING is never cached — a stale answer
 * beats an empty one, and caching a failure would serve it to every user for
 * a day.
 * ------------------------------------------------------------------------- */

/* NOTHING IS REFETCHED INSIDE 23 HOURS — not by a browser asking, not by the
 * cron. Twenty-three rather than twenty-four for an operational reason: the
 * cron runs once a day at a fixed minute, so a row fetched a minute after the
 * previous run is 23h59m old when the next one comes round, would not count as
 * due at a 24h threshold, and would sit unrefreshed for another whole day. The
 * floor also caps how often YouTube is asked about any one channel, however
 * many accounts approve it.
 *
 * The one thing that ignores it is a row that is not a RECORD at all — see
 * usableRecord — because serving those is worse than a fetch, and it converges
 * after one. */
export const MIN_REFRESH_MS = 23 * 3600_000;
const CHANNEL_CACHE_TTL_MS = MIN_REFRESH_MS;

/** PT1H2M3S -> seconds; null when unparsable (e.g. P0D live placeholders). */
export function parseIsoDuration(iso) {
  const m = typeof iso === "string" ? iso.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/) : null;
  if (!m || (!m[1] && !m[2] && !m[3])) return null;
  return (Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + (Number(m[3]) || 0);
}

/** Data API replies -> the cached shape, every id re-validated, thumbnails
 * BUILT from the id rather than trusted, 18+ dropped. Exported for the tests. */
export function apiVideoList(playlistItems, videoDetails) {
  const durations = {};
  const restricted = new Set();
  for (const v of videoDetails ?? []) {
    if (!VIDEO_ID.test(v?.id ?? "")) continue;
    durations[v.id] = parseIsoDuration(v.contentDetails?.duration);
    if (v.contentDetails?.contentRating?.ytRating === "ytAgeRestricted") restricted.add(v.id);
  }
  const out = [];
  for (const it of playlistItems ?? []) {
    const id = it?.contentDetails?.videoId;
    if (!VIDEO_ID.test(id ?? "") || restricted.has(id)) continue;
    out.push({
      id,
      title: typeof it.snippet?.title === "string" ? it.snippet.title : id,
      duration: durations[id] ?? null,
      thumbnail: thumbnailUrl(id),
    });
  }
  return out;
}

async function apiVideos(ytKey, channelId) {
  const API = "https://www.googleapis.com/youtube/v3";
  const playlist = longFormPlaylistId(channelId);
  const pl = await fetch(
    `${API}/playlistItems?part=snippet,contentDetails&playlistId=${playlist}&maxResults=50&key=${ytKey}`,
  );
  if (!pl.ok) throw new Error(`playlistItems: HTTP ${pl.status}`);
  const items = (await pl.json()).items ?? [];
  const ids = items.map((it) => it?.contentDetails?.videoId).filter((id) => VIDEO_ID.test(id ?? ""));
  let details = [];
  if (ids.length) {
    const dv = await fetch(`${API}/videos?part=contentDetails&id=${ids.join(",")}&key=${ytKey}`);
    if (dv.ok) details = (await dv.json()).items ?? [];
  }
  return apiVideoList(items, details);
}

/* topicCategories are Wikipedia URLs: .../wiki/Children%27s_music -> "Children's music" */
function topicNames(topicDetails) {
  const names = (topicDetails?.topicCategories ?? []).map(url =>
    decodeURIComponent(url.split("/").pop()).replace(/_/g, " "),
  );
  return [...new Set(names)];
}

/** Channel title, avatar and the stats the parent's channel table shows.
 * Every extra `part` here is free — channels.list costs one unit whatever it
 * is asked for — so the alternative was a second fetch for the same price. */
async function apiChannelMeta(ytKey, channelId) {
  const resp = await fetch(
    `https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics,status,topicDetails&id=${channelId}&key=${ytKey}`,
  );
  if (!resp.ok) throw new Error(`channels: HTTP ${resp.status}`);
  const item = ((await resp.json()).items ?? [])[0];
  if (!item) return null;
  const thumb = item.snippet?.thumbnails?.medium?.url ?? item.snippet?.thumbnails?.default?.url;
  let avatar = null;
  try {
    if (typeof thumb === "string" && AVATAR_HOSTS.has(new URL(thumb).hostname)) avatar = thumb;
  } catch {
    avatar = null;
  }
  return {
    title: typeof item.snippet?.title === "string" ? item.snippet.title : null,
    thumbnail: avatar,
    made_for_kids: item.status?.madeForKids ?? null, // COPPA designation; null = unknown
    topics: topicNames(item.topicDetails),
    subscribers: Number(item.statistics?.subscriberCount) || null,
    video_count: Number(item.statistics?.videoCount) || null,
    view_count: Number(item.statistics?.viewCount) || null,
  };
}

/** The same, off the channel page, for when there is no key. Same parsers
 * /channel uses, so there is one reader of YouTube's markup, not two. */
async function scrapedChannelMeta(channelId) {
  const html = await fetchText(`https://www.youtube.com/channel/${channelId}?hl=en`, {
    "User-Agent": DESKTOP_UA,
    "Accept-Language": "en-US,en;q=0.9",
  });
  if (!html) return null;
  return { title: parseChannelTitle(html), thumbnail: parseChannelAvatar(html) };
}

/**
 * Everything the cache knows about one channel — title, avatar and videos —
 * fetched from YouTube and written to D1. Returns null when the fetch produced
 * no videos, which is the one case that must never be cached.
 */
async function fetchChannelRecord(channelId, ytKey) {
  let videos = [];
  let meta = null;
  if (ytKey) {
    try {
      videos = await apiVideos(ytKey, channelId);
      meta = await apiChannelMeta(ytKey, channelId);
    } catch {
      videos = videos.length ? videos : [];
    }
  }
  if (!videos.length) {
    const scraped = await uploadsFor(channelId, EMPTY).catch(() => null);
    videos = (scraped ?? [])
      .filter((v) => typeof v === "object" && VIDEO_ID.test(v?.id ?? ""))
      .map((v) => ({ id: v.id, title: v.title, duration: null, thumbnail: thumbnailUrl(v.id) }));
  }
  if (!videos.length) return null; // stale beats empty; never cached
  if (!meta) meta = await scrapedChannelMeta(channelId).catch(() => null);
  return { channel_id: channelId, ...(meta ?? {}), title: meta?.title ?? null, thumbnail: meta?.thumbnail ?? null, videos };
}

async function writeChannelRecord(db, record) {
  await db
    .prepare(
      "INSERT INTO channel_cache (channel_id, data, fetched_at) VALUES (?1, ?2, ?3) " +
        "ON CONFLICT (channel_id) DO UPDATE SET data = excluded.data, fetched_at = excluded.fetched_at",
    )
    .bind(record.channel_id, JSON.stringify(record), Date.now())
    .run();
}

/**
 * A row is only usable as a record if it IS one. The first version of this
 * cache stored videos and nothing else, so a row written by it has no title —
 * and serving it inside its day would show a bare channel id where a name
 * belongs. Treating those as stale costs one refetch each, once.
 */
export function usableRecord(cached) {
  return !!cached && "title" in cached;
}

/** The cached record for one channel, refreshing it first when it is stale. */
async function channelRecord(db, channelId, ytKey) {
  const row = await db
    .prepare("SELECT data, fetched_at FROM channel_cache WHERE channel_id = ?1")
    .bind(channelId)
    .first();
  const cached = row ? JSON.parse(row.data) : null;
  if (usableRecord(cached) && Date.now() - row.fetched_at < CHANNEL_CACHE_TTL_MS) {
    return { ...cached, cached: true };
  }

  const fresh = await fetchChannelRecord(channelId, ytKey).catch(() => null);
  if (!fresh) {
    // a stale answer beats an empty one, and a failure is never written
    return cached ? { ...cached, cached: true, stale: true } : { channel_id: channelId, title: null, thumbnail: null, videos: [] };
  }
  await writeChannelRecord(db, fresh);
  return fresh;
}

/* Several ids in one request, because a parent with a dozen approved channels
   should cost one round trip rather than a dozen. */
const MAX_CHANNELS_PER_REQUEST = 50;

async function channelVideos(request, db, ytKey) {
  await ensureSchema(db);
  const body = await readBody(request);

  /* One id or many. The single form is what the first version of this route
     took and is kept so an older page keeps working. */
  const asked = Array.isArray(body?.channels) ? body.channels : body?.channel != null ? [body.channel] : [];
  if (!asked.length || asked.length > MAX_CHANNELS_PER_REQUEST) return json({ error: "bad channels" }, 400);
  if (!asked.every((id) => typeof id === "string" && CHANNEL_ID.test(id))) return json({ error: "bad channel" }, 400);

  const unique = [...new Set(asked)];
  const records = [];
  for (const id of unique) records.push(await channelRecord(db, id, ytKey));

  // the single form answers the way it always did
  if (!Array.isArray(body?.channels)) return json(records[0]);
  return json({ channels: Object.fromEntries(records.map((r) => [r.channel_id, r])) });
}

/**
 * The cron's work: refresh the channels whose cached answer is oldest, on the
 * Worker's own schedule, so the DB stays current without any browser asking.
 * Bounded per run — this is a background top-up, not a full sweep, and the
 * on-demand path above still covers anything it has not reached.
 */
const CRON_REFRESH_LIMIT = 25;

export async function refreshStaleChannels(db, ytKey, now = Date.now()) {
  await ensureSchema(db);
  const { results } = await db
    .prepare("SELECT channel_id FROM channel_cache WHERE fetched_at < ?1 ORDER BY fetched_at ASC LIMIT ?2")
    .bind(now - MIN_REFRESH_MS, CRON_REFRESH_LIMIT)
    .all();
  let refreshed = 0;
  for (const { channel_id: id } of results ?? []) {
    const fresh = await fetchChannelRecord(id, ytKey).catch(() => null);
    if (fresh) {
      await writeChannelRecord(db, fresh);
      refreshed++;
    }
  }
  return { looked: (results ?? []).length, refreshed };
}

function corsPreflight() {
  return new Response(null, {
    status: 204,
    headers: {
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "POST, OPTIONS",
      "access-control-allow-headers": "content-type, authorization",
      "access-control-max-age": "86400",
    },
  });
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      /* The app decides when to ask; nothing downstream should decide for it.
         The edge cache above is where the sharing happens. */
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
    },
  });
}

export default {
  /* The Worker keeps its own cache current: a daily cron refreshes the stalest
     channels straight into D1, with no client involved. Browsers still get a
     fresh answer on demand when they ask for something the cron has not
     reached — this only means they rarely have to wait for one. */
  async scheduled(event, env, ctx) {
    if (!env.DB) return;
    ctx.waitUntil(
      refreshStaleChannels(env.DB, env.YOUTUBE_API_KEY ?? "").then(
        r => console.log(`cron: refreshed ${r.refreshed}/${r.looked} stale channels`),
        e => console.log(`cron: refresh failed: ${e}`),
      ),
    );
  },

  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("ok\n", { headers: { "content-type": "text/plain" } });
    }

    if (url.pathname in RELEASE_ASSETS) {
      return releaseAsset(env, RELEASE_ASSETS[url.pathname]);
    }

    /* POST only. The known-id list is a hundred ids and belongs in a body, and
       a route with side effects at the edge should not be reachable by a link
       somebody clicks. Note that `env` is not passed on: nothing under
       /uploads can reach the GitHub credential. */
    if (url.pathname === "/uploads") {
      if (request.method !== "POST") return json({ error: "use POST" }, 405);
      return uploads(request);
    }

    /* Same terms as /uploads: POST, and `env` is deliberately not passed on, so
       nothing here can reach the GitHub credential. The input is the URL the
       app is standing on — which is not the exception it looks like, because
       that string is never fetched: channelTargetFromUrl takes it apart and
       BUILDS the address. See the note above channel(). */
    if (url.pathname === "/channel") {
      if (request.method === "OPTIONS") return corsPreflight();
      if (request.method !== "POST") return json({ error: "use POST" }, 405);
      return channel(request);
    }

    /* The shared per-channel video cache. Same terms as /uploads — the one
       caller input is a channel id against a fixed pattern — plus the D1
       binding and the optional server-held Data API key; never env itself. */
    if (url.pathname === "/videos") {
      if (request.method === "OPTIONS") return corsPreflight();
      if (request.method !== "POST") return json({ error: "use POST" }, 405);
      if (!env.DB) return json({ error: "cache not configured" }, 503);
      return channelVideos(request, env.DB, env.YOUTUBE_API_KEY ?? "");
    }

    /* The web app's per-account state. Same terms again, one binding wider:
       `env` itself is not passed on — the handlers get env.DB, the D1 binding,
       and can reach nothing else; the GitHub credential stays in the release
       path. Everything else these routes trust is either verified against
       Google's published keys or matched against a fixed pattern — see the
       SYNC section above. 503 until both the binding and the client id exist:
       sync is off until the deploy that turns it on. */
    if (url.pathname.startsWith("/sync/")) {
      if (request.method === "OPTIONS") return corsPreflight();
      if (request.method !== "POST") return json({ error: "use POST" }, 405);
      if (!env.DB || !GOOGLE_CLIENT_ID) return json({ error: "sync not configured" }, 503);
      if (url.pathname === "/sync/login") return syncLogin(request, env.DB, GOOGLE_CLIENT_ID);
      if (url.pathname === "/sync/pull") return syncPull(request, env.DB);
      if (url.pathname === "/sync/push") return syncPush(request, env.DB);
    }

    return new Response("not found\n", { status: 404 });
  },
};
