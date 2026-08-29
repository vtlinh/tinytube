/** When this device asks the DB what it has. Everything syncs on its own —
 * boot, child switch, every playback — so the rules worth pinning are the ones
 * that keep "ask freely" from meaning "ask constantly", and the one case where
 * the answer matters enough to always wait for it. */

import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useSync, useSettings, useWatchStore, PULL_MIN_MS, LIVE_PULL_MS, FIRST_CHILD_ID } from '../src/lib.js'

function fakeStorage() {
  let store = {}
  return {
    getItem: k => store[k] ?? null,
    setItem: (k, v) => { store[k] = String(v) },
    removeItem: k => { delete store[k] },
    clear: () => { store = {} },
  }
}

const EMPTY = { settings: null, watched: [], usage: { days: {}, hours: {} } }
let calls

function signedIn() {
  localStorage.setItem(
    'tinytube:sync:v1',
    JSON.stringify({ token: 't', email: 'a@b.c', expiresAt: Date.now() + 1e9, deviceId: 'dev-1', lastPushAt: {} }),
  )
}

beforeEach(() => {
  vi.stubGlobal('localStorage', fakeStorage())
  vi.stubGlobal('crypto', { ...globalThis.crypto, randomUUID: () => 'uuid-1' })
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url, init) => {
    calls.push({ url, body: JSON.parse(init.body) })
    return { ok: true, status: 200, json: async () => EMPTY }
  }))
})
afterEach(() => vi.unstubAllGlobals())

const pulls = () => calls.filter(c => c.url.endsWith('/sync/pull'))

function renderSync() {
  return renderHook(() => {
    const settings = useSettings()
    const watch = useWatchStore(settings.settings.childId)
    return useSync(settings, watch)
  })
}

describe('the pull throttle', () => {
  it('pulls once on boot and then refuses until PULL_MIN_MS has passed', async () => {
    signedIn()
    const { result } = await act(async () => renderSync())
    expect(pulls()).toHaveLength(1) // boot

    await act(async () => { await result.current.pull() })
    await act(async () => { await result.current.pull() })
    expect(pulls()).toHaveLength(1) // both refused — too soon

    vi.setSystemTime(Date.now() + PULL_MIN_MS + 1)
    await act(async () => { await result.current.pull() })
    expect(pulls()).toHaveLength(2)
    vi.useRealTimers()
  })

  it('force ignores the throttle entirely — the Refresh button and a spent quota', async () => {
    signedIn()
    const { result } = await act(async () => renderSync())
    expect(pulls()).toHaveLength(1)

    await act(async () => { await result.current.pull({ force: true }) })
    await act(async () => { await result.current.pull({ force: true }) })
    expect(pulls()).toHaveLength(3)
  })

  it('returns the payload when it pulled, and null when it declined', async () => {
    signedIn()
    const { result } = await act(async () => renderSync())
    let declined, forced
    await act(async () => { declined = await result.current.pull() })
    await act(async () => { forced = await result.current.pull({ force: true }) })
    expect(declined).toBe(null)
    expect(forced).toEqual(EMPTY)
  })

  /* A failed pull is not a pull: the mark stays at the last SUCCESSFUL one, so
     an offline moment does not buy itself another fifteen minutes of
     staleness after the network comes back. */
  it('does not count a failed pull against the throttle', async () => {
    signedIn()
    const { result } = await act(async () => renderSync())
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    vi.setSystemTime(Date.now() + PULL_MIN_MS + 1) // the boot pull is now stale

    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline') }))
    await act(async () => { await result.current.pull() }) // due, and fails

    // back online: the very next ordinary ask goes through rather than
    // waiting out a window the failure never earned
    vi.stubGlobal('fetch', vi.fn(async (url, init) => {
      calls.push({ url, body: JSON.parse(init.body) })
      return { ok: true, status: 200, json: async () => EMPTY }
    }))
    await act(async () => { await result.current.pull() })
    expect(pulls()).toHaveLength(2) // boot + this one
    vi.useRealTimers()
  })

  it('does nothing at all when nobody is signed in', async () => {
    const { result } = await act(async () => renderSync())
    let out
    await act(async () => { out = await result.current.pull({ force: true }) })
    expect(out).toBe(null)
    expect(calls).toHaveLength(0)
  })
})

