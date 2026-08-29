/** Who may change the parent's controls. Two states lock them — a child
 * signed in with their own account, and nobody signed in at all — and the way
 * out of both is the same sign-in control, which is why it has to stay live
 * inside a locked screen. */

import { render, screen, fireEvent, renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import Settings from '../src/settings.jsx'
import { useSettings, useWatchStore, settingsLock, matchingChild, normEmail, CHILD_DEFAULTS } from '../src/lib.js'

vi.mock('react-youtube', () => ({ default: () => null }))

function fakeStorage() {
  let store = {}
  return {
    getItem: k => store[k] ?? null,
    setItem: (k, v) => { store[k] = String(v) },
    removeItem: k => { delete store[k] },
    clear: () => { store = {} },
  }
}

const CLIENT = 'client-id'

beforeEach(() => {
  vi.stubGlobal('localStorage', fakeStorage())
  vi.stubGlobal('crypto', { ...globalThis.crypto, randomUUID: () => 'uuid-1' })
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({}) })))
})
afterEach(() => vi.unstubAllGlobals())

describe('settingsLock', () => {
  const kids = [
    { ...CHILD_DEFAULTS, id: 'a', name: 'Ann', email: 'ann@example.com' },
    { ...CHILD_DEFAULTS, id: 'b', name: 'Bob', email: null },
  ]

  it('locks for a child’s own account, naming who', () => {
    expect(settingsLock(kids, { email: 'ann@example.com' }, CLIENT)).toMatchObject({ kind: 'child', name: 'Ann' })
    expect(matchingChild(kids, { email: 'ann@example.com' })).toMatchObject({ id: 'a', name: 'Ann' })
    expect(matchingChild(kids, { email: 'parent@example.com' })).toBe(null)
  })

  it('locks when nobody is signed in — settings anyone can open are not a control', () => {
    expect(settingsLock(kids, null, CLIENT)).toEqual({ kind: 'signed-out' })
    expect(settingsLock(kids, { email: '' }, CLIENT)).toEqual({ kind: 'signed-out' })
  })

  it('opens for a grown-up — any account that is not a child’s', () => {
    expect(settingsLock(kids, { email: 'parent@example.com' }, CLIENT)).toBe(null)
  })

  it('matches an address however it was typed', () => {
    expect(settingsLock(kids, { email: '  Ann@Example.COM ' }, CLIENT)).toMatchObject({ kind: 'child' })
    expect(normEmail('  Bob@Gmail.com ')).toBe('bob@gmail.com')
    expect(normEmail('   ')).toBe(null)
    expect(normEmail(undefined)).toBe(null)
  })

  it('never matches a child who has no email', () => {
    // a blank email must not collide with a blank session
    expect(settingsLock(kids, { email: 'someone@else.com' }, CLIENT)).toBe(null)
    expect(settingsLock([{ ...CHILD_DEFAULTS, id: 'b', name: 'Bob', email: '' }], { email: '' }, CLIENT))
      .toEqual({ kind: 'signed-out' })
  })

  /* A build that CANNOT sign in must not be permanently locked: there would
     be no way back, on any device, ever. */
  it('does not lock at all when signing in is impossible', () => {
    expect(settingsLock(kids, null, '')).toBe(null)
    expect(settingsLock(kids, { email: 'ann@example.com' }, '')).toBe(null)
  })
})

function Harness({ sync }) {
  const store = useSettings()
  const watchStore = useWatchStore(store.settings.childId)
  return <Settings store={store} watchStore={watchStore} sync={sync} onDone={() => {}} />
}

describe('a locked Parents Mode', () => {
  const signedOut = { session: null, pull: vi.fn(), pulling: false }

  it('greys the controls out and says why', () => {
    render(<Harness sync={signedOut} />)
    expect(screen.getByRole('alert').textContent).toMatch(/Sign in with a grown-up/)
    expect(screen.getByLabelText('Name').closest('fieldset').disabled).toBe(true)
  })

  /* The escape hatch: every route out of a lock runs through signing in, so
     that control cannot be inside what the lock disables. */
  it('leaves the sign-in control live', () => {
    render(<Harness sync={signedOut} />)
    fireEvent.click(screen.getByLabelText('More options'))
    const signIn = screen.getByText('Sign in')
    expect(signIn.closest('fieldset')).toBe(null) // outside the disabled block
    expect(signIn.disabled).toBe(false)
    // ...while the things a child could otherwise reach are not
    expect(screen.getByText('Add child').disabled).toBe(true)
    expect(screen.getByText('Export').disabled).toBe(true)
  })

  it('opens up for a grown-up', () => {
    render(<Harness sync={{ session: { email: 'parent@example.com' }, pull: vi.fn(), pulling: false }} />)
    expect(screen.queryByRole('alert')).toBe(null)
    expect(screen.getByLabelText('Name').closest('fieldset').disabled).toBe(false)
  })

  it('locks for a child but still shows their settings', async () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setChildEmail('ann@example.com'))
    act(() => result.current.renameChild('Ann'))
    const store = result.current
    const watchStore = { usage: { window: { start: null, secs: 0 }, days: {}, hours: {} }, watched: {}, remote: null }
    render(<Settings store={store} watchStore={watchStore} sync={{ session: { email: 'ann@example.com' }, pull: vi.fn(), pulling: false }} onDone={() => {}} />)
    expect(screen.getByRole('alert').textContent).toMatch(/Ann/)
    expect(screen.getByLabelText('Name').value).toBe('Ann')
    expect(screen.getByLabelText('Name').closest('fieldset').disabled).toBe(true)
  })
})

describe('the child email field', () => {
  const parent = { session: { email: 'parent@example.com' }, pull: vi.fn(), pulling: false }

  /* The row already names the field. A placeholder on a filled value is what
     iOS paints on top of the name and the address — so there is none. */
  it('does not put a placeholder on the name or the email', () => {
    render(<Harness sync={parent} />)
    expect(screen.getByLabelText('Name').getAttribute('placeholder')).toBe(null)
    expect(screen.getByLabelText('Email').getAttribute('placeholder')).toBe(null)
  })

  it('stores the address folded down, so the lock cannot miss it', () => {
    render(<Harness sync={parent} />)
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: '  Ann@Example.COM ' } })
    expect(JSON.parse(localStorage.getItem('tinytube:settings:v1')).children[0].email).toBe('ann@example.com')
  })

  it('clears back to none', () => {
    render(<Harness sync={parent} />)
    const field = screen.getByLabelText('Email')
    fireEvent.change(field, { target: { value: 'ann@example.com' } })
    fireEvent.change(field, { target: { value: '' } })
    expect(JSON.parse(localStorage.getItem('tinytube:settings:v1')).children[0].email).toBe(null)
  })

  /* The one edit with no way back: give a child the address the parent is
     signed in with and every route out of the resulting lock lands on a lock. */
  it('refuses the address the parent is signed in with', () => {
    render(<Harness sync={parent} />)
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'Parent@example.com' } })
    expect(screen.getByText(/would lock you out/)).toBeTruthy()
    // nothing was stored at all — the refusal is before the write, not after
    const stored = localStorage.getItem('tinytube:settings:v1')
    expect(stored === null || JSON.parse(stored).children[0].email === null).toBe(true)
  })
})
