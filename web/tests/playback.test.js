/** What plays when a video finishes — the web half of the Android app's
 * `Playlist`. The first three blocks are `PlaylistTest.kt` ported case for
 * case: in order STOPS at the end rather than wrapping, random never repeats
 * the video that just played and reaches every other one exactly once, and an
 * unreadable stored mode reads as in order rather than as nothing.
 *
 * After those comes the one place the web app DIVERGES — random preferring
 * videos that have not been watched, which Playlist.kt cannot do because it
 * knows only a count and an index. That block carries its own equivalence
 * test: with nothing known about watching, every roll gives the Kotlin
 * answer, so the divergence is an addition rather than a drift. */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  nextIndex,
  nextVideoIndex,
  playbackMode,
  useSettings,
  normalizeSettings,
  CHILD_DEFAULTS,
  FIRST_CHILD_ID,
  PLAYBACK_IN_ORDER,
  PLAYBACK_RANDOM,
  PLAYBACK_MODES,
} from '../src/lib.js'

const order = PLAYBACK_IN_ORDER
const random = PLAYBACK_RANDOM

/* A roll that is never used, so a test that says IN_ORDER really means it:
   calling this fails rather than quietly returning zero. */
const never = () => {
  throw new Error('should not have rolled')
}

describe('in order', () => {
  it('walks down the list', () => {
    expect(nextIndex(5, 0, order, never)).toBe(1)
    expect(nextIndex(5, 3, order, never)).toBe(4)
  })

  /* Not a loop. A grid that plays forever with no edge is a different product
     from one that plays what a parent approved and stops. */
  it('stops at the end rather than wrapping', () => {
    expect(nextIndex(5, 4, order, never)).toBe(null)
    expect(nextIndex(1, 0, order, never)).toBe(null)
  })
})

describe('random', () => {
  it('never repeats the video that just played', () => {
    for (let current = 0; current < 5; current++) {
      for (let roll = 0; roll < 4; roll++) {
        const next = nextIndex(5, current, random, () => roll)
        expect(next, `rolled ${roll} at ${current}`).not.toBe(current)
        expect(next).toBeGreaterThanOrEqual(0)
        expect(next).toBeLessThan(5)
      }
    }
  })

  /* Every other video is reachable, and each from exactly one roll — so the
     skip-past does not double any of them up or leave one unreachable. */
  it('reaches every other video exactly once', () => {
    const count = 6
    for (let current = 0; current < count; current++) {
      const reached = Array.from({ length: count - 1 }, (_, roll) =>
        nextIndex(count, current, random, () => roll),
      )
      const others = [...Array(count).keys()].filter(i => i !== current)
      expect([...new Set(reached)].sort((a, b) => a - b)).toEqual(others)
      expect(new Set(reached).size).toBe(count - 1)
    }
  })

  /* With one video there is no other one to pick, and replaying it would read
     as a stuck player rather than as randomness. */
  it('stops on a single video', () => {
    expect(nextIndex(1, 0, random, never)).toBe(null)
  })

  /* A roll outside its contract must not index off the end of the list. This
     is the one input that comes from outside the pure code. */
  it('clamps an out-of-range roll rather than trusting it', () => {
    for (const roll of [-5, -1, 4, 99, Number.MAX_SAFE_INTEGER, -Infinity, NaN]) {
      const next = nextIndex(5, 2, random, () => roll)
      expect(next, `roll ${roll}`).not.toBe(null)
      expect(next, `roll ${roll}`).not.toBe(2)
      expect(next).toBeGreaterThanOrEqual(0)
      expect(next).toBeLessThan(5)
    }
  })

  it('uses Math.random when no roll is injected, and still never repeats', () => {
    for (let i = 0; i < 200; i++) {
      const next = nextIndex(4, 2, random)
      expect(next).not.toBe(2)
      expect(next).toBeGreaterThanOrEqual(0)
      expect(next).toBeLessThan(4)
    }
  })
})

/* The one divergence from Playlist.kt, which knows only a count and an index.
   Random over everything meant a child who had seen most of a channel kept
   being handed repeats — the complaint random was supposed to answer. */
describe('random prefers what has not been watched', () => {
  // 0 and 1 seen, 2 and 3 not
  const seen = i => i === 0 || i === 1

  it('never lands on a watched video while an unwatched one is left', () => {
    for (let current = 0; current < 4; current++) {
      for (let roll = 0; roll < 4; roll++) {
        const next = nextIndex(4, current, random, () => roll, seen)
        expect([2, 3].filter(i => i !== current), `at ${current}, roll ${roll}`).toContain(next)
      }
    }
  })

  it('reaches every unwatched video exactly once, and no other', () => {
    const reached = [0, 1].map(roll => nextIndex(4, 0, random, () => roll, seen))
    expect(reached.sort()).toEqual([2, 3])
  })

  /* A preference, not a restriction: running out of new things must not stop
     playback dead. */
  it('falls back to the watched ones when nothing is left unseen', () => {
    const allSeen = () => true
    const reached = [0, 1, 2].map(roll => nextIndex(4, 1, random, () => roll, allSeen))
    expect(reached.sort()).toEqual([0, 2, 3]) // every other video, current excluded
  })

  it('still never repeats the current video, watched or not', () => {
    // current is the ONLY unwatched one: it must not pick itself
    const onlyCurrentUnseen = i => i !== 2
    for (let roll = 0; roll < 4; roll++) {
      expect(nextIndex(4, 2, random, () => roll, onlyCurrentUnseen)).not.toBe(2)
    }
  })

  it('clamps a bad roll against the narrowed pool, not the whole list', () => {
    for (const roll of [-9, 99, NaN, Infinity]) {
      const next = nextIndex(4, 0, random, () => roll, seen)
      expect([2, 3], `roll ${roll}`).toContain(next)
    }
  })

  it('is Playlist.kt exactly when nothing is known about watching', () => {
    // same rolls, same answers as the ported cases above — the skip-past
    // arithmetic and rolling over the other-indices list agree
    for (let current = 0; current < 5; current++) {
      for (let roll = 0; roll < 4; roll++) {
        const kotlin = roll >= current ? roll + 1 : roll
        expect(nextIndex(5, current, random, () => roll)).toBe(kotlin)
      }
    }
  })
})

