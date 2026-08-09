/** The video-length RANGE and the week-long bonus grant. */

import { renderHook, act } from '@testing-library/react'
import {
  LENGTH_STOPS,
  LENGTH_MAX_MINS,
  lengthIndex,
  lengthLabel,
  clampLengthRange,
  mergeChannels,
  activeBonusMins,
  effectiveQuota,
  quotaState,
  endOfWeek,
  useSettings,
  DEFAULTS,
} from '../src/lib.js'

describe('the length scale', () => {
  it('runs 0 to 2h in quarter hours, then stops filtering entirely', () => {
    expect(LENGTH_STOPS[0]).toBe(0)
    expect(LENGTH_STOPS[1]).toBe(15)
    expect(LENGTH_STOPS.at(-2)).toBe(LENGTH_MAX_MINS)
    expect(LENGTH_STOPS.at(-1)).toBe(Infinity)
    expect(LENGTH_STOPS).toHaveLength(10)
  })

  it('labels both open ends "any"', () => {
    expect(lengthLabel(0)).toBe('any') // no floor
    expect(lengthLabel(Infinity)).toBe('any') // no ceiling
    expect(lengthLabel(null)).toBe('any')
    expect(lengthLabel(15)).toBe('15m')
    expect(lengthLabel(120)).toBe('2h')
  })

  it('maps minutes to stops, and treats a missing ceiling as the last one', () => {
    expect(lengthIndex(0)).toBe(0)
    expect(lengthIndex(30)).toBe(2)
    expect(lengthIndex(null)).toBe(LENGTH_STOPS.length - 1)
    expect(lengthIndex(Infinity)).toBe(LENGTH_STOPS.length - 1)
  })
})

describe('clampLengthRange', () => {
  it('keeps the ends a step apart however hard either is pushed', () => {
    // floor pushed up into the ceiling stops one step short
    expect(clampLengthRange([0, 4], 'lo', 9)).toEqual([3, 4])
    // ceiling pushed down into the floor likewise
    expect(clampLengthRange([4, 8], 'hi', 0)).toEqual([4, 5])
    // and neither can leave the scale
    expect(clampLengthRange([2, 6], 'lo', -3)).toEqual([0, 6])
    expect(clampLengthRange([2, 6], 'hi', 99)).toEqual([2, 9])
  })

  it('moves the end asked for and leaves the other alone', () => {
    expect(clampLengthRange([0, 9], 'lo', 2)).toEqual([2, 9])
    expect(clampLengthRange([0, 9], 'hi', 5)).toEqual([0, 5])
  })
})

describe('filtering the grid by length', () => {
  const db = {
    channels: [
      {
        channel_id: 'UCa',
        channel_title: 'Chan',
        min_age: 1,
        max_age: 15,
        videos: [
          { id: 'short', duration: 5 * 60 },
          { id: 'mid', duration: 30 * 60 },
          { id: 'long', duration: 180 * 60 },
          { id: 'unknown' }, // no duration at all
        ],
      },
    ],
  }
  const ids = settings => mergeChannels(db, {}, { ...DEFAULTS, ...settings })[0].videos.map(v => v.id)

  it('shows everything when both ends say any', () => {
    expect(ids({})).toEqual(['short', 'mid', 'long', 'unknown'])
  })

  it('applies the floor, and an unmeasured video counts as too short', () => {
    expect(ids({ minVideoMins: 15 })).toEqual(['mid', 'long'])
  })

  it('applies the ceiling', () => {
    expect(ids({ maxVideoMins: 60 })).toEqual(['short', 'mid', 'unknown'])
  })

  it('applies both at once', () => {
    expect(ids({ minVideoMins: 15, maxVideoMins: 60 })).toEqual(['mid'])
  })
})

