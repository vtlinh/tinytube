/** The dual age slider's arithmetic. The bug this pins: with two stacked
 * native range inputs only the THUMBS take pointer events, so once lo and hi
 * met, the upper thumb covered the lower one and that end could never be
 * dragged again. The surface over them asks grabEnd which end to move. */

import { ageAtFraction, grabEnd, AGE_MIN, AGE_MAX } from '../src/lib.js'

describe('ageAtFraction', () => {
  it('maps the track onto the 1-15 scale and clamps past both ends', () => {
    expect(ageAtFraction(0)).toBe(AGE_MIN)
    expect(ageAtFraction(1)).toBe(AGE_MAX)
    expect(ageAtFraction(0.5)).toBe(8)
    expect(ageAtFraction(-0.4)).toBe(AGE_MIN) // dragged off the left edge
    expect(ageAtFraction(3)).toBe(AGE_MAX) // ...and off the right
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
    expect(grabEnd(AGE_MIN, AGE_MIN, AGE_MIN)).toBe('pending')
    expect(grabEnd(AGE_MAX, AGE_MIN, AGE_MIN)).toBe('hi')
    expect(grabEnd(AGE_MIN, AGE_MAX, AGE_MAX)).toBe('lo')
  })
})
