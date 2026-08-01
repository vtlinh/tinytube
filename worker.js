/* The Worker behind both apps.
 *
 * Three jobs, and the credential is kept apart from two of them on purpose.
 *
 * RELEASE ASSETS. The repository is private, so its release assets answer 404
 * to anyone without a credential and an installed app could never discover an
 * update — nothing on the device could recover from that, the update mechanism
 * being the thing that broke. This holds a read-only GitHub token and re-serves
 * them. Those routes are fixed: none takes a URL, a repo or a path from the
 * caller, so the credential cannot be pointed anywhere.
 *
 * The iOS ones serve a DIFFERENT purpose from the Android ones, and it is worth
 * not confusing them. /app/* exists because an installed app has to be able to
 * find its own update; /ios/* exists because a private repository's release
 * assets 404 in a browser, so without it the owner cannot hand themselves a
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
    if (esc[0] === "u") return String.fromCharCode(parseInt(esc.slice(1), 16));
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

export function xmlUnescape(s) {
  return s
    .replace(/&#x([0-9A-Fa-f]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#([0-9]+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
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
  const r = await fetch(url, {
    headers,
    cf: { cacheTtl: 1800, cacheEverything: true },
  });
  return r.ok ? await r.text() : null;
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
 * to the id rather than blocking an approval. */
export function parseChannelTitle(html) {
  const og = html.match(/<meta[^>]+property="og:title"[^>]+content="([^"]{1,120})"/);
  if (og) {
    const t = og[1].trim();
    if (t) return t;
  }
  const title = html.match(/<title>([^<]{1,120})<\/title>/);
  if (title) {
    /* the page title is "Name - YouTube" */
    const t = title[1].replace(/ - YouTube$/, "").trim();
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
       nothing here can reach the GitHub credential. The input is a handle, a
       video id or a channel id — never a URL. See the note above channel(). */
    if (url.pathname === "/channel") {
      if (request.method !== "POST") return json({ error: "use POST" }, 405);
      return channel(request);
    }

    return new Response("not found\n", { status: 404 });
  },
};