describe('bonus time', () => {
  const now = new Date(2026, 7, 12, 18, 0).getTime() // a Wednesday evening

  const limits = { per6h: null, perDay: 60, perWeek: null, perMonth: null }

  it('counts only while the week it was granted in lasts', () => {
    const granted = { quota: limits, week: { bonusMins: 30, until: endOfWeek(now) } }
    expect(activeBonusMins(granted, now)).toBe(30)
    expect(effectiveQuota(granted, now).perDay).toBe(90)
    // a period with no limit is not given one by a grant
    expect(effectiveQuota(granted, now).perWeek).toBe(null)

    // ...and it stops existing once the week is out
    const nextWeek = endOfWeek(now) + 1000
    expect(activeBonusMins(granted, nextWeek)).toBe(0)
    expect(effectiveQuota(granted, nextWeek).perDay).toBe(60)
  })

  it('is nothing at all when none was granted', () => {
    expect(activeBonusMins({ quota: limits }, now)).toBe(0)
    expect(effectiveQuota({ quota: limits }, now).perDay).toBe(60)
  })

  it("this week's limits replace the standing ones until the week is out", () => {
    const overridden = {
      quota: limits,
      week: { limits: { ...limits, perDay: 15 }, until: endOfWeek(now) },
    }
    expect(effectiveQuota(overridden, now).perDay).toBe(15)
    expect(effectiveQuota(overridden, endOfWeek(now) + 1000).perDay).toBe(60) // back to standing
  })

  it('the tightest limit is the one that stops a child', () => {
    const store = { usage: { window: {}, days: {}, hours: {} }, remote: { days: {}, hours: {} } }
    const settings = { quota: { per6h: 30, perDay: 60, perWeek: null, perMonth: null } }
    const state = quotaState(settings, store, now)
    expect(state.period).toBe('per6h') // 30 minutes is less than 60
    expect(state.secsLeft).toBe(30 * 60)
    expect(state.blocked).toBe(false)
  })

  it('ends the week it was granted in, not seven days later', () => {
    const end = endOfWeek(now)
    expect(new Date(end).getDay()).toBe(0) // a Sunday
    expect(end).toBeGreaterThan(now)
    expect(end - now).toBeLessThan(7 * 86_400_000)
  })
})

describe('granting through the store', () => {
  function fakeStorage() {
    let store = {}
    return {
      getItem: k => store[k] ?? null,
      setItem: (k, v) => { store[k] = String(v) },
      removeItem: k => { delete store[k] },
    }
  }
  beforeEach(() => vi.stubGlobal('localStorage', fakeStorage()))
  afterEach(() => vi.unstubAllGlobals())

  it('stacks within the week and belongs to one child alone', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.addBonusMins(15))
    act(() => result.current.addBonusMins(30))
    expect(activeBonusMins(result.current.settings)).toBe(45)

    act(() => result.current.addChild('Bob'))
    expect(activeBonusMins(result.current.settings)).toBe(0) // Bob got nothing
  })
})

describe('overriding this week from the store', () => {
  function fakeStorage() {
    let store = {}
    return {
      getItem: k => store[k] ?? null,
      setItem: (k, v) => { store[k] = String(v) },
      removeItem: k => { delete store[k] },
    }
  }
  beforeEach(() => vi.stubGlobal('localStorage', fakeStorage()))
  afterEach(() => vi.unstubAllGlobals())

  const limits = { per6h: null, perDay: 60, perWeek: null, perMonth: null }

  it('replaces the limits for the week and leaves the standing ones alone', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setQuota(limits))
    act(() => result.current.setWeekLimits({ ...limits, perDay: 15 }))

    expect(effectiveQuota(result.current.settings).perDay).toBe(15) // in force
    expect(result.current.settings.quota.perDay).toBe(60) // untouched underneath

    act(() => result.current.clearWeekOverride())
    expect(effectiveQuota(result.current.settings).perDay).toBe(60)
  })

  it('keeps a grant when the week is overridden, and the override when time is granted', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setQuota(limits))

    act(() => result.current.addBonusMins(30))
    act(() => result.current.setWeekLimits({ ...limits, perDay: 15 }))
    // the two are one object: setting limits must not drop the granted minutes
    expect(effectiveQuota(result.current.settings).perDay).toBe(45)

    act(() => result.current.addBonusMins(15))
    expect(effectiveQuota(result.current.settings).perDay).toBe(60) // 15 + 45 granted
  })
})
