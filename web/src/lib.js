/** Shared non-UI logic: webauthn parent gate, math-gate challenge, channel
 * filtering, the three localStorage-backed hooks (settings, watch history,
 * gallery data), and cross-device sync against the Worker's /sync routes. */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getChannelVideosCached } from './youtubeApi.js'

// ---------------------------------------------------------------------------
// webauthn — serverless WebAuthn parent gate: we never verify signatures (the
// adversary is a child, and everything lives in localStorage anyway) — we only
// rely on the OS refusing to resolve credentials.get() without a successful
// biometric (userVerification: 'required' on a platform authenticator).

export function toBase64url(buf) {
  return btoa(String.fromCharCode(...new Uint8Array(buf)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

export function fromBase64url(s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64), c => c.charCodeAt(0)).buffer
}

const random = bytes => crypto.getRandomValues(new Uint8Array(bytes))

export async function isBiometricAvailable() {
  try {
    return !!window.PublicKeyCredential && (await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable())
  } catch {
    return false
  }
}

/** Register the device biometric; returns the credential id (base64url) to store. */
export async function enroll() {
  const credential = await navigator.credentials.create({
    publicKey: {
      challenge: random(32),
      rp: { name: 'TinyTube', id: location.hostname },
      user: { id: random(16), name: 'parent', displayName: 'Parent' },
      pubKeyCredParams: [
        { type: 'public-key', alg: -7 }, // ES256
        { type: 'public-key', alg: -257 }, // RS256
      ],
      authenticatorSelection: {
        authenticatorAttachment: 'platform',
        userVerification: 'required',
        residentKey: 'discouraged',
      },
      timeout: 60_000,
      attestation: 'none',
    },
  })
  return toBase64url(credential.rawId)
}

/** Fire the OS biometric prompt for the stored credential. False on cancel/failed scan. */
export async function verify(credentialIdB64) {
  try {
    const assertion = await navigator.credentials.get({
      publicKey: {
        challenge: random(32),
        allowCredentials: [{ type: 'public-key', id: fromBase64url(credentialIdB64) }],
        userVerification: 'required',
        timeout: 60_000,
      },
    })
    return assertion !== null
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// mathGate

/**
 * Parent gate challenge: random 2-digit addition with 4 choices — one correct,
 * three plausible mistakes (off-by-carry, off-by-one...). rand is injectable
 * for tests.
 */
export function makeChallenge(rand = Math.random) {
  const int = (lo, hi) => lo + Math.floor(rand() * (hi - lo + 1))
  const a = int(10, 99)
  const b = int(10, 99)
  const answer = a + b

  const pool = [answer - 10, answer + 10, answer - 1, answer + 1, answer - 2, answer + 2, answer - 20, answer + 20]
  const distractors = [...new Set(pool.filter(n => n > 0 && n !== answer))].slice(0, 3)

  const choices = [answer, ...distractors]
  for (let i = choices.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1))
    ;[choices[i], choices[j]] = [choices[j], choices[i]]
  }
  return { a, b, answer, choices }
}

// ---------------------------------------------------------------------------
// channels

/** Inclusive range overlap: does [min_age, max_age] intersect the parent's [lo, hi]? */
export function overlaps([lo, hi], minAge, maxAge) {
  return minAge <= hi && lo <= maxAge
}

// ---------------------------------------------------------------------------
// birthday — the parent enters WHEN THE CHILD WAS BORN (mm/yy) rather than an
// age, so the filter keeps up on its own instead of being a number that went
// stale a birthday ago. Born on the 1st of the month, by declaration.

/** 'YYYY-MM' -> whole years old today (day-of-month is always the 1st), or
 * null for anything unparsable or in the future. */
export function ageFromBirthday(birthday, now = Date.now()) {
  const m = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(birthday ?? '')
  if (!m) return null
  const d = new Date(now)
  const months = (d.getFullYear() - +m[1]) * 12 + (d.getMonth() + 1 - +m[2])
  return months < 0 ? null : Math.floor(months / 12)
}

/** What the channel filter compares against: the single age computed from the
 * birthday when one is set (clamped to the 1-15 the ratings use), else the
 * legacy manual range. */
export function effectiveAgeRange(settings, now = Date.now()) {
  const age = ageFromBirthday(settings.birthday, now)
  if (age == null) return settings.ageRange
  const a = Math.max(1, Math.min(15, age))
  return [a, a]
}

/** UI text 'mm/yy' -> 'YYYY-MM', or null when it isn't one. 2-digit years are
 * this century: a child's birthday is never 19xx. */
export function parseBirthdayInput(text) {
  const m = /^\s*(0?[1-9]|1[0-2])\s*\/\s*(\d{2})\s*$/.exec(text ?? '')
  return m ? `20${m[2]}-${String(m[1]).padStart(2, '0')}` : null
}

/**
 * Curated channels from videos.json with the parent's per-channel edits
 * applied — overrides[channel_id] may adjust min_age/max_age or set hidden.
 */
export function curatedChannels(db, overrides = {}) {
  return (db?.channels ?? []).map(ch => ({ ...ch, ...overrides[ch.channel_id] }))
}

/**
 * The gallery's channel list: curated channels (edits applied) that aren't
 * hidden or toggled off and overlap the age range, plus parent-added
 * channels — shaped exactly like curated ones ({channel_title, videos}) so
 * gallerySort works unchanged. Curated wins if a parent adds an
 * already-curated channel.
 */
export function mergeChannels(db, customVideosById, settings) {
  const { customChannels, overrides, minVideoMins = 0 } = settings
  const ageRange = effectiveAgeRange(settings)
  const curated = curatedChannels(db, overrides).filter(
    ch => !ch.hidden && !ch.disabled && overlaps(ageRange, ch.min_age, ch.max_age),
  )
  const curatedIds = new Set((db?.channels ?? []).map(ch => ch.channel_id))
  const custom = customChannels
    .filter(ch => !curatedIds.has(ch.channel_id) && !ch.disabled && overlaps(ageRange, ch.min_age, ch.max_age))
    .map(ch => ({ ...ch, videos: customVideosById[ch.channel_id] ?? [] }))
  // unknown durations count as too short: don't let un-probed videos slip past
  return [...curated, ...custom].map(ch => ({
    ...ch,
    videos: (ch.videos ?? []).filter(v => (v.duration ?? 0) >= minVideoMins * 60),
  }))
}

// ---------------------------------------------------------------------------
// channelGroups — channels gathered into named groups, ported from the
// Android app's ChannelGroups.kt with its rules intact, because they are
// fiddlier than they look:
//
//   THE INVARIANT: a group has AT LEAST TWO channels. Grouping is offered only
//   for two or more; a group that loses members until one is left dissolves
//   (tidyGroups, run after every mutation rather than at remembered call
//   sites). There is no group of one.
//
//   THE ORDER: groups first, A-Z among themselves whatever else is going on —
//   a parent looking for a group wants it where it was last time. Channels
//   sort A-Z inside a group and in the loose list below.
//
// Groups live in settings as {groups: [{id, name}], groupOf: {channel_id ->
// group id}}, so they ride the settings sync like everything else.

/** The list, flattened: [{type:'header', group, size} | {type:'item', channel, grouped}].
 * A group whose visible members fall below two is SKIPPED, not drawn — and its
 * members then count as loose, because a defensive skip must never hide a
 * channel from the list entirely. */
export function arrangeChannels(channels, groups = [], groupOf = {}) {
  const byTitle = (a, b) =>
    (a.channel_title ?? '').toLowerCase().localeCompare((b.channel_title ?? '').toLowerCase()) ||
    a.channel_id.localeCompare(b.channel_id)
  const byGroup = {}
  for (const ch of channels) {
    const gid = groupOf[ch.channel_id]
    if (gid) (byGroup[gid] ??= []).push(ch)
  }
  // name then id: the id is a real tiebreaker, not decoration — a list that
  // reorders itself between redraws for equal keys is the kind of thing
  // nobody reports and everybody notices
  const ordered = [...groups].sort(
    (a, b) => a.name.trim().toLowerCase().localeCompare(b.name.trim().toLowerCase()) || a.id.localeCompare(b.id),
  )
  const rows = []
  const drawn = new Set()
  for (const group of ordered) {
    const members = byGroup[group.id] ?? []
    if (members.length < 2) continue
    drawn.add(group.id)
    rows.push({ type: 'header', group, size: members.length })
    for (const ch of [...members].sort(byTitle)) rows.push({ type: 'item', channel: ch, grouped: true })
  }
  const loose = channels.filter(ch => !drawn.has(groupOf[ch.channel_id]))
  for (const ch of [...loose].sort(byTitle)) rows.push({ type: 'item', channel: ch, grouped: false })
  return rows
}

/** Ids belonging to a group, off the membership map. */
export function groupMembers(groupId, groupOf) {
  return new Set(Object.keys(groupOf).filter(id => groupOf[id] === groupId))
}

/* Two or more, because a group of one is not a group. */
export function canGroup(selectedIds) {
  return selectedIds.size >= 2
}

/* Offered only when everything selected is already in ONE group — "ungroup"
   across two groups would mean "empty two different groups", which is a bigger
   thing than the word promises. */
export function canUngroup(selectedIds, groupOf) {
  if (!selectedIds.size) return false
  const ids = [...selectedIds]
  const first = groupOf[ids[0]]
  return first != null && ids.every(id => groupOf[id] === first)
}

/** The name to prefill the dialog with, or null for an empty box. Filled only
 * for "add these loose channels to this whole group" — one group involved,
 * ALL of it selected, plus at least one loose channel. */
export function prefillGroupName(selectedIds, groups, groupOf) {
  const involved = new Set([...selectedIds].map(id => groupOf[id]).filter(Boolean))
  if (involved.size !== 1) return null
  const [groupId] = involved
  const members = groupMembers(groupId, groupOf)
  if (![...members].every(id => selectedIds.has(id))) return null
  if (![...selectedIds].some(id => !groupOf[id])) return null
  return groups.find(g => g.id === groupId)?.name ?? null
}

function emptiedBy(group, groupOf, selectedIds) {
  const members = groupMembers(group.id, groupOf)
  // size guard: a memberless group should not exist, and treating "nothing
  // left to move" as "fully selected" would hand its name to any selection
  return members.size > 0 && [...members].every(id => selectedIds.has(id))
}

/** Names a NEW group may not take: every current group's, except one about to
 * be emptied by this very selection — its name comes free. */
export function groupNamesInUse(groups, groupOf, selectedIds) {
  return groups.filter(g => !emptiedBy(g, groupOf, selectedIds)).map(g => g.name)
}

/** The group whose ROW a new group of this name takes over (same name, every
 * member selected), or null. Absorbing rather than inserting matters because
 * the emptied group only dissolves in the tidy that runs AFTER. */
export function absorbingGroup(name, groups, groupOf, selectedIds) {
  const trimmed = name.trim().toLowerCase()
  return groups.find(g => g.name.trim().toLowerCase() === trimmed && emptiedBy(g, groupOf, selectedIds))?.id ?? null
}

/** 'empty' | 'taken' | null, as the dialog judges it: trimmed, case-insensitive. */
export function groupNameError(name, existingNames) {
  const trimmed = name.trim()
  if (!trimmed) return 'empty'
  return existingNames.some(n => n.trim().toLowerCase() === trimmed.toLowerCase()) ? 'taken' : null
}

/** THE INVARIANT, enforced: drop groups with fewer than two members and every
 * membership pointing at a dropped (or unknown) group. Run after EVERY
 * mutation — a removal, an ungroup of half a group, and a move into another
 * group each strand whatever is left behind. */
export function tidyGroups({ groups, groupOf }) {
  const counts = {}
  for (const gid of Object.values(groupOf)) counts[gid] = (counts[gid] ?? 0) + 1
  const kept = groups.filter(g => (counts[g.id] ?? 0) >= 2)
  const keptIds = new Set(kept.map(g => g.id))
  return {
    groups: kept,
    groupOf: Object.fromEntries(Object.entries(groupOf).filter(([, gid]) => keptIds.has(gid))),
  }
}

function withoutIds(groupOf, ids) {
  const idSet = new Set(ids)
  return Object.fromEntries(Object.entries(groupOf).filter(([id]) => !idSet.has(id)))
}

/** Move the selected ids into a group of this name — absorbing the emptied
 * same-name group's row when there is one — then tidy. Returns the settings
 * patch. */
export function groupInto(settings, selectedIds, name) {
  const absorbed = absorbingGroup(name, settings.groups, settings.groupOf, selectedIds)
  const id = absorbed ?? crypto.randomUUID()
  const groups = absorbed ? settings.groups : [...settings.groups, { id, name: name.trim() }]
  const groupOf = { ...settings.groupOf }
  for (const cid of selectedIds) groupOf[cid] = id
  return tidyGroups({ groups, groupOf })
}

// ---------------------------------------------------------------------------
// useSettings

const SETTINGS_KEY = 'tinytube:settings:v1'

export const DEFAULTS = {
  apiKey: '',
  ageRange: [1, 15], // everything; superseded by birthday when set
  birthday: null, // 'YYYY-MM'; the child's age is computed from this (born the 1st)
  quotaMins: 180, // watch quota per rolling 12h window; 0 = no watching (there is no "off")
  minVideoMins: 0, // hide videos shorter than this; 0 = show everything
  customChannels: [], // parent-added, same flat shape as channels.json entries: [{channel_id, channel_title, thumbnail, min_age, max_age}]
  overrides: {}, // per curated channel_id: {min_age?, max_age?, hidden?, disabled?} edited in the table
  groups: [], // channel groups [{id, name}] — see the channelGroups section
  groupOf: {}, // channel_id -> group id membership
  passkeyId: null, // WebAuthn credential id (base64url); when set, the parent gate is biometric-only
}

function loadSettings() {
  try {
    const parsed = JSON.parse(localStorage.getItem(SETTINGS_KEY)) ?? {}
    // fold pre-refactor fields into the unified overrides map
    const overrides = { ...parsed.ageOverrides, ...parsed.overrides }
    for (const id of parsed.hiddenChannels ?? []) overrides[id] = { ...overrides[id], hidden: true }
    delete parsed.hiddenChannels
    delete parsed.ageOverrides
    delete parsed.parentLockUntil // lockout mechanism removed
    return { ...DEFAULTS, ...parsed, overrides }
  } catch {
    return { ...DEFAULTS }
  }
}

// mutator API over a settings object; `update` takes shallow patches. Shared
// by the persistent store below and the Settings page's unsaved draft.
export function storeApi(settings, update) {
  return {
    settings,
    setApiKey: apiKey => update({ apiKey: apiKey.trim() }),
    setAgeRange: ([lo, hi]) => update({ ageRange: [Math.min(lo, hi), Math.max(lo, hi)] }),
    setQuotaMins: quotaMins => update({ quotaMins }),
    setMinVideoMins: minVideoMins => update({ minVideoMins }),
    addCustomChannel: ch =>
      update({
        customChannels: [...settings.customChannels.filter(c => c.channel_id !== ch.channel_id), ch],
      }),
    updateCustomChannel: (id, patch) =>
      update({
        customChannels: settings.customChannels.map(c => (c.channel_id === id ? { ...c, ...patch } : c)),
      }),
    removeCustomChannel: id =>
      update({
        customChannels: settings.customChannels.filter(c => c.channel_id !== id),
        // its group membership goes with it, and the tidy may dissolve the group
        ...tidyGroups({ groups: settings.groups, groupOf: withoutIds(settings.groupOf, [id]) }),
      }),
    setOverride: (id, patch) =>
      update({ overrides: { ...settings.overrides, [id]: { ...settings.overrides[id], ...patch } } }),
    restoreHidden: () =>
      update({
        overrides: Object.fromEntries(
          Object.entries(settings.overrides)
            .map(([id, { hidden, ...rest }]) => [id, rest])
            .filter(([, rest]) => Object.keys(rest).length > 0),
        ),
      }),
    setPasskey: id => update({ passkeyId: id }),
    setBirthday: birthday => update({ birthday }),
    /* Put the selected channels in a (possibly existing — `absorbing`) group,
       or dissolve their membership. Both run `tidy` after, like the Android
       stores do: every mutation can strand a group's last member. */
    groupChannels: (ids, name) =>
      update(groupInto(settings, ids, name)),
    ungroupChannels: ids =>
      update(tidyGroups({ ...settings, groupOf: withoutIds(settings.groupOf, ids) })),
  }
}

export function useSettings() {
  const [settings, setSettings] = useState(loadSettings)

  const update = useCallback(patch => {
    setSettings(prev => {
      // updatedAt is the sync LWW clock. Stamped on every edit — unless the
      // patch carries its own, which is how a pulled remote blob keeps the
      // stamp it was written under instead of instantly looking newer.
      const next = { ...prev, updatedAt: Date.now(), ...patch }
      try {
        localStorage.setItem(SETTINGS_KEY, JSON.stringify(next))
      } catch (e) {
        console.error('settings persist failed', e)
      }
      return next
    })
  }, [])

  return { ...storeApi(settings, update), save: update }
}

// ---------------------------------------------------------------------------
// watch quota — cumulative PLAYING seconds against a parent-set cap
// (settings.quotaMins) per rolling 12h window. The window starts at the first
// counted second and expires lazily on read; no timers anywhere. Day/hour
// buckets exist only to feed the parent-facing stats table.

export const QUOTA_WINDOW_MS = 12 * 3600_000
const HOUR_MS = 3600_000
const DAY_MS = 86_400_000
const EMPTY_USAGE = { window: { start: null, secs: 0 }, days: {}, hours: {} }

const pad2 = n => String(n).padStart(2, '0')
const localDate = d => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`

/** "3h", "1h 45m", "45m", "0m" */
export function fmtMins(mins) {
  const h = Math.floor(mins / 60)
  const m = Math.round(mins % 60)
  return h && m ? `${h}h ${m}m` : h ? `${h}h` : `${m}m`
}

/** Seconds used in the current 12h window; 0 if none, expired, or the clock ran backwards. */
export function windowUsed(usage, now = Date.now()) {
  const { start, secs } = usage?.window ?? {}
  return start != null && now >= start && now - start < QUOTA_WINDOW_MS ? secs : 0
}

/** Count watched seconds: extend (or start) the 12h window and bump the stats buckets. */
export function accrueUsage(usage, secs, now = Date.now()) {
  const window = { start: windowUsed(usage, now) ? usage.window.start : now, secs: windowUsed(usage, now) + secs }
  const dayKey = localDate(new Date(now))
  const days = { ...usage.days, [dayKey]: (usage.days[dayKey] ?? 0) + secs }
  const oldestDay = localDate(new Date(now - 366 * DAY_MS)) // YYYY-MM-DD compares lexicographically
  for (const k of Object.keys(days)) if (k < oldestDay) delete days[k]
  const hourKey = Math.floor(now / HOUR_MS)
  const hours = { ...usage.hours, [hourKey]: (usage.hours[hourKey] ?? 0) + secs }
  for (const k of Object.keys(hours)) if (+k < hourKey - 48) delete hours[k]
  return { window, days, hours }
}

/** Parent-facing stats, all in seconds. Session = the current 12h quota window; WTD weeks start Sunday. */
export function usageStats(usage, now = Date.now()) {
  const d = new Date(now)
  const daysSince = key =>
    Object.entries(usage.days).reduce((total, [k, secs]) => (k >= key ? total + secs : total), 0)
  const nowHour = Math.floor(now / HOUR_MS)
  return {
    session: windowUsed(usage, now),
    last24h: Object.entries(usage.hours).reduce((total, [k, secs]) => (+k > nowHour - 24 ? total + secs : total), 0),
    wtd: daysSince(localDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() - d.getDay()))),
    mtd: daysSince(localDate(new Date(d.getFullYear(), d.getMonth(), 1))),
    ytd: daysSince(localDate(new Date(d.getFullYear(), 0, 1))),
  }
}

// ---------------------------------------------------------------------------
// useWatchStore

const WATCH_KEY = 'tinytube:v1'
const MAX_ENTRIES = 500
export const WATCHED_THRESHOLD = 0.95 // beyond this it's just credits/outros
const LIKED_THRESHOLD = 0.2 // bailed before this -> probably didn't like it

const EMPTY_REMOTE = { days: {}, hours: {} }

function loadWatchStore() {
  try {
    const parsed = JSON.parse(localStorage.getItem(WATCH_KEY)) ?? {}
    return {
      lastVideoId: null,
      watched: {},
      ...parsed,
      usage: { ...EMPTY_USAGE, ...parsed.usage },
      remote: { ...EMPTY_REMOTE, ...parsed.remote },
    }
  } catch {
    return { lastVideoId: null, watched: {}, usage: EMPTY_USAGE, remote: EMPTY_REMOTE }
  }
}

function persist(store) {
  const ids = Object.keys(store.watched)
  if (ids.length > MAX_ENTRIES) {
    ids.sort((a, b) => store.watched[a].updatedAt - store.watched[b].updatedAt)
    for (const id of ids.slice(0, ids.length - MAX_ENTRIES)) delete store.watched[id]
  }
  localStorage.setItem(WATCH_KEY, JSON.stringify(store))
}

export function fraction(entry) {
  if (!entry) return 0
  if (entry.completed) return 1
  return entry.dur ? Math.min(entry.pos / entry.dur, 1) : 0
}

export function useWatchStore() {
  const [store, setStore] = useState(loadWatchStore)

  const saveProgress = useCallback((id, pos, dur) => {
    setStore(prev => {
      const entry = prev.watched[id]
      const completed = (entry?.completed ?? false) || (dur > 0 && pos / dur > WATCHED_THRESHOLD)
      const next = {
        ...prev,
        lastVideoId: id,
        watched: { ...prev.watched, [id]: { pos, dur, completed, updatedAt: Date.now() } },
      }
      persist(next)
      return next
    })
  }, [])

  const markCompleted = useCallback(id => {
    setStore(prev => {
      const entry = prev.watched[id] ?? { pos: 0, dur: 0 }
      const next = {
        ...prev,
        lastVideoId: id,
        watched: { ...prev.watched, [id]: { ...entry, completed: true, updatedAt: Date.now() } },
      }
      persist(next)
      return next
    })
  }, [])

  const addWatchTime = useCallback(secs => {
    setStore(prev => {
      const next = { ...prev, usage: accrueUsage(prev.usage, secs) }
      persist(next)
      return next
    })
  }, [])

  /* A /sync/pull's answer folded in: watched merges row-wise LWW; the
     account-wide usage sums land in `remote`, NEVER in `usage` — usage is what
     this device pushes back up, so folding remote into it would compound
     everyone's totals on every round trip. */
  const applyRemote = useCallback(({ watched, usage }) => {
    setStore(prev => {
      const next = {
        ...prev,
        watched: mergeWatched(prev.watched, watched),
        remote: usage ? { ...EMPTY_REMOTE, ...usage } : prev.remote,
      }
      persist(next)
      return next
    })
  }, [])

  return {
    watched: store.watched,
    usage: store.usage,
    remote: store.remote,
    saveProgress,
    markCompleted,
    addWatchTime,
    applyRemote,
  }
}

/** Interleave lists round-robin: first item of each list, then second of each, ... */
function roundRobin(lists) {
  const out = []
  const longest = Math.max(0, ...lists.map(l => l.length))
  for (let i = 0; i < longest; i++) {
    for (const list of lists) if (i < list.length) out.push(list[i])
  }
  return out
}

/**
 * Gallery order:
 * 1. continue watching (20-95% done), closest to finished first
 * 2. fresh videos, round-robin across channels (newest first within a channel)
 *    so a high-volume channel can't flood out a quiet one
 * 3. abandoned (<20%, started but bailed), same round-robin
 * 4. watched (>95%) last
 */
export function gallerySort(channels, watched) {
  const inProgress = []
  const freshPerChannel = []
  const abandonedPerChannel = []
  const done = []

  for (const ch of channels) {
    const fresh = []
    const abandoned = []
    for (const video of ch.videos) {
      const v = { ...video, channelTitle: ch.channel_title }
      const entry = watched[v.id]
      const f = fraction(entry)
      if (f > WATCHED_THRESHOLD) done.push(v)
      else if (f >= LIKED_THRESHOLD) inProgress.push({ v, f })
      else if (entry) abandoned.push(v)
      else fresh.push(v)
    }
    freshPerChannel.push(fresh)
    abandonedPerChannel.push(abandoned)
  }

  inProgress.sort((a, b) => b.f - a.f)
  return [
    ...inProgress.map(x => x.v),
    ...roundRobin(freshPerChannel),
    ...roundRobin(abandonedPerChannel),
    ...done,
  ]
}

// ---------------------------------------------------------------------------
// useVideos

/**
 * Gallery data: curated channels from videos.json filtered by the parent's
 * settings (age range, hidden), merged with parent-added channels whose
 * videos are fetched via the Data API (cache-first).
 */
export function useVideos(settings) {
  const [db, setDb] = useState(null)
  const [error, setError] = useState(null)
  const [customVideosById, setCustomVideosById] = useState({})

  useEffect(() => {
    fetch(import.meta.env.BASE_URL + 'videos.json')
      .then(r => {
        if (!r.ok) throw new Error(`videos.json: HTTP ${r.status}`)
        return r.json()
      })
      .then(setDb)
      .catch(setError)
  }, [])

  const { apiKey, customChannels } = settings
  useEffect(() => {
    let cancelled = false
    Promise.all(
      customChannels.map(ch =>
        getChannelVideosCached(apiKey, ch.channel_id).then(videos => [ch.channel_id, videos]),
      ),
    ).then(entries => {
      if (!cancelled) setCustomVideosById(Object.fromEntries(entries))
    })
    return () => {
      cancelled = true
    }
  }, [apiKey, customChannels])

  const channels = useMemo(
    () => (db ? mergeChannels(db, customVideosById, settings) : null),
    [db, customVideosById, settings],
  )

  return { db, channels, error }
}

// ---------------------------------------------------------------------------
// sync — cross-device state against the Worker's /sync routes, keyed by a
// Google account. The parent signs in (behind the parent gate) with Google
// Identity Services; the Worker trades the ID token for a 90-day session
// token, and from then on this device pulls on boot and pushes deltas as
// state changes. Merging is per-row last-write-wins; quota usage is summed
// across devices by the Worker, which is what makes the watch quota hold when
// a child switches devices.

/* The Worker (see worker.js's SYNC section) and the web app's OAuth client id
   (a public identifier — keep the two copies equal, the other is worker.js).
   Empty client id = the Sync row does not render and nothing here runs. */
export const SYNC_URL = 'https://tinytube.vtlinh87.workers.dev'
export const GOOGLE_CLIENT_ID = '559900350228-kamkqhee408lf7nh0kg5p9njgo71qjtt.apps.googleusercontent.com'

const SYNC_KEY = 'tinytube:sync:v1' // {token, email, expiresAt, deviceId, lastPushAt}
const PUSH_DEBOUNCE_MS = 5_000
const RECENT_HOURS = 12 // mirror of the quota window, in whole clock hours

export function loadSyncSession() {
  try {
    const s = JSON.parse(localStorage.getItem(SYNC_KEY))
    return s?.token && s.expiresAt > Date.now() ? s : null
  } catch {
    return null
  }
}

function saveSyncSession(session) {
  if (session) localStorage.setItem(SYNC_KEY, JSON.stringify(session))
  else localStorage.removeItem(SYNC_KEY)
}

/** Watched maps merged row-wise, newest updatedAt wins. Remote rows are the
 * Worker's shape: [{id, pos, dur, completed, updatedAt}]. */
export function mergeWatched(local, remoteRows) {
  const merged = { ...local }
  for (const r of remoteRows ?? []) {
    const mine = merged[r.id]
    if (!mine || (r.updatedAt ?? 0) > (mine.updatedAt ?? 0)) {
      merged[r.id] = { pos: r.pos, dur: r.dur, completed: !!r.completed, updatedAt: r.updatedAt }
    }
  }
  return merged
}

/** Seconds in the account-wide hour buckets over the trailing 12 clock hours. */
export function remoteRecentSecs(hours, now = Date.now()) {
  const nowHour = Math.floor(now / 3600_000)
  return Object.entries(hours ?? {}).reduce(
    (total, [k, secs]) => (+k > nowHour - RECENT_HOURS ? total + secs : total),
    0,
  )
}

/** What the quota checks compare against: the larger of this device's live
 * 12h window and the account-wide trailing-12h sum. MAX and not a sum,
 * because the remote figure already contains this device's pushed buckets;
 * the cost is undercounting simultaneous two-device watching by the smaller
 * device's share, which errs on the side of the child getting to finish. */
export function usedSecs(watchStore, now = Date.now()) {
  return Math.max(windowUsed(watchStore.usage, now), remoteRecentSecs(watchStore.remote?.hours, now))
}

/** Per-bucket max of this device's stats buckets and the account-wide sums —
 * for DISPLAY (the parent's stats table); never pushed. */
export function statsUsage(watchStore) {
  const merge = (mine = {}, theirs = {}) => {
    const out = { ...mine }
    for (const [k, secs] of Object.entries(theirs)) out[k] = Math.max(out[k] ?? 0, secs)
    return out
  }
  return {
    window: watchStore.usage.window,
    days: merge(watchStore.usage.days, watchStore.remote?.days),
    hours: merge(watchStore.usage.hours, watchStore.remote?.hours),
  }
}

/** Rows this device changed after `since`, in the Worker's push shape. */
export function watchedDeltas(watched, since) {
  return Object.entries(watched)
    .filter(([, e]) => (e.updatedAt ?? 0) > since)
    .map(([id, e]) => ({ id, pos: e.pos ?? 0, dur: e.dur ?? 0, completed: !!e.completed, updatedAt: e.updatedAt }))
}

async function syncFetch(path, body, token) {
  const resp = await fetch(SYNC_URL + path, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  const data = await resp.json().catch(() => ({}))
  if (!resp.ok) {
    const err = new Error(data?.error ?? `sync: HTTP ${resp.status}`)
    err.status = resp.status
    throw err
  }
  return data
}

/* One <script> load per page; Google's GIS client is the sanctioned way to
   get an ID token in a browser and cannot be bundled. */
let gisLoading = null

export function loadGoogleSignIn() {
  gisLoading ??= new Promise((resolve, reject) => {
    if (window.google?.accounts?.id) return resolve(window.google)
    const s = document.createElement('script')
    s.src = 'https://accounts.google.com/gsi/client'
    s.async = true
    s.onload = () => resolve(window.google)
    s.onerror = () => {
      gisLoading = null
      reject(new Error('Google sign-in failed to load'))
    }
    document.head.appendChild(s)
  })
  return gisLoading
}

/**
 * The sync loop. Inert without a session (and entirely so when
 * GOOGLE_CLIENT_ID is empty): tests and signed-out devices never touch the
 * network. With one: pull once per boot and fold the answer in, then push
 * this device's deltas, debounced, as state changes.
 */
export function useSync(settingsStore, watchStore) {
  const [session, setSession] = useState(loadSyncSession)
  const pulled = useRef(false)
  const timer = useRef(null)

  const signOut = useCallback(() => {
    saveSyncSession(null)
    setSession(null)
    pulled.current = false
  }, [])

  /* 401 means the session aged out server-side — surface as signed-out so the
     Settings row offers the button again; anything else is a blip to retry
     next time something changes. */
  const dead = useCallback(
    err => {
      if (err?.status === 401) signOut()
      else console.warn('sync failed', err)
    },
    [signOut],
  )

  const signIn = useCallback(async idToken => {
    const { token, email, expires_at } = await syncFetch('/sync/login', { id_token: idToken })
    const session = {
      token,
      email,
      expiresAt: expires_at,
      deviceId: loadSyncSession()?.deviceId ?? crypto.randomUUID(),
      lastPushAt: 0,
    }
    saveSyncSession(session)
    pulled.current = false
    setSession(session)
    return email
  }, [])

  // pull on boot / sign-in
  const { save } = settingsStore
  const { applyRemote } = watchStore
  const settings = settingsStore.settings
  useEffect(() => {
    if (!session || pulled.current) return
    pulled.current = true
    syncFetch('/sync/pull', {}, session.token)
      .then(remote => {
        applyRemote({ watched: remote.watched, usage: remote.usage })
        if (remote.settings && (remote.settings.updatedAt ?? 0) > (settings.updatedAt ?? 0)) {
          // keep the remote stamp: adopting a blob is not an edit
          save({ ...remote.settings.data, updatedAt: remote.settings.updatedAt })
        }
      })
      .catch(dead)
  }, [session]) // eslint-disable-line react-hooks/exhaustive-deps

  // push deltas, debounced, whenever local state moves
  const { watched, usage } = watchStore
  useEffect(() => {
    if (!session) return
    clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      const since = session.lastPushAt ?? 0
      const payload = {
        usage: { deviceId: session.deviceId, days: usage.days, hours: usage.hours },
      }
      const deltas = watchedDeltas(watched, since)
      if (deltas.length) payload.watched = deltas
      if ((settings.updatedAt ?? 0) > since) {
        payload.settings = { data: settings, updatedAt: settings.updatedAt }
      }
      syncFetch('/sync/push', payload, session.token)
        .then(() => {
          const next = { ...session, lastPushAt: Date.now() }
          saveSyncSession(next)
          setSession(next)
        })
        .catch(dead)
    }, PUSH_DEBOUNCE_MS)
    return () => clearTimeout(timer.current)
  }, [session, settings, watched, usage, dead])

  return { session, signIn, signOut }
}
