/** The ChannelGroups rules, ported from the Android app alongside the code
 * they pin. The invariant everything hangs off: a group has at least two
 * channels — see lib.js's channelGroups section. */

import {
  arrangeChannels,
  canGroup,
  canUngroup,
  prefillGroupName,
  groupNamesInUse,
  absorbingGroup,
  groupNameError,
  tidyGroups,
  groupInto,
} from '../src/lib.js'

const ch = (id, title) => ({ channel_id: id, channel_title: title })
const A = ch('a', 'Alpha')
const B = ch('b', 'Bravo')
const C = ch('c', 'Charlie')
const D = ch('d', 'Delta')

describe('arrangeChannels', () => {
  const groups = [
    { id: 'g2', name: 'zoo' },
    { id: 'g1', name: 'Art' },
  ]
  const groupOf = { a: 'g1', c: 'g1', b: 'g2', d: 'g2' }

  it('draws group headers A-Z with their members, then loose channels', () => {
    const rows = arrangeChannels([A, B, C, D, ch('e', 'Echo')], groups, groupOf)
    expect(rows.map(r => (r.type === 'header' ? `#${r.group.name}(${r.size})` : r.channel.channel_id))).toEqual([
      '#Art(2)',
      'a',
      'c',
      '#zoo(2)',
      'b',
      'd',
      'e',
    ])
    expect(rows.filter(r => r.type === 'item').map(r => r.grouped)).toEqual([true, true, true, true, false])
  })

  it('skips a group with fewer than two visible members and shows them loose — never hides a channel', () => {
    // only one member of g1 made it through the age filter
    const rows = arrangeChannels([A, B, D], groups, groupOf)
    expect(rows.map(r => (r.type === 'header' ? `#${r.group.name}` : r.channel.channel_id))).toEqual([
      '#zoo',
      'b',
      'd',
      'a', // g1's lone survivor is loose, not vanished
    ])
  })

  it('a channel naming a group that no longer exists is loose, not invisible', () => {
    const rows = arrangeChannels([A, B], [], { a: 'ghost' })
    expect(rows.map(r => r.channel.channel_id)).toEqual(['a', 'b'])
  })
})

describe('selection rules', () => {
  const groupOf = { a: 'g1', c: 'g1' }

  it('grouping needs two, ungrouping needs one group exactly', () => {
    expect(canGroup(new Set(['a']))).toBe(false)
    expect(canGroup(new Set(['a', 'b']))).toBe(true)
    expect(canUngroup(new Set(['a', 'c']), groupOf)).toBe(true)
    expect(canUngroup(new Set(['a', 'b']), groupOf)).toBe(false) // b is loose
    expect(canUngroup(new Set(['b']), groupOf)).toBe(false)
    expect(canUngroup(new Set(), groupOf)).toBe(false)
  })

  it('prefills the dialog only for whole-group-plus-loose selections', () => {
    const groups = [{ id: 'g1', name: 'Cartoons' }]
    expect(prefillGroupName(new Set(['a', 'c', 'b']), groups, groupOf)).toBe('Cartoons')
    expect(prefillGroupName(new Set(['a', 'b']), groups, groupOf)).toBe(null) // partial group
    expect(prefillGroupName(new Set(['a', 'c']), groups, groupOf)).toBe(null) // no loose channel
    expect(prefillGroupName(new Set(['b', 'd']), groups, groupOf)).toBe(null) // no group involved
  })

  it("a fully-selected group's name comes free, and its row is absorbed rather than duplicated", () => {
    const groups = [
      { id: 'g1', name: 'Cartoons' },
      { id: 'g2', name: 'Science' },
    ]
    const both = { a: 'g1', c: 'g1', b: 'g2', d: 'g2' }
    const selected = new Set(['a', 'c', 'e'])
    expect(groupNamesInUse(groups, both, selected)).toEqual(['Science'])
    expect(absorbingGroup('cartoons ', groups, both, selected)).toBe('g1')
    expect(absorbingGroup('Science', groups, both, selected)).toBe(null) // partly selected keeps its name
  })
})

describe('names and the invariant', () => {
  it('judges names trimmed and case-insensitively', () => {
    expect(groupNameError('  ', ['Cartoons'])).toBe('empty')
    expect(groupNameError('cartoons ', ['Cartoons'])).toBe('taken')
    expect(groupNameError('Science', ['Cartoons'])).toBe(null)
  })

  it('tidyGroups dissolves the under-two groups and their memberships', () => {
    const tidied = tidyGroups({
      groups: [
        { id: 'g1', name: 'Keeps' },
        { id: 'g2', name: 'Dissolves' },
      ],
      groupOf: { a: 'g1', b: 'g1', c: 'g2' },
    })
    expect(tidied.groups.map(g => g.id)).toEqual(['g1'])
    expect(tidied.groupOf).toEqual({ a: 'g1', b: 'g1' })
  })

  it('groupInto absorbs the emptied same-name group and tidies what it leaves', () => {
    const settings = {
      groups: [{ id: 'g1', name: 'Cartoons' }],
      groupOf: { a: 'g1', b: 'g1' },
    }
    // move all of Cartoons plus c into "Cartoons": the row is reused, no duplicate
    const patch = groupInto(settings, new Set(['a', 'b', 'c']), 'Cartoons')
    expect(patch.groups).toEqual([{ id: 'g1', name: 'Cartoons' }])
    expect(patch.groupOf).toEqual({ a: 'g1', b: 'g1', c: 'g1' })

    // moving b and c into a NEW group strands a; its old group dissolves
    const patch2 = groupInto(settings, new Set(['b', 'c']), 'Science')
    expect(patch2.groups.map(g => g.name)).toEqual(['Science'])
    expect(patch2.groupOf.a).toBeUndefined()
    expect(new Set(Object.values(patch2.groupOf)).size).toBe(1)
  })
})
