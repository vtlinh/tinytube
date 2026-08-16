/**
 * Minimal YouTube Data API v3 client for parent-added channels. Every call
 * uses the PARENT's own API key (stored in localStorage) — this repo/site
 * ships no key.
 */

const API = 'https://www.googleapis.com/youtube/v3'
const CACHE_KEY = 'tinytube:videocache:v1'
const CACHE_TTL_MS = 24 * 60 * 60 * 1000
const UC_ID = /UC[0-9A-Za-z_-]{22}/

async function get(path, params) {
  const url = `${API}/${path}?${new URLSearchParams(params)}`
  const resp = await fetch(url)
  const body = await resp.json().catch(() => ({}))
  if (!resp.ok) throw new Error(body?.error?.message ?? `YouTube API: HTTP ${resp.status}`)
  return body
}

function channelFromSnippet(id, snippet) {
  return {
    channel_id: id,
    channel_title: snippet.title,
    thumbnail: snippet.thumbnails?.medium?.url ?? snippet.thumbnails?.default?.url,
  }
}

/** topicCategories are Wikipedia URLs, e.g. .../wiki/Children%27s_music -> "Children's music" */
function topicNames(topicDetails) {
  const names = (topicDetails?.topicCategories ?? []).map(url =>
    decodeURIComponent(url.split('/').pop()).replace(/_/g, ' '),
  )
  return [...new Set(names)]
}

/** Full channels.list item -> channel blob with the kid-relevant extras and stats. */
function channelFromItem(item) {
  return {
    ...channelFromSnippet(item.id, item.snippet),
    made_for_kids: item.status?.madeForKids ?? null, // COPPA designation; null = unknown
    topics: topicNames(item.topicDetails),
    subscribers: Number(item.statistics?.subscriberCount) || null,
    video_count: Number(item.statistics?.videoCount) || null,
    view_count: Number(item.statistics?.viewCount) || null,
  }
}

/**
 * Free-text channel search with preview stats. search.list costs 100 quota
 * units per call — callers must debounce (the 10k/day default quota affords
 * ~100 fired searches). The follow-up channels.list (1 unit) upgrades results
 * with real avatars and subscriber counts for previews.
 */
export async function searchChannels(apiKey, query) {
  const body = await get('search', { part: 'snippet', type: 'channel', q: query, maxResults: 6, key: apiKey })
  const results = (body.items ?? []).map(item => channelFromSnippet(item.id.channelId, item.snippet))
  if (!results.length) return results
  try {
    const details = await get('channels', {
      part: 'snippet,statistics,status,topicDetails',
      id: results.map(r => r.channel_id).join(','),
      key: apiKey,
    })
    const byId = Object.fromEntries((details.items ?? []).map(it => [it.id, it]))
    return results.map(r => {
      const d = byId[r.channel_id]
      return d ? channelFromItem(d) : r
    })
  } catch {
    return results // preview enrichment is best-effort
  }
}

/** Key sanity-check via the cheapest keyed endpoint (i18nLanguages, 1 unit). */
export async function validateApiKey(apiKey) {
  await get('i18nLanguages', { part: 'snippet', key: apiKey })
}

export function formatCount(n) {
  return n ? Intl.NumberFormat('en', { notation: 'compact' }).format(n) : ''
}

/** Resolve a pasted UC id, channel URL (with or without @), or @handle to a channel. 1 unit. */
export async function resolveChannel(apiKey, input) {
  const text = input.trim()
  const id = text.match(UC_ID)?.[0]
  const handle = text.match(/@[\w.-]+/)?.[0]
  // legacy URLs carry no @: /user/Name has its own lookup param, while /c/Name
  // and bare youtube.com/Name custom URLs nearly always match today's handle
  const user = text.match(/youtube\.com\/user\/([\w.-]+)/)?.[1]
  const legacy = text.match(
    /youtube\.com\/(?:c\/)?(?!(?:watch|shorts|playlist|embed|results|feed|channel|user)\b)([\w.-]+)/,
  )?.[1]
  const params = { part: 'snippet,statistics,status,topicDetails', key: apiKey }
  if (id) params.id = id
  else if (handle) params.forHandle = handle
  else if (user) params.forUsername = user
  else if (legacy) params.forHandle = legacy
  else throw new Error('Paste a channel URL, @handle, or UC… id')
  const body = await get('channels', params)
  const item = (body.items ?? [])[0]
  if (!item) throw new Error('Channel not found')
  return channelFromItem(item)
}

/* ---- parent-mode YouTube URLs (YouTubeUrls.kt, ported) ----
 *
 * What a channel id looks like, whether the parent is standing on a channel
 * page, and what a pasted URL may name. The same rules the phone WebView
 * uses: match on the parsed host, never a substring, and a watch page that
 * merely MENTIONS a channel is not one. */

