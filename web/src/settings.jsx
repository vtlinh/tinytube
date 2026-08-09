import { useEffect, useMemo, useRef, useState } from 'react'
import {
  curatedChannels,
  overlaps,
  fmtMins,
  usageStats,
  usedSecs,
  statsUsage,
  loadGoogleSignIn,
  GOOGLE_CLIENT_ID,
  QUOTA_WINDOW_MS,
  ageFromBirthday,
  effectiveAgeRange,
  effectiveQuota,
  activeDayOverride,
  lastFiniteLimit,
  activeBonusMins,
  quotaState,
  QUOTA_PERIODS,
  LENGTH_STOPS,
  lengthIndex,
  lengthLabel,
  minuteLabel,
  limitLabel,
  clampLengthRange,
  hydrateChannel,
  arrangeChannels,
  AGE_MIN,
  AGE_MAX,
  ageAtFraction,
  grabEnd,
  canGroup,
  canUngroup,
  groupMembers,
  prefillGroupName,
  groupNamesInUse,
  groupNameError,
  exportChannels,
  parseChannelImport,
  importConflicts,
  applyImport,
} from './lib.js'
import {
  searchChannels,
  resolveChannel,
  evictChannelCache,
  seedChannelMeta,
  formatCount,
  validateApiKey,
} from './youtubeApi.js'
import { TabButton } from './gallery.jsx'

const API_CONSOLE_URL = 'https://console.cloud.google.com/apis/library/youtube.googleapis.com'
const looksLikeLink = s => /^@|^UC[0-9A-Za-z_-]{22}$|youtube\.com/.test(s.trim())
const channelUrl = ch => ch.source_url ?? `https://www.youtube.com/channel/${ch.channel_id}`

export default function Settings({ db, customById = {}, store, watchStore, sync, onDone }) {
  // every valid change persists the moment it is made — no draft, no Save
  // button; half-typed values are held locally by their rows (see BirthdayRow)
  // and only committed once they parse
  const settings = store.settings
  // 'settings' | 'channels' | 'stats' — the bottom bar below switches
  const [tab, setTab] = useState('settings')
  const titles = { settings: 'Parents Mode', channels: 'Channels', stats: 'Stats' }

  return (
    <div className="settings container-xl py-4 pb-5 mb-4">
      {/* explicit back button: iOS standalone PWAs have no browser chrome or
          hardware back, so without it a no-change visit would strand you here.
          flex: 1 sides keep the title truly centered */}
      <div className="d-flex align-items-center gap-3 mb-4">
        <div style={{ flex: 1 }}>
          <button type="button" className="btn btn-outline-secondary" aria-label="Back to gallery" onClick={onDone}>
            <i className="fa-sharp-duotone fa-regular fa-arrow-left" />
          </button>
        </div>
        <div className="text-center">
          <h1 className="fs-3 fw-bold m-0">{titles[tab]}</h1>
          {/* whose settings these are — the menu switches children */}
          <div className="text-secondary small">{settings.childName}</div>
        </div>
        <div className="d-flex align-items-center justify-content-end gap-3" style={{ flex: 1 }}>
          <HeaderMenu store={store} sync={sync} />
        </div>
      </div>

      {tab === 'settings' && (
        <>
          <NameRow value={settings.childName} onChange={store.renameChild} />
          <BirthdayRow value={settings.birthday} onChange={store.setBirthday} />
          <QuotaRow store={store} watchStore={watchStore} />
          <VideoLengthRow
            value={[settings.minVideoMins, settings.maxVideoMins]}
            onChange={store.setVideoLength}
          />
          <ApiKeyRow apiKey={settings.apiKey} onChange={store.setApiKey} />
          {/* the About position, like the Android app: the bottom of settings */}
          <div className="text-center mt-5">
            <VersionLink />
          </div>
        </>
      )}
      {tab === 'channels' && (
        <>
          <SearchRow apiKey={settings.apiKey} store={store} db={db} />
          <ChannelList db={db} customById={customById} store={store} />
        </>
      )}
      {tab === 'stats' && <StatsTab watchStore={watchStore} settings={settings} />}

      <nav className="bottom-tabs fixed-bottom d-flex border-top">
        <TabButton label="Settings" icon="fa-gear" on={tab === 'settings'} onClick={() => setTab('settings')} />
        <TabButton label="Channels" icon="fa-list" on={tab === 'channels'} onClick={() => setTab('channels')} />
        <TabButton label="Stats" icon="fa-chart-simple" on={tab === 'stats'} onClick={() => setTab('stats')} />
      </nav>
    </div>
  )
}

/** Watch-time stats on their own tab, account-wide: statsUsage folds in what
 * every synced device watched. */
