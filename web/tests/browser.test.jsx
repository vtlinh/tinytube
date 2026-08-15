/** The Parents Mode Browser tab: find a channel by name or URL, through the
 *  Worker, with no parent API key. Same header and bottom tabs as the other
 *  parent screens — YouTube cannot be iframed. */

import { render, screen, fireEvent, waitFor } from '@testing-library/react'
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
      const u = String(url)
      if (u.includes('/search')) {
        const body = JSON.parse(init.body)
        return {
          ok: true,
          json: async () => ({
            channels:
              String(body.query).toLowerCase().includes('coco')
                ? [{ id: UC, title: 'Cocomelon', avatarUrl: 'https://yt3.ggpht.com/a.jpg' }]
                : [],
          }),
        }
      }
      if (u.includes('/channel')) {
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

describe('the Browser tab', () => {
  it('uses the same header and bottom tabs as the other parent screens', () => {
    openBrowser()
    expect(screen.getByRole('heading', { name: 'Browser' })).toBeTruthy()
    expect(screen.getByLabelText('Back to gallery')).toBeTruthy()
    expect(screen.getByLabelText('More options')).toBeTruthy()
    expect(screen.getByText('Settings')).toBeTruthy()
    expect(screen.getByText('Channels')).toBeTruthy()
    expect(screen.getByText('Stats')).toBeTruthy()
    expect(screen.getByLabelText('Find a channel')).toBeTruthy()
    expect(screen.queryByTitle('YouTube')).toBeNull()
    expect(screen.queryByText('← Kids mode')).toBeNull()
  })

  it('searches by name through the Worker, with no API key', async () => {
    openBrowser()
    fireEvent.change(screen.getByLabelText('Find a channel'), { target: { value: 'cocomelon' } })
    expect(await screen.findByText('Cocomelon')).toBeTruthy()
    const call = fetch.mock.calls.find(([u]) => String(u).includes('/search'))
    expect(JSON.parse(call[1].body)).toEqual({ query: 'cocomelon' })
    expect(fetch.mock.calls.some(([u]) => String(u).includes('googleapis'))).toBe(false)
  })

  it('adds a search hit to the approved list', async () => {
    openBrowser()
    fireEvent.change(screen.getByLabelText('Find a channel'), { target: { value: 'cocomelon' } })
    fireEvent.click(await screen.findByText('Add'))
    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem('tinytube:settings:v1')).children[0].customChannels).toEqual([
        { channel_id: UC, min_age: 1, max_age: 15 },
      ])
    })
  })

  it('approves a pasted channel URL via the Worker, with no API key', async () => {
    openBrowser()
    const page = `https://m.youtube.com/channel/${UC}`
    fireEvent.change(screen.getByLabelText('Find a channel'), { target: { value: page } })
    expect(await screen.findByText('Cocomelon')).toBeTruthy()
    const call = fetch.mock.calls.find(([u]) => String(u).includes('/channel'))
    expect(JSON.parse(call[1].body)).toEqual({ url: page })
    fireEvent.click(screen.getByText('Add'))
    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem('tinytube:settings:v1')).children[0].customChannels).toEqual([
        { channel_id: UC, min_age: 1, max_age: 15 },
      ])
    })
  })
})