export const PARENT_START = 'https://m.youtube.com/'

const CHANNEL_ID_EXACT = /^UC[A-Za-z0-9_-]{22}$/
const CHANNEL_PATH = /^\/channel\/(UC[A-Za-z0-9_-]{22})(?:\/.*)?$/
const HANDLE_PATH = /^\/@([A-Za-z0-9._-]{3,30})(?:\/.*)?$/
const PAGE_HOSTS = new Set(['www.youtube.com', 'm.youtube.com', 'youtube.com'])
const PARENT_HOSTS = new Set([
  'www.youtube.com',
  'm.youtube.com',
  'youtube.com',
  'www.youtube-nocookie.com',
  's.ytimg.com',
  'i.ytimg.com',
  'yt3.ggpht.com',
  'yt3.googleusercontent.com',
  'fonts.gstatic.com',
])
const SIGN_IN_HOSTS = new Set(['google.com', 'accounts.youtube.com', 'consent.youtube.com'])
const AVATAR_HOSTS_STRICT = new Set(['yt3.ggpht.com', 'yt3.googleusercontent.com'])

/** Host of an http(s) URL, userinfo and port stripped. Null for anything else
 *  — including `https://www.youtube.com@attacker.example/`, whose host is
 *  attacker.example. Hand-rolled so lookalikes cannot hide in a substring. */
export function urlHost(url) {
  const m = /^(https?):\/\/([^/?#]+)/i.exec(String(url).trim())
  if (!m) return null
  let host = m[2].toLowerCase()
  const at = host.lastIndexOf('@')
  if (at >= 0) host = host.slice(at + 1)
  const colon = host.indexOf(':')
  if (colon >= 0) host = host.slice(0, colon)
  return host || null
}

export function urlPath(url) {
  const m = /^https?:\/\/[^/?#]+([^?#]*)/i.exec(String(url).trim())
  if (!m) return null
  return m[1] || '/'
}

export function isValidChannelId(id) {
  return CHANNEL_ID_EXACT.test(id)
}

export function channelIdFromUrl(url) {
  if (!urlHost(url)) return null
  const path = urlPath(url)
  if (!path) return null
  const m = CHANNEL_PATH.exec(path)
  return m && isValidChannelId(m[1]) ? m[1] : null
}

export function handleFromUrl(url) {
  if (!urlHost(url)) return null
  const path = urlPath(url)
  if (!path) return null
  return HANDLE_PATH.exec(path)?.[1] ?? null
}

/** Is the parent standing on a channel, such that "approve" means something
 *  unambiguous? Anchored at the start of the path on purpose. */
export function isChannelPage(url) {
  const host = urlHost(url)
  if (!host || !PAGE_HOSTS.has(host)) return false
  const path = urlPath(url)
  if (!path) return false
  return CHANNEL_PATH.test(path) || HANDLE_PATH.test(path)
}

export function isParentBrowsable(url) {
  const host = urlHost(url)
  if (!host) return false
  if (PARENT_HOSTS.has(host) || SIGN_IN_HOSTS.has(host)) return true
  return (
    host.endsWith('.google.com') ||
    host.endsWith('.googlevideo.com') ||
    host.endsWith('.googleusercontent.com') ||
    host.endsWith('.gstatic.com')
  )
}

export function isAllowedAvatar(url) {
  const host = urlHost(url)
  if (!host) return false
  return AVATAR_HOSTS_STRICT.has(host) || host.endsWith('.googleusercontent.com')
}

/** What a parent typed into search, as a URL we may resolve. Bare
 *  @handles and UC… ids become m.youtube.com pages; anything off YouTube is
 *  null — this is not a general browser. */
export function parentBrowseUrl(input) {
  const text = String(input ?? '').trim()
  if (!text) return null
  if (isValidChannelId(text)) return `https://m.youtube.com/channel/${text}`
  if (/^@[A-Za-z0-9._-]{3,30}$/.test(text)) return `https://m.youtube.com/${text}`
  const url = /^https?:\/\//i.test(text) ? text : `https://${text}`
  return isParentBrowsable(url) ? url : null
}

/** PT1H2M3S -> seconds; null when unparsable (e.g. P0D live placeholders). */
export function parseDuration(iso) {
  const m = iso?.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/)
  if (!m || (!m[1] && !m[2] && !m[3])) return null
  return (Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + (Number(m[3]) || 0)
}

/**
 * Latest long-form uploads (UULF playlist excludes Shorts), shaped like the
 * scraper's output. 18+ age-restricted videos (ytRating) are dropped — they
 * wouldn't play in an embed anyway and this is a kids app. 2 units.
 */
export async function fetchChannelVideos(apiKey, channelId) {
  const playlist = await get('playlistItems', {
    part: 'snippet,contentDetails',
    playlistId: `UULF${channelId.slice(2)}`,
    maxResults: 50,
    key: apiKey,
  })
  const items = playlist.items ?? []
  if (!items.length) return []

  const ids = items.map(it => it.contentDetails.videoId)
  const durations = {}
  const ageRestricted = new Set()
  try {
    const details = await get('videos', { part: 'contentDetails', id: ids.join(','), key: apiKey })
    for (const v of details.items ?? []) {
      durations[v.id] = parseDuration(v.contentDetails?.duration)
      if (v.contentDetails?.contentRating?.ytRating === 'ytAgeRestricted') ageRestricted.add(v.id)
    }
  } catch (e) {
    console.warn('duration lookup failed, continuing without', e)
  }

  return items
    .filter(it => !ageRestricted.has(it.contentDetails.videoId))
    .map(it => ({
      id: it.contentDetails.videoId,
      title: it.snippet.title,
      duration: durations[it.contentDetails.videoId] ?? null,
      thumbnail: `https://i.ytimg.com/vi/${it.contentDetails.videoId}/mqdefault.jpg`,
    }))
}

function readCache() {
  try {
    return JSON.parse(localStorage.getItem(CACHE_KEY)) ?? {}
  } catch {
    return {}
  }
}

function writeCache(cache) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(cache))
  } catch (e) {
    console.warn('video cache persist failed', e)
  }
}
const WORKER_URL = 'https://tinytube.vtlinh87.workers.dev'

