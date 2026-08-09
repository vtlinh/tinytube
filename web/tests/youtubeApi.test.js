import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  searchChannels,
  resolveChannel,
  parseDuration,
  fetchChannelVideos,
  getChannelsCached,
  seedChannelMeta,
  validateApiKey,
} from '../src/youtubeApi.js'

const UC = 'UCoookXUzPciGrEZEXmh4Jjg'
const UC2 = 'UCG2CL6EUjG8TVT1Tpl9nJdg'

function mockFetch(bodyByPath) {
  return vi.fn(async url => {
    const u = new URL(url)
    const path = u.pathname.split('/').pop()
    const body = bodyByPath[path]
    if (!body) return { ok: false, status: 404, json: async () => ({ error: { message: 'not found' } }) }
    return { ok: true, json: async () => (typeof body === 'function' ? body(u.searchParams) : body) }
  })
}

// Node 22+ exposes a broken experimental localStorage global that shadows
// jsdom's — stub a real in-memory one so the cache actually persists in tests.
function fakeStorage() {
  let store = {}
  return {
    getItem: k => store[k] ?? null,
    setItem: (k, v) => { store[k] = String(v) },
    removeItem: k => { delete store[k] },
    clear: () => { store = {} },
  }
}

beforeEach(() => vi.stubGlobal('localStorage', fakeStorage()))
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('parseDuration', () => {
  it('parses ISO8601 durations', () => {
    expect(parseDuration('PT1H2M3S')).toBe(3723)
    expect(parseDuration('PT2M')).toBe(120)
    expect(parseDuration('PT45S')).toBe(45)
    expect(parseDuration('P0D')).toBeNull()
    expect(parseDuration(undefined)).toBeNull()
  })
})

describe('resolveChannel', () => {
  const snippet = { title: 'Chan', thumbnails: { medium: { url: 't.jpg' } } }

  it.each([
    [`https://www.youtube.com/channel/${UC}`, 'id', UC],
    [UC, 'id', UC],
    ['https://www.youtube.com/@SciShowKids', 'forHandle', '@SciShowKids'],
    ['@SciShowKids', 'forHandle', '@SciShowKids'],
    ['https://www.youtube.com/c/SciShowKids', 'forHandle', 'SciShowKids'],
    ['https://www.youtube.com/SciShowKids/videos', 'forHandle', 'SciShowKids'],
    ['youtube.com/user/scishow', 'forUsername', 'scishow'],
  ])('parses %s', async (input, param, expected) => {
    const fetch = mockFetch({ channels: params => {
      expect(params.get(param)).toBe(expected)
      return { items: [{ id: UC, snippet }] }
    } })
    vi.stubGlobal('fetch', fetch)
    const ch = await resolveChannel('KEY', input)
    expect(ch).toEqual({
      channel_id: UC,
      channel_title: 'Chan',
      thumbnail: 't.jpg',
      made_for_kids: null,
      topics: [],
      subscribers: null,
      video_count: null,
      view_count: null,
    })
  })

  it('surfaces the made-for-kids flag and topic categories', async () => {
    vi.stubGlobal('fetch', mockFetch({ channels: { items: [{
      id: UC,
      snippet,
      status: { madeForKids: true },
      topicDetails: { topicCategories: [
        'https://en.wikipedia.org/wiki/Children%27s_music',
        'https://en.wikipedia.org/wiki/Education',
        'https://en.wikipedia.org/wiki/Education', // API repeats parents; deduped
      ] },
    }] } }))
    const ch = await resolveChannel('KEY', UC)
    expect(ch.made_for_kids).toBe(true)
    expect(ch.topics).toEqual(["Children's music", 'Education'])
  })

  it('rejects unparsable input without a network call', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    await expect(resolveChannel('KEY', 'just words')).rejects.toThrow(/Paste a channel/)
    await expect(resolveChannel('KEY', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ')).rejects.toThrow(
      /Paste a channel/,
    )
    expect(fetch).not.toHaveBeenCalled()
  })
})

