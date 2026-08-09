/** When this device asks the DB what it has. Everything syncs on its own —
 * boot, child switch, every playback — so the rules worth pinning are the ones
 * that keep "ask freely" from meaning "ask constantly", and the one case where
 * the answer matters enough to always wait for it. */

import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useSync, useSettings, useWatchStore, PULL_MIN_MS } from '../src/lib.js'

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
