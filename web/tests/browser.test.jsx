/** The Parents Mode Browser tab: YouTube in a webview, with the same bar the
 *  phone uses (Kids mode, +, approved list, settings). */

import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import Settings from '../src/settings.jsx'
import { useSettings, useWatchStore } from '../src/lib.js'

vi.mock('react-youtube', () => ({ default: () => null }))

const UC = 'UC' + 'a'.repeat(22)

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

function standOn(url) {
  act(() => window.postMessage({ tinytubeUrl: url }, '*'))
}

describe('the Browser tab', () => {
  it('is on the parent-mode bottom bar', () => {
    render(<Harness />)
    expect(screen.getByText('Browser')).toBeTruthy()
    expect(screen.getByText('Settings')).toBeTruthy()
    expect(screen.getByText('Channels')).toBeTruthy()
    expect(screen.getByText('Stats')).toBeTruthy()
  })

  it('opens mobile youtube under the Android parent bar, with + dimmed', () => {
    openBrowser()
    expect(screen.getByText('← Kids mode')).toBeTruthy()
    expect(screen.getByLabelText('Approve this channel').disabled).toBe(true)
    expect(screen.getByLabelText('Approved channels')).toBeTruthy()
    expect(screen.getByLabelText('Settings')).toBeTruthy()
    expect(screen.getByTitle('YouTube').getAttribute('src')).toBe('https://m.youtube.com/')
    expect(screen.queryByLabelText('YouTube address')).toBeNull()
    expect(screen.queryByText('Open YouTube')).toBeNull()
    expect(screen.queryByText('Browser')).toBeNull() // the tab label is gone; this IS the screen
  })

  it('the list and gear open the same screens the phone bar does', () => {
    openBrowser()
    fireEvent.click(screen.getByLabelText('Approved channels'))
    expect(screen.getByText('Add Channel')).toBeTruthy() // Channels tab

    fireEvent.click(screen.getByText('Browser'))
    fireEvent.click(screen.getByLabelText('Settings'))
    expect(screen.getByText('Parents Mode')).toBeTruthy()
  })

  it('enables approve on a channel page, not on a watch page', async () => {
    openBrowser()
    standOn(`https://m.youtube.com/channel/${UC}`)
    await waitFor(() => expect(screen.getByLabelText('Approve this channel').disabled).toBe(false))

    standOn('https://m.youtube.com/watch?v=aaaaaaaaaaa')
    await waitFor(() => expect(screen.getByLabelText('Approve this channel').disabled).toBe(true))
  })

  it('treats a handle page as a channel', async () => {
    openBrowser()
    standOn('https://m.youtube.com/@SomeChannel')
    await waitFor(() => expect(screen.getByLabelText('Approve this channel').disabled).toBe(false))
  })

  it('ignores a site that is not youtube', async () => {
    openBrowser()
    standOn('https://example.com/')
    await waitFor(() => expect(screen.getByLabelText('Approve this channel').disabled).toBe(true))
    expect(screen.queryByText(/Blocked/)).toBeNull()
  })

  it('approves via the Worker, with no API key, then the button becomes remove', async () => {
    openBrowser()
    standOn(`https://m.youtube.com/channel/${UC}`)
    await waitFor(() => expect(screen.getByLabelText('Approve this channel').disabled).toBe(false))
    fireEvent.click(screen.getByLabelText('Approve this channel'))

    expect(await screen.findByText(/Approved “Cocomelon”/)).toBeTruthy()
    const call = fetch.mock.calls.find(([u]) => String(u).includes('/channel'))
    expect(JSON.parse(call[1].body)).toEqual({ url: `https://m.youtube.com/channel/${UC}` })
    expect(screen.getByLabelText('Remove this channel')).toBeTruthy()

    const stored = JSON.parse(localStorage.getItem('tinytube:settings:v1'))
    expect(stored.children[0].customChannels).toEqual([{ channel_id: UC, min_age: 1, max_age: 15 }])
  })

  it('confirms before removing', async () => {
    openBrowser()
    standOn(`https://m.youtube.com/channel/${UC}`)
    await waitFor(() => expect(screen.getByLabelText('Approve this channel').disabled).toBe(false))
    fireEvent.click(screen.getByLabelText('Approve this channel'))
    await screen.findByLabelText('Remove this channel')

    fireEvent.click(screen.getByLabelText('Remove this channel'))
    expect(screen.getByText(/Its videos will stop appearing/)).toBeTruthy()
    fireEvent.click(screen.getByText('Remove'))
    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem('tinytube:settings:v1')).children[0].customChannels).toEqual([])
    })
    expect(screen.getByLabelText('Approve this channel')).toBeTruthy()
  })
})
