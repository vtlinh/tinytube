import { useEffect, useMemo, useRef, useState } from 'react'
import { useReactTable, getCoreRowModel, flexRender } from '@tanstack/react-table'
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
  parseBirthdayInput,
  effectiveAgeRange,
  canGroup,
  canUngroup,
  groupMembers,
  prefillGroupName,
  groupNamesInUse,
  groupNameError,
} from './lib.js'
import { searchChannels, resolveChannel, evictChannelCache, formatCount, validateApiKey } from './youtubeApi.js'

const API_CONSOLE_URL = 'https://console.cloud.google.com/apis/library/youtube.googleapis.com'
const looksLikeLink = s => /^@|^UC[0-9A-Za-z_-]{22}$|youtube\.com/.test(s.trim())
const channelUrl = ch => ch.source_url ?? `https://www.youtube.com/channel/${ch.channel_id}`

export default function Settings({ db, store, watchStore, sync, onDone }) {
  // every valid change persists the moment it is made — no draft, no Save
  // button; half-typed values are held locally by their rows (see BirthdayRow)
  // and only committed once they parse
  const settings = store.settings
  // 'main' | 'channels' — channel management is its own page
  const [page, setPage] = useState('main')

  return (
    <div className="settings container-xl py-4">
      {/* explicit back button: iOS standalone PWAs have no browser chrome or
          hardware back, so without it a no-change visit would strand you here.
          flex: 1 sides keep the title truly centered */}
      <div className="d-flex align-items-center gap-3 mb-4">
        <div style={{ flex: 1 }}>
          <button
            type="button"
            className="btn btn-outline-secondary"
            aria-label={page === 'channels' ? 'Back to settings' : 'Back to gallery'}
            onClick={page === 'channels' ? () => setPage('main') : onDone}
          >
            <i className="fa-sharp-duotone fa-regular fa-arrow-left" />
          </button>
        </div>
        <h1 className="fs-3 fw-bold m-0">{page === 'channels' ? 'Channels' : 'Parents Mode'}</h1>
        <div className="d-flex align-items-center justify-content-end gap-3" style={{ flex: 1 }}>
          <SyncMenu sync={sync} />
        </div>
      </div>

      {page === 'main' ? (
        <>
          <BirthdayRow value={settings.birthday} onChange={store.setBirthday} />
          <QuotaRow value={settings.quotaMins} onChange={store.setQuotaMins} watchStore={watchStore} />
          <MinLengthRow value={settings.minVideoMins} onChange={store.setMinVideoMins} />
          <ApiKeyRow apiKey={settings.apiKey} onChange={store.setApiKey} />
          <div className="mb-4 d-flex align-items-center gap-3">
            <span className="text-secondary text-nowrap">
              <i className="fa-brands fa-youtube me-2" />
              Channels
            </span>
            <button type="button" className="btn btn-outline-light" onClick={() => setPage('channels')}>
              Manage channels
              <i className="fa-sharp-duotone fa-regular fa-chevron-right ms-2" />
            </button>
          </div>
          {/* the About position, like the Android app: the bottom of settings */}
          <div className="text-center mt-5">
            <VersionLink />
          </div>
        </>
      ) : (
        <>
          <SearchRow apiKey={settings.apiKey} store={store} db={db} />
          <ChannelTable db={db} store={store} />
        </>
      )}
    </div>
  )
}

