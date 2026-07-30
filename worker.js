/* The Worker behind the Android app.
 *
 * It exists because the repository is private. A private repo's release assets
 * answer 404 to anyone without a credential, so an installed app could never
 * discover an update — and nothing on the device could recover from that, the
 * update mechanism being the thing that broke. This holds a read-only GitHub
 * token and re-serves those assets, without one.
 *
 * Curation is not served from here. Approved channels live on the device and
 * their uploads come straight from YouTube's per-channel feeds; this Worker is
 * only about the app updating itself.
 *
 * Every route is public and every route is fixed. None of them takes a URL, a
 * repo or a path from the caller: the path decides the asset, the constants
 * below decide the repository, and the GitHub credential never leaves here.
 * That is the whole reason it is safe to leave these unauthenticated.
 *
 * One-time setup, from this directory:
 *   npx wrangler deploy                  # if the git-connected build isn't on
 *   npx wrangler secret put GH_TOKEN     # fine-grained, Contents:read, this repo only
 */

const RELEASE_REPO = "vtlinh/yt_kids";
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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("ok\n", { headers: { "content-type": "text/plain" } });
    }

    if (url.pathname in RELEASE_ASSETS) {
      return releaseAsset(env, RELEASE_ASSETS[url.pathname]);
    }

    return new Response("not found\n", { status: 404 });
  },
};
