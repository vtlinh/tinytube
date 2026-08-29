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
  isVirginSettings,
  shouldAdoptRemoteSettings,
  applyRemoteSettings,
  shouldPushSettings,
  settingsPushPayload,
  CHILD_DEFAULTS,
  FIRST_CHILD_ID,
  normalizeSettings,
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

const UC = 'UC' + 'a'.repeat(22)
const family = {
  children: [{
    id: FIRST_CHILD_ID,
    name: 'Emma',
    customChannels: [{ channel_id: UC, min_age: null, max_age: null }],
    birthday: '2020-01',
  }],
  activeChildId: FIRST_CHILD_ID,
}
const empty = normalizeSettings({})

describe('isVirginSettings', () => {
  it('treats factory defaults as empty, and any parental setup as not', () => {
    expect(isVirginSettings(empty)).toBe(true)
    expect(isVirginSettings({})).toBe(true)
    expect(isVirginSettings(null)).toBe(true)
    expect(isVirginSettings({ ...empty, passkeyId: 'pk', updatedAt: 9999 })).toBe(true)
    expect(isVirginSettings(family)).toBe(false)
    expect(isVirginSettings({ children: [{ id: FIRST_CHILD_ID, name: 'Emma' }] })).toBe(false)
    expect(isVirginSettings({ apiKey: 'KEY' })).toBe(false)
    expect(isVirginSettings({ birthday: '2020-01' })).toBe(false)
    expect(isVirginSettings({ quota: { ...CHILD_DEFAULTS.quota, perDay: 30 } })).toBe(false)
  })
})

describe('shouldAdoptRemoteSettings / applyRemoteSettings', () => {
  it('a new phone adopts the account even when its empty defaults look newer', () => {
    const local = { ...empty, updatedAt: 9000, passkeyId: 'phone-b' }
    const remote = { data: family, updatedAt: 1000 }
    expect(shouldAdoptRemoteSettings(local, remote)).toBe(true)
    const adopted = applyRemoteSettings(local, remote)
    expect(adopted.children[0].name).toBe('Emma')
    expect(adopted.children[0].customChannels).toHaveLength(1)
    expect(adopted.passkeyId).toBe('phone-b') // this device's authenticator
    expect(adopted.updatedAt).toBe(1000) // adopting is not an edit
  })

  it('never adopts a wiped remote over a phone that still has the family', () => {
    const local = { ...normalizeSettings(family), updatedAt: 1000 }
    const remote = { data: empty, updatedAt: 9999 }
    expect(shouldAdoptRemoteSettings(local, remote)).toBe(false)
    expect(applyRemoteSettings(local, remote)).toBe(null)
  })

  it('two family blobs still last-write-wins', () => {
    const local = { ...normalizeSettings(family), updatedAt: 2000 }
    const newer = { data: { ...family, children: [{ ...family.children[0], name: 'Em' }] }, updatedAt: 3000 }
    const older = { data: { ...family, children: [{ ...family.children[0], name: 'Em' }] }, updatedAt: 1000 }
    expect(shouldAdoptRemoteSettings(local, newer)).toBe(true)
    expect(shouldAdoptRemoteSettings(local, older)).toBe(false)
  })
})

describe('shouldPushSettings / settingsPushPayload', () => {
  it('will not send factory defaults over an account that already has a family', () => {
    const local = { ...empty, updatedAt: 9000 }
    expect(shouldPushSettings(local, { data: family }, 0)).toBe(false)
    expect(shouldPushSettings(local, null, 0)).toBe(true) // first device may seed
    expect(shouldPushSettings({ ...normalizeSettings(family), updatedAt: 2000 }, { data: empty }, 0)).toBe(true)
  })

  it('does not push when the stamp has not moved past the last push', () => {
    expect(shouldPushSettings({ ...empty, updatedAt: 100 }, null, 100)).toBe(false)
    expect(shouldPushSettings({ ...empty, updatedAt: 100 }, null, 50)).toBe(true)
  })

  it('strips the device-local passkey from what goes to the Worker', () => {
    const local = { ...normalizeSettings(family), passkeyId: 'phone-b', updatedAt: 5 }
    const payload = settingsPushPayload(local)
    expect(payload.updatedAt).toBe(5)
    expect(payload.data.passkeyId).toBeUndefined()
    expect(payload.data.children[0].name).toBe('Emma')
    expect('passkeyId' in payload.data).toBe(false)
  })
})
