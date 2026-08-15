/** Birthday -> age: the parent enters mm/yy, the child is born on the 1st of
 * that month by declaration, and the age filter keeps up on its own. */

import { ageFromBirthday, effectiveAgeRange, parseBirthdayInput } from '../src/lib.js'

// a fixed "today": 2026-08-09
const NOW = new Date(2026, 7, 9).getTime()

describe('ageFromBirthday', () => {
  it('counts whole years from the 1st of the birth month', () => {
    expect(ageFromBirthday('2022-08', NOW)).toBe(4) // birthday month, day >= 1: just turned 4
    expect(ageFromBirthday('2022-09', NOW)).toBe(3) // next month: still 3
    expect(ageFromBirthday('2026-08', NOW)).toBe(0)
  })

  it('refuses garbage and the future', () => {
    expect(ageFromBirthday('2027-01', NOW)).toBe(null)
    expect(ageFromBirthday('08/22', NOW)).toBe(null)
    expect(ageFromBirthday('2022-13', NOW)).toBe(null)
    expect(ageFromBirthday(null, NOW)).toBe(null)
  })
})

describe('effectiveAgeRange', () => {
  it('uses the computed age as a single-point range, floored at 1 but not capped', () => {
    expect(effectiveAgeRange({ birthday: '2022-08', ageRange: [1, 15] }, NOW)).toEqual([4, 4])
    expect(effectiveAgeRange({ birthday: '2026-08', ageRange: [1, 15] }, NOW)).toEqual([1, 1]) // a baby is 1 on the scale
    expect(effectiveAgeRange({ birthday: '2005-01', ageRange: [1, 15] }, NOW)).toEqual([21, 21])
  })

  it('falls back to the manual range without a birthday', () => {
    expect(effectiveAgeRange({ birthday: null, ageRange: [3, 7] }, NOW)).toEqual([3, 7])
    expect(effectiveAgeRange({ ageRange: [3, 7] }, NOW)).toEqual([3, 7])
  })
})

describe('parseBirthdayInput', () => {
  it("mm/yy -> 'YYYY-MM', this century", () => {
    expect(parseBirthdayInput('08/22')).toBe('2022-08')
    expect(parseBirthdayInput('8/22')).toBe('2022-08')
    expect(parseBirthdayInput(' 12/19 ')).toBe('2019-12')
  })

  it('refuses non-months and non-dates', () => {
    expect(parseBirthdayInput('13/22')).toBe(null)
    expect(parseBirthdayInput('0/22')).toBe(null)
    expect(parseBirthdayInput('2022-08')).toBe(null)
    expect(parseBirthdayInput('')).toBe(null)
  })
})