describe('restoring the account onto a new phone', () => {
  const UC = 'UC' + 'a'.repeat(22)

  it('adopts the DB family even when empty local defaults stamped a newer clock', async () => {
    localStorage.setItem(
      'tinytube:settings:v1',
      JSON.stringify({
        updatedAt: 9_000,
        passkeyId: 'phone-b',
        children: [{ id: FIRST_CHILD_ID, name: 'Child 1', customChannels: [] }],
        activeChildId: FIRST_CHILD_ID,
      }),
    )
    signedIn()
    vi.stubGlobal('fetch', vi.fn(async (url, init) => {
      calls.push({ url, body: JSON.parse(init.body) })
      return {
        ok: true,
        status: 200,
        json: async () => ({
          settings: {
            updatedAt: 1000,
            data: {
              children: [{
                id: FIRST_CHILD_ID,
                name: 'Emma',
                customChannels: [{ channel_id: UC, min_age: null, max_age: null }],
                birthday: '2020-01',
              }],
              activeChildId: FIRST_CHILD_ID,
            },
          },
          watched: [],
          usage: { days: {}, hours: {} },
        }),
      }
    }))
    const { result } = await act(async () => renderHook(() => {
      const settings = useSettings()
      const watch = useWatchStore(settings.settings.childId)
      return { sync: useSync(settings, watch), settings }
    }))
    expect(result.current.settings.settings.childName).toBe('Emma')
    expect(result.current.settings.settings.customChannels).toHaveLength(1)
    expect(result.current.settings.settings.passkeyId).toBe('phone-b')
    expect(result.current.settings.stored.updatedAt).toBe(1000)
  })

  it('a child signing in restores their channels and sits on their own grid', async () => {
    localStorage.setItem(
      'tinytube:sync:v1',
      JSON.stringify({ token: 't', email: 'emma@example.com', expiresAt: Date.now() + 1e9, deviceId: 'dev-1', lastPushAt: {} }),
    )
    vi.stubGlobal('fetch', vi.fn(async (url, init) => {
      calls.push({ url, body: JSON.parse(init.body) })
      return {
        ok: true,
        status: 200,
        json: async () => ({
          settings: {
            updatedAt: 1000,
            data: {
              children: [
                { id: FIRST_CHILD_ID, name: 'Ann', email: 'ann@example.com', customChannels: [] },
                {
                  id: 'emma-1',
                  name: 'Emma',
                  email: 'emma@example.com',
                  customChannels: [{ channel_id: UC, min_age: null, max_age: null }],
                  birthday: '2020-01',
                },
              ],
              activeChildId: FIRST_CHILD_ID, // the parent last looked at Ann
            },
          },
          watched: [],
          usage: { days: {}, hours: {} },
        }),
      }
    }))
    const { result } = await act(async () => renderHook(() => {
      const settings = useSettings()
      const watch = useWatchStore(settings.settings.childId)
      return { sync: useSync(settings, watch), settings }
    }))
    expect(result.current.settings.settings.childId).toBe('emma-1')
    expect(result.current.settings.settings.childName).toBe('Emma')
    expect(result.current.settings.settings.customChannels).toHaveLength(1)
    expect(result.current.settings.settings.passkeyId).toBeFalsy()
  })

  it('pulls again when the app has been in the background past LIVE_PULL_MS', async () => {
    signedIn()
    const { result } = await act(async () => renderSync())
    expect(pulls()).toHaveLength(1)

    vi.setSystemTime(Date.now() + LIVE_PULL_MS + 1)
    await act(async () => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(pulls()).toHaveLength(2)
    // the playback throttle has not elapsed — an ordinary ask is still refused
    await act(async () => { await result.current.pull() })
    expect(pulls()).toHaveLength(2)
    vi.useRealTimers()
  })
})
