/** The pure half of sync: merging, delta selection, and the quota math that
 * folds in what other devices watched. The network half is the Worker's
 * problem and is tested in worker.test.mjs at the repo root. */

import {
  mergeWatched,
  remoteRecentSecs,
  usedSecs,
  statsUsage,
  watchedDeltas,
  usagePushDelta,
  PUSH_RECENT_HOURS,
} from '../src/lib.js'

const HOUR_MS = 3600_000

/* A device is an authority on its OWN recent watching and nothing else: the
   trailing 6 hours (what per6h reads) and today (the only day still moving).
   Week, month and year are summed out of D1 across every device on the pull,
   so pushing them again was hundreds of rows of settled history every few
   seconds. */
describe('usagePushDelta', () => {
  const now = new Date(2026, 7, 9, 14, 30).getTime() // local 9 Aug 2026, 14:30
  const nowHour = Math.floor(now / HOUR_MS)
  const usage = {
    days: { '2026-08-09': 600, '2026-08-08': 900, '2026-07-31': 1200, '2025-12-25': 60 },
    hours: {
      [nowHour]: 100,
      [nowHour - 1]: 200,
      [nowHour - PUSH_RECENT_HOURS + 1]: 300, // just inside
      [nowHour - PUSH_RECENT_HOURS]: 400, // just outside
      [nowHour - 30]: 500, // long gone
    },
  }

  it('sends today and the trailing 6 hours, and nothing else', () => {
    const out = usagePushDelta(usage, 1000, now)
    expect(out.days).toEqual({ '2026-08-09': 600 })
    expect(out.hours).toEqual({
      [nowHour]: 100,
      [nowHour - 1]: 200,
      [nowHour - PUSH_RECENT_HOURS + 1]: 300,
    })
  })

  it('matches the window per6h actually reads, so the tightest limit is never short', () => {
    // usageByPeriod counts hours with `+k > nowHour - 6`; the push must carry
    // every bucket that check can see or a fresh device under-counts
    const out = usagePushDelta(usage, 1000, now)
    const counted = Object.keys(usage.hours).filter(k => +k > nowHour - 6)
    expect(Object.keys(out.hours).sort()).toEqual(counted.sort())
  })

  /* A phone used offline for a week and then signed in owes the account that
     history, and the first push is the one moment nothing else carries it. */
  it('sends everything on a device’s first push', () => {
    expect(usagePushDelta(usage, 0, now)).toEqual({ days: usage.days, hours: usage.hours })
    expect(usagePushDelta(usage, undefined, now)).toEqual({ days: usage.days, hours: usage.hours })
  })

  it('sends no day at all when nothing was watched today', () => {
    const out = usagePushDelta({ days: { '2026-08-08': 900 }, hours: {} }, 1000, now)
    expect(out).toEqual({ days: {}, hours: {} })
  })

  it('survives empty and absent buckets', () => {
    expect(usagePushDelta({}, 1000, now)).toEqual({ days: {}, hours: {} })
    expect(usagePushDelta(undefined, 1000, now)).toEqual({ days: {}, hours: {} })
  })
})

describe('mergeWatched', () => {
  const local = {
    aaaaaaaaaaa: { pos: 10, dur: 100, completed: false, updatedAt: 2000 },
    bbbbbbbbbbb: { pos: 50, dur: 100, completed: true, updatedAt: 1000 },
  }

  it('newer remote rows win, older lose, unknown ids are added', () => {
    const merged = mergeWatched(local, [
      { id: 'aaaaaaaaaaa', pos: 99, dur: 100, completed: true, updatedAt: 3000 }, // newer -> wins
      { id: 'bbbbbbbbbbb', pos: 1, dur: 100, completed: false, updatedAt: 500 }, // older -> loses
      { id: 'ccccccccccc', pos: 5, dur: 60, completed: false, updatedAt: 100 }, // new -> added
    ])
    expect(merged.aaaaaaaaaaa).toEqual({ pos: 99, dur: 100, completed: true, updatedAt: 3000 })
    expect(merged.bbbbbbbbbbb).toEqual(local.bbbbbbbbbbb)
    expect(merged.ccccccccccc).toEqual({ pos: 5, dur: 60, completed: false, updatedAt: 100 })
  })

  it('does not mutate the local map and tolerates an absent remote', () => {
    const before = JSON.parse(JSON.stringify(local))
    mergeWatched(local, [{ id: 'aaaaaaaaaaa', pos: 99, dur: 100, completed: true, updatedAt: 9999 }])
    expect(local).toEqual(before)
    expect(mergeWatched(local, undefined)).toEqual(local)
  })
})

describe('remoteRecentSecs / usedSecs', () => {
  const now = 1_700_000_000_000
  const nowHour = Math.floor(now / HOUR_MS)

  it('sums only the trailing 12 clock hours', () => {
    const hours = {
      [nowHour]: 100,
      [nowHour - 11]: 200, // just inside
      [nowHour - 12]: 400, // just outside
    }
    expect(remoteRecentSecs(hours, now)).toBe(300)
    expect(remoteRecentSecs({}, now)).toBe(0)
    expect(remoteRecentSecs(undefined, now)).toBe(0)
  })

  it('usedSecs is the max of the local window and the remote sum, never the sum of both', () => {
    const store = {
      usage: { window: { start: now - 1000, secs: 500 }, days: {}, hours: {} },
      remote: { days: {}, hours: { [nowHour]: 200 } },
    }
    expect(usedSecs(store, now)).toBe(500) // local ahead (remote lags behind pushes)
    store.remote.hours[nowHour] = 900
    expect(usedSecs(store, now)).toBe(900) // another device watched more
    // an expired local window leaves only the remote figure
    store.usage.window.start = now - 13 * HOUR_MS
    expect(usedSecs(store, now)).toBe(900)
  })

  it('a store that has never synced behaves exactly as before', () => {
    const store = { usage: { window: { start: now - 1000, secs: 500 }, days: {}, hours: {} } }
    expect(usedSecs(store, now)).toBe(500)
  })
})

describe('statsUsage', () => {
  it('takes the per-bucket max of local and remote, keeping the local window', () => {
    const store = {
      usage: { window: { start: 1, secs: 2 }, days: { '2026-08-09': 100 }, hours: { 100: 5 } },
      remote: { days: { '2026-08-09': 300, '2026-08-08': 50 }, hours: { 100: 3 } },
    }
    expect(statsUsage(store)).toEqual({
      window: { start: 1, secs: 2 },
      days: { '2026-08-09': 300, '2026-08-08': 50 },
      hours: { 100: 5 },
    })
  })
})

describe('watchedDeltas', () => {
  it('selects rows changed after the cutoff, shaped for the push body', () => {
    const watched = {
      aaaaaaaaaaa: { pos: 1, dur: 2, completed: false, updatedAt: 100 },
      bbbbbbbbbbb: { pos: 3, dur: 4, completed: true, updatedAt: 300 },
    }
    expect(watchedDeltas(watched, 200)).toEqual([
      { id: 'bbbbbbbbbbb', pos: 3, dur: 4, completed: true, updatedAt: 300 },
    ])
    expect(watchedDeltas(watched, 300)).toEqual([])
    expect(watchedDeltas({}, 0)).toEqual([])
  })
})
