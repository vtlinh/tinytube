import { render, screen, fireEvent, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import App from '../src/main.jsx'
import { verify } from '../src/lib.js'

// partial mock: webauthn now lives in lib.js next to the hooks, which must stay real
vi.mock('../src/lib.js', async importOriginal => ({
  ...(await importOriginal()),
  verify: vi.fn(async () => true),
  isBiometricAvailable: vi.fn(async () => false),
  enroll: vi.fn(async () => 'fresh-credential'),
}))
import { isBiometricAvailable } from '../src/lib.js'

// the quota tests walk into the player view; stub the iframe like VideoPlayer.test
// does, keeping the props so the auto-advance tests can see which video is loaded
// and fire the ENDED the real player would
let yt
vi.mock('react-youtube', () => ({
  default: props => {
    yt = props
    return <div data-testid="yt-stub" />
  },
}))

const db = {
  schema_version: 2,
  generated_at: 'x',
  channels: [
    {
      channel_id: 'UCa',
      channel_title: 'Chan',
      min_age: 1,
      max_age: 15,
      videos: [{ id: 'v1', title: 'Vid', duration: 10, thumbnail: 't.jpg' }],
    },
  ],
}

// Node 22+'s broken experimental localStorage shadows jsdom's — use a real fake
function fakeStorage() {
  let store = {}
  return {
    getItem: k => store[k] ?? null,
    setItem: (k, v) => { store[k] = String(v) },
    removeItem: k => { delete store[k] },
    clear: () => { store = {} },
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', fakeStorage())
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => db })))
  verify.mockClear()
})

describe('first-run enrollment', () => {
  it('blocks everything with the enroll screen on a biometric device until enrolled', async () => {
    isBiometricAvailable.mockResolvedValueOnce(true)
    render(<App />)
    expect(await screen.findByText('Enter')).toBeTruthy()
    expect(screen.queryByLabelText('Parents')).toBeNull() // no gallery behind it
    fireEvent.click(screen.getByText('Enter'))
    expect(await screen.findByLabelText('Parents')).toBeTruthy() // gallery after enrolling
    expect(JSON.parse(localStorage.getItem('tinytube:settings:v1')).passkeyId).toBe('fresh-credential')
  })

  it('skips the enroll screen when already enrolled', async () => {
    isBiometricAvailable.mockResolvedValueOnce(true)
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ passkeyId: 'abc' }))
    render(<App />)
    expect(await screen.findByLabelText('Parents')).toBeTruthy()
    expect(screen.queryByText('Enter')).toBeNull()
  })
})

describe('parent gate', () => {
  it('opens the math gate when no passkey is enrolled', async () => {
    render(<App />)
    fireEvent.click(await screen.findByLabelText('Parents'))
    expect(await screen.findByText(/Grown-ups only/)).toBeTruthy()
    expect(verify).not.toHaveBeenCalled()
  })

  it('goes straight to the biometric and into settings when enrolled', async () => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ passkeyId: 'abc' }))
    render(<App />)
    fireEvent.click(await screen.findByLabelText('Parents'))
    expect(await screen.findByText(/Parents Mode/)).toBeTruthy()
    expect(verify).toHaveBeenCalledWith('abc')
    expect(screen.queryByText(/Grown-ups only/)).toBeNull()
  })

  it('stays on the gallery when the biometric is cancelled', async () => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ passkeyId: 'abc' }))
    verify.mockResolvedValueOnce(false)
    render(<App />)
    fireEvent.click(await screen.findByLabelText('Parents'))
    expect(verify).toHaveBeenCalled()
    expect(screen.queryByText(/Parents Mode/)).toBeNull()
    expect(screen.queryByText(/Grown-ups only/)).toBeNull()
  })
})

