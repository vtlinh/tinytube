/** The kid-facing side: the video grid, the read-only Channels tab, and the
 * two-tab bottom bar that switches between them — the web copy of the Android
 * app's MainActivity + BottomTabs.
 *
 * The Channels tab is READ-ONLY, like Android's: it narrows the grid to one
 * channel or one group, and that is all it can do. Nothing here edits what is
 * approved — that lives behind the parent gate. */

import { useMemo, useState } from 'react'
import { gallerySort, fraction, arrangeChannels, WATCHED_THRESHOLD } from './lib.js'

export default function Gallery({ channels, groups = [], groupOf = {}, watchStore, onPlay, onParents }) {
  const [tab, setTab] = useState('videos')
  // {kind: 'channel'|'group', id, title} — which slice of the grid is showing
  const [filter, setFilter] = useState(null)

  const visible = useMemo(() => {
    if (!filter) return channels
    if (filter.kind === 'channel') return channels.filter(ch => ch.channel_id === filter.id)
    return channels.filter(ch => groupOf[ch.channel_id] === filter.id)
  }, [channels, filter, groupOf])

  const videos = useMemo(() => gallerySort(visible, watchStore.watched), [visible, watchStore.watched])

  const pick = f => {
    setFilter(f)
    setTab('videos')
  }

  return (
    <div className="gallery pb-5 mb-4">
      <nav className="gallery-toolbar d-flex align-items-center px-3 py-2">
        {/* an arrow at the start, like every other app on the phone: narrowing
            to a channel is a step into something, and back steps out */}
        {filter && (
          <button
            type="button"
            className="btn btn-lg ps-0"
            aria-label="Show all videos"
            onClick={() => setFilter(null)}
          >
            <i className="fa-sharp-duotone fa-regular fa-arrow-left" />
          </button>
        )}
        <span className="fs-4 fw-bold me-auto text-truncate d-flex align-items-center">
          {!filter && (
            // the REAL app icon, not a lookalike glyph — same master as the
            // Android launcher (art/app-icon.png via make-icons.py)
            <img
              src={`${import.meta.env.BASE_URL}icons/icon-192.png?v=2`}
              alt=""
              width="28"
              height="28"
              className="me-2 rounded"
            />
          )}
          {filter?.title ?? 'TinyTube'}
        </span>
        {/* a worded button, not an icon — the Android status bar's Parent
            button. Visible rather than hidden: what stops a child using it is
            the gate behind it, not obscurity. */}
        <button
          type="button"
          className="btn btn-link text-danger fw-semibold text-decoration-none fs-6 px-2"
          aria-label="Parents"
          onClick={onParents}
        >
          Parent
        </button>
      </nav>

      {tab === 'videos' ? (
        <div className="container-fluid py-3">
          <div className="row g-3">
            {videos.map(video => (
              <div key={video.id} className="col-6 col-md-4 col-lg-3">
                <VideoCard video={video} entry={watchStore.watched[video.id]} onPlay={() => onPlay(video)} />
              </div>
            ))}
          </div>
        </div>
      ) : (
        <ChannelsTab channels={channels} groups={groups} groupOf={groupOf} onPick={pick} />
      )}

      <nav className="bottom-tabs fixed-bottom d-flex border-top">
        <TabButton
          label="Videos"
          icon="fa-tv-retro"
          on={tab === 'videos'}
          onClick={() => setTab('videos')}
        />
        <TabButton
          label="Channels"
          icon="fa-list"
          on={tab === 'channels'}
          onClick={() => setTab('channels')}
        />
      </nav>
    </div>
  )
}

/* Colour only — no indicator bar, no size change: on two tabs the accent is
   unambiguous, and something that moves under a thumb is one more thing for a
   child to play with rather than watch a video. Exported: Parents Mode's
   bottom bar uses the same buttons. */
export function TabButton({ label, icon, on, onClick }) {
  return (
    <button
      type="button"
      className={`btn flex-fill py-2 rounded-0 ${on ? 'text-danger' : 'text-secondary'}`}
      onClick={onClick}
    >
      <div>
        <i className={`fa-sharp-duotone fa-regular ${icon}`} />
      </div>
      <div style={{ fontSize: '0.75rem' }}>{label}</div>
    </button>
  )
}

/* The groups are shown AND their members are shown too: a header filters the
   grid to every channel in it, and each member is still listed individually
   below it — reaching one channel of a group must not cost a child two taps
   and an idea about how grouping works. */
function ChannelsTab({ channels, groups, groupOf, onPick }) {
  const rows = useMemo(() => arrangeChannels(channels, groups, groupOf), [channels, groups, groupOf])
  return (
    <div className="list-group list-group-flush py-2">
      {rows.map(row =>
        row.type === 'header' ? (
          <button
            key={`g:${row.group.id}`}
            type="button"
            className="list-group-item list-group-item-action d-flex align-items-center gap-3 py-2 fw-semibold"
            onClick={() => onPick({ kind: 'group', id: row.group.id, title: row.group.name })}
          >
            <i className="fa-sharp-duotone fa-regular fa-folders fs-5 text-danger" />
            <span className="flex-grow-1 text-start">{row.group.name}</span>
            <span className="text-secondary small">{row.size}</span>
          </button>
        ) : (
          <button
            key={row.channel.channel_id}
            type="button"
            className={`list-group-item list-group-item-action d-flex align-items-center gap-3 py-2 ${row.grouped ? 'ps-5' : ''}`}
            onClick={() =>
              onPick({ kind: 'channel', id: row.channel.channel_id, title: row.channel.channel_title })
            }
          >
            {row.channel.thumbnail ? (
              <img src={row.channel.thumbnail} alt="" className="rounded-circle" width="36" height="36" />
            ) : (
              <i className="fa-duotone fa-regular fa-tv-retro fs-5 text-secondary" />
            )}
            <span className="flex-grow-1 text-start">{row.channel.channel_title}</span>
          </button>
        ),
      )}
    </div>
  )
}

function formatDuration(seconds) {
  if (!seconds) return null
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return m >= 60
    ? `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${m}:${String(s).padStart(2, '0')}`
}

function VideoCard({ video, entry, onPlay }) {
  const f = fraction(entry)
  const isWatched = f > WATCHED_THRESHOLD

  return (
    // h-100: columns in a bootstrap row already stretch to the tallest, so
    // filling them equalizes the row — a short title no longer shortens its card
    <button type="button" className="video-card card w-100 h-100 border-0 p-0" onClick={onPlay}>
      <div className="position-relative">
        <img src={video.thumbnail} alt="" className="card-img-top" loading="lazy" />
        {video.duration && (
          <span className="badge text-bg-dark position-absolute bottom-0 end-0 m-1">
            {formatDuration(video.duration)}
          </span>
        )}
        {isWatched && (
          <span className="watched-badge position-absolute top-0 end-0 m-1">
            <i className="fa-sharp-duotone fa-regular fa-circle-check" />
          </span>
        )}
        {f > 0 && !isWatched && (
          <div className="progress card-progress position-absolute bottom-0 start-0 w-100">
            <div className="progress-bar bg-danger" style={{ width: `${f * 100}%` }} />
          </div>
        )}
      </div>
      <div className="card-body p-2 text-start">
        <div className="card-title small fw-semibold mb-1 video-title">{video.title}</div>
        <div className="text-secondary" style={{ fontSize: '0.75rem' }}>{video.channelTitle}</div>
      </div>
    </button>
  )
}