function ConfirmModal({ title, body, onConfirm, onCancel }) {
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
                <i className="fa-sharp-duotone fa-regular fa-trash me-2" />
                Delete
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
 * Cross-device sync, keyed by the parent's Google account, tucked behind the
 * header's three-dot menu and worded plainly: "Sign in" / "Sign out".
 * Immediate-effect like passkey enrollment, NOT part of the draft: signing in
 * starts a pull that may rewrite settings, which a draft would silently
 * clobber on Save. Renders nothing until an OAuth client id is configured.
 *
 * The sign-in itself is still Google underneath — the menu item triggers the
 * One Tap prompt, and when the browser suppresses that (FedCM cooldown,
 * blocked third-party cookies) it falls through to clicking an invisible
 * rendered Google button, whose popup flow always works.
 */
function SyncMenu({ sync = {} }) {
  const { session, signIn, signOut } = sync
  const [open, setOpen] = useState(false)
  const [error, setError] = useState(null)
  const hiddenBtn = useRef(null)
  const ready = useRef(false)

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

  if (!GOOGLE_CLIENT_ID) return null
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
          <div className="dropdown-menu dropdown-menu-end show position-absolute end-0 mt-1" style={{ zIndex: 1050 }}>
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
          </div>
        </>
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

/* "Version N", the way the Android app's About words it — the deploy's run
   number, not a commit hash a parent can't do anything with. */
function VersionLink() {
  const version = typeof __BUILD_VERSION__ !== 'undefined' ? __BUILD_VERSION__ : ''
  if (!version) return null
  return <span className="text-secondary small text-nowrap">Version {version}</span>
}

function DualAgeSlider({ value: [lo, hi], onChange }) {
  const pos = v => `calc(${(v - 1) / 14} * (100% - 32px) + 16px)`
  return (
    <div className="dual-slider flex-grow-1">
      <input
        type="range"
        min="1"
        max="15"
        value={lo}
        aria-label="Youngest age"
        onChange={e => onChange([Math.min(+e.target.value, hi), hi])}
      />
      <input
        type="range"
        min="1"
        max="15"
        value={hi}
        aria-label="Oldest age"
        onChange={e => onChange([lo, Math.max(+e.target.value, lo)])}
      />
      <span className="thumb-label" style={{ left: pos(lo) }}>{lo}</span>
      <span className="thumb-label" style={{ left: pos(hi) }}>{hi}</span>
    </div>
  )
}

/** The child's BIRTHDAY (mm/yy, born the 1st of that month by declaration)
 * rather than an age: the computed age keeps up on its own instead of going
 * stale a birthday later. The channel filter uses it as a single-point range —
 * see effectiveAgeRange. */
/* Bare digits get their slash typed for them: "1217" becomes "12/17" on the
   fourth keystroke. A hand-typed slash is left alone (so "1/17" still works),
   and anything else is stripped. */
function formatBirthdayText(raw) {
  const cleaned = raw.replace(/[^\d/]/g, '')
  if (cleaned.includes('/')) return cleaned
  return cleaned.length > 2 ? `${cleaned.slice(0, 2)}/${cleaned.slice(2, 4)}` : cleaned
}

function BirthdayRow({ value, onChange }) {
  // local text so a half-typed date doesn't thrash the draft; committed on
  // every keystroke that parses, cleared when emptied
  const [text, setText] = useState(value ? `${value.slice(5, 7)}/${value.slice(2, 4)}` : '')
  const age = ageFromBirthday(value)
  const bad = text.trim() !== '' && parseBirthdayInput(text) == null
  return (
    <div className="d-flex align-items-center gap-3 mb-4">
      <span
        className="text-secondary text-nowrap"
        title="Your child's birthday (mm/yy) — only channels rated for their age are shown"
      >
        <i className="fa-duotone fa-solid fa-children me-2" />
        Birthday
      </span>
      <input
        type="text"
        inputMode="numeric"
        className={`form-control w-auto ${bad ? 'is-invalid' : ''}`}
        style={{ maxWidth: '7rem' }}
        placeholder="mm/yy"
        aria-label="Child's birthday, month and two-digit year"
        value={text}
        onChange={e => {
          const formatted = formatBirthdayText(e.target.value)
          setText(formatted)
          onChange(formatted.trim() === '' ? null : (parseBirthdayInput(formatted) ?? value))
        }}
      />
      {age != null && <span className="text-secondary text-nowrap">{age} year{age === 1 ? '' : 's'} old</span>}
    </div>
  )
}

const QUOTA_MAX_MINS = 240 // 4h, in 15-min steps
const fmtClock = mins => (mins ? `${Math.floor(mins / 60)}:${String(mins % 60).padStart(2, '0')}` : '0')

/** Single-thumb sibling of DualAgeSlider on the same track CSS; the fill span
 * shows time already used on the shared 0-4h scale. The thumb is 44px (not the
 * age slider's 32px) so the h:mm label inside it stays readable. */
function QuotaSlider({ value, usedMins, onChange }) {
  const pos = v => `calc(${v / QUOTA_MAX_MINS} * (100% - 44px) + 22px)`
  return (
    <div className="dual-slider quota-slider flex-grow-1">
      {usedMins > 0 && <span className="track-fill" style={{ width: pos(Math.min(usedMins, QUOTA_MAX_MINS)) }} />}
      <input
        type="range"
        min="0"
        max={QUOTA_MAX_MINS}
        step="15"
        value={value}
        aria-label="Watch quota"
        onChange={e => onChange(+e.target.value)}
      />
      <span className="thumb-label" style={{ left: pos(value) }}>{fmtClock(value)}</span>
    </div>
  )
}

// no reset button: dragging the quota above what's used grants time, and the
// 12h window expiry clears usage on its own
function QuotaRow({ value, onChange, watchStore }) {
  // statsUsage folds in what the other synced devices watched
  const stats = usageStats(statsUsage(watchStore))
  const cols = [
    ['Session', stats.session, `Watched in the current ${QUOTA_WINDOW_MS / 3600_000}h quota window`],
    ['24Hr', stats.last24h, 'Watched in the last 24 hours'],
    ['WTD', stats.wtd, 'Week to date (since Sunday)'],
    ['MTD', stats.mtd, 'Month to date'],
    ['YTD', stats.ytd, 'Year to date'],
  ]
  return (
    // flex-wrap + the slider's min-width: stats sit inline on wide screens
    // and wrap below the slider on phones instead of crushing it
    <div className="d-flex align-items-center gap-3 flex-wrap mb-4">
      <span
        className="text-secondary text-nowrap"
        title={`Set the viewing limit (resets every ${QUOTA_WINDOW_MS / 3600_000} hrs)`}
      >
        <i className="fa-sharp-duotone fa-regular fa-stopwatch me-2" />
        Quota
      </span>
      <QuotaSlider value={value} usedMins={usedSecs(watchStore) / 60} onChange={onChange} />
      <table className="table table-dark table-borderless table-sm w-auto small text-nowrap m-0">
        <tbody>
          <tr className="text-secondary">
            {cols.map(([label, , hover]) => (
              <td key={label} className="py-0 px-2 text-center" title={hover}>{label}</td>
            ))}
          </tr>
          <tr>
            {cols.map(([label, secs, hover]) => (
              <td key={label} className="py-0 px-2 text-center" title={hover}>{fmtMins(Math.round(secs / 60))}</td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  )
}

const MIN_LENGTH_MAX_MINS = 60 // 1h, in 5-min steps

/** Same 44px-thumb track as QuotaSlider, but no usage fill — the thumb label
 * is the minimum video length; 0 means show everything. */
function MinLengthSlider({ value, onChange }) {
  const pos = v => `calc(${v / MIN_LENGTH_MAX_MINS} * (100% - 44px) + 22px)`
  return (
    <div className="dual-slider quota-slider flex-grow-1">
      <input
        type="range"
        min="0"
        max={MIN_LENGTH_MAX_MINS}
        step="5"
        value={value}
        aria-label="Minimum video length"
        onChange={e => onChange(+e.target.value)}
      />
      {/* ">" marks it as a floor; at 0 nothing is filtered, so "all" */}
      <span className="thumb-label" style={{ left: pos(value) }}>{value ? `>${fmtMins(value)}` : 'all'}</span>
    </div>
  )
}

function MinLengthRow({ value, onChange }) {
  return (
    <div className="d-flex align-items-center gap-3 mb-4">
      <span
        className="text-secondary text-nowrap"
        title="Hide videos shorter than this — keeps quick-hit clips out of the gallery (0m shows everything)"
      >
        <i className="fa-duotone fa-solid fa-video-arrow-up-right me-2" />
        Video Length
      </span>
      <MinLengthSlider value={value} onChange={onChange} />
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
                  else store.addCustomChannel({ ...ch, min_age: 1, max_age: 15 })
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

function ChannelAgeSlider({ ch, store }) {
  const save = channelPatcher(ch, store)
  return (
    <DualAgeSlider
      value={[ch.min_age, ch.max_age]}
      onChange={([min_age, max_age]) => save({ min_age, max_age })}
    />
  )
}

/** Quick on/off without deleting; the age filter still applies independently. */
function EnabledCheckbox({ ch, store }) {
  const save = channelPatcher(ch, store)
  return (
    <input
      type="checkbox"
      className="form-check-input"
      checked={!ch.disabled}
      aria-label={`Enable ${ch.channel_title}`}
      onChange={e => save({ disabled: !e.target.checked })}
    />
  )
}

function ChannelTable({ db, store }) {
  const { customChannels, overrides, groups, groupOf } = store.settings
  const hiddenCount = Object.values(overrides).filter(o => o.hidden).length
  // ticked rows, for grouping; a Set of channel ids
  const [selected, setSelected] = useState(new Set())
  const [naming, setNaming] = useState(false)

  const data = useMemo(
    () =>
      [
        ...customChannels.map(ch => ({ ...ch, custom: true })),
        ...curatedChannels(db, overrides).filter(ch => !ch.hidden),
      ].sort((a, b) => b.min_age - a.min_age || b.max_age - a.max_age),
    [db, customChannels, overrides],
  )

  const ageRange = effectiveAgeRange(store.settings)
  const inRange = data.filter(ch => !ch.disabled && overlaps(ageRange, ch.min_age, ch.max_age)).length

  const toggle = id =>
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const groupNameOf = id => groups.find(g => g.id === groupOf[id])?.name

  const columns = useMemo(
    () => [
      {
        id: 'select',
        header: '',
        cell: ({ row }) => (
          <input
            type="checkbox"
            className="form-check-input"
            checked={selected.has(row.original.channel_id)}
            aria-label={`Select ${row.original.channel_title}`}
            onChange={() => toggle(row.original.channel_id)}
          />
        ),
      },
      {
        id: 'enabled',
        header: '',
        cell: ({ row }) => <EnabledCheckbox ch={row.original} store={store} />,
      },
      {
        header: `Channel (${inRange}/${data.length})`,
        accessorKey: 'channel_title',
        cell: ({ row }) => {
          const ch = row.original
          const avatar = ch.thumbnail ?? ch.videos?.[0]?.thumbnail
          return (
            <a
              href={channelUrl(ch)}
              target="_blank"
              rel="noreferrer"
              className="fw-semibold d-inline-flex align-items-center gap-2"
            >
              {avatar && <img src={avatar} alt="" width="36" height="36" className="rounded-circle object-fit-cover" />}
              {ch.channel_title}
            </a>
          )
        },
      },
      {
        header: 'Stats',
        id: 'stats',
        cell: ({ row }) => <StatsCell ch={row.original} />,
      },
      {
        header: 'Topics',
        id: 'topics',
        cell: ({ row }) => <TopicBadges ch={row.original} />,
      },
      {
        header: 'Group',
        id: 'group',
        cell: ({ row }) => {
          const name = groupNameOf(row.original.channel_id)
          if (!name) return null
          return (
            // tapping the badge selects the whole group — a header's tap, table-shaped
            <button
              type="button"
              className="badge text-bg-secondary border-0 fw-normal"
              title={`Select every channel in ${name}`}
              onClick={() =>
                setSelected(prev => new Set([...prev, ...groupMembers(groupOf[row.original.channel_id], groupOf)]))
              }
            >
              <i className="fa-sharp-duotone fa-regular fa-folders me-1" />
              {name}
            </button>
          )
        },
      },
      {
        header: 'Age',
        id: 'ages',
        cell: ({ row }) => <ChannelAgeSlider ch={row.original} store={store} />,
      },
      {
        id: 'delete',
        header: '',
        cell: ({ row }) => (
          <button
            type="button"
            className="btn btn-outline-danger btn-sm"
            aria-label={`Delete ${row.original.channel_title}`}
            onClick={() => {
              const ch = row.original
              if (ch.custom) {
                store.removeCustomChannel(ch.channel_id)
                evictChannelCache(ch.channel_id)
              } else {
                store.setOverride(ch.channel_id, { hidden: true })
              }
            }}
          >
            <i className="fa-sharp-duotone fa-regular fa-trash" />
          </button>
        ),
      },
    ],
    [store, data, inRange, selected, groups, groupOf],
  )

  const table = useReactTable({ data, columns, getCoreRowModel: getCoreRowModel() })

  return (
    <>
      {/* the grouping toolbar, live once two rows are ticked — the Android
          approved-list's Group/Ungroup, table-shaped. Ungroup only when the
          whole selection sits in ONE group (see canUngroup). */}
      {selected.size > 0 && (
        <div className="d-flex align-items-center gap-2 mb-2">
          <span className="text-secondary small">{selected.size} selected</span>
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
        </div>
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
      {/* the ages column reserves real width so the 1-15 dual slider stays as
          readable as the global one up top; narrow screens scroll horizontally
          via table-responsive instead of crushing the track */}
      <div className="table-responsive">
        <table className="table table-dark align-middle">
          <thead>
            {table.getHeaderGroups().map(hg => (
              <tr key={hg.id}>
                {hg.headers.map(h => (
                  <th
                    key={h.id}
                    className="text-secondary fw-normal"
                    style={h.column.id === 'ages' ? { width: '55%', minWidth: 420 } : undefined}
                  >
                    {flexRender(h.column.columnDef.header, h.getContext())}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map(row => (
              <tr
                key={row.id}
                className={
                  !row.original.disabled && overlaps(ageRange, row.original.min_age, row.original.max_age)
                    ? undefined
                    : 'out-of-range' // hidden from the kid: age-filtered or toggled off
                }
              >
                {row.getVisibleCells().map(cell => (
                  <td key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {hiddenCount > 0 && (
        <button
          type="button"
          className="btn btn-link btn-sm text-secondary"
          onClick={() => store.restoreHidden()}
        >
          restore {hiddenCount} deleted built-in channel{hiddenCount > 1 ? 's' : ''}
        </button>
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