describe('watch quota gate', () => {
  // the day bucket is what a daily limit reads; the key is the local date
  const today = new Date()
  const dayKey = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  const spendQuota = (mins = 15) => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ quotaMins: mins }))
    localStorage.setItem(
      'tinytube:v1',
      JSON.stringify({ watched: {}, usage: { window: { start: Date.now(), secs: 0 }, days: { [dayKey]: mins * 60 }, hours: {} } }),
    )
  }

  it('opens the player while under quota', async () => {
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByTitle('Watch time left')).toBeTruthy() // player top bar quota meter
  })

  it('blocks the tap with the exceeded screen when the quota is spent', async () => {
    spendQuota()
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByText(/Watch Quota Exceeded/)).toBeTruthy()
    expect(screen.queryByTitle('Watch time left')).toBeNull() // no player behind it
  })

  it('always blocks when the quota is 0 (no off state)', async () => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ quotaMins: 0 }))
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByText(/Watch Quota Exceeded/)).toBeTruthy()
  })

  it('unblocks by itself once the day it was spent in is over', async () => {
    spendQuota()
    const stale = JSON.parse(localStorage.getItem('tinytube:v1'))
    stale.usage.days = { '2020-01-01': 15 * 60 } // spent, but not today
    localStorage.setItem('tinytube:v1', JSON.stringify(stale))
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByTitle('Watch time left')).toBeTruthy()
  })

  it('sends a parent through the biometric into settings', async () => {
    spendQuota()
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ quotaMins: 15, passkeyId: 'abc' }))
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    fireEvent.click(await screen.findByText(/Parents/))
    expect(await screen.findByText(/Parents Mode/)).toBeTruthy()
    expect(verify).toHaveBeenCalledWith('abc')
  })

  it('falls back to the math gate without a passkey', async () => {
    spendQuota()
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    fireEvent.click(await screen.findByText(/Parents/))
    expect(await screen.findByText(/Grown-ups only/)).toBeTruthy()
  })

  it('goes back to the gallery on browser back', async () => {
    spendQuota()
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    await screen.findByText(/Watch Quota Exceeded/)
    fireEvent.popState(window)
    expect(await screen.findByLabelText('Parents')).toBeTruthy()
    expect(screen.queryByText(/Watch Quota Exceeded/)).toBeNull()
  })
})

/* Every playback is an occasion to catch up with the DB, and a spent quota is
 * the one moment the answer matters enough to wait for: a grown-up may have
 * granted time from another device, and that grant lives in the settings blob. */
describe('pulling from the DB on playback', () => {
  const today = new Date()
  const dayKey = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

  // signed in, with 15 of 15 daily minutes already spent
  const spentAndSignedIn = () => {
    localStorage.setItem('tinytube:settings:v1', JSON.stringify({ quotaMins: 15 }))
    localStorage.setItem(
      'tinytube:v1',
      JSON.stringify({ watched: {}, usage: { window: { start: Date.now(), secs: 0 }, days: { [dayKey]: 15 * 60 }, hours: {} } }),
    )
    localStorage.setItem(
      'tinytube:sync:v1',
      JSON.stringify({ token: 't', email: 'a@b.c', expiresAt: Date.now() + 1e9, deviceId: 'd1', lastPushAt: {} }),
    )
  }

  const syncStub = (settings = null) => {
    const calls = []
    vi.stubGlobal('fetch', vi.fn(async (url, init) => {
      if (String(url).includes('videos.json')) return { ok: true, json: async () => db }
      calls.push(String(url))
      return { ok: true, status: 200, json: async () => ({ settings, watched: [], usage: { days: {}, hours: {} } }) }
    }))
    return calls
  }

  it('asks the DB when a video is tapped, without holding the tap up', async () => {
    localStorage.setItem(
      'tinytube:sync:v1',
      JSON.stringify({ token: 't', email: 'a@b.c', expiresAt: Date.now() + 1e9, deviceId: 'd1', lastPushAt: {} }),
    )
    const calls = syncStub()
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByTitle('Watch time left')).toBeTruthy() // player opened
    expect(calls.filter(u => u.endsWith('/sync/pull'))).toHaveLength(1) // the boot one; the tap's was throttled
  })

  /* The grant is in the settings blob, so a pull that brings a newer blob with
     bonus minutes has to unblock this device without a reload. */
  it('lets the child through when the DB says a parent granted more time', async () => {
    spentAndSignedIn()
    syncStub({
      updatedAt: Date.now() + 10_000, // newer than anything local
      data: {
        children: [{ id: 'default', name: 'Child 1', quota: { per6h: null, perDay: 15, perWeek: null, perMonth: null },
          day: { until: new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1).getTime(), bonusMins: 30 } }],
        activeChildId: 'default',
      },
    })
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByTitle('Watch time left')).toBeTruthy()
    expect(screen.queryByText(/Watch Quota Exceeded/)).toBeNull()
  })

  it('still blocks when the DB has nothing new to say', async () => {
    spentAndSignedIn()
    const calls = syncStub()
    render(<App />)
    fireEvent.click(await screen.findByText('Vid'))
    expect(await screen.findByText(/Watch Quota Exceeded/)).toBeTruthy()
    // forced past the throttle: boot pull + the tap's
    expect(calls.filter(u => u.endsWith('/sync/pull'))).toHaveLength(2)
  })
})