describe('searchChannels', () => {
  it('searches type=channel and enriches with subscriber counts for preview', async () => {
    vi.stubGlobal('fetch', mockFetch({
      search: params => {
        expect(params.get('type')).toBe('channel')
        expect(params.get('q')).toBe('blippi')
        return { items: [{ id: { channelId: UC }, snippet: { title: 'Blippi', thumbnails: {} } }] }
      },
      channels: {
        items: [{
          id: UC,
          snippet: { title: 'Blippi', thumbnails: { medium: { url: 'avatar.jpg' } } },
          statistics: { subscriberCount: '1000000', videoCount: '850', viewCount: '2500000000' },
        }],
      },
    }))
    const results = await searchChannels('KEY', 'blippi')
    expect(results).toEqual([
      {
        channel_id: UC,
        channel_title: 'Blippi',
        thumbnail: 'avatar.jpg',
        subscribers: 1000000,
        video_count: 850,
        view_count: 2500000000,
        made_for_kids: null,
        topics: [],
      },
    ])
  })

  it('falls back to bare search snippets if the preview lookup fails', async () => {
    vi.stubGlobal('fetch', mockFetch({
      search: { items: [{ id: { channelId: UC }, snippet: { title: 'Blippi', thumbnails: {} } }] },
      // no `channels` route -> 404 -> enrichment swallowed
    }))
    const results = await searchChannels('KEY', 'blippi')
    expect(results).toEqual([{ channel_id: UC, channel_title: 'Blippi', thumbnail: undefined }])
  })
})

describe('validateApiKey', () => {
  it('resolves for a working key', async () => {
    vi.stubGlobal('fetch', mockFetch({ i18nLanguages: params => {
      expect(params.get('key')).toBe('KEY')
      return { items: [] }
    } }))
    await expect(validateApiKey('KEY')).resolves.toBeUndefined()
  })

  it('rejects with the API error message for a bad key', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 400,
      json: async () => ({ error: { message: 'API key not valid. Please pass a valid API key.' } }),
    })))
    await expect(validateApiKey('BAD')).rejects.toThrow('API key not valid')
  })
})

describe('fetchChannelVideos', () => {
  it('uses the long-form UULF playlist and merges durations', async () => {
    vi.stubGlobal('fetch', mockFetch({
      playlistItems: params => {
        expect(params.get('playlistId')).toBe(`UULF${UC.slice(2)}`)
        return { items: [{ contentDetails: { videoId: 'vid1' }, snippet: { title: 'T1' } }] }
      },
      videos: { items: [{ id: 'vid1', contentDetails: { duration: 'PT2M' } }] },
    }))
    const videos = await fetchChannelVideos('KEY', UC)
    expect(videos).toEqual([
      { id: 'vid1', title: 'T1', duration: 120, thumbnail: 'https://i.ytimg.com/vi/vid1/mqdefault.jpg' },
    ])
  })

  it('drops 18+ age-restricted videos', async () => {
    vi.stubGlobal('fetch', mockFetch({
      playlistItems: { items: [
        { contentDetails: { videoId: 'ok1' }, snippet: { title: 'Fine' } },
        { contentDetails: { videoId: 'adult1' }, snippet: { title: 'Nope' } },
      ] },
      videos: { items: [
        { id: 'ok1', contentDetails: { duration: 'PT1M' } },
        { id: 'adult1', contentDetails: { duration: 'PT1M', contentRating: { ytRating: 'ytAgeRestricted' } } },
      ] },
    }))
    const videos = await fetchChannelVideos('KEY', UC)
    expect(videos.map(v => v.id)).toEqual(['ok1'])
  })

  it('propagates API errors', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 403,
      json: async () => ({ error: { message: 'quota exceeded' } }),
    })))
    await expect(fetchChannelVideos('KEY', UC)).rejects.toThrow('quota exceeded')
  })
})

