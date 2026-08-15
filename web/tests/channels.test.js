import { describe, it, expect } from 'vitest'
import { overlaps, mergeChannels } from '../src/lib.js'
import { DEFAULTS } from '../src/lib.js'

const decisions = [
  { channel_id: 'UCa', min_age: 2, max_age: 4 },
  { channel_id: 'UCb', min_age: 3, max_age: 7 },
  { channel_id: 'UCc', min_age: 5, max_age: 10 },
]
const records = {
  UCa: { title: 'Toddler', videos: [{ id: 'v1' }] },
  UCb: { title: 'Preschool', videos: [{ id: 'v2' }] },
  UCc: { title: 'School', videos: [{ id: 'v3' }] },
}

const settings = (extra = {}) => ({ ...DEFAULTS, customChannels: decisions, ...extra })

describe('overlaps', () => {
  it('is inclusive at the boundaries', () => {
    expect(overlaps([4, 4], 2, 4)).toBe(true)
    expect(overlaps([4, 4], 3, 7)).toBe(true)
    expect(overlaps([4, 4], 5, 10)).toBe(false)
  })
})

describe('mergeChannels', () => {
  it('default range shows every approved channel', () => {
    expect(mergeChannels(records, settings()).map(c => c.channel_id)).toEqual(['UCa', 'UCb', 'UCc'])
  })

  it('filters channels by age overlap', () => {
    expect(mergeChannels(records, settings({ ageRange: [5, 6] })).map(c => c.channel_id)).toEqual([
      'UCb', 'UCc',
    ])
  })

  it('shows every approved channel: leftover hidden/disabled flags do not hide one', () => {
    // those flags belonged to the built-in catalog's editor. An old blob may
    // still carry them; normalizeSettings strips them on load, and nothing
    // here honours one.
    const s = settings({ overrides: { UCb: { hidden: true, disabled: true } } })
    expect(mergeChannels(records, s).map(c => c.channel_id)).toEqual(['UCa', 'UCb', 'UCc'])
  })

  it('still lists a channel whose record has not arrived, under its id', () => {
    // it has to stay on the screen that manages it, or it cannot be removed
    const custom = [{ channel_id: 'UCx', min_age: 1, max_age: 15 }]
    const merged = mergeChannels({}, settings({ customChannels: custom }))
    expect(merged.find(c => c.channel_id === 'UCx')).toMatchObject({ channel_title: 'UCx', videos: [] })
  })

  it('hydrates the parent’s decision from the Worker record and filters by the channel’s own range', () => {
    const custom = [
      { channel_id: 'UCx', min_age: 1, max_age: 15 },
      { channel_id: 'UCy', min_age: 13, max_age: 15 },
    ]
    const byId = { UCx: { title: 'Custom', thumbnail: 'https://yt3.ggpht.com/a.jpg', videos: [{ id: 'cv1' }] } }
    const merged = mergeChannels(byId, settings({ customChannels: custom, ageRange: [3, 8] }))
    expect(merged.find(c => c.channel_id === 'UCx')).toMatchObject({
      channel_title: 'Custom',
      thumbnail: 'https://yt3.ggpht.com/a.jpg',
      videos: [{ id: 'cv1' }],
    })
    expect(merged.find(c => c.channel_id === 'UCy')).toBeUndefined()
  })

  it('drops videos shorter than minVideoMins, counting unknown durations as too short', () => {
    const withDurations = {
      UCa: {
        title: 'Mixed',
        videos: [{ id: 'short', duration: 299 }, { id: 'long', duration: 300 }, { id: 'unknown' }],
      },
    }
    const merged = mergeChannels(withDurations, settings({ minVideoMins: 5 }))
    expect(merged[0].videos.map(v => v.id)).toEqual(['long'])
  })

  it('minVideoMins of 0 keeps videos with unknown durations', () => {
    const merged = mergeChannels(records, settings())
    expect(merged.map(c => c.videos.length)).toEqual([1, 1, 1])
  })

  it('an empty approved list is an empty grid', () => {
    expect(mergeChannels(records, settings({ customChannels: [] }))).toEqual([])
  })
})