/* One video ending starts the next one, out of the list the child tapped on —
 * the whole point of carrying that list into the player. What "the next one"
 * means is the parent's Play Next setting. */
describe('what plays next', () => {
  const three = {
    schema_version: 2,
    generated_at: 'x',
    channels: [
      {
        channel_id: 'UCa',
        channel_title: 'Chan',
        min_age: 1,
        max_age: 15,
        videos: [
          { id: 'v1', title: 'One', duration: 10, thumbnail: 't.jpg' },
          { id: 'v2', title: 'Two', duration: 10, thumbnail: 't.jpg' },
          { id: 'v3', title: 'Three', duration: 10, thumbnail: 't.jpg' },
        ],
      },
    ],
  }
  const withVideos = (settings = {}) => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => three })))
    localStorage.setItem('tinytube:settings:v1', JSON.stringify(settings))
  }
  const end = () => act(() => yt.onStateChange({ data: 0 })) // ENDED

  it('plays the next one down the grid, in order', async () => {
    withVideos()
    render(<App />)
    fireEvent.click(await screen.findByText('One'))
    expect(yt.videoId).toBe('v1')
    end()
    expect(yt.videoId).toBe('v2')
    end()
    expect(yt.videoId).toBe('v3')
  })

  it('stops at the end of the list rather than wrapping', async () => {
    withVideos()
    render(<App />)
    fireEvent.click(await screen.findByText('Three'))
    expect(yt.videoId).toBe('v3')
    end()
    expect(await screen.findByLabelText('Parents')).toBeTruthy() // back on the grid
  })

  it('picks another video at random, never the one that just played', async () => {
    withVideos({ playback: 'RANDOM' })
    render(<App />)
    fireEvent.click(await screen.findByText('Two'))
    for (let i = 0; i < 20; i++) {
      const was = yt.videoId
      end()
      expect(yt.videoId).not.toBe(was)
      expect(['v1', 'v2', 'v3']).toContain(yt.videoId)
    }
  })

  /* Random over everything meant a child who had seen most of a channel kept
     being handed repeats. It skips what the grid would badge as watched. */
  it('picks an unwatched video in random mode, not one already seen', async () => {
    withVideos({ playback: 'RANDOM' })
    // v1 and v3 finished; only v2 is left unseen
    localStorage.setItem(
      'tinytube:v1',
      JSON.stringify({
        watched: {
          v1: { pos: 10, dur: 10, completed: true, updatedAt: 1 },
          v3: { pos: 10, dur: 10, completed: true, updatedAt: 1 },
        },
        usage: { window: { start: null, secs: 0 }, days: {}, hours: {} },
      }),
    )
    render(<App />)
    fireEvent.click(await screen.findByText('One'))
    expect(yt.videoId).toBe('v1')

    // v2 is the only one not already seen, so random has exactly one answer
    end()
    expect(yt.videoId).toBe('v2')

    /* And now everything has been seen. A preference is not a restriction:
       it falls back to the rest rather than ending playback. */
    end()
    expect(['v1', 'v3']).toContain(yt.videoId)
  })

  it('stays inside the channel the child stepped into', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      json: async () => ({
        ...three,
        channels: [
          three.channels[0],
          {
            channel_id: 'UCb',
            channel_title: 'Other',
            min_age: 1,
            max_age: 15,
            videos: [{ id: 'v9', title: 'Elsewhere', duration: 10, thumbnail: 't.jpg' }],
          },
        ],
      }),
    })))
    render(<App />)
    fireEvent.click(await screen.findByText('Channels'))
    fireEvent.click(await screen.findByText('Chan'))
    fireEvent.click(await screen.findByText('One'))
    end()
    end()
    expect(yt.videoId).toBe('v3') // never v9, and there is no rule in the player saying so
  })
})