describe('getChannelsCached', () => {
  const record = (id, extra = {}) => ({
    channel_id: id,
    title: 'Chan',
    thumbnail: 'https://yt3.ggpht.com/a.jpg',
    made_for_kids: true,
    topics: ['Music'],
    subscribers: 1234,
    video_count: 10,
    view_count: 99,
    videos: [{ id: 'dQw4w9WgXcQ', title: 'Vid', duration: 212 }],
    ...extra,
  })

  // route by host: the Worker's /videos vs the Data API
  const routedFetch = (workerBody, apiBodies = {}) =>
    vi.fn(async (url, init) => {
      const u = new URL(url)
      if (u.hostname.endsWith('workers.dev')) {
        if (!workerBody) return { ok: false, status: 503, json: async () => ({}) }
        return { ok: true, json: async () => workerBody(JSON.parse(init.body)) }
      }
      const body = apiBodies[u.pathname.split('/').pop()]
      if (!body) return { ok: false, status: 404, json: async () => ({ error: { message: 'not found' } }) }
      return { ok: true, json: async () => body }
    })

  it('asks the Worker ONCE for every channel and returns titles, avatars and videos', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000)
    const fetch = routedFetch(body => ({
      channels: Object.fromEntries(body.channels.map(id => [id, record(id)])),
    }))
    vi.stubGlobal('fetch', fetch)

    // NO api key: the shared cache is what makes custom channels work keyless
    const byId = await getChannelsCached('', [UC, UC2])
    expect(fetch.mock.calls).toHaveLength(1) // one request, two channels
    expect(JSON.parse(fetch.mock.calls[0][1].body).channels).toEqual([UC, UC2])
    // the whole record, pinned: title, avatar, the table's stats, and videos
    expect(byId[UC]).toEqual({
      title: 'Chan',
      thumbnail: 'https://yt3.ggpht.com/a.jpg',
      made_for_kids: true,
      topics: ['Music'],
      subscribers: 1234,
      video_count: 10,
      view_count: 99,
      fetchedAt: 1_000_000,
      videos: [
        { id: 'dQw4w9WgXcQ', title: 'Vid', duration: 212, thumbnail: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg' },
      ],
    })

    // a second call inside the TTL costs nothing
    await getChannelsCached('', [UC, UC2])
    expect(fetch.mock.calls).toHaveLength(1)

    // ...and past it, asks again
    vi.setSystemTime(1_000_000 + 25 * 60 * 60 * 1000)
    await getChannelsCached('', [UC])
    expect(fetch.mock.calls).toHaveLength(2)
  })

  it('re-validates what the Worker sends, even though it is our own server', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(2_000_000)
    vi.stubGlobal(
      'fetch',
      routedFetch(() => ({
        channels: {
          [UC]: {
            channel_id: UC,
            title: 'Chan',
            thumbnail: 'https://evil.example/track.gif', // not a YouTube host
            videos: [{ id: 'not-an-id!' }, { id: 'dQw4w9WgXcQ', title: 'Ok' }],
          },
        },
      })),
    )
    const byId = await getChannelsCached('', [UC])
    expect(byId[UC].thumbnail).toBe(null) // dropped, but the channel is kept
    expect(byId[UC].videos.map(v => v.id)).toEqual(['dQw4w9WgXcQ'])
  })

  it("falls back to the parent's key when the Worker has nothing, and keeps stale entries otherwise", async () => {
    vi.useFakeTimers()
    vi.setSystemTime(3_000_000)
    const fetch = routedFetch(null, {
      playlistItems: { items: [{ contentDetails: { videoId: 'aqz-KE-bpKQ' }, snippet: { title: 'Own' } }] },
      videos: { items: [] },
    })
    vi.stubGlobal('fetch', fetch)
    vi.spyOn(console, 'warn').mockImplementation(() => {})

    const byId = await getChannelsCached('KEY', [UC])
    expect(byId[UC].videos.map(v => v.id)).toEqual(['aqz-KE-bpKQ'])

    // the Worker is still down and there is no key: what was cached survives
    vi.setSystemTime(3_000_000 + 25 * 60 * 60 * 1000)
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const later = await getChannelsCached('', [UC])
    expect(later[UC].videos.map(v => v.id)).toEqual(['aqz-KE-bpKQ'])
  })

  it('seedChannelMeta puts the name on screen before the Worker is ever asked', async () => {
    seedChannelMeta({ channel_id: UC, channel_title: 'Just added', thumbnail: 'https://yt3.ggpht.com/x.jpg' })
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline') }))
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const byId = await getChannelsCached('', [UC])
    expect(byId[UC].title).toBe('Just added')
    expect(byId[UC].thumbnail).toBe('https://yt3.ggpht.com/x.jpg')
  })
})
