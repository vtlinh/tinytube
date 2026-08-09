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
  endOfDay,
  lastFiniteLimit,
  normalizeQuota,
  useSettings,
  DEFAULTS,
  QUOTA_PERIODS,
  shortDuration,
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

  it('counts only while the DAY it was granted in lasts', () => {
    const granted = { quota: limits, day: { bonusMins: 30, until: endOfDay(now) } }
    expect(activeBonusMins(granted, now)).toBe(30)
    expect(effectiveQuota(granted, now).perDay).toBe(90)
    // a period with no limit is not given one by a grant
    expect(effectiveQuota(granted, now).perWeek).toBe(null)

    // ...and it stops existing at midnight
    const tomorrow = endOfDay(now) + 1000
    expect(activeBonusMins(granted, tomorrow)).toBe(0)
    expect(effectiveQuota(granted, tomorrow).perDay).toBe(60)
  })

  it('is nothing at all when none was granted', () => {
    expect(activeBonusMins({ quota: limits }, now)).toBe(0)
    expect(effectiveQuota({ quota: limits }, now).perDay).toBe(60)
  })

  it("today's limits replace the standing ones until midnight", () => {
    const overridden = {
      quota: limits,
      day: { limits: { ...limits, perDay: 15 }, until: endOfDay(now) },
    }
    expect(effectiveQuota(overridden, now).perDay).toBe(15)
    expect(effectiveQuota(overridden, endOfDay(now) + 1000).perDay).toBe(60) // back to standing
  })

  it('the tightest limit is the one that stops a child', () => {
    const store = { usage: { window: {}, days: {}, hours: {} }, remote: { days: {}, hours: {} } }
    const settings = { quota: { per6h: 30, perDay: 60, perWeek: null, perMonth: null } }
    const state = quotaState(settings, store, now)
    expect(state.period).toBe('per6h') // 30 minutes is less than 60
    expect(state.secsLeft).toBe(30 * 60)
    expect(state.blocked).toBe(false)
  })

  it('ends at the next midnight, not 24 hours later', () => {
    const end = endOfDay(now)
    expect(new Date(end).getHours()).toBe(0)
    expect(end).toBeGreaterThan(now)
    expect(end - now).toBeLessThan(86_400_000)
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

describe('overriding today from the store', () => {
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
    act(() => result.current.setDayLimits({ ...limits, perDay: 15 }))

    expect(effectiveQuota(result.current.settings).perDay).toBe(15) // in force
    expect(result.current.settings.quota.perDay).toBe(60) // untouched underneath

    act(() => result.current.clearDayOverride())
    expect(effectiveQuota(result.current.settings).perDay).toBe(60)
  })

  it('keeps a grant when today is overridden, and the override when time is granted', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setQuota(limits))

    act(() => result.current.addBonusMins(30))
    act(() => result.current.setDayLimits({ ...limits, perDay: 15 }))
    // the two are one object: setting limits must not drop the granted minutes
    expect(effectiveQuota(result.current.settings).perDay).toBe(45)

    act(() => result.current.addBonusMins(15))
    expect(effectiveQuota(result.current.settings).perDay).toBe(60) // 15 + 45 granted
  })
})

describe('the day override and the longer limits', () => {
  function fakeStorage() {
    let store = {}
    return { getItem: k => store[k] ?? null, setItem: (k, v) => { store[k] = String(v) }, removeItem: k => { delete store[k] } }
  }
  beforeEach(() => vi.stubGlobal('localStorage', fakeStorage()))
  afterEach(() => vi.unstubAllGlobals())

  it('carries the standing weekly and monthly caps through a day that does not mention them', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setQuota({ per6h: null, perDay: 60, perWeek: null, perMonth: 600 }))
    // today's dialog offers neither week nor month, so it saves two periods
    act(() => result.current.setDayLimits({ per6h: null, perDay: 15 }))

    const inForce = effectiveQuota(result.current.settings)
    expect(inForce.perDay).toBe(15) // overridden
    expect(inForce.perMonth).toBe(600) // NOT dropped: an override may not remove a cap
  })
})

describe('each quota period has its own scale', () => {
  it('runs to the whole window it governs, in a step worth dragging', () => {
    const by = Object.fromEntries(QUOTA_PERIODS.map(p => [p.key, p]))
    expect(by.per6h).toMatchObject({ maxMins: 360, stepMins: 15 })
    expect(by.perDay).toMatchObject({ maxMins: 1440, stepMins: 60 })
    expect(by.perWeek).toMatchObject({ maxMins: 10080, stepMins: 240 })
    expect(by.perMonth).toMatchObject({ maxMins: 44640, stepMins: 1440 }) // 31 days
  })

  it('every scale divides into whole stops, so no limit is unreachable', () => {
    for (const { key, maxMins, stepMins } of QUOTA_PERIODS) {
      expect(maxMins % stepMins, `${key} does not divide evenly`).toBe(0)
      // and the slider stays a draggable length rather than a thousand steps
      expect(maxMins / stepMins).toBeLessThanOrEqual(48)
    }
  })

  it('the default daily limit lands exactly on a stop', () => {
    const day = QUOTA_PERIODS.find(p => p.key === 'perDay')
    expect(DEFAULTS.quota.perDay % day.stepMins).toBe(0)
  })
})

describe('shortDuration', () => {
  it('says one thing, never two', () => {
    expect(shortDuration(45)).toBe('45') // under an hour: bare minutes
    expect(shortDuration(180)).toBe('3h')
    expect(shortDuration(1440)).toBe('1d')
    expect(shortDuration(44640)).toBe('31d') // a month, not 44640
    expect(shortDuration(90)).toBe('90') // neither whole hours nor days
    expect(shortDuration(null)).toBe('∞')
  })
})

describe('the top of every scale is “no limit”, not a number', () => {
  it('the largest settable limit is one step below the period itself', () => {
    // a cap of six hours inside a rolling six hours forbids nothing, so it was
    // "no limit" wearing a number — the scale stops one step short of it
    const by = Object.fromEntries(QUOTA_PERIODS.map(p => [p.key, p]))
    expect(lastFiniteLimit(by.per6h)).toBe(345) // 5h45m, not 6h
    expect(lastFiniteLimit(by.perDay)).toBe(23 * 60)
    expect(lastFiniteLimit(by.perWeek)).toBe(7 * 24 * 60 - 240)
    expect(lastFiniteLimit(by.perMonth)).toBe(30 * 24 * 60)
  })

  it('a stored limit that reaches its period is kept as no limit', () => {
    const stored = { per6h: 360, perDay: 1440, perWeek: 5000, perMonth: null }
    expect(normalizeQuota(stored)).toEqual({
      per6h: null, // 6h in 6h forbids nothing
      perDay: null, // ...and a day in a day
      perWeek: 5000, // a real cap survives untouched
      perMonth: null,
    })
  })
})
