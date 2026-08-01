/* The Worker behind the Android app.
 *
 * Two jobs, and they are kept apart on purpose.
 *
 * RELEASE ASSETS. The repository is private, so its release assets answer 404
 * to anyone without a credential and an installed app could never discover an
 * update — nothing on the device could recover from that, the update mechanism
 * being the thing that broke. This holds a read-only GitHub token and re-serves
 * them. Those routes are fixed: none takes a URL, a repo or a path from the
 * caller, so the credential cannot be pointed anywhere.
 *
 * UPLOADS. /uploads answers "what has this channel posted" so the phone does
 * not have to download two megabytes of YouTube's web app and parse it. It
 * takes a channel id and a list of video ids the caller already has, and
 * answers with the current list — full details for what is new, bare ids for
 * what the caller already knows. Measured against a live channel: 1.4 KB when
 * the caller is up to date, 19 KB for a channel it has never seen, against two
 * megabytes of markup before.
 *
 * That route DOES take input from the caller, which every other route here
 * deliberately does not, so it is worth being exact about what that means:
 *
 *   - It never reads env.GH_TOKEN. The credential is in the release path and
 *     nothing on this path can reach it.
 *   - The only thing taken from the caller is a channel id matched against
 *     ^UC[A-Za-z0-9_-]{22}$ and a bounded list of ids matched against
 *     ^[A-Za-z0-9_-]{11}$. Every URL fetched is BUILT here from a validated
 *     id — no caller-supplied URL, host or path reaches fetch(), so this
 *     cannot be used to make the Worker retrieve something else.
 *   - The known-id list is capped, and it only ever makes the response
 *     smaller. A caller that lies about what it has gets fewer details, not
 *     someone else's.
 *
 * Curation is still not served from here. Which channels a child may watch
 * lives in SQLite on the device and nothing about this route can change it —
 * this answers a question about a channel the parent already approved.
 *
 * One-time setup, from this directory:
 *   npx wrangler deploy                  # if the git-connected build isn't on
 *   npx wrangler secret put GH_TOKEN     # fine-grained, Contents:read, this repo only
 */

/* The repository was renamed from yt_kids to tinytube with everything else.
 * GitHub 301-redirects the old name and fetch() follows redirects, so this kept
 * working across the gap — but a redirect is somebody else's promise, and the
 * one thing this Worker must never fail at is telling an app where its update
 * is. Name it directly. */
const RELEASE_REPO = "vtlinh/tinytube";
const RELEASE_TAG = "android-latest";

/* path -> the asset name published by .github/workflows/android.yml */
const RELEASE_ASSETS = {
  "/app/version.json": "version.json",
  "/app/app-release.apk": "app-release.apk",
};

/* GitHub hands an asset download off to a signed URL on another host; these are
 * the only ones we follow it to. */
const GH_HOSTS = /^https:\/\/([a-z0-9-]+\.)*(githubusercontent\.com|github\.com)\//i;

const MAX_HOPS = 5;

async function releaseAsset(env, name) {
  if (!env.GH_TOKEN) return new Response("GH_TOKEN is not set\n", { status: 500 });
  const api = {
    "User-Agent": "yt-kids-worker",
    "Authorization": `Bearer ${env.GH_TOKEN}`,
    "X-GitHub-Api-Version": "2022-11-28",
  };
  const rel = await fetch(
    `https://api.github.com/repos/${RELEASE_REPO}/releases/tags/${RELEASE_TAG}`,
    { headers: { ...api, Accept: "application/vnd.github+json" } },
  );
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
    carry = { "User-Agent": "yt-kids-worker" };
  }
  if (!r.ok) return new Response(`asset fetch failed: ${r.status}\n`, { status: 502 });

  const headers = new Headers();
  headers.set(
    "content-type",
    name.endsWith(".json")
      ? "application/json; charset=utf-8"
      : "application/vnd.android.package-archive",
  );
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

async function uploads(request) {
  let body;
  try {
    body = await request.json();
  } catch (e) {
    return json({ error: "bad json" }, 400);
  }

  const channel = typeof body?.channel === "string" ? body.channel : "";
  if (!CHANNEL_ID.test(channel)) return json({ error: "bad channel" }, 400);

  /* Only ever shrinks the response. A caller that lies about what it has gets
   * fewer details, never someone else's. */
  const known = new Set(
    (Array.isArray(body?.known) ? body.known : [])
      .slice(0, MAX_KNOWN)
      .filter(v => typeof v === "string" && VIDEO_ID.test(v)),
  );

  const playlist = longFormPlaylistId(channel);
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
  if (ordered.length === 0) return json({ error: "nothing upstream" }, 502);

  const videos = datePositions(ordered, dated, Math.floor(Date.now() / 1000)).map(v =>
    known.has(v.id)
      ? v.id
      : { id: v.id, title: v.title, published: v.published, thumb: thumbnailUrl(v.id) },
  );

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

    return new Response("not found\n", { status: 404 });
  },
};