/**
 * The Worker's SHARED per-channel cache — TITLE, AVATAR AND VIDEOS, for
 * several channels in one request.
 *
 * What a channel is called and what it has posted are facts about the channel,
 * not about a user: the Worker fetches them, keeps them in its own database
 * and refreshes them on its own schedule. This app stores only the parent's
 * DECISION — which ids are approved and for what ages — and asks for the rest.
 * Nothing a browser sends can write that cache, which is what keeps one
 * account from planting "videos" under a channel another child then sees.
 */
async function fetchSharedChannels(ids) {
  const resp = await fetch(`${WORKER_URL}/videos`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ channels: ids }),
  })
  if (!resp.ok) throw new Error(`videos: HTTP ${resp.status}`)
  const body = await resp.json()
  const out = {}
  for (const [id, rec] of Object.entries(body.channels ?? {})) {
    if (!UC_ID.test(id)) continue
    out[id] = normalizeRecord(rec)
  }
  return out
}

/* Re-validated even though our own server said so: an id reaches a URL and a
   thumbnail URL is fetched and drawn. */
function normalizeRecord(rec) {
  const count = n => (Number.isFinite(n) && n >= 0 ? n : null)
  return {
    title: typeof rec?.title === 'string' && rec.title ? rec.title : null,
    thumbnail: safeAvatar(rec?.thumbnail),
    // the stats the parent's channel table shows; absent on the scrape path
    made_for_kids: typeof rec?.made_for_kids === 'boolean' ? rec.made_for_kids : null,
    topics: Array.isArray(rec?.topics) ? rec.topics.filter(t => typeof t === 'string').slice(0, 10) : [],
    subscribers: count(rec?.subscribers),
    video_count: count(rec?.video_count),
    view_count: count(rec?.view_count),
    videos: (rec?.videos ?? [])
      .filter(v => /^[A-Za-z0-9_-]{11}$/.test(v?.id ?? ''))
      .map(v => ({
        id: v.id,
        title: typeof v.title === 'string' ? v.title : v.id,
        duration: Number.isFinite(v.duration) ? v.duration : null,
        thumbnail: `https://i.ytimg.com/vi/${v.id}/mqdefault.jpg`,
      })),
  }
}

const AVATAR_HOSTS = new Set(['yt3.ggpht.com', 'yt3.googleusercontent.com', 'i.ytimg.com'])

function safeAvatar(url) {
  try {
    const u = new URL(url)
    return u.protocol === 'https:' && AVATAR_HOSTS.has(u.hostname) ? u.href : null
  } catch {
    return null
  }
}

/** What the parent already saw when they added a channel, so its name and
 * avatar are on screen before the Worker has ever been asked about it. */
export function seedChannelMeta(ch) {
  const cache = readCache()
  const entry = cache[ch.channel_id]
  cache[ch.channel_id] = {
    fetchedAt: 0, // meta only: still due a real fetch for the videos
    ...entry,
    title: ch.channel_title ?? entry?.title ?? null,
    thumbnail: safeAvatar(ch.thumbnail) ?? entry?.thumbnail ?? null,
  }
  writeCache(cache)
}

