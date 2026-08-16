/** The dual age slider's arithmetic. Stops are any | 1 … 14 | any.
 *
 * The bug grabEnd pins: with two stacked native range inputs only the THUMBS
 * take pointer events, so once lo and hi met, the upper thumb covered the
 * lower one and that end could never be dragged again. The surface over them
 * asks grabEnd which end to move. */

import {
  ageAtFraction,
  grabEnd,
  AGE_MIN,
  AGE_MAX,
  AGE_LAST,
  ageIndex,
  ageFromIndex,
  ageLabel,
  ageRangeLabel,
  clampAgeRange,
  decisionOnly,
  overlaps,
  boundAge,
} from '../src/lib.js'

describe('ageAtFraction', () => {
  it('maps the track onto any|1…14|any and clamps past both ends', () => {
    expect(ageAtFraction(0)).toBe(0)
    expect(ageAtFraction(1)).toBe(AGE_LAST)
    expect(ageAtFraction(0.5)).toBe(8)
    expect(ageAtFraction(-0.4)).toBe(0)
    expect(ageAtFraction(3)).toBe(AGE_LAST)
  })
})

describe('ageIndex / ageFromIndex', () => {
  it('stores null at both anys, and 1–14 in between', () => {
    expect(ageFromIndex(0)).toBe(null)
    expect(ageFromIndex(1)).toBe(1)
    expect(ageFromIndex(14)).toBe(14)
    expect(ageFromIndex(AGE_LAST)).toBe(null)
    expect(ageIndex(null, 'lo')).toBe(0)
    expect(ageIndex(null, 'hi')).toBe(AGE_LAST)
    expect(ageIndex(7, 'lo')).toBe(7)
    expect(ageIndex(15, 'hi')).toBe(AGE_LAST) // old ceiling
    expect(ageLabel(null)).toBe('any')
    expect(ageLabel(4)).toBe('4')
  })
})

describe('ageRangeLabel', () => {
  it('collapses any–any to any, and keeps a real bound as a pair', () => {
    expect(ageRangeLabel(null, null)).toBe('any')
    expect(ageRangeLabel(3, 8)).toBe('3–8')
    expect(ageRangeLabel(5, null)).toBe('5–any')
    expect(ageRangeLabel(null, 10)).toBe('any–10')
    expect(ageRangeLabel(7, 7)).toBe('7–7')
  })
})

describe('clampAgeRange', () => {
  it('lets the ends meet on a finite age', () => {
    expect(clampAgeRange([3, 11], 'lo', 11)).toEqual([11, 11])
    expect(clampAgeRange([3, 11], 'hi', 3)).toEqual([3, 3])
  })

  it('will not stack both thumbs on an any', () => {
    // any–any is [0, 15]; lo cannot join the right any, hi cannot join the left
    expect(clampAgeRange([0, AGE_LAST], 'lo', 99)).toEqual([AGE_MAX, AGE_LAST])
    expect(clampAgeRange([0, AGE_LAST], 'hi', -3)).toEqual([0, AGE_MIN])
  })

  it('moves the end asked for and leaves the other alone', () => {
    expect(clampAgeRange([0, AGE_LAST], 'lo', 4)).toEqual([4, AGE_LAST])
    expect(clampAgeRange([0, AGE_LAST], 'hi', 9)).toEqual([0, 9])
  })
})

describe('decisionOnly migrates the old 1–15 scale', () => {
  const UC = 'UC' + 'a'.repeat(22)

  it('turns the old “everything” default into any–any', () => {
    expect(decisionOnly({ channel_id: UC, min_age: 1, max_age: 15 })).toEqual({
      channel_id: UC,
      min_age: null,
      max_age: null,
    })
  })

  it('turns a leftover 15 ceiling into any, and keeps a real floor', () => {
    expect(decisionOnly({ channel_id: UC, min_age: 5, max_age: 15 })).toEqual({
      channel_id: UC,
      min_age: 5,
      max_age: null,
    })
  })

  it('keeps 1–14 as itself, and a missing pair as any–any', () => {
    expect(decisionOnly({ channel_id: UC, min_age: 1, max_age: 14 })).toEqual({
      channel_id: UC,
      min_age: 1,
      max_age: 14,
    })
    expect(decisionOnly({ channel_id: UC })).toEqual({
      channel_id: UC,
      min_age: null,
      max_age: null,
    })
  })

  it('treats garbage as any', () => {
    expect(boundAge(99)).toBe(null)
    expect(boundAge(-3)).toBe(null)
    expect(boundAge(15)).toBe(null)
    expect(boundAge(7)).toBe(7)
  })
})

describe('overlaps treats null as unbounded', () => {
  it('any–any matches every child', () => {
    expect(overlaps([4, 4], null, null)).toBe(true)
    expect(overlaps([15, 15], null, null)).toBe(true)
    expect(overlaps([1, 1], null, null)).toBe(true)
  })

  it('a 15-year-old misses 1–14 and matches a ceiling of any', () => {
    expect(overlaps([15, 15], 1, 14)).toBe(false)
    expect(overlaps([15, 15], 5, null)).toBe(true)
    expect(overlaps([4, 4], 5, null)).toBe(false)
  })
})

describe('grabEnd', () => {
  it('drags the nearer end', () => {
    expect(grabEnd(4, 3, 11)).toBe('lo')
    expect(grabEnd(10, 3, 11)).toBe('hi')
    expect(grabEnd(7, 3, 11)).toBe('lo') // exact tie goes to lo
  })

  it('pushes the end a press outside the pair belongs to', () => {
    expect(grabEnd(1, 5, 9)).toBe('lo')
    expect(grabEnd(15, 5, 9)).toBe('hi')
  })

  it('refuses to guess when both ends sit on the pressed value', () => {
    // THE BUG: choosing an end here is how one gets stuck — pick lo, drag
    // right, and every value clamps against hi, which looks like a dead
    // slider. 'pending' waits for the first move to say which end it is.
    expect(grabEnd(6, 6, 6)).toBe('pending')
    // pressing away from a merged pair is unambiguous, so it is answered
    expect(grabEnd(2, 6, 6)).toBe('lo')
    expect(grabEnd(9, 6, 6)).toBe('hi')
  })

  it('a merged pair at either extreme still parts', () => {
    expect(grabEnd(0, 0, 0)).toBe('pending')
    expect(grabEnd(AGE_LAST, 0, 0)).toBe('hi')
    expect(grabEnd(0, AGE_LAST, AGE_LAST)).toBe('lo')
  })
})
