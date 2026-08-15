/** The Parents Mode Browser tab: YouTube in a webview, with the same +/- the
 *  phone uses to approve the channel on the page. */

import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import Settings from '../src/settings.jsx'
import { useSettings, useWatchStore } from '../src/lib.js'

vi.mock('react-youtube', () => ({ default: () => null }))

const UC = 'UC' + 'a'.repeat(22)
const db = { schema_version: 2, generated_at: 'x', channels: [] }

function fakeStorage() {
  let store = {}
  return {
    getItem: k => store[k] ?? null,
    setItem: (k, v) => {
      store[k] = String(v)
    },
    removeItem: k => {
      delete store[k]
    },
    clear: () => {
      store = {}
    },
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', fakeStorage())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url, init) => {
      if (String(url).includes('/channel')) {
        const body = JSON.parse(init.body)
        return {
          ok: true,
          json: async () => ({
            id: UC,
            title: 'Cocomelon',
            avatarUrl: 'https://yt3.ggpht.com/a.jpg',
            videos: [{ id: 'dQw4w9WgXcQ', title: 'Vid' }],
            requested: body.url,
          }),
        }
      }
      return { ok: true, json: async () => ({}) }
    }),
  )
})
afterEach(() => vi.unstubAllGlobals())

function Harness() {
  const store = useSettings()
  const watchStore = useWatchStore(store.settings.childId)
  return (
    <Settings
      db={db}
      store={store}
      watchStore={watchStore}
      sync={{ session: { email: 'parent@example.com' }, pull: vi.fn(), pulling: false }}
      onDone={() => {}}
    />
  )
}

function openBrowser() {
  render(<Harness />)
  fireEvent.click(screen.getByText('Browser'))
}

describe('the Browser tab', () => {
  it('is on the parent-mode bottom bar', () => {
    render(<Harness />)
    expect(screen.getByText('Browser')).toBeTruthy()
    expect(screen.getByText('Settings')).toBeTruthy()
    expect(screen.getByText('Channels')).toBeTruthy()
    expect(screen.getByText('Stats')).toBeTruthy()
  })

  it('opens on mobile youtube with the approve button dimmed', () => {
    openBrowser()
    expect(screen.getByLabelText('YouTube address').value).toBe('https://m.youtube.com/')
    expect(screen.getByLabelText('Approve this channel').disabled).toBe(true)
    expect(screen.getByText('Open YouTube')).toBeTruthy()
  })

  it('opens youtube in a named window, so a second tap reuses it', () => {
    openBrowser()
    const link = screen.getByRole('link', { name: /Open YouTube/ })
    expect(link.getAttribute('href')).toBe('https://m.youtube.com/')
    expect(link.getAttribute('target')).toBe('tinytube-yt')
  })

  it('enables approve on a channel page, not on a watch page', () => {
    openBrowser()
    const address = screen.getByLabelText('YouTube address')
    fireEvent.change(address, { target: { value: `https://m.youtube.com/channel/${UC}` } })
    fireEvent.click(screen.getByText('Go'))
    expect(screen.getByLabelText('Approve this channel').disabled).toBe(false)

    fireEvent.change(address, { target: { value: 'https://m.youtube.com/watch?v=aaaaaaaaaaa' } })
    fireEvent.click(screen.getByText('Go'))
    expect(screen.getByLabelText('Approve this channel').disabled).toBe(true)
  })

  it('turns a bare handle into a channel page', () => {
    openBrowser()
    fireEvent.change(screen.getByLabelText('YouTube address'), { target: { value: '@SomeChannel' } })
    fireEvent.click(screen.getByText('Go'))
    expect(screen.getByLabelText('YouTube address').value).toBe('https://m.youtube.com/@SomeChannel')
    expect(screen.getByLabelText('Approve this channel').disabled).toBe(false)
    expect(screen.getByRole('link', { name: /Open YouTube/ }).getAttribute('href')).toBe(
      'https://m.youtube.com/@SomeChannel',
    )
  })

  it('refuses a site that is not youtube', () => {
    openBrowser()
    fireEvent.change(screen.getByLabelText('YouTube address'), { target: { value: 'https://example.com/' } })
    fireEvent.click(screen.getByText('Go'))
    expect(screen.getByText(/Blocked: example.com/)).toBeTruthy()
    expect(screen.getByLabelText('YouTube address').value).toBe('https://example.com/')
    // still standing on youtube — we did not navigate
    expect(screen.getByLabelText('Approve this channel').disabled).toBe(true)
  })

  it('approves via the Worker, with no API key, then the button becomes remove', async () => {
    openBrowser()
    fireEvent.change(screen.getByLabelText('YouTube address'), {
      target: { value: `https://m.youtube.com/channel/${UC}` },
    })
    fireEvent.click(screen.getByText('Go'))
    fireEvent.click(screen.getByLabelText('Approve this channel'))

    expect(await screen.findByText(/Approved “Cocomelon”/)).toBeTruthy()
    const call = fetch.mock.calls.find(([u]) => String(u).includes('/channel'))
    expect(JSON.parse(call[1].body)).toEqual({ url: `https://m.youtube.com/channel/${UC}` })
    expect(screen.getByLabelText('Remove channel')).toBeTruthy()

    const stored = JSON.parse(localStorage.getItem('tinytube:settings:v1'))
    expect(stored.children[0].customChannels).toEqual([{ channel_id: UC, min_age: 1, max_age: 15 }])
  })

  it('confirms before removing', async () => {
    openBrowser()
    fireEvent.change(screen.getByLabelText('YouTube address'), {
      target: { value: `https://m.youtube.com/channel/${UC}` },
    })
    fireEvent.click(screen.getByText('Go'))
    fireEvent.click(screen.getByLabelText('Approve this channel'))
    await screen.findByLabelText('Remove channel')

    fireEvent.click(screen.getByLabelText('Remove channel'))
    expect(screen.getByText(/Its videos will stop appearing/)).toBeTruthy()
    fireEvent.click(screen.getByText('Remove'))
    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem('tinytube:settings:v1')).children[0].customChannels).toEqual([])
    })
    expect(screen.getByLabelText('Approve this channel')).toBeTruthy()
  })
})
