import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import Gallery from '../src/gallery.jsx'

const channels = [{ channel_title: 'Chan', videos: [{ id: 'v1', title: 'Vid', thumbnail: 't.jpg' }] }]
const watchStore = { watched: {} }

describe('Parents button', () => {
  it('is always visible and opens the parent gate', () => {
    const onParents = vi.fn()
    render(<Gallery channels={channels} watchStore={watchStore} onPlay={() => {}} onParents={onParents} />)
    fireEvent.click(screen.getByLabelText('Parents'))
    expect(onParents).toHaveBeenCalled()
  })
})

/* What plays next comes from the list the child tapped on, so the tap has to
   carry that list — not just the one video. A tap inside a channel-filtered
   grid therefore hands over that channel's videos and nothing else, which is
   what keeps the player from ever leading out of it. */
describe('tapping a video', () => {
  const two = [
    { channel_id: 'UCa', channel_title: 'A', videos: [{ id: 'a1', title: 'A1' }, { id: 'a2', title: 'A2' }] },
    { channel_id: 'UCb', channel_title: 'B', videos: [{ id: 'b1', title: 'B1' }] },
  ]

  it('hands over the whole visible list and where in it the tap landed', () => {
    const onPlay = vi.fn()
    render(<Gallery channels={two} watchStore={watchStore} onPlay={onPlay} onParents={() => {}} />)
    fireEvent.click(screen.getByText('A2').closest('button'))
    const [list, index] = onPlay.mock.calls[0]
    expect(list.map(v => v.id)).toEqual(['a1', 'b1', 'a2'])
    expect(list[index].id).toBe('a2')
  })

  it('narrows that list to the channel the child stepped into', () => {
    const onPlay = vi.fn()
    render(
      <Gallery
        channels={two}
        watchStore={watchStore}
        groups={[]}
        groupOf={{}}
        onPlay={onPlay}
        onParents={() => {}}
      />,
    )
    fireEvent.click(screen.getByText('Channels').closest('button'))
    fireEvent.click(screen.getByText('A').closest('button'))
    fireEvent.click(screen.getByText('A1').closest('button'))
    const [list] = onPlay.mock.calls[0]
    expect(list.map(v => v.id)).toEqual(['a1', 'a2']) // B is not reachable from here
  })

  it('leaves watched videos off the grid when the parent has hidden them', () => {
    const store = { watched: { a1: { pos: 100, dur: 100, completed: true } } }
    const onPlay = vi.fn()
    const { rerender } = render(
      <Gallery channels={two} watchStore={store} onPlay={onPlay} onParents={() => {}} />,
    )
    expect(screen.queryByText('A1')).not.toBe(null) // sunk to the bottom, still there

    rerender(<Gallery channels={two} watchStore={store} hideWatched onPlay={onPlay} onParents={() => {}} />)
    expect(screen.queryByText('A1')).toBe(null)
    fireEvent.click(screen.getByText('A2').closest('button'))
    // and the list the player gets is the one on screen, watched video gone
    expect(onPlay.mock.calls[0][0].map(v => v.id)).toEqual(['a2', 'b1'])
  })
})