describe('nextVideoIndex — the app’s call', () => {
  const list = [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }]
  // b is finished, c is 95% done (past the threshold), d is half-watched
  const watched = {
    b: { pos: 10, dur: 10, completed: true },
    c: { pos: 95, dur: 100 },
    d: { pos: 50, dur: 100 },
  }

  it('treats "watched" as the badge does — past 90%, completed included', () => {
    // from a: b and c are seen, so only d is left
    for (let roll = 0; roll < 3; roll++) {
      expect(nextVideoIndex(list, 0, PLAYBACK_RANDOM, watched, () => roll)).toBe(3)
    }
  })

  it('counts a half-watched video as still worth playing', () => {
    // from d: a is untouched, b and c are done — a is the only unseen one
    for (let roll = 0; roll < 3; roll++) {
      expect(nextVideoIndex(list, 3, PLAYBACK_RANDOM, watched, () => roll)).toBe(0)
    }
  })

  it('leaves in-order alone — one by one means one by one', () => {
    expect(nextVideoIndex(list, 0, PLAYBACK_IN_ORDER, watched, never)).toBe(1) // b, though seen
    expect(nextVideoIndex(list, 3, PLAYBACK_IN_ORDER, watched, never)).toBe(null)
  })

  it('works with no history at all', () => {
    expect(nextVideoIndex(list, 0, PLAYBACK_RANDOM, {}, () => 0)).toBe(1)
    expect(nextVideoIndex(list, 0, PLAYBACK_RANDOM, undefined, () => 2)).toBe(3)
  })
})

describe('an empty or out-of-range list', () => {
  it('has no next, whatever the mode', () => {
    expect(nextIndex(0, 0, order, never)).toBe(null)
    expect(nextIndex(0, 0, random, never)).toBe(null)
    expect(nextIndex(3, -1, order, never)).toBe(null)
    expect(nextIndex(3, 3, order, never)).toBe(null)
    expect(nextIndex(3, 99, random, never)).toBe(null)
  })
})

describe('the stored mode', () => {
  /* The stored value is a name, and an unknown one has to mean something. In
     order, because that is the behaviour a parent who has never opened
     settings would expect from a list. */
  it('reads as in order when it is unreadable', () => {
    for (const bad of [null, undefined, '', 'SHUFFLE', 'in_order', 0, {}]) {
      expect(playbackMode(bad)).toBe(PLAYBACK_IN_ORDER)
    }
    // and an unreadable mode PLAYS in order rather than falling through
    expect(nextIndex(3, 0, 'SHUFFLE', never)).toBe(1)
  })

  it('round-trips by name', () => {
    for (const mode of PLAYBACK_MODES) expect(playbackMode(mode)).toBe(mode)
  })

  it('is repaired on load, so a hand-edited blob cannot store a mode that isn’t one', () => {
    expect(normalizeSettings({ playback: 'SHUFFLE' }).children[0].playback).toBe(PLAYBACK_IN_ORDER)
    expect(normalizeSettings({ children: [{ id: 'a', playback: 'nonsense' }] }).children[0].playback)
      .toBe(PLAYBACK_IN_ORDER)
    expect(normalizeSettings({ playback: PLAYBACK_RANDOM }).children[0].playback).toBe(PLAYBACK_RANDOM)
  })
})

/* Both settings belong to the CHILD, not the household: one child watching a
   channel end to end and another shuffling it are not the same child. */
describe('the two parent settings', () => {
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
    vi.stubGlobal('crypto', { ...globalThis.crypto, randomUUID: () => `uuid-${Math.random().toString(36).slice(2)}` })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('start out sinking watched videos and playing in order', () => {
    expect(CHILD_DEFAULTS.hideWatched).toBe(false)
    expect(CHILD_DEFAULTS.playback).toBe(PLAYBACK_IN_ORDER)
  })

  it('are per child, and each child keeps their own', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setHideWatched(true))
    act(() => result.current.setPlayback(PLAYBACK_RANDOM))
    expect(result.current.settings.hideWatched).toBe(true)
    expect(result.current.settings.playback).toBe(PLAYBACK_RANDOM)

    act(() => result.current.addChild('Bob'))
    expect(result.current.settings.hideWatched).toBe(false) // Bob starts fresh
    expect(result.current.settings.playback).toBe(PLAYBACK_IN_ORDER)

    act(() => result.current.switchChild(FIRST_CHILD_ID))
    expect(result.current.settings.hideWatched).toBe(true)
    expect(result.current.settings.playback).toBe(PLAYBACK_RANDOM)
  })

  it('store a mode that is one, whatever the caller passes', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setPlayback('SHUFFLE'))
    expect(result.current.settings.playback).toBe(PLAYBACK_IN_ORDER)
    act(() => result.current.setHideWatched('yes'))
    expect(result.current.settings.hideWatched).toBe(true) // a boolean, not the string
  })
})
