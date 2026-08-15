/** The Refresh button in Parents Mode. Everything syncs on its own, so this
 * exists for the moment a parent has just changed something on the other
 * device and wants to SEE it here rather than trust that it will arrive. */

import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import Settings from '../src/settings.jsx'
import { useSettings, useWatchStore } from '../src/lib.js'

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

function Harness({ sync }) {
  const store = useSettings()
  const watchStore = useWatchStore(store.settings.childId)
  return <Settings store={store} watchStore={watchStore} sync={sync} onDone={() => {}} />
}

beforeEach(() => {
  vi.stubGlobal('localStorage', fakeStorage())
  vi.stubGlobal('crypto', { ...globalThis.crypto, randomUUID: () => 'uuid-1' })
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({}) })))
})
afterEach(() => vi.unstubAllGlobals())

describe('the Refresh button', () => {
  it('forces a pull past the throttle', async () => {
    const pull = vi.fn(async () => ({}))
    render(<Harness sync={{ session: { email: 'a@b.c' }, pull, pulling: false }} />)
    fireEvent.click(screen.getByLabelText('Refresh from sync'))
    // forced: the whole point is that waiting fifteen minutes is not an answer
    await waitFor(() => expect(pull).toHaveBeenCalledWith({ force: true }))
  })

  /* Sync is inert with nobody signed in, and a button that cannot do anything
     is worse than no button. */
  it('is absent when nobody is signed in', () => {
    render(<Harness sync={{ session: null, pull: vi.fn(), pulling: false }} />)
    expect(screen.queryByLabelText('Refresh from sync')).toBeNull()
  })

  it('is disabled while a pull is in flight', () => {
    render(<Harness sync={{ session: { email: 'a@b.c' }, pull: vi.fn(), pulling: true }} />)
    expect(screen.getByLabelText('Refresh from sync').disabled).toBe(true)
  })

  /* A pull that changed nothing looks identical to one that never happened,
     so the button says it did something. */
  it('acknowledges a finished pull', async () => {
    const pull = vi.fn(async () => ({}))
    const { container } = render(<Harness sync={{ session: { email: 'a@b.c' }, pull, pulling: false }} />)
    fireEvent.click(screen.getByLabelText('Refresh from sync'))
    await waitFor(() => expect(container.querySelector('.fa-check')).not.toBe(null))
  })
})
