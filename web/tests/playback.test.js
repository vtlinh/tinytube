/** What plays when a video finishes — the web half of the Android app's
 * `Playlist`, and this file is `PlaylistTest.kt` ported case for case so the
 * two cannot drift. The rules being pinned: in order STOPS at the end rather
 * than wrapping, random never repeats the video that just played and reaches
 * every other one exactly once, and an unreadable stored mode reads as in
 * order rather than as nothing. */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  nextIndex,
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