function StatsTab({ watchStore, settings }) {
  const stats = usageStats(statsUsage(watchStore))
  const { secsLeft } = quotaState(settings, watchStore)
  const bonusMins = activeBonusMins(settings)
  const rows = [
    ['Last 6 hours', stats.last6h, 'A rolling six hours'],
    ['Today', stats.today, 'Since midnight'],
    ['This week', stats.wtd, 'Since Sunday'],
    ['This month', stats.mtd, 'Since the 1st'],
    ['This year', stats.ytd, 'Year to date'],
  ]
  return (
    <div className="col-md-6 mx-auto">
      <div className="text-secondary mb-3">
        <i className="fa-sharp-duotone fa-regular fa-stopwatch me-2" />
        {Number.isFinite(secsLeft)
          ? `${fmtMins(Math.max(0, Math.ceil(secsLeft / 60)))} left before the tightest limit`
          : 'No limit set'}
      </div>
      {bonusMins > 0 && (
        <div className="mb-3">
          <i className="fa-sharp-duotone fa-regular fa-gift text-danger me-2" />
          {fmtMins(bonusMins)} extra granted — until midnight
        </div>
      )}
      <table className="table align-middle">
        <tbody>
          {rows.map(([label, secs, hint]) => (
            <tr key={label}>
              <td>
                <div className="fw-semibold">{label}</div>
                <div className="text-secondary small">{hint}</div>
              </td>
              <td className="text-end fs-5">{fmtMins(Math.round(secs / 60))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ConfirmModal({ title, body, onConfirm, onCancel, confirmLabel = 'Delete', confirmIcon = 'fa-trash' }) {
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" onClick={onCancel}>
        <div className="modal-dialog modal-dialog-centered" onClick={e => e.stopPropagation()}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">{title}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onCancel} />
            </div>
            <div className="modal-body">{body}</div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" onClick={onCancel}>
                Cancel
              </button>
              <button type="button" className="btn btn-danger" onClick={onConfirm}>
                <i className={`fa-sharp-duotone fa-regular ${confirmIcon} me-2`} />
                {confirmLabel}
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  )
}

/**
 * The header's three-dot menu: which child the parent is looking at, adding
 * another, and the account sign-in.
 *
 * The wording is deliberately plain — "Sign in" / "Sign out", no vendor name —
 * though underneath it is still Google: the item triggers the One Tap prompt
 * and falls through to the popup flow of an invisible rendered button when the
 * browser suppresses One Tap (FedCM cooldown, blocked third-party cookies).
 *
 * Immediate-effect, like everything else on this screen: switching child
 * changes the grid and the quota under you at once.
 */
function HeaderMenu({ store, sync = {} }) {
  const { session, signIn, signOut } = sync
  const [open, setOpen] = useState(false)
  const [adding, setAdding] = useState(false)
  const [pending, setPending] = useState(null) // a parsed import, awaiting confirmation
  const [error, setError] = useState(null)
  const hiddenBtn = useRef(null)
  const fileInput = useRef(null)
  const ready = useRef(false)

  /* A file per child rather than per account: the export is what a parent
     copies to another child, another device, or keeps somewhere safe. */
  const doExport = () => {
    setOpen(false)
    setError(null)
    const blob = new Blob([JSON.stringify(exportChannels(store.settings), null, 2)], {
      type: 'application/json',
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `tinytube-channels-${(store.settings.childName || 'child').replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  const onFile = async e => {
    const file = e.target.files?.[0]
    e.target.value = '' // so choosing the same file twice still fires
    if (!file) return
    setError(null)
    try {
      setPending(parseChannelImport(await file.text()))
    } catch (err) {
      setError(err.message)
    }
  }

  const startSignIn = async () => {
    setOpen(false)
    setError(null)
    try {
      const google = await loadGoogleSignIn()
      if (!ready.current) {
        google.accounts.id.initialize({
          client_id: GOOGLE_CLIENT_ID,
          callback: resp => signIn(resp.credential).catch(e => setError(e.message)),
        })
        google.accounts.id.renderButton(hiddenBtn.current, { type: 'icon', size: 'medium' })
        ready.current = true
      }
      google.accounts.id.prompt(moment => {
        if (moment.isNotDisplayed?.() || moment.isSkippedMoment?.()) {
          const btn = hiddenBtn.current?.querySelector('div[role="button"]')
          if (btn) btn.click()
          else setError('Sign-in was blocked by the browser — try again')
        }
      })
    } catch (e) {
      setError(e.message)
    }
  }

  const children = store.children ?? []
  return (
    <div className="position-relative">
      <button
        type="button"
        className="btn btn-outline-secondary"
        aria-label="More options"
        onClick={() => setOpen(o => !o)}
      >
        <i className="fa-sharp-duotone fa-regular fa-ellipsis-vertical" />
      </button>
      {open && (
        <>
          {/* click-away layer under the menu */}
          <div
            className="position-fixed top-0 start-0 w-100 h-100"
            style={{ zIndex: 1040 }}
            onClick={() => setOpen(false)}
          />
          <div
            className="dropdown-menu dropdown-menu-end show position-absolute end-0 mt-1 text-start"
            style={{ zIndex: 1050 }}
          >
            <h6 className="dropdown-header">Children</h6>
            {children.map(child => (
              <button
                key={child.id}
                type="button"
                className={`dropdown-item ${child.id === store.settings.childId ? 'active' : ''}`}
                onClick={() => {
                  store.switchChild(child.id)
                  setOpen(false)
                }}
              >
                {child.id === store.settings.childId && (
                  <i className="fa-sharp-duotone fa-regular fa-check me-2" />
                )}
                {child.name}
              </button>
            ))}
            <button
              type="button"
              className="dropdown-item"
              onClick={() => {
                setOpen(false)
                setAdding(true)
              }}
            >
              <i className="fa-sharp-duotone fa-regular fa-plus me-2" />
              Add child
            </button>
            {children.length > 1 && (
              <button
                type="button"
                className="dropdown-item text-danger"
                onClick={() => {
                  store.removeChild(store.settings.childId)
                  setOpen(false)
                }}
              >
                <i className="fa-sharp-duotone fa-regular fa-trash me-2" />
                Remove {store.settings.childName}
              </button>
            )}
            <hr className="dropdown-divider" />
            <h6 className="dropdown-header">{store.settings.childName}’s channels</h6>
            <button type="button" className="dropdown-item" onClick={doExport}>
              <i className="fa-sharp-duotone fa-regular fa-file-export me-2" />
              Export
            </button>
            <button
              type="button"
              className="dropdown-item"
              onClick={() => {
                setOpen(false)
                fileInput.current?.click()
              }}
            >
              <i className="fa-sharp-duotone fa-regular fa-file-import me-2" />
              Import
            </button>
            {GOOGLE_CLIENT_ID && (
              <>
                <hr className="dropdown-divider" />
                {session ? (
                  <>
                    <span className="dropdown-item-text text-secondary small">{session.email}</span>
                    <button
                      type="button"
                      className="dropdown-item"
                      onClick={() => {
                        signOut()
                        setOpen(false)
                      }}
                    >
                      Sign out
                    </button>
                  </>
                ) : (
                  <button type="button" className="dropdown-item" onClick={startSignIn}>
                    Sign in
                  </button>
                )}
              </>
            )}
          </div>
        </>
      )}
      <input
        ref={fileInput}
        type="file"
        accept="application/json,.json"
        hidden
        aria-hidden="true"
        onChange={onFile}
      />
      {pending && (
        <ImportModal
          pending={pending}
          settings={store.settings}
          onConfirm={mode => {
            store.importChannels(applyImport(store.settings, pending, mode))
            setPending(null)
          }}
          onCancel={() => setPending(null)}
        />
      )}
      {adding && (
        <ChildNameModal
          onConfirm={name => {
            store.addChild(name)
            setAdding(false)
          }}
          onCancel={() => setAdding(false)}
        />
      )}
      {/* the invisible real Google button the fallback clicks */}
      <div
        ref={hiddenBtn}
        aria-hidden="true"
        className="position-absolute overflow-hidden"
        style={{ width: 1, height: 1, opacity: 0, pointerEvents: 'none' }}
      />
      {error && (
        <div className="alert alert-warning position-absolute end-0 mt-2 py-1 px-2 small text-nowrap">{error}</div>
      )}
    </div>
  )
}

/**
 * What to do with a file when the child already has channels.
 *
 * Replacing is the whole file and only the file; merging never removes a
 * channel. The third button only appears when the two lists actually disagree
 * about something — with no conflicts, "who wins" is a question about nothing.
 */
function ImportModal({ pending, settings, onConfirm, onCancel }) {
  const conflicts = importConflicts(settings, pending)
  const count = `${pending.customChannels.length} channel${pending.customChannels.length === 1 ? '' : 's'}`
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" onClick={onCancel}>
        <div className="modal-dialog modal-dialog-centered" onClick={e => e.stopPropagation()}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">Import {count}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onCancel} />
            </div>
            <div className="modal-body d-grid gap-2">
              {conflicts.length > 0 && (
                <p className="text-secondary">
                  {conflicts.length} of them {conflicts.length === 1 ? 'is' : 'are'} already set up for{' '}
                  {settings.childName}.
                </p>
              )}
              <button type="button" className="btn btn-outline-light text-start" onClick={() => onConfirm('replace')}>
                <div className="fw-semibold">Replace everything</div>
                <div className="small text-secondary">
                  {settings.childName}’s list becomes exactly this file. Channels not in it are removed.
                </div>
              </button>
              {conflicts.length > 0 ? (
                <>
                  <button type="button" className="btn btn-outline-light text-start" onClick={() => onConfirm('theirs')}>
                    <div className="fw-semibold">Merge — the file wins</div>
                    <div className="small text-secondary">
                      Adds what is new and updates the {conflicts.length} that clash. Nothing is removed.
                    </div>
                  </button>
                  <button type="button" className="btn btn-outline-light text-start" onClick={() => onConfirm('mine')}>
                    <div className="fw-semibold">Merge — keep what is here</div>
                    <div className="small text-secondary">
                      Adds only what is new; the {conflicts.length} already set up are left alone.
                    </div>
                  </button>
                </>
              ) : (
                <button type="button" className="btn btn-outline-light text-start" onClick={() => onConfirm('theirs')}>
                  <div className="fw-semibold">Merge</div>
                  <div className="small text-secondary">Adds these alongside what {settings.childName} already has.</div>
                </button>
              )}
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" onClick={onCancel}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  )
}

/** Name for a new child. A blank name is allowed — the store falls back to
 * "Child N" — so the dialog never blocks on a field nobody wants to fill. */
function ChildNameModal({ onConfirm, onCancel }) {
  const [name, setName] = useState('')
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" onClick={onCancel}>
        <div className="modal-dialog modal-dialog-centered" onClick={e => e.stopPropagation()}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">Add child</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onCancel} />
            </div>
            <div className="modal-body">
              <input
                type="text"
                className="form-control"
                placeholder="Name"
                aria-label="Child name"
                autoFocus
                value={name}
                onChange={e => setName(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter') onConfirm(name)
                }}
              />
              <div className="form-text">
                Their own age, quota, channels and watch history — nothing is shared with the others.
              </div>
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" onClick={onCancel}>
                Cancel
              </button>
              <button type="button" className="btn btn-danger" onClick={() => onConfirm(name)}>
                <i className="fa-sharp-duotone fa-regular fa-plus me-2" />
                Add
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  )
}

function VersionLink() {
  const version = typeof __BUILD_VERSION__ !== 'undefined' ? __BUILD_VERSION__ : ''
  if (!version) return null
  return <span className="text-secondary small text-nowrap">Version {version}</span>
}

/* THE POINTER IS OURS HERE, and that is the fix rather than a flourish.
 *
 * Two stacked <input type="range"> expose only their THUMBS to a pointer, so
 * the moment lo and hi meet, the upper input's thumb sits exactly over the
 * other's and that end can never be dragged again — and a press anywhere on
 * the track does nothing at all, because the track itself takes no events.
 *
 * So a transparent surface over the pair handles the dragging and decides
 * which end moves (see grabEnd). The inputs stay exactly where they are, for
 * the keyboard and for screen readers; the single-thumb sliders below share
 * this CSS and are untouched, which is why the thumbs keep their own pointer
 * handling rather than having it taken away globally.
 */
function DualAgeSlider({ value: [lo, hi], onChange }) {
  const track = useRef(null)
  const grabbed = useRef(null)
  const pos = v => `calc(${(v - AGE_MIN) / (AGE_MAX - AGE_MIN)} * (100% - 32px) + 16px)`

  // the thumb's CENTRE travels between 16px and width-16px, matching pos()
  const ageAt = clientX => {
    const r = track.current.getBoundingClientRect()
    return ageAtFraction((clientX - r.left - 16) / Math.max(1, r.width - 32))
  }
  const apply = (end, v) => (end === 'lo' ? onChange([Math.min(v, hi), hi]) : onChange([lo, Math.max(v, lo)]))

  const onPointerDown = e => {
    const v = ageAt(e.clientX)
    grabbed.current = grabEnd(v, lo, hi)
    // capture, so a drag that leaves the row keeps steering the slider
    e.currentTarget.setPointerCapture(e.pointerId)
    if (grabbed.current !== 'pending') apply(grabbed.current, v)
  }

  const onPointerMove = e => {
    if (!grabbed.current) return
    const v = ageAt(e.clientX)
    // two ends on one value: the first move that goes anywhere says which
    if (grabbed.current === 'pending') {
      if (v === lo) return
      grabbed.current = v > hi ? 'hi' : 'lo'
    }
    apply(grabbed.current, v)
  }

  // capture releases itself on pointerup/cancel; only the grab needs clearing
  const release = () => {
    grabbed.current = null
  }

  return (
    <div className="dual-slider flex-grow-1" ref={track}>
      <input
        type="range"
        min={AGE_MIN}
        max={AGE_MAX}
        value={lo}
        aria-label="Youngest age"
        onChange={e => onChange([Math.min(+e.target.value, hi), hi])}
      />
      <input
        type="range"
        min={AGE_MIN}
        max={AGE_MAX}
        value={hi}
        aria-label="Oldest age"
        onChange={e => onChange([lo, Math.max(+e.target.value, lo)])}
      />
      <div
        className="slider-surface"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={release}
        onPointerCancel={release}
      />
    </div>
  )
}

/** Whose settings these are. Renames the child in place — the name is theirs,
 * not a label on a screen, so it travels with them everywhere. */
function NameRow({ value, onChange }) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  return (
    <div className="d-flex align-items-center gap-3 mb-4">
      <span className="text-secondary text-nowrap">
        <i className="fa-sharp-duotone fa-regular fa-child me-2" />
        Name
      </span>
      <input
        type="text"
        className="form-control"
        aria-label="Child’s name"
        value={text}
        onChange={e => {
          setText(e.target.value)
          // a blank name is not stored: the store would fall back to "Child"
          // and the field would fight whoever is mid-edit
          if (e.target.value.trim()) onChange(e.target.value)
        }}
      />
    </div>
  )
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const OLDEST_YEARS = 17 // a 16-year-old is past this app; one spare year of room

/**
 * The birthday, PICKED rather than typed: a year to step through and twelve
 * months to tap. Typing mm/yy meant a keyboard, a format to get right, and a
 * field that could hold something meaningless — a grid of months cannot, and
 * a month that has not happened yet is simply not offered.
 */
function BirthdayDialog({ value, onPick, onCancel }) {
  const now = new Date()
  const thisYear = now.getFullYear()
  const [year, setYear] = useState(value ? +value.slice(0, 4) : thisYear - 5)
  const step = by => setYear(y => Math.min(thisYear, Math.max(thisYear - OLDEST_YEARS, y + by)))
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" onClick={onCancel}>
        <div className="modal-dialog modal-dialog-centered" onClick={e => e.stopPropagation()}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">Birthday</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onCancel} />
            </div>
            <div className="modal-body">
              <div className="d-flex align-items-center justify-content-between mb-3">
                <button
                  type="button"
                  className="btn btn-outline-secondary"
                  aria-label="Previous year"
                  disabled={year <= thisYear - OLDEST_YEARS}
                  onClick={() => step(-1)}
                >
                  <i className="fa-sharp-duotone fa-regular fa-chevron-left" />
                </button>
                <span className="fs-4 fw-bold">{year}</span>
                <button
                  type="button"
                  className="btn btn-outline-secondary"
                  aria-label="Next year"
                  disabled={year >= thisYear}
                  onClick={() => step(1)}
                >
                  <i className="fa-sharp-duotone fa-regular fa-chevron-right" />
                </button>
              </div>
              <div className="d-grid gap-2" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
                {MONTHS.map((name, i) => {
                  const picked = value && +value.slice(0, 4) === year && +value.slice(5, 7) === i + 1
                  const future = year === thisYear && i > now.getMonth()
                  return (
                    <button
                      key={name}
                      type="button"
                      className={`btn ${picked ? 'btn-danger' : 'btn-outline-light'}`}
                      disabled={future}
                      onClick={() => onPick(`${year}-${String(i + 1).padStart(2, '0')}`)}
                    >
                      {name}
                    </button>
                  )
                })}
              </div>
            </div>
            <div className="modal-footer">
              {value && (
                <button type="button" className="btn btn-outline-light me-auto" onClick={() => onPick(null)}>
                  Clear
                </button>
              )}
              <button type="button" className="btn btn-secondary" onClick={onCancel}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  )
}

/** The child's BIRTHDAY (born the 1st of that month by declaration) rather
 * than an age: the computed age keeps up on its own instead of going stale a
 * birthday later. The channel filter uses it as a single-point range. */
function BirthdayRow({ value, onChange }) {
  const [picking, setPicking] = useState(false)
  const age = ageFromBirthday(value)
  const shown = value ? `${MONTHS[+value.slice(5, 7) - 1]} ${value.slice(0, 4)}` : 'not set'
  return (
    <div className="d-flex align-items-center gap-3 mb-4">
      <span
        className="text-secondary text-nowrap"
        title="Your child’s birthday — only channels rated for their age are shown"
      >
        <i className="fa-duotone fa-solid fa-children me-2" />
        Birthday
      </span>
      <button
        type="button"
        className="btn btn-outline-secondary"
        aria-label="Child’s birthday"
        onClick={() => setPicking(true)}
      >
        {shown}
        <i className="fa-sharp-duotone fa-regular fa-calendar-days ms-2" />
      </button>
      {age != null && <span className="text-secondary text-nowrap">{age} year{age === 1 ? '' : 's'} old</span>}
      {picking && (
        <BirthdayDialog
          value={value}
          onPick={v => {
            onChange(v)
            setPicking(false)
          }}
          onCancel={() => setPicking(false)}
        />
      )}
    </div>
  )
}


/** One period's limit: the shared track, plus a stop past the end that means
 * "no limit over this period" — which is a different thing from zero. */
/** One period's limit, on that period's own scale: 0 to the whole window in
 * its own step, then one stop past the end for "no limit". */
function LimitSlider({ value, onChange, label, period, maxMins, stepMins }) {
  /* One stop short of the period, then "no limit": a cap equal to the window
     it governs forbids nothing, so it WAS "no limit" wearing a number. */
  const steps = maxMins / stepMins // index `steps` is the no-limit stop
  // a value stored under an older, coarser or finer scale may sit between stops
  const index = value == null ? steps : Math.min(steps - 1, Math.round(value / stepMins))
  const pos = i => `calc(${i / steps} * (100% - 44px) + 22px)`
  return (
    <div className="dual-slider quota-slider">
      <input
        type="range"
        min="0"
        max={steps}
        value={index}
        aria-label={label}
        onChange={e => onChange(+e.target.value === steps ? null : +e.target.value * stepMins)}
      />
      <span className="thumb-label" style={{ left: pos(index) }}>{limitLabel(value, period)}</span>
    </div>
  )
}

/**
 * The four limits, edited together and saved together.
 *
 * The one screen in this app that does NOT persist as you go: four limits
 * half-edited are not a state worth keeping, and a parent dragging the weekly
 * cap past the daily one on the way to where they meant it should not have
 * that stick.
 */
function QuotaDialog({ title, note, limits, periods = QUOTA_PERIODS, onSave, onClear, onCancel }) {
  const [draft, setDraft] = useState(limits)
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" onClick={onCancel}>
        <div className="modal-dialog modal-dialog-centered" onClick={e => e.stopPropagation()}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">{title}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onCancel} />
            </div>
            <div className="modal-body">
              {note && <p className="text-secondary small">{note}</p>}
              {periods.map(period => {
                const { key, label, hint, maxMins, stepMins } = period
                return (
                <div key={key} className="mb-3">
                  <div className="d-flex align-items-baseline gap-2">
                    <span>{label}</span>
                    <span className="text-secondary small">{hint}</span>
                    <span className="ms-auto fw-semibold text-nowrap">
                      {draft[key] == null ? 'no limit' : limitLabel(draft[key], period)}
                    </span>
                  </div>
                  <LimitSlider
                    label={label}
                    period={period}
                    value={draft[key]}
                    maxMins={maxMins}
                    stepMins={stepMins}
                    onChange={v => setDraft(prev => ({ ...prev, [key]: v }))}
                  />
                </div>
                )
              })}
            </div>
            <div className="modal-footer">
              {onClear && (
                <button type="button" className="btn btn-outline-light me-auto" onClick={onClear}>
                  Remove override
                </button>
              )}
              <button type="button" className="btn btn-secondary" onClick={onCancel}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={() =>
                  onSave(
                    Object.fromEntries(
                      periods.map(p => [p.key, draft[p.key] != null && draft[p.key] >= p.maxMins ? null : draft[p.key]]),
                    ),
                  )
                }
              >
                <i className="fa-sharp-duotone fa-regular fa-check me-2" />
                Save
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  )
}

/** "1h a day · 5h a week", or "no limits". */
function limitsSummary(limits) {
  const said = QUOTA_PERIODS.filter(({ key }) => limits[key] != null).map(
    ({ key, label }) => `${fmtMins(limits[key])} ${label.toLowerCase()}`,
  )
  return said.length ? said.join(' · ') : 'no limits'
}

// no reset button: raising a limit above what is used grants time, and every
// period rolls over on its own
function QuotaRow({ store, watchStore }) {
  const [editing, setEditing] = useState(null) // 'standing' | 'today'
  const settings = store.settings
  const today = activeDayOverride(settings)
  const bonus = activeBonusMins(settings)
  const inForce = effectiveQuota(settings)
  const { secsLeft } = quotaState(settings, watchStore)

  return (
    <div className="mb-4">
      <div className="d-flex align-items-center gap-3">
        <span className="text-secondary text-nowrap">
          <i className="fa-sharp-duotone fa-regular fa-stopwatch me-2" />
          Quota
        </span>
        <span className="flex-grow-1" style={{ minWidth: 0 }}>
          {limitsSummary(settings.quota)}
        </span>
        <button
          type="button"
          className="btn btn-sm btn-outline-secondary flex-shrink-0"
          aria-label="Edit quota"
          onClick={() => setEditing('standing')}
        >
          <i className="fa-sharp-duotone fa-regular fa-pencil" />
        </button>
      </div>

      <div className="text-secondary small mt-1">
        {Number.isFinite(secsLeft) ? `${fmtMins(Math.max(0, Math.ceil(secsLeft / 60)))} left right now` : 'no limit'}
      </div>

      {today && (
        // red, because it is not what the settings above say: a limit that
        // silently differs from the one on screen is the confusing kind
        <div className="d-flex align-items-center gap-2 mt-2 text-danger">
          <i className="fa-sharp-duotone fa-regular fa-triangle-exclamation" />
          <span className="flex-grow-1" style={{ minWidth: 0 }}>
            Today is overridden — {limitsSummary(inForce)}
            {bonus > 0 && ` (+${fmtMins(bonus)} granted)`}
          </span>
          <button
            type="button"
            className="btn btn-sm btn-outline-danger flex-shrink-0"
            aria-label="Edit today’s quota"
            onClick={() => setEditing('today')}
          >
            <i className="fa-sharp-duotone fa-regular fa-pencil" />
          </button>
        </div>
      )}

      {!today && (
        <button
          type="button"
          className="btn btn-link btn-sm text-secondary ps-0"
          onClick={() => setEditing('today')}
        >
          override for today only
        </button>
      )}

      {editing === 'standing' && (
        <QuotaDialog
          title="Quota"
          limits={settings.quota}
          onSave={limits => {
            store.setQuota(limits)
            setEditing(null)
          }}
          onCancel={() => setEditing(null)}
        />
      )}
      {editing === 'today' && (
        <QuotaDialog
          title="Today only"
          note="These limits replace the ones above until midnight, then stop existing."
          // only the periods a DAY can speak for: it cannot redraw a week or a
          // month, and the standing ones keep applying underneath
          periods={QUOTA_PERIODS.filter(({ key }) => key === 'per6h' || key === 'perDay')}
          limits={today?.limits ?? settings.quota}
          onSave={limits => {
            store.setDayLimits(limits)
            setEditing(null)
          }}
          onClear={
            today
              ? () => {
                  store.clearDayOverride()
                  setEditing(null)
                }
              : undefined
          }
          onCancel={() => setEditing(null)}
        />
      )}
    </div>
  )
}

/**
 * The length range: two thumbs on the 0…2h scale, in 15-minute steps, with
 * "any" at both ends — no floor on the left, no ceiling on the right.
 *
 * Built on the same pointer surface as the age slider (the native thumbs
 * cannot be trusted to stay reachable once two of them meet), stepping over
 * LENGTH_STOPS by index rather than by minutes so the last stop can be
 * "no ceiling" rather than a number.
 */
function VideoLengthSlider({ value: [minMins, maxMins], onChange }) {
  const track = useRef(null)
  const grabbed = useRef(null)
  const last = LENGTH_STOPS.length - 1
  const lo = lengthIndex(minMins)
  const hi = lengthIndex(maxMins)
  const pos = i => `calc(${i / last} * (100% - 44px) + 22px)`

  const indexAt = clientX => {
    const r = track.current.getBoundingClientRect()
    const t = (clientX - r.left - 22) / Math.max(1, r.width - 44)
    return Math.round(Math.min(1, Math.max(0, t)) * last)
  }
  const apply = (end, v) => {
    const [nextLo, nextHi] = clampLengthRange([lo, hi], end, v)
    onChange([LENGTH_STOPS[nextLo], Number.isFinite(LENGTH_STOPS[nextHi]) ? LENGTH_STOPS[nextHi] : null])
  }

  const onPointerDown = e => {
    const v = indexAt(e.clientX)
    grabbed.current = grabEnd(v, lo, hi)
    e.currentTarget.setPointerCapture(e.pointerId)
    if (grabbed.current !== 'pending') apply(grabbed.current, v)
  }
  const onPointerMove = e => {
    if (!grabbed.current) return
    const v = indexAt(e.clientX)
    if (grabbed.current === 'pending') {
      if (v === lo) return
      grabbed.current = v > hi ? 'hi' : 'lo'
    }
    apply(grabbed.current, v)
  }
  const release = () => {
    grabbed.current = null
  }

  return (
    <div className="dual-slider quota-slider flex-grow-1" ref={track}>
      <input
        type="range"
        min="0"
        max={last}
        value={lo}
        aria-label="Shortest video"
        onChange={e => apply('lo', +e.target.value)}
      />
      <input
        type="range"
        min="0"
        max={last}
        value={hi}
        aria-label="Longest video"
        onChange={e => apply('hi', +e.target.value)}
      />
      {/* MINUTES ONLY, inside the thumb: "1h 15m" beside "1h" was two wide
          labels colliding whenever the ends came close. A bare number is
          three characters at its worst and fits the thumb it belongs to. */}
      <span className="thumb-label" style={{ left: pos(lo) }}>{minuteLabel(LENGTH_STOPS[lo])}</span>
      <span className="thumb-label" style={{ left: pos(hi) }}>{minuteLabel(LENGTH_STOPS[hi])}</span>
      <div
        className="slider-surface"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={release}
        onPointerCancel={release}
      />
    </div>
  )
}

function VideoLengthRow({ value: [minMins, maxMins], onChange }) {
  return (
    <div className="mb-4">
      {/* the values live HERE, not inside the thumbs: two thumbs that meet
          used to print their labels on top of each other, and a slider with
          the room to be dragged is worth more than one with words in it */}
      <div className="d-flex align-items-center gap-3 mb-1">
        <span
          className="text-secondary text-nowrap"
          title="Show only videos between these lengths — either end can be “any”"
        >
          <i className="fa-duotone fa-solid fa-video-arrow-up-right me-2" />
          Video Length
        </span>
      </div>
      <VideoLengthSlider value={[minMins, maxMins]} onChange={onChange} />
    </div>
  )
}

// enough of a stored key to recognize it without exposing the whole thing;
// one dot per hidden character so the preview keeps the key's real length
const maskKey = k => `${k.slice(0, 6)}${'•'.repeat(k.length - 10)}${k.slice(-4)}`

function ApiKeyRow({ apiKey, onChange }) {
  const [confirming, setConfirming] = useState(false)
  const [focused, setFocused] = useState(false)
  const [check, setCheck] = useState(null) // null | 'busy' | 'ok' | error message
  // masked preview only when idle; while editing the password dots take over.
  // short strings are left unmasked — slices would overlap and it's not a key
  const masked = !focused && apiKey.length > 12

  // validate as soon as a key lands in the field (keys are usually pasted, so
  // the 500ms debounce mostly guards slow typists; i18nLanguages = 1 quota
  // unit per check). Also re-verifies a saved key on every Settings visit.
  useEffect(() => {
    if (!apiKey) {
      setCheck(null)
      return
    }
    let stale = false
    const timer = setTimeout(async () => {
      setCheck('busy')
      try {
        await validateApiKey(apiKey)
        if (!stale) setCheck('ok')
      } catch (err) {
        if (!stale) setCheck(`This API key did not work: ${err.message}`)
      }
    }, 500)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [apiKey])

  return (
    <div className="mb-4">
      <div className="d-flex align-items-center gap-3">
        <span
          className="text-secondary text-nowrap"
          title="Required only for adding new channels"
        >
          <i className="fa-sharp-duotone fa-regular fa-key me-2" />
          <a href={API_CONSOLE_URL} target="_blank" rel="noreferrer">YouTube API Key</a>
        </span>
        {/* real <form> + username/current-password hints so the browser's
            password manager offers to save the key; Save submits it via
            form="api-key-form" and preventDefault keeps the SPA in place */}
        <form
          id="api-key-form"
          className="d-flex align-items-center gap-3 flex-grow-1"
          onSubmit={e => e.preventDefault()}
        >
          <input type="text" name="username" value="youtube-api-key" autoComplete="username" readOnly hidden />
          <div className="position-relative flex-grow-1">
            <input
              type="password"
              name="api-key"
              className="form-control"
              placeholder="AIza… (needed to add channels)"
              value={apiKey}
              onChange={e => {
                setCheck(null)
                onChange(e.target.value)
              }}
              // validation is automatic; preventDefault stops the implicit
              // form submission from clicking the Save button
              onKeyDown={e => {
                if (e.key === 'Enter') e.preventDefault()
              }}
              onFocus={() => setFocused(true)}
              onBlur={() => setFocused(false)}
              // dots hidden under the masked preview; still a password input
              // so the browser's save-key prompt keeps working
              style={masked ? { color: 'transparent' } : undefined}
              autoComplete="current-password"
            />
            {masked && (
              <span
                className="position-absolute top-50 translate-middle-y pe-none font-monospace"
                style={{ left: '0.75rem' }}
              >
                {maskKey(apiKey)}
              </span>
            )}
            {check === 'busy' && (
              <span
                className="spinner-border spinner-border-sm position-absolute top-50 end-0 translate-middle-y me-2"
                role="status"
              />
            )}
            {check === 'ok' && (
              <i
                className="fa-sharp-duotone fa-regular fa-check text-success position-absolute top-50 end-0 translate-middle-y me-2"
                aria-label="API key is valid"
              />
            )}
          </div>
        </form>
        {apiKey && (
          <button
            type="button"
            className="btn btn-outline-danger btn-sm"
            aria-label="Delete API key"
            onClick={() => setConfirming(true)}
          >
            <i className="fa-sharp-duotone fa-regular fa-trash" />
          </button>
        )}
      </div>
      {check && check !== 'busy' && check !== 'ok' && <div className="alert alert-warning mt-2 py-2">{check}</div>}
      {confirming && (
        <ConfirmModal
          title="Delete API key?"
          body="You won't be able to search for or add channels until you enter a new key."
          onConfirm={() => {
            onChange('')
            setConfirming(false)
          }}
          onCancel={() => setConfirming(false)}
        />
      )}
    </div>
  )
}

function SearchRow({ apiKey, store, db }) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  // inline autocomplete: debounced 500ms, min 3 chars (search.list = 100
  // quota units per fired query, so don't search every keystroke)
  useEffect(() => {
    const q = query.trim()
    if (!apiKey || q.length < 3) {
      setResults([])
      setError(null)
      return
    }
    let stale = false
    const timer = setTimeout(async () => {
      setBusy(true)
      try {
        const found = looksLikeLink(q) ? [await resolveChannel(apiKey, q)] : await searchChannels(apiKey, q)
        if (!stale) {
          setResults(found)
          setError(null)
        }
      } catch (err) {
        if (!stale) {
          setResults([])
          setError(err.message)
        }
      } finally {
        if (!stale) setBusy(false)
      }
    }, 500)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [query, apiKey])

  return (
    <div className="mb-4">
      <div className="d-flex align-items-center gap-3">
        <span className="text-secondary text-nowrap">
          <i className="fa-brands fa-youtube me-2" />
          Add Channel
        </span>
        <div className="position-relative flex-grow-1">
          <input
            type="text"
            className="form-control"
            placeholder={apiKey ? 'Channel name, @handle, or URL' : 'Enter an API key above first'}
            disabled={!apiKey}
            value={query}
            onChange={e => setQuery(e.target.value)}
          />
          {busy && (
            <span
              className="spinner-border spinner-border-sm position-absolute top-50 end-0 translate-middle-y me-2"
              role="status"
            />
          )}
        </div>
      </div>
      {error && <div className="alert alert-warning mt-2 py-2">{error}</div>}
      {results.map(ch => {
        const isCustom = store.settings.customChannels.some(c => c.channel_id === ch.channel_id)
        const isCurated = (db?.channels ?? []).some(c => c.channel_id === ch.channel_id)
        // a deleted (hidden) curated channel counts as not added: Add un-hides
        // it, since adding it as custom would be a no-op (curated wins on merge)
        const added = isCustom || (isCurated && !store.settings.overrides[ch.channel_id]?.hidden)
        return (
          <div key={ch.channel_id} className="d-flex align-items-center gap-3 bg-body-tertiary rounded p-2 mt-2">
            <img src={ch.thumbnail} alt="" width="36" height="36" className="rounded-circle" />
            <span className="fw-semibold text-truncate">{ch.channel_title}</span>
            <TopicBadges ch={ch} />
            <div className="ms-auto">
              <StatsCell ch={ch} />
            </div>
            {added ? (
              // same semantics as the table's delete: drop custom, hide curated
              <button
                type="button"
                className="btn btn-outline-danger btn-sm"
                onClick={() => {
                  if (isCustom) {
                    store.removeCustomChannel(ch.channel_id)
                    evictChannelCache(ch.channel_id)
                  } else {
                    store.setOverride(ch.channel_id, { hidden: true })
                  }
                }}
              >
                <i className="fa-sharp-duotone fa-regular fa-trash me-1" />
                Remove
              </button>
            ) : (
              <button
                type="button"
                className="btn btn-danger btn-sm"
                onClick={() => {
                  if (isCurated) store.setOverride(ch.channel_id, { hidden: false })
                  else {
                    // the search already showed the parent this channel's name
                    // and avatar; seeding them means the row it becomes is not
                    // a bare id until the Worker has been asked
                    seedChannelMeta(ch)
                    store.addCustomChannel({ ...ch, min_age: 1, max_age: 15 })
                  }
                  setQuery('')
                }}
              >
                <i className="fa-sharp-duotone fa-regular fa-plus me-1" />
                Add
              </button>
            )}
          </div>
        )
      })}
    </div>
  )
}

// the API's canonical topicCategories plus the COPPA made-for-kids flag
function TopicBadges({ ch }) {
  const labels = ch.topics ?? []
  if (!labels.length && !ch.made_for_kids) return null
  return (
    <div className="d-flex flex-wrap gap-1" title={labels.join(', ')}>
      {ch.made_for_kids && (
        <span className="badge text-bg-success fw-normal">
          <i className="fa-duotone fa-solid fa-child me-1" />
          made for kids
        </span>
      )}
      {labels.slice(0, 3).map(t => (
        <span key={t} className="badge text-bg-secondary fw-normal">{t}</span>
      ))}
    </div>
  )
}

function StatsCell({ ch }) {
  const stats = [
    [ch.subscribers, 'fa-users', 'subscribers'],
    [ch.video_count, 'fa-clapperboard', 'videos'],
    [ch.view_count, 'fa-eye', 'views'],
  ].filter(([n]) => n)
  return (
    <div className="text-secondary small text-nowrap">
      {stats.map(([n, icon, label]) => (
        <div key={label} title={`${n.toLocaleString('en')} ${label}`}>
          <i className={`fa-sharp-duotone fa-regular ${icon} me-1`} />
          {formatCount(n)} {label}
        </div>
      ))}
    </div>
  )
}

// per-channel edits land on the custom channel object itself or in the
// curated channel's override, depending on where the row came from
const channelPatcher = (ch, store) =>
  ch.custom
    ? patch => store.updateCustomChannel(ch.channel_id, patch)
    : patch => store.setOverride(ch.channel_id, patch)

/** "1.2M subscribers · 340 videos · 89M views", or nothing at all. Under the
 * name rather than in a column of its own, and unlabelled: a row of numbers
 * with units does not need the word "stats" over it. */
function StatsLine({ ch }) {
  const parts = [
    [ch.subscribers, 'subscribers'],
    [ch.video_count, 'videos'],
    [ch.view_count, 'views'],
  ].filter(([n]) => n)
  if (!parts.length) return null
  return (
    <div className="text-secondary small text-truncate">
      {parts.map(([n, label]) => `${formatCount(n)} ${label}`).join(' · ')}
    </div>
  )
}

/** One channel: avatar, name, its stats beneath, the age range as a chip, and
 * the two things a parent does to it. */
function ChannelRow({ ch, store, selected, onToggle, onEdit, onDelete }) {
  const avatar = ch.thumbnail ?? ch.videos?.[0]?.thumbnail
  const out = !overlaps(effectiveAgeRange(store.settings), ch.min_age, ch.max_age)
  return (
    <div className={`channel-row d-flex align-items-center gap-2 py-2 ${out ? 'out-of-range' : ''}`}>
      <input
        type="checkbox"
        className="form-check-input flex-shrink-0 m-0"
        checked={selected}
        aria-label={`Select ${ch.channel_title}`}
        onChange={onToggle}
      />
      {avatar ? (
        <img src={avatar} alt="" width="36" height="36" className="rounded-circle object-fit-cover flex-shrink-0" />
      ) : (
        <i className="fa-duotone fa-regular fa-tv-retro fs-5 text-secondary flex-shrink-0" />
      )}
      {/* min-width:0 is what lets the truncation happen instead of the row
          growing wider than the phone */}
      <div className="flex-grow-1" style={{ minWidth: 0 }}>
        <a
          href={channelUrl(ch)}
          target="_blank"
          rel="noreferrer"
          className="fw-semibold text-truncate d-block text-decoration-none"
        >
          {ch.channel_title}
        </a>
        <StatsLine ch={ch} />
        <TopicBadges ch={ch} />
      </div>
      <button
        type="button"
        className="btn btn-sm btn-outline-secondary flex-shrink-0 text-nowrap"
        aria-label={`Ages for ${ch.channel_title}`}
        onClick={onEdit}
      >
        {ch.min_age}–{ch.max_age}
        <i className="fa-sharp-duotone fa-regular fa-pencil ms-2" />
      </button>
      <button
        type="button"
        className="btn btn-sm btn-outline-danger flex-shrink-0"
        aria-label={`Delete ${ch.channel_title}`}
        onClick={onDelete}
      >
        <i className="fa-sharp-duotone fa-regular fa-trash" />
      </button>
    </div>
  )
}

/** The ages for one channel, in a dialog rather than in the row: a 1-15 dual
 * slider needs more width than a phone has left over beside a name. */
function AgeDialog({ ch, store, onClose }) {
  const save = channelPatcher(ch, store)
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" onClick={onClose}>
        <div className="modal-dialog modal-dialog-centered" onClick={e => e.stopPropagation()}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title text-truncate">{ch.channel_title}</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onClose} />
            </div>
            <div className="modal-body">
              <div className="text-secondary mb-2">Show this channel to ages</div>
              <DualAgeSlider
                value={[ch.min_age, ch.max_age]}
                onChange={([min_age, max_age]) => save({ min_age, max_age })}
              />
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-danger" onClick={onClose}>
                Done
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  )
}

/**
 * The approved channels, as a LIST rather than a table.
 *
 * Every column this used to have has gone somewhere better: the enable
 * checkbox is gone entirely (an approved channel is on), the stats sit under
 * the name, the ages live behind the pencil, and the group is the SECTION a
 * channel is drawn in rather than a cell repeating its name on every row.
 * What is left fits a phone without scrolling sideways, which a six-column
 * table with a dual slider in it never could.
 */
function ChannelList({ db, customById = {}, store }) {
  const { customChannels, overrides, groups, groupOf } = store.settings
  const ageRange = effectiveAgeRange(store.settings)
  const [selected, setSelected] = useState(new Set())
  const [naming, setNaming] = useState(false)
  const [editing, setEditing] = useState(null)
  const [deleting, setDeleting] = useState(null)
  const hiddenCount = Object.values(overrides).filter(o => o.hidden).length

  const all = useMemo(
    () => [
      // stored rows are the decision only; the name, avatar and stats come
      // from the Worker's record for that id
      ...customChannels.map(ch => ({ ...hydrateChannel(ch, customById[ch.channel_id]), custom: true })),
      ...curatedChannels(db, overrides).filter(ch => !ch.hidden),
    ],
    [db, customChannels, overrides, customById],
  )

  // the same arrangement the child's Channels tab uses, so the two cannot
  // disagree about what a group contains or where it sits
  const sections = useMemo(() => {
    const out = []
    const inRangeFor = ch => overlaps(ageRange, ch.min_age, ch.max_age)
    for (const row of arrangeChannels(all, groups, groupOf, inRangeFor)) {
      if (row.type === 'header') out.push({ group: row.group, items: [] })
      else if (row.grouped && out.length) out[out.length - 1].items.push(row.channel)
      else {
        if (!out.length || out[out.length - 1].group) out.push({ group: null, items: [] })
        out[out.length - 1].items.push(row.channel)
      }
    }
    return out
  }, [all, groups, groupOf, ageRange])

  
  const inRange = all.filter(ch => overlaps(ageRange, ch.min_age, ch.max_age)).length

  const toggle = id =>
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const remove = ch => {
    if (ch.custom) {
      store.removeCustomChannel(ch.channel_id)
      evictChannelCache(ch.channel_id)
    } else {
      store.setOverride(ch.channel_id, { hidden: true })
    }
    setDeleting(null)
  }

  const row = ch => (
    <ChannelRow
      key={ch.channel_id}
      ch={ch}
      store={store}
      selected={selected.has(ch.channel_id)}
      onToggle={() => toggle(ch.channel_id)}
      onEdit={() => setEditing(ch)}
      onDelete={() => setDeleting(ch)}
    />
  )

  return (
    <>
      <div className="d-flex align-items-center gap-2 mb-2 flex-wrap">
        <span className="text-secondary">
          {inRange}/{all.length} shown to {store.settings.childName}
        </span>
        {selected.size > 0 && (
          <>
            <span className="text-secondary">· {selected.size} selected</span>
            <button
              type="button"
              className="btn btn-outline-light btn-sm"
              disabled={!canGroup(selected)}
              onClick={() => setNaming(true)}
            >
              <i className="fa-sharp-duotone fa-regular fa-folders me-1" />
              Group
            </button>
            {canUngroup(selected, groupOf) && (
              <button
                type="button"
                className="btn btn-outline-light btn-sm"
                onClick={() => {
                  store.ungroupChannels(selected)
                  setSelected(new Set())
                }}
              >
                Ungroup
              </button>
            )}
            <button type="button" className="btn btn-link btn-sm text-secondary" onClick={() => setSelected(new Set())}>
              Clear
            </button>
          </>
        )}
      </div>

      {sections.map((section, i) =>
        section.group ? (
          // a group is one block with its name on top — the channels in it are
          // not repeating that name in a column of their own
          <div key={section.group.id} className="channel-group mb-3">
            <button
              type="button"
              className="channel-group-title d-flex align-items-center gap-2 w-100 text-start"
              title={`Select every channel in ${section.group.name}`}
              onClick={() => setSelected(prev => new Set([...prev, ...groupMembers(section.group.id, groupOf)]))}
            >
              <i className="fa-sharp-duotone fa-regular fa-folders text-danger" />
              <span className="fw-semibold flex-grow-1">{section.group.name}</span>
              <span className="text-secondary small">{section.items.length}</span>
            </button>
            <div className="px-2">{section.items.map(row)}</div>
          </div>
        ) : (
          <div key={`loose-${i}`}>{section.items.map(row)}</div>
        ),
      )}

      {hiddenCount > 0 && (
        <button type="button" className="btn btn-link btn-sm text-secondary" onClick={() => store.restoreHidden()}>
          restore {hiddenCount} deleted built-in channel{hiddenCount > 1 ? 's' : ''}
        </button>
      )}

      {editing && (
        <AgeDialog
          ch={all.find(c => c.channel_id === editing.channel_id) ?? editing}
          store={store}
          onClose={() => setEditing(null)}
        />
      )}
      {deleting && (
        <ConfirmModal
          title={`Remove ${deleting.channel_title}?`}
          body={
            deleting.custom
              ? 'This channel goes from this child’s list. You can add it again from the search above.'
              : 'This built-in channel is hidden from this child. You can restore it at the bottom of this page.'
          }
          onConfirm={() => remove(deleting)}
          onCancel={() => setDeleting(null)}
        />
      )}
      {naming && (
        <GroupNameModal
          selected={selected}
          groups={groups}
          groupOf={groupOf}
          onConfirm={name => {
            store.groupChannels(selected, name)
            setSelected(new Set())
            setNaming(false)
          }}
          onCancel={() => setNaming(false)}
        />
      )}
    </>
  )
}

/** The group-name dialog, judging the name the way the Android one does:
 * empty-after-trim and already-taken disable Confirm rather than failing
 * after it — and "taken" forgives a group this very selection empties, whose
 * name is exactly what prefill offered. */
function GroupNameModal({ selected, groups, groupOf, onConfirm, onCancel }) {
  const [name, setName] = useState(() => prefillGroupName(selected, groups, groupOf) ?? '')
  const error = groupNameError(name, groupNamesInUse(groups, groupOf, selected))
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" onClick={onCancel}>
        <div className="modal-dialog modal-dialog-centered" onClick={e => e.stopPropagation()}>
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title">Group {selected.size} channels</h5>
              <button type="button" className="btn-close" aria-label="Close" onClick={onCancel} />
            </div>
            <div className="modal-body">
              <input
                type="text"
                className="form-control"
                placeholder="Group name"
                aria-label="Group name"
                autoFocus
                value={name}
                onChange={e => setName(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !error) onConfirm(name)
                }}
              />
              {error === 'taken' && <div className="form-text text-warning">That name is already a group.</div>}
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" onClick={onCancel}>
                Cancel
              </button>
              <button type="button" className="btn btn-danger" disabled={!!error} onClick={() => onConfirm(name)}>
                <i className="fa-sharp-duotone fa-regular fa-folders me-2" />
                Group
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  )
}
