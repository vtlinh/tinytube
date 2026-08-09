/** Import/export of a child's channel list. The file is the one input a
 * parent can hand this app from anywhere, and what it becomes is the list of
 * channels a child may watch — so everything coming back in is re-validated,
 * and these are the cases that pins it. */

import {
  exportChannels,
  parseChannelImport,
  EXPORT_KIND,
  EXPORT_VERSION,
  AGE_MIN,
  AGE_MAX,
} from '../src/lib.js'

const UC = 'UCoookXUzPciGrEZEXmh4Jjg'
const UC2 = 'UCG2CL6EUjG8TVT1Tpl9nJdg'
const UC3 = 'UC5PYHgAzJ1wLEidB58SK6Xw'

const settings = {
  childName: 'Ann',
  customChannels: [
    { channel_id: UC, channel_title: 'Chan', thumbnail: 'https://yt3.ggpht.com/a.jpg', min_age: 3, max_age: 6 },
  ],
  overrides: { [UC2]: { min_age: 4, hidden: true } },
  groups: [{ id: 'g1', name: 'Cartoons' }],
  groupOf: { [UC]: 'g1', [UC2]: 'g1' },
}

describe('round trip', () => {
  it('exports what a parent can import back unchanged', () => {
    const file = exportChannels(settings)
    expect(file.kind).toBe(EXPORT_KIND)
    expect(file.version).toBe(EXPORT_VERSION)
    expect(file.child).toBe('Ann') // whose list it was, for the parent's benefit

    const back = parseChannelImport(JSON.stringify(file))
    expect(back.customChannels).toEqual(settings.customChannels)
    expect(back.overrides).toEqual(settings.overrides)
    expect(back.groups).toEqual(settings.groups)
    expect(back.groupOf).toEqual(settings.groupOf)
  })
})

describe('refusing the wrong file, loudly', () => {
  it('names the problem instead of emptying a grid', () => {
    expect(() => parseChannelImport('not json at all')).toThrow(/not JSON/)
    expect(() => parseChannelImport('{"hello":"world"}')).toThrow(/not a TinyTube channel export/)
    expect(() => parseChannelImport(JSON.stringify({ kind: 'something-else' }))).toThrow(/not a TinyTube/)
    expect(() => parseChannelImport(JSON.stringify({ kind: EXPORT_KIND, version: 99 }))).toThrow(/newer version/)
    expect(() => parseChannelImport('null')).toThrow(/not a TinyTube/)
  })

  it('accepts a recognised file that carries nothing', () => {
    expect(parseChannelImport(JSON.stringify({ kind: EXPORT_KIND, version: 1 }))).toEqual({
      customChannels: [],
      overrides: {},
      groups: [],
      groupOf: {},
    })
  })
})

describe('re-validating what comes back in', () => {
  const file = rest => JSON.stringify({ kind: EXPORT_KIND, version: 1, ...rest })

  it('drops channels whose id could reach a URL as anything but an id', () => {
    const { customChannels } = parseChannelImport(
      file({
        customChannels: [
          { channel_id: UC, channel_title: 'Good' },
          { channel_id: 'UC../../evil', channel_title: 'Traversal' },
          { channel_id: 'not-a-channel', channel_title: 'Bad' },
          { channel_title: 'No id' },
          { channel_id: UC, channel_title: 'Duplicate' },
        ],
      }),
    )
    expect(customChannels.map(c => c.channel_id)).toEqual([UC])
    expect(customChannels[0].channel_title).toBe('Good') // the duplicate lost, not the original
  })

  it('drops a thumbnail that is not YouTube over https, keeping the channel', () => {
    const { customChannels } = parseChannelImport(
      file({
        customChannels: [
          { channel_id: UC, thumbnail: 'https://evil.example/track.gif' },
          { channel_id: UC2, thumbnail: 'javascript:alert(1)' },
          { channel_id: UC3, thumbnail: 'https://i.ytimg.com/vi/x/mq.jpg' },
        ],
      }),
    )
    expect(customChannels).toHaveLength(3) // no channel lost over a bad picture
    expect(customChannels[0].thumbnail).toBeUndefined()
    expect(customChannels[1].thumbnail).toBeUndefined()
    expect(customChannels[2].thumbnail).toBe('https://i.ytimg.com/vi/x/mq.jpg')
  })

  it('repairs ages: out of range becomes the full span, inverted pairs swap', () => {
    const { customChannels } = parseChannelImport(
      file({
        customChannels: [
          { channel_id: UC, min_age: 99, max_age: -3 },
          { channel_id: UC2, min_age: 9, max_age: 4 },
          { channel_id: UC3, min_age: 2.5, max_age: 'six' },
        ],
      }),
    )
    expect(customChannels[0]).toMatchObject({ min_age: AGE_MIN, max_age: AGE_MAX })
    expect(customChannels[1]).toMatchObject({ min_age: 4, max_age: 9 })
    expect(customChannels[2]).toMatchObject({ min_age: AGE_MIN, max_age: AGE_MAX })
  })

  it('keeps only the override keys this app knows, and only when true', () => {
    const { overrides } = parseChannelImport(
      file({
        overrides: {
          [UC]: { min_age: 5, hidden: true, evil: 'payload', disabled: 'yes' },
          [UC2]: { min_age: 9, max_age: 3 },
          'not-an-id': { hidden: true },
          [UC3]: { nothing: 'useful' },
        },
      }),
    )
    expect(overrides[UC]).toEqual({ min_age: 5, hidden: true }) // evil and the non-true disabled gone
    expect(overrides[UC2]).toEqual({ min_age: 3, max_age: 9 }) // swapped
    expect(overrides['not-an-id']).toBeUndefined()
    expect(overrides[UC3]).toBeUndefined() // nothing worth keeping
  })

  it('holds the two-member group rule and drops memberships pointing nowhere', () => {
    const { groups, groupOf } = parseChannelImport(
      file({
        groups: [
          { id: 'g1', name: 'Keeps' },
          { id: 'g2', name: 'Only one member' },
        ],
        groupOf: { [UC]: 'g1', [UC2]: 'g1', [UC3]: 'g2', 'not-an-id': 'g1', badChannel: 'ghost' },
      }),
    )
    expect(groups.map(g => g.id)).toEqual(['g1']) // g2 dissolved: one member
    expect(groupOf).toEqual({ [UC]: 'g1', [UC2]: 'g1' })
  })
})