/** The phone's ChannelResolver, for channel search: POST the URL to the
 *  Worker's /channel, re-validate the id and avatar on arrival. No API key.
 *  Null when this page is not a channel we can identify. */
export async function resolveChannelPage(url) {
  if (!isParentBrowsable(url)) return null
  const direct = channelIdFromUrl(url)
  let json = null
  try {
    const resp = await fetch(`${WORKER_URL}/channel`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ url }),
    })
    json = resp.ok ? await resp.json() : null
  } catch {
    json = null
  }
  /* Worker unreachable: a /channel/UC… URL still names the id, just without
     a title or videos. The daily refresh will fill them in. */
  if (!json) {
    return direct ? { channel_id: direct, channel_title: direct, thumbnail: null, videos: [] } : null
  }
  const id = typeof json.id === 'string' && isValidChannelId(json.id) ? json.id : null
  if (!id) return null
  const title = typeof json.title === 'string' && json.title.trim() ? json.title.trim() : id
  const thumbnail = isAllowedAvatar(json.avatarUrl ?? '') ? json.avatarUrl : null
  return { channel_id: id, channel_title: title, thumbnail, videos: json.videos }
}

/** Name search via the Worker: no parent API key. Re-validates ids and avatars
 *  even though our own server said so. */
export async function searchChannelsViaWorker(query) {
  const resp = await fetch(`${WORKER_URL}/search`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query }),
  })
  if (!resp.ok) throw new Error("Couldn't search for channels")
  const body = await resp.json()
  const out = []
  for (const ch of body.channels ?? []) {
    const id = typeof ch.id === 'string' && isValidChannelId(ch.id) ? ch.id : null
    if (!id) continue
    out.push({
      channel_id: id,
      channel_title: typeof ch.title === 'string' && ch.title.trim() ? ch.title.trim() : id,
      thumbnail: isAllowedAvatar(ch.avatarUrl ?? '') ? ch.avatarUrl : null,
    })
  }
  return out
}

/** Write the Worker's /channel reply into the local cache so the grid is full
 *  before the parent has closed Parents Mode. An empty/missing videos list
 *  is "could not tell" — fetchedAt stays 0 so a later refresh still asks. */
export function cacheResolvedChannel(ch) {
  if (!isValidChannelId(ch?.channel_id)) return
  const rec = normalizeRecord({
    title: ch.channel_title,
    thumbnail: ch.thumbnail,
    videos: Array.isArray(ch.videos) ? ch.videos : [],
  })
  const cache = readCache()
  const prev = cache[ch.channel_id]
  cache[ch.channel_id] = {
    ...prev,
    ...rec,
    fetchedAt: rec.videos.length ? Date.now() : 0,
    title: rec.title ?? prev?.title ?? null,
    thumbnail: rec.thumbnail ?? prev?.thumbnail ?? null,
  }
  writeCache(cache)
}

/**
 * Title, avatar and videos for every id, cache-first: fresh (<24h) local
 * entries cost nothing, the rest are asked for in ONE request to the Worker,
 * and the parent's own key is the per-channel fallback for videos when the
 * Worker could not answer. A stale entry is kept and returned rather than
 * dropped — a name that is a day old beats a raw channel id.
 */
export async function getChannelsCached(apiKey, ids) {
  const cache = readCache()
  const now = Date.now()
  const wanted = [...new Set(ids)].filter(id => UC_ID.test(id))
  const stale = wanted.filter(id => !(cache[id] && now - cache[id].fetchedAt < CACHE_TTL_MS))

  if (stale.length) {
    let fetched = {}
    try {
      fetched = await fetchSharedChannels(stale)
    } catch (e) {
      console.warn('shared channel cache unreachable', e)
    }
    for (const id of stale) {
      const rec = fetched[id]
      if (rec?.videos.length) {
        cache[id] = { fetchedAt: now, ...rec }
        continue
      }
      // the Worker had nothing: the parent's own key, if there is one
      if (apiKey) {
        try {
          const videos = await fetchChannelVideos(apiKey, id)
          if (videos.length) {
            cache[id] = { ...cache[id], fetchedAt: now, title: rec?.title ?? cache[id]?.title ?? null, videos }
            continue
          }
        } catch (e) {
          console.error(`fetch failed for ${id}, keeping what we have`, e)
        }
      }
      // keep whatever was already known, including a name with no videos yet
      if (rec && !cache[id]) cache[id] = { fetchedAt: 0, ...rec }
    }
    writeCache(cache)
  }

  return Object.fromEntries(wanted.map(id => [id, { ...cache[id], videos: cache[id]?.videos ?? [] }]))
}

export function evictChannelCache(channelId) {
  const cache = readCache()
  delete cache[channelId]
  writeCache(cache)
}
