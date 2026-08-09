/** One account, several children. The rules worth pinning: a pre-children
 * blob becomes exactly one child at the FIXED id the Worker migrates its rows
 * under, the flattened view is what every reader sees, and one child's
 * settings and history never leak into another's. */

import { renderHook, act } from '@testing-library/react'
import {
  normalizeSettings,
  childView,
  activeChild,
  useSettings,
  useWatchStore,
  watchKey,
  CHILD_DEFAULTS,
  FIRST_CHILD_ID,
  exportChannels,
} from '../src/lib.js'

function fakeStorage() {
  let store = {}
  return {
    getItem: k => store[k] ?? null,
    setItem: (k, v) => { store[k] = String(v) },
    removeItem: k => { delete store[k] },
    clear: () => { store = {} },
    _dump: () => store,
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', fakeStorage())
  vi.stubGlobal('crypto', { ...globalThis.crypto, randomUUID: () => `uuid-${Math.random().toString(36).slice(2)}` })
})
afterEach(() => vi.unstubAllGlobals())

describe('normalizeSettings', () => {
  it('folds a pre-children blob into one child at the fixed migration id', () => {
    const s = normalizeSettings({
      apiKey: 'KEY',
      passkeyId: 'pk',
      birthday: '2022-08',
      quotaMins: 60, // the pre-periods single number
      customChannels: [{ channel_id: 'UCa' }],
      groups: [{ id: 'g1', name: 'Cartoons' }],
      groupOf: { UCa: 'g1' },
    })
    expect(s.children).toHaveLength(1)
    // FIXED, not random: the Worker copies existing synced rows under this id
    expect(s.children[0].id).toBe(FIRST_CHILD_ID)
    // the one 12h number lands on the daily limit, the closest thing it meant
    expect(s.children[0]).toMatchObject({ birthday: '2022-08', groupOf: { UCa: 'g1' } })
    expect(s.children[0].quota.perDay).toBe(60)
    expect(s.activeChildId).toBe(FIRST_CHILD_ID)
    // account-level fields stay account-level
    expect(s.apiKey).toBe('KEY')
    expect(s.passkeyId).toBe('pk')
    expect(s.children[0].apiKey).toBeUndefined()
  })

  it('still folds the legacy hiddenChannels/ageOverrides fields into the child', () => {
    const s = normalizeSettings({ hiddenChannels: ['UCx'], ageOverrides: { UCy: { min_age: 3 } } })
    expect(s.children[0].overrides).toEqual({ UCx: { hidden: true }, UCy: { min_age: 3 } })
  })

  it('is idempotent and repairs a dangling activeChildId', () => {
    const once = normalizeSettings({ birthday: '2020-01' })
    expect(normalizeSettings(once)).toEqual(once)
    const dangling = normalizeSettings({ ...once, activeChildId: 'gone' })
    expect(dangling.activeChildId).toBe(once.children[0].id)
  })

  it('gives a fresh install one child with the defaults', () => {
    const s = normalizeSettings({})
    expect(s.children).toEqual([{ ...CHILD_DEFAULTS, id: FIRST_CHILD_ID, name: 'Child 1' }])
  })
})

describe('childView', () => {
  it('flattens account + active child, keeping the child id/name clear of settings keys', () => {
    const stored = normalizeSettings({
      apiKey: 'KEY',
      children: [
        { id: 'a', name: 'Ann', ...CHILD_DEFAULTS, quota: { ...CHILD_DEFAULTS.quota, perDay: 30 } },
        { id: 'b', name: 'Bob', ...CHILD_DEFAULTS, quota: { ...CHILD_DEFAULTS.quota, perDay: 90 } },
      ],
      activeChildId: 'b',
    })
    const view = childView(stored)
    expect(view.quota.perDay).toBe(90)
    expect(view.childId).toBe('b')
    expect(view.childName).toBe('Bob')
    expect(view.apiKey).toBe('KEY')
    expect(activeChild(stored).name).toBe('Bob')
  })
})

describe('useSettings with several children', () => {
  it('adds, switches, and keeps each child’s settings to themselves', () => {
    const { result } = renderHook(() => useSettings())
    const perDay = mins => ({ ...CHILD_DEFAULTS.quota, perDay: mins })
    act(() => result.current.setQuota(perDay(30)))
    expect(result.current.settings.quota.perDay).toBe(30)

    act(() => result.current.addChild('Bob'))
    // adding switches to the new child, who starts from the defaults
    expect(result.current.settings.childName).toBe('Bob')
    expect(result.current.settings.quota.perDay).toBe(CHILD_DEFAULTS.quota.perDay)

    act(() => result.current.setQuota(perDay(90)))
    act(() => result.current.setBirthday('2021-03'))
    expect(result.current.settings.quota.perDay).toBe(90)

    // back to the first child: their 30 is untouched by Bob's 90
    act(() => result.current.switchChild(FIRST_CHILD_ID))
    expect(result.current.settings.quota.perDay).toBe(30)
    expect(result.current.settings.birthday).toBe(null)
    expect(result.current.children).toHaveLength(2)
  })

  it('keeps the API key and passkey account-wide across children', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setApiKey('KEY'))
    act(() => result.current.setPasskey('pk'))
    act(() => result.current.addChild('Bob'))
    expect(result.current.settings.apiKey).toBe('KEY')
    expect(result.current.settings.passkeyId).toBe('pk')
  })

  it('removes a child with their history, but never the last one', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.addChild('Bob'))
    const bob = result.current.settings.childId
    localStorage.setItem(watchKey(bob), JSON.stringify({ watched: { v: 1 } }))

    act(() => result.current.removeChild(bob))
    expect(result.current.children).toHaveLength(1)
    expect(result.current.settings.childId).toBe(FIRST_CHILD_ID) // fell back
    expect(localStorage.getItem(watchKey(bob))).toBe(null) // history went with them

    act(() => result.current.removeChild(FIRST_CHILD_ID))
    expect(result.current.children).toHaveLength(1) // the last child stays
  })

  it('persists the un-flattened blob — what sync pushes — not the view', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.setQuota({ ...CHILD_DEFAULTS.quota, perDay: 45 }))
    const raw = JSON.parse(localStorage.getItem('tinytube:settings:v1'))
    expect(raw.children[0].quota.perDay).toBe(45)
    expect(raw.quota).toBeUndefined() // no duplicated child fields at the top
    expect(result.current.stored).toEqual(raw)
  })
})

