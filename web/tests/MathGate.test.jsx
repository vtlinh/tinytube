import { render, screen, fireEvent, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MathGate } from '../src/landing.jsx'

const challenge = { a: 47, b: 78, answer: 125, choices: [115, 125, 126, 124] }
// partial mock: makeChallenge lives in lib.js next to the hooks, which must stay real
vi.mock('../src/lib.js', async importOriginal => ({
  ...(await importOriginal()),
  makeChallenge: () => challenge,
}))

let onPass, onFail, rerender
beforeEach(() => {
  vi.useFakeTimers()
  onPass = vi.fn()
  onFail = vi.fn()
  ;({ rerender } = render(<MathGate onPass={onPass} onFail={onFail} />))
})
afterEach(() => vi.useRealTimers())

describe('MathGate', () => {
  it('shows the question and all four choices', () => {
    expect(screen.getByText('47 + 78 = ?')).toBeTruthy()
    for (const c of challenge.choices) expect(screen.getByText(String(c))).toBeTruthy()
  })

  it('fails when the 5 second countdown runs out', () => {
    act(() => vi.advanceTimersByTime(5000))
    expect(onFail).toHaveBeenCalled()
    expect(onPass).not.toHaveBeenCalled()
  })

  it('fails immediately on a wrong answer', () => {
    fireEvent.click(screen.getByText('126'))
    expect(onFail).toHaveBeenCalledTimes(1)
    expect(onPass).not.toHaveBeenCalled()
  })

  it('passes on the correct answer and the leftover countdown does not fail', () => {
    fireEvent.click(screen.getByText('125'))
    expect(onPass).toHaveBeenCalledTimes(1)
    act(() => vi.advanceTimersByTime(5000))
    expect(onFail).not.toHaveBeenCalled()
  })

  it('fails at most once when onFail is a new function after timeout', () => {
    act(() => vi.advanceTimersByTime(5000))
    expect(onFail).toHaveBeenCalledTimes(1)
    const nextFail = vi.fn()
    rerender(<MathGate onPass={onPass} onFail={nextFail} />)
    expect(onFail).toHaveBeenCalledTimes(1)
    expect(nextFail).not.toHaveBeenCalled()
  })
})