describe('useWatchStore per child', () => {
  it('adopts the pre-children history for the first child and keys the rest apart', () => {
    localStorage.setItem('tinytube:v1', JSON.stringify({ watched: { legacyvideo: { pos: 5, dur: 10 } } }))

    const first = renderHook(() => useWatchStore(FIRST_CHILD_ID))
    expect(first.result.current.watched.legacyvideo).toBeTruthy() // migrated in

    const bob = renderHook(() => useWatchStore('uuid-bob'))
    expect(bob.result.current.watched).toEqual({}) // a different child starts empty

    act(() => bob.result.current.saveProgress('dQw4w9WgXcQ', 30, 100))
    expect(JSON.parse(localStorage.getItem(watchKey('uuid-bob'))).watched.dQw4w9WgXcQ).toBeTruthy()
    // the first child's history is untouched by Bob watching something
    expect(first.result.current.watched.dQw4w9WgXcQ).toBeUndefined()
  })

  it('swaps the whole history when the child changes under it', () => {
    localStorage.setItem(watchKey('a'), JSON.stringify({ watched: { aaaaaaaaaaa: { pos: 1, dur: 2 } } }))
    localStorage.setItem(watchKey('b'), JSON.stringify({ watched: { bbbbbbbbbbb: { pos: 3, dur: 4 } } }))
    const { result, rerender } = renderHook(({ id }) => useWatchStore(id), { initialProps: { id: 'a' } })
    expect(Object.keys(result.current.watched)).toEqual(['aaaaaaaaaaa'])
    rerender({ id: 'b' })
    expect(Object.keys(result.current.watched)).toEqual(['bbbbbbbbbbb'])
  })
})

describe('the enable toggle is gone', () => {
  it('clears a stored disabled flag on load, leaving no state without a control', () => {
    const s = normalizeSettings({
      overrides: {
        UCa: { disabled: true }, // nothing else: the whole override goes
        UCb: { min_age: 4, disabled: true }, // the age edit survives it
      },
    })
    expect(s.children[0].overrides).toEqual({ UCb: { min_age: 4 } })
  })
})

describe('import and export are one child’s business', () => {
  it('exports the ACTIVE child’s channels, never another’s', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.addCustomChannel({ channel_id: 'UCoookXUzPciGrEZEXmh4Jjg', min_age: 3, max_age: 6 }))

    act(() => result.current.addChild('Bob'))
    act(() => result.current.addCustomChannel({ channel_id: 'UCG2CL6EUjG8TVT1Tpl9nJdg', min_age: 8, max_age: 12 }))

    const bobs = exportChannels(result.current.settings)
    expect(bobs.child).toBe('Bob')
    expect(bobs.customChannels.map(c => c.channel_id)).toEqual(['UCG2CL6EUjG8TVT1Tpl9nJdg'])

    act(() => result.current.switchChild(FIRST_CHILD_ID))
    const firsts = exportChannels(result.current.settings)
    expect(firsts.customChannels.map(c => c.channel_id)).toEqual(['UCoookXUzPciGrEZEXmh4Jjg'])
  })

  it('imports into the ACTIVE child alone, leaving the others untouched', () => {
    const { result } = renderHook(() => useSettings())
    act(() => result.current.addCustomChannel({ channel_id: 'UCoookXUzPciGrEZEXmh4Jjg', min_age: 3, max_age: 6 }))
    act(() => result.current.addChild('Bob'))

    act(() =>
      result.current.importChannels({
        customChannels: [{ channel_id: 'UC5PYHgAzJ1wLEidB58SK6Xw', min_age: 1, max_age: 15 }],
        overrides: {},
        groups: [],
        groupOf: {},
      }),
    )
    expect(result.current.settings.customChannels.map(c => c.channel_id)).toEqual(['UC5PYHgAzJ1wLEidB58SK6Xw'])

    // the first child kept theirs, untouched by Bob's import
    act(() => result.current.switchChild(FIRST_CHILD_ID))
    expect(result.current.settings.customChannels.map(c => c.channel_id)).toEqual(['UCoookXUzPciGrEZEXmh4Jjg'])
  })
})
