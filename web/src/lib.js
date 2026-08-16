/** Shared non-UI logic: webauthn parent gate, math-gate challenge, channel
 * filtering, the three localStorage-backed hooks (settings, watch history,
 * gallery data), and cross-device sync against the Worker's /sync routes. */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getChannelsCached } from './youtubeApi.js'

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

/** Inclusive range overlap: does [min_age, max_age] intersect the parent's [lo, hi]?
 *  null on a channel bound is “any”: no floor, or no ceiling. */
export function overlaps([lo, hi], minAge, maxAge) {
  const min = minAge == null ? -Infinity : minAge
  const max = maxAge == null ? Infinity : maxAge
  return min <= hi && lo <= max
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
 * birthday when one is set. Floor at 1 (a baby is 1 on the scale) but do NOT
 * cap at 14: a 15-year-old must miss a 1–14 channel and still match `any`.
 * No birthday falls back to the legacy manual range. */
export function effectiveAgeRange(settings, now = Date.now()) {
  const age = ageFromBirthday(settings.birthday, now)
  if (age == null) return settings.ageRange
  const a = Math.max(1, age)
  return [a, a]
}

/** UI text 'mm/yy' -> 'YYYY-MM', or null when it isn't one. 2-digit years are
 * this century: a child's birthday is never 19xx. */
export function parseBirthdayInput(text) {
  const m = /^\s*(0?[1-9]|1[0-2])\s*\/\s*(\d{2})\s*$/.exec(text ?? '')
  return m ? `20${m[2]}-${String(m[1]).padStart(2, '0')}` : null
}

// ---------------------------------------------------------------------------
// the age slider's arithmetic, pure so it can be tested without a layout
//
// Stops are any | 1 … 14 | any. Finite ages are 1–14; null is “any” at that
// end. Unlike video length the thumbs MAY meet on a finite age (exactly 7
// is a real choice) — they just cannot both sit on an “any”.

export const AGE_MIN = 1
export const AGE_MAX = 14
/** Index of the right-hand “any”. 0 is the left-hand “any”; 1–14 are ages. */
export const AGE_LAST = 15

/** 0..1 along the track -> a stop index on any|1…14|any, clamped at both ends. */
export function ageAtFraction(t) {
  return Math.round(Math.min(1, Math.max(0, t)) * AGE_LAST)
}

/** Stored bound -> stop index. null (and the old 15 ceiling) is “any”. */
export function ageIndex(age, end) {
  if (age == null || age > AGE_MAX) return end === 'hi' ? AGE_LAST : 0
  if (!Number.isInteger(age) || age < AGE_MIN) return 0
  return age
}

/** Stop index -> stored bound: 0 and 15 are null, 1–14 are themselves. */
export function ageFromIndex(i) {
  if (i <= 0 || i >= AGE_LAST) return null
  return i
}

export function ageLabel(age) {
  return age == null ? 'any' : String(age)
}

/** What the channel chip says: a pair of bounds, or just “any” when both
 *  ends are unbounded — “any–any” is two words for one fact. */
export function ageRangeLabel(min, max) {
  if (min == null && max == null) return 'any'
  return `${ageLabel(min)}–${ageLabel(max)}`
}

/**
 * Move one end of the age range, in stop indexes.
 *
 * The ends may meet on 1–14. They may not occupy the same “any”: stacking
 * both on the left or both on the right would look like a single thumb and
 * store the same any–any the opposite-ends pair already means.
 */
export function clampAgeRange([lo, hi], end, v) {
  if (end === 'lo') {
    const cap = Math.min(hi, AGE_LAST - 1)
    return [Math.max(0, Math.min(v, cap)), hi]
  }
  const floor = Math.max(lo, 1)
  return [lo, Math.min(AGE_LAST, Math.max(v, floor))]
}

/**
 * Which end of a [lo, hi] pair a press at `v` should drag: the nearer one, or
 * the end being pushed when the press is outside the pair.
 *
 * 'pending' when the two ends are SITTING ON THE SAME VALUE and that is where
 * the press landed — the answer is not knowable until the pointer moves, and
 * guessing is exactly how an end gets stuck: pick `lo`, drag right, and every
 * value clamps against `hi`, which looks like a dead slider.
 */
export function grabEnd(v, lo, hi) {
  if (v < lo) return 'lo'
  if (v > hi) return 'hi'
  if (lo === hi) return 'pending'
  return v - lo <= hi - v ? 'lo' : 'hi'
}

/** A stored decision + the Worker's record = a channel row the gallery and
 * the parent's list can both render. Falling back to the id keeps a channel
 * whose record has not arrived on the screen that manages it, rather than
 * vanishing from it. */
export function hydrateChannel(ch, record = {}) {
  const { videos, title, ...meta } = record ?? {}
  return {
    ...meta,
    ...ch,
    channel_title: title ?? ch.channel_id,
    thumbnail: record?.thumbnail,
    videos: videos ?? [],
  }
}

// ---------------------------------------------------------------------------
// video length — a RANGE: a floor and a ceiling, 15 minutes apart at the very
// least, in 15-minute steps up to two hours. Both ends can say "any": the
// floor at 0 (nothing is too short) and the ceiling at the far end (nothing is
// too long), which is why the ceiling's last stop is Infinity rather than 2h.

export const LENGTH_STEP_MINS = 15
export const LENGTH_MAX_MINS = 120

/** The stops both thumbs move between: 0, 15, … 120, then no ceiling at all. */
export const LENGTH_STOPS = [
  ...Array.from({ length: LENGTH_MAX_MINS / LENGTH_STEP_MINS + 1 }, (_, i) => i * LENGTH_STEP_MINS),
  Infinity,
]

/** minutes -> the stop index that holds them; Infinity and null are the last. */
export function lengthIndex(mins) {
  if (mins == null || !Number.isFinite(mins)) return LENGTH_STOPS.length - 1
  const i = LENGTH_STOPS.indexOf(mins)
  return i >= 0 ? i : Math.max(0, Math.min(LENGTH_STOPS.length - 1, Math.round(mins / LENGTH_STEP_MINS)))
}

/** What a thumb says: bare minutes, and "any" at the ends that stop
 * filtering. No "1h 15m" — two wide labels collided whenever the two thumbs
 * came close, and a number is three characters at its worst. */
export function minuteLabel(mins) {
  return !mins || !Number.isFinite(mins) ? 'any' : String(mins)
}

/** The same value in words, for prose rather than for a thumb. */
export function lengthLabel(mins) {
  return !mins || !Number.isFinite(mins) ? 'any' : fmtMins(mins)
}

/**
 * Move one end of the length range, in stop indexes.
 *
 * THE ENDS MAY NOT MEET. A floor equal to its ceiling would hide every video
 * but the ones exactly that long, which is never what a parent dragging a
 * slider means — so they stay a step (15 minutes) apart, and pushing one into
 * the other moves it as far as it may go instead of past.
 */
export function clampLengthRange([lo, hi], end, v) {
  const last = LENGTH_STOPS.length - 1
  return end === 'lo'
    ? [Math.max(0, Math.min(v, hi - 1)), hi]
    : [lo, Math.min(last, Math.max(v, lo + 1))]
}

/**
 * The gallery's channel list: parent-approved channels that overlap the age
 * range, with names, avatars and videos from the Worker's shared cache.
 * Falling back to the id means a channel whose record has never arrived is
 * still listed and still removable.
 */
export function mergeChannels(customVideosById, settings) {
  const { customChannels, minVideoMins = 0, maxVideoMins = null } = settings
  const ageRange = effectiveAgeRange(settings)
  const custom = customChannels
    .filter(ch => overlaps(ageRange, ch.min_age, ch.max_age))
    .map(ch => hydrateChannel(ch, customVideosById[ch.channel_id]))
  /* Unknown duration is kept. The Worker scrape that usually fills the grid
     has no lengths, and treating those as zero hid every video the moment a
     parent set a floor. A known length still has to clear both ends. */
  const ceiling = maxVideoMins == null || !Number.isFinite(maxVideoMins) ? Infinity : maxVideoMins * 60
  return custom.map(ch => ({
    ...ch,
    videos: (ch.videos ?? []).filter(v => {
      const secs = v.duration
      if (secs == null || !Number.isFinite(secs)) return true
      return secs >= minVideoMins * 60 && secs <= ceiling
    }),
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
export function arrangeChannels(channels, groups = [], groupOf = {}, isActive = () => true) {
  /* GROUPED FIRST (groups A-Z among themselves), then the ones the child can
     actually see, then A-Z. The middle key matters on the parent's screen,
     where channels outside this child's age are still listed but greyed: they
     belong under the ones in use, not scattered through them. The child's own
     tab passes nothing, so everything there is active and it is plain A-Z. */
  const byTitle = (a, b) =>
    (isActive(b) ? 1 : 0) - (isActive(a) ? 1 : 0) ||
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
// import / export — a child's channel list as a file, so a parent can copy a
// setup to another child, to another device, or keep it somewhere safe.
//
// EVERYTHING COMING BACK IN IS RE-VALIDATED. The file is the one input to this
// app that a parent can hand it from anywhere, and what it becomes is the list
// of channels a child may watch: a bad id reaches a URL, a bad thumbnail URL
// is fetched and drawn. Nothing is trusted for having been exported by us.

export const EXPORT_KIND = 'tinytube-channels'
export const EXPORT_VERSION = 1

const CHANNEL_ID_RE = /^UC[A-Za-z0-9_-]{22}$/
// the hosts YouTube serves avatars and posters from; anything else is dropped
const MAX_IMPORT_CHANNELS = 500

/** A finite age on the 1–14 scale, or null for “any” / garbage / the old 15 ceiling. */
export function boundAge(n) {
  if (n == null || !Number.isInteger(n) || n < AGE_MIN || n > AGE_MAX) return null
  return n
}

/** The active child's channel setup, as the object written to the file. */
export function exportChannels(settings) {
  return {
    kind: EXPORT_KIND,
    version: EXPORT_VERSION,
    child: settings.childName,
    customChannels: settings.customChannels,
    overrides: settings.overrides,
    groups: settings.groups,
    groupOf: settings.groupOf,
  }
}

/**
 * File text -> a child patch, or a thrown Error a parent can read.
 *
 * Anything malformed INSIDE a recognised file is dropped rather than fatal —
 * one unreadable row should not cost a parent the other forty — but the file
 * itself must say what it is, so picking the wrong file fails loudly instead
 * of silently emptying a child's grid.
 */
export function parseChannelImport(text) {
  let data
  try {
    data = JSON.parse(text)
  } catch {
    throw new Error('That file is not JSON.')
  }
  if (!data || typeof data !== 'object' || data.kind !== EXPORT_KIND) {
    throw new Error('That is not a TinyTube channel export.')
  }
  if (Number(data.version) > EXPORT_VERSION) {
    throw new Error('That export came from a newer version of TinyTube.')
  }

  const customChannels = []
  const seen = new Set()
  for (const ch of Array.isArray(data.customChannels) ? data.customChannels : []) {
    if (!ch || !CHANNEL_ID_RE.test(ch.channel_id ?? '') || seen.has(ch.channel_id)) continue
    if (customChannels.length >= MAX_IMPORT_CHANNELS) break
    seen.add(ch.channel_id)
    // the decision only, exactly as it is stored: a file that carries a name
    // or an avatar carries a copy of something the Worker owns. Garbage ages
    // become any–any; an inverted pair is a typo, not a reason to refuse it.
    customChannels.push(decisionOnly({ channel_id: ch.channel_id, min_age: ch.min_age, max_age: ch.max_age }))
  }

  const overrides = {}
  for (const [id, patch] of Object.entries(data.overrides ?? {})) {
    if (!CHANNEL_ID_RE.test(id) || !patch || typeof patch !== 'object') continue
    const kept = {}
    if ('min_age' in patch) kept.min_age = boundAge(patch.min_age)
    if ('max_age' in patch) kept.max_age = boundAge(patch.max_age)
    if (kept.min_age != null && kept.max_age != null && kept.min_age > kept.max_age) {
      ;[kept.min_age, kept.max_age] = [kept.max_age, kept.min_age]
    }
    if (Object.keys(kept).length) overrides[id] = kept
  }

  const groups = []
  const groupIds = new Set()
  for (const g of Array.isArray(data.groups) ? data.groups : []) {
    if (!g || typeof g.id !== 'string' || typeof g.name !== 'string' || groupIds.has(g.id)) continue
    groupIds.add(g.id)
    groups.push({ id: g.id, name: g.name.slice(0, 100) })
  }

  const groupOf = {}
  for (const [id, gid] of Object.entries(data.groupOf ?? {})) {
    // a membership pointing at a group the file did not carry is meaningless
    if (CHANNEL_ID_RE.test(id) && groupIds.has(gid)) groupOf[id] = gid
  }

  // the >=2 rule holds for imported groups exactly as it does for made ones
  return { customChannels, overrides, ...tidyGroups({ groups, groupOf }) }
}

/** The ids a file would land on top of: channels the child already has.
 * Empty means the import cannot take anything away by merging. */
export function importConflicts(settings, imported) {
  const mine = new Set(settings.customChannels.map(c => c.channel_id))
  return imported.customChannels.filter(c => mine.has(c.channel_id)).map(c => c.channel_id)
}

/**
 * A file applied to a child, three ways:
 *
 *   'replace' — the file IS the list. Channels the file does not carry go.
 *   'theirs'  — merge, and the file wins where the two disagree.
 *   'mine'    — merge, and what is already there wins; the file only adds.
 *
 * Merging never removes a channel, which is the point of offering it: a
 * parent importing a sibling's list usually means "and also these".
 */
export function applyImport(settings, imported, mode = 'replace') {
  if (mode === 'replace') return imported
  const theirsWins = mode === 'theirs'

  const byId = new Map(settings.customChannels.map(c => [c.channel_id, c]))
  for (const ch of imported.customChannels) {
    if (theirsWins || !byId.has(ch.channel_id)) byId.set(ch.channel_id, ch)
  }

  const overrides = { ...settings.overrides }
  for (const [id, patch] of Object.entries(imported.overrides ?? {})) {
    if (theirsWins || !overrides[id]) overrides[id] = patch
  }

  /* Groups merge BY NAME: two lists that both call a group "Cartoons" mean the
     same shelf, and ending up with two of them is the confusing answer. An id
     that collides while the name does not is renamed rather than dropped. */
  const groups = [...settings.groups]
  const groupOf = { ...settings.groupOf }
  const idMap = {}
  for (const g of imported.groups ?? []) {
    const key = g.name.trim().toLowerCase()
    const existing = groups.find(x => x.name.trim().toLowerCase() === key)
    if (existing) {
      idMap[g.id] = existing.id
      continue
    }
    const id = groups.some(x => x.id === g.id) ? `${g.id}-imported` : g.id
    idMap[g.id] = id
    groups.push({ id, name: g.name })
  }
  for (const [channelId, groupId] of Object.entries(imported.groupOf ?? {})) {
    const mapped = idMap[groupId]
    if (!mapped) continue
    if (theirsWins || !groupOf[channelId]) groupOf[channelId] = mapped
  }

  return { customChannels: [...byId.values()], overrides, ...tidyGroups({ groups, groupOf }) }
}

// ---------------------------------------------------------------------------
// what plays next

/* The Android app's `Playlist`, ported from it — same names, same semantics,
   same `roll` injection so the random half is testable rather than sampled.
   It lives up here rather than beside the gallery sort it serves because
   CHILD_DEFAULTS names the default mode, and a const cannot be read before it
   is declared.

   Three rules that look like details and are not:

   IN_ORDER STOPS AT THE END rather than wrapping. A grid that loops is a grid
   a child never reaches the bottom of.

   RANDOM NEVER REPEATS THE CURRENT VIDEO, and gives up when there is nothing
   else — the roll picks out of the OTHER videos, so the same one cannot come
   round again immediately.

   RANDOM PREFERS WHAT HAS NOT BEEN WATCHED. This is the one place the web app
   DIVERGES from Playlist.kt, which knows only a count and an index. Random
   over everything meant a child who had seen most of a channel kept being
   handed repeats, which is the complaint random was supposed to answer. So
   the pool is the unwatched videos when there are any, and every other video
   when there are not — a preference, not a restriction, because running out
   of new things must not stop playback. Watched means the same thing here as
   everywhere else, `WATCHED_THRESHOLD`; and when `hideWatched` is on this
   changes nothing, because those videos never reach the list.

   And the list is whatever the child was looking at when they tapped: a video
   started from inside one channel cannot lead out of it, and there is no rule
   here saying so. The screen that was on is the authority. */
export const PLAYBACK_IN_ORDER = 'IN_ORDER'
export const PLAYBACK_RANDOM = 'RANDOM'
export const PLAYBACK_MODES = [PLAYBACK_IN_ORDER, PLAYBACK_RANDOM]

/** Anything unrecognised — an old blob, a hand-edited file — plays in order. */
export function playbackMode(name) {
  return PLAYBACK_MODES.includes(name) ? name : PLAYBACK_IN_ORDER
}

/** The index to play after `current`, or null for "nothing follows this".
 * `roll(bound)` returns an integer in [0, bound), like Kotlin's nextInt.
 * `isWatched(i)` is optional; without it this is Playlist.kt exactly. */
export function nextIndex(count, current, mode, roll = n => Math.floor(Math.random() * n), isWatched = null) {
  if (count <= 0 || current < 0 || current >= count) return null
  if (playbackMode(mode) === PLAYBACK_IN_ORDER) return current + 1 < count ? current + 1 : null
  if (count < 2) return null

  /* Every video but this one, in order. Rolling over this list rather than
     doing the skip-past arithmetic is the same answer for the same roll —
     `others[n]` is `n` below `current` and `n + 1` at or above it — and it is
     what lets the pool narrow to the unwatched without a second rule. */
  const others = []
  for (let i = 0; i < count; i++) if (i !== current) others.push(i)
  const unseen = isWatched ? others.filter(i => !isWatched(i)) : []
  const pool = unseen.length ? unseen : others

  /* The roll is the one input from outside this file, so it is floored and
     clamped rather than trusted — and NaN is clamped too, which Math.min/max
     would pass straight through into an index. */
  const raw = Math.floor(roll(pool.length))
  const n = Number.isFinite(raw) ? Math.min(pool.length - 1, Math.max(0, raw)) : 0
  return pool[n]
}

/** What the app calls: the same rule, with "watched" read off the store the
 * grid already uses, so the player and the badge cannot disagree about what
 * counts as seen. */
export function nextVideoIndex(list, current, mode, watched = {}, roll) {
  return nextIndex(list.length, current, mode, roll, i => fraction(watched[list[i]?.id]) > WATCHED_THRESHOLD)
}

// ---------------------------------------------------------------------------
// useSettings

const SETTINGS_KEY = 'tinytube:settings:v1'

/* ONE ACCOUNT, SEVERAL CHILDREN. What a child owns — their age, their quota,
   their channels and groups — is per child; what the household owns — the API
   key, the parent's passkey — is per account. The stored shape is
   {apiKey, passkeyId, children: [{id, name, ...CHILD_DEFAULTS}], activeChildId},
   and everything that READS settings is handed a FLATTENED view (account
   fields plus the active child's) so no caller has to know any of this. */
export const CHILD_DEFAULTS = {
  /* The child's OWN Google account, if they have one. Not used to sign in and
     not required — it exists so that a child who signs in on a shared device
     is recognised as a child, and the parent's controls lock. See
     `settingsLock`. */
  email: null,
  ageRange: [1, 15], // everything; superseded by birthday when set
  birthday: null, // 'YYYY-MM'; the child's age is computed from this (born the 1st)
  /* minutes per period, or null for "no limit over this one". 0 means no
     watching at all, which is still deliberately reachable. */
  quota: { per6h: null, perDay: 180, perWeek: null, perMonth: null },
  day: null, // {until, limits?, bonusMins?} — today's override, see above
  minVideoMins: 0, // the floor: hide videos shorter than this; 0 = no floor
  maxVideoMins: null, // the ceiling: hide videos longer than this; null = none
  /* Watched videos always sink to the bottom of the grid; this takes them off
     it entirely. Off by default — sinking is enough for most, and a child
     re-watching a favourite is not a problem to solve. */
  hideWatched: false,
  playback: PLAYBACK_IN_ORDER, // what plays next: 'IN_ORDER' | 'RANDOM'
  /* parent-added channels, as the PARENT'S DECISION only:
     [{channel_id, min_age, max_age}]. The name, avatar and videos are facts
     about the channel, so they live in the Worker's shared cache and are
     asked for — never stored here and never uploaded. */
  customChannels: [],
  overrides: {}, // leftover from when a built-in catalog had per-row edits; unused
  groups: [], // channel groups [{id, name}] — see the channelGroups section
  groupOf: {}, // channel_id -> group id membership
}

export const ACCOUNT_DEFAULTS = {
  apiKey: '',
  passkeyId: null, // WebAuthn credential id (base64url); when set, the parent gate is biometric-only
}

// the flattened view's defaults — what every reader of `settings` sees
export const DEFAULTS = { ...ACCOUNT_DEFAULTS, ...CHILD_DEFAULTS }

/* The id the pre-children settings become. FIXED rather than random on
   purpose: the Worker migrates existing synced rows under this same id, so an
   account that was already syncing keeps its history instead of finding an
   empty first child. New children get UUIDs. */
export const FIRST_CHILD_ID = 'default'

/** An email as it is compared: trimmed and lowercased, or null. Addresses are
 * typed by a parent on a phone keyboard, so "Bob@Gmail.com " and
 * "bob@gmail.com" have to be the same person or the lock misses. */
export function normEmail(email) {
  const t = String(email ?? '').trim().toLowerCase()
  return t || null
}

/** Why the parent's controls are locked, or null when they are open.
 *
 * Two reasons, and the ORDER of the checks is the interesting part. A child
 * signed in with their own account gets `{kind:'child'}`; nobody signed in at
 * all gets `{kind:'signed-out'}`, because settings that anyone can open on an
 * unclaimed device are not a parental control.
 *
 * The escape hatch is not optional. Signing in is the only way out of both
 * states, so the sign-in control must stay live inside a locked screen — and
 * a build with no `GOOGLE_CLIENT_ID` cannot sign in AT ALL, which would make
 * this a permanent lock with no recovery. There, sync is inert and so is
 * this: no client id, no lock. */
export function settingsLock(children, session, clientId = GOOGLE_CLIENT_ID) {
  if (!clientId) return null
  const who = normEmail(session?.email)
  if (!who) return { kind: 'signed-out' }
  const child = (children ?? []).find(c => normEmail(c.email) && normEmail(c.email) === who)
  return child ? { kind: 'child', name: child.name, email: who } : null
}

/** A parent-added channel, reduced to the decision: which channel, for what
 * ages. Everything else about a channel is the channel's own and comes from
 * the Worker's shared cache.
 *
 * null is “any”. The old 1–15 default meant everyone, which is now any–any;
 * a leftover 15 as a ceiling (5–15) becomes 5–any. */
export function decisionOnly(ch) {
  if (ch.min_age === 1 && ch.max_age === 15) {
    return { channel_id: ch.channel_id, min_age: null, max_age: null }
  }
  let min_age = boundAge(ch.min_age)
  let max_age = boundAge(ch.max_age)
  if (min_age != null && max_age != null && min_age > max_age) {
    ;[min_age, max_age] = [max_age, min_age]
  }
  return { channel_id: ch.channel_id, min_age, max_age }
}

/** Stored shape -> stored shape, with legacy blobs folded in. Idempotent. */
export function normalizeSettings(parsed = {}) {
  // fold pre-refactor fields into the unified overrides map
  const overrides = { ...parsed.ageOverrides, ...parsed.overrides }
  /* The enable checkbox is gone and every approved channel is on. A stored
     `disabled` would otherwise be a state with no control to leave it.
     `hidden` was how a built-in channel was "deleted"; those channels are
     gone, so a leftover flag is just noise. `hiddenChannels` was the same
     flag as a list, and is ignored. */
  for (const [id, patch] of Object.entries(overrides)) {
    if (patch?.disabled !== undefined || patch?.hidden !== undefined) {
      const { disabled, hidden, ...rest } = patch
      if (Object.keys(rest).length) overrides[id] = rest
      else delete overrides[id]
    }
  }

  const children =
    Array.isArray(parsed.children) && parsed.children.length
      ? parsed.children.map((c, i) => ({
          ...CHILD_DEFAULTS,
          ...c,
          // the one 12h number became four periods; a daily cap is the
          // closest thing to what it meant, so that is where it lands
          quota: normalizeQuota(c.quota ?? { ...CHILD_DEFAULTS.quota, perDay: c.quotaMins ?? CHILD_DEFAULTS.quota.perDay }),
          // channel names and avatars used to be stored here; they are the
          // Worker's now, and a stored copy would only go stale
          customChannels: (c.customChannels ?? []).map(decisionOnly),
          playback: playbackMode(c.playback),
          email: normEmail(c.email),
          id: c.id ?? `child-${i}`,
          name: c.name ?? `Child ${i + 1}`,
        }))
      : [
          {
            ...CHILD_DEFAULTS,
            // a pre-children blob carried these at the top level
            ...Object.fromEntries(
              Object.keys(CHILD_DEFAULTS)
                .filter(k => parsed[k] !== undefined)
                .map(k => [k, parsed[k]]),
            ),
            overrides,
            quota: normalizeQuota(parsed.quota ?? { ...CHILD_DEFAULTS.quota, perDay: parsed.quotaMins ?? CHILD_DEFAULTS.quota.perDay }),
            customChannels: (parsed.customChannels ?? []).map(decisionOnly),
            playback: playbackMode(parsed.playback),
            email: normEmail(parsed.email),
            id: FIRST_CHILD_ID,
            name: parsed.children?.[0]?.name ?? 'Child 1',
          },
        ]
  const activeChildId = children.some(c => c.id === parsed.activeChildId) ? parsed.activeChildId : children[0].id
  return {
    ...ACCOUNT_DEFAULTS,
    ...Object.fromEntries(Object.keys(ACCOUNT_DEFAULTS).filter(k => parsed[k] !== undefined).map(k => [k, parsed[k]])),
    updatedAt: parsed.updatedAt,
    children,
    activeChildId,
  }
}

/** The child in front of us — never undefined, whatever activeChildId says. */
export function activeChild(settings) {
  return settings.children.find(c => c.id === settings.activeChildId) ?? settings.children[0]
}

/** The flattened view every consumer reads: account fields, then the active
 * child's, with the child's own id/name kept clear of the settings keys. */
export function childView(settings) {
  const { id, name, ...fields } = activeChild(settings)
  return { ...settings, ...fields, childId: id, childName: name }
}

function loadSettings() {
  try {
    return normalizeSettings(JSON.parse(localStorage.getItem(SETTINGS_KEY)) ?? {})
  } catch {
    return normalizeSettings({})
  }
}

/* Mutator API over the FLATTENED view. `update` patches account-level fields
 * (and the children list itself); `updateChild` patches the active child. Which
 * one a setter uses is the whole per-child/per-account distinction, in one
 * readable place. */
export function storeApi(settings, update, updateChild = update) {
  return {
    settings,
    setApiKey: apiKey => update({ apiKey: apiKey.trim() }),
    setPasskey: id => update({ passkeyId: id }),
    /* Grants stack within the week and restart it: a second helping at
       Wednesday's bedtime should not expire on Wednesday's grant clock. An
       expired one contributes nothing, so this resets rather than compounds. */
    addBonusMins: mins =>
      updateChild({
        day: { ...(activeDayOverride(settings) ?? {}), bonusMins: activeBonusMins(settings) + mins, until: endOfDay() },
      }),
    setAgeRange: ([lo, hi]) => updateChild({ ageRange: [Math.min(lo, hi), Math.max(lo, hi)] }),
    /* The standing limits, and this week's override of them. Both are set
       whole, from a dialog with a Save — a half-edited set of four limits is
       not something to persist a keystroke at a time. */
    setQuota: quota => updateChild({ quota }),
    /* The week's dialog offers no monthly limit — a week cannot redraw a
       month — so the standing one is carried through rather than dropped:
       an override must never quietly REMOVE a cap. */
    /* Today's dialog offers no weekly or monthly limit — a day cannot redraw
       either — so the standing ones are carried through rather than dropped:
       an override must never quietly REMOVE a cap. */
    setDayLimits: limits =>
      updateChild({
        day: {
          ...(activeDayOverride(settings) ?? {}),
          limits: { ...settings.quota, ...limits },
          until: endOfDay(),
        },
      }),
    clearDayOverride: () => updateChild({ day: null }),
    setVideoLength: ([minVideoMins, maxVideoMins]) => updateChild({ minVideoMins, maxVideoMins }),
    setHideWatched: hideWatched => updateChild({ hideWatched: !!hideWatched }),
    setPlayback: mode => updateChild({ playback: playbackMode(mode) }),
    setChildEmail: email => updateChild({ email: normEmail(email) }),
    setBirthday: birthday => updateChild({ birthday }),
    /* Only the decision is kept, whatever the caller hands over: a search
       result arrives with a title, avatar, subscriber counts and topics, and
       none of that is ours to store — it is the channel's, and it changes. */
    addCustomChannel: ch =>
      updateChild({
        customChannels: [
          ...settings.customChannels.filter(c => c.channel_id !== ch.channel_id),
          decisionOnly(ch),
        ],
      }),
    updateCustomChannel: (id, patch) =>
      updateChild({
        customChannels: settings.customChannels.map(c =>
          c.channel_id === id ? decisionOnly({ ...c, ...patch }) : c,
        ),
      }),
    removeCustomChannel: id =>
      updateChild({
        customChannels: settings.customChannels.filter(c => c.channel_id !== id),
        // its group membership goes with it, and the tidy may dissolve the group
        ...tidyGroups({ groups: settings.groups, groupOf: withoutIds(settings.groupOf, [id]) }),
      }),
    /* Put the selected channels in a (possibly existing — `absorbing`) group,
       or dissolve their membership. Both run `tidy` after, like the Android
       stores do: every mutation can strand a group's last member. */
    /* An imported file REPLACES the child's channel setup rather than merging
       into it: a parent importing a list means "this list", and a merge would
       leave channels behind that they cannot see they still have. */
    importChannels: patch => updateChild(patch),
    groupChannels: (ids, name) => updateChild(groupInto(settings, ids, name)),
    ungroupChannels: ids => updateChild(tidyGroups({ ...settings, groupOf: withoutIds(settings.groupOf, ids) })),
  }
}

export function useSettings() {
  const [settings, setSettings] = useState(loadSettings)

  // updatedAt is the sync LWW clock. Stamped on every edit — unless the caller
  // carries its own, which is how a pulled remote blob keeps the stamp it was
  // written under instead of instantly looking newer.
  const write = useCallback(mutate => {
    setSettings(prev => {
      const next = mutate(prev)
      try {
        localStorage.setItem(SETTINGS_KEY, JSON.stringify(next))
      } catch (e) {
        console.error('settings persist failed', e)
      }
      return next
    })
  }, [])

  const update = useCallback(
    patch => write(prev => normalizeSettings({ ...prev, updatedAt: Date.now(), ...patch })),
    [write],
  )

  const updateChild = useCallback(
    patch =>
      write(prev => ({
        ...prev,
        updatedAt: Date.now(),
        children: prev.children.map(c => (c.id === prev.activeChildId ? { ...c, ...patch } : c)),
      })),
    [write],
  )

  const addChild = useCallback(
    name =>
      write(prev => {
        const child = { ...CHILD_DEFAULTS, id: crypto.randomUUID(), name: name.trim() || `Child ${prev.children.length + 1}` }
        // switching to the new child is the point of adding one
        return { ...prev, updatedAt: Date.now(), children: [...prev.children, child], activeChildId: child.id }
      }),
    [write],
  )

  const switchChild = useCallback(id => update({ activeChildId: id }), [update])

  const renameChild = useCallback(name => updateChild({ name: name.trim() || 'Child' }), [updateChild])

  /* The last child cannot be removed — an account with no child has no grid to
     show and no settings to edit. */
  const removeChild = useCallback(
    id =>
      write(prev => {
        if (prev.children.length < 2) return prev
        const children = prev.children.filter(c => c.id !== id)
        localStorage.removeItem(watchKey(id)) // their watch history goes with them
        return {
          ...prev,
          updatedAt: Date.now(),
          children,
          activeChildId: prev.activeChildId === id ? children[0].id : prev.activeChildId,
        }
      }),
    [write],
  )

  const view = childView(settings)
  return {
    ...storeApi(view, update, updateChild),
    save: update,
    stored: settings, // the un-flattened blob: what sync pushes
    children: settings.children,
    addChild,
    switchChild,
    renameChild,
    removeChild,
  }
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

/* FOUR LIMITS, and a child is watching under all of them at once: whichever
   has the least left is the one that stops them. Each is minutes, or null for
   "no limit over this period" — which is not the same as 0, and 0 still means
   what it always did: no watching at all. */
/* Each period's scale reaches ALMOST the whole period, and stops one step
   short: a cap of six hours inside a rolling six hours forbids nothing, and
   neither does a day inside a day — the top of every scale was another way of
   spelling "no limit", which is the stop past the end. So the last finite
   value is maxMins - stepMins, and maxMins is only ever the period's length.
   The step is whatever is worth dragging at that size: quarter hours inside
   six, whole hours inside a day, four hours inside a week, whole days inside
   a month. */
export const QUOTA_PERIODS = [
  { key: 'per6h', label: 'Every 6 hours', hint: 'a rolling six hours', maxMins: 6 * 60, stepMins: 15 },
  { key: 'perDay', label: 'Each day', hint: 'resets at midnight', maxMins: 24 * 60, stepMins: 60 },
  // a week is counted in DAYS, half a day at a time: four-hour steps made a
  // parent drag through 42 stops to say "about three days"
  { key: 'perWeek', label: 'Each week', hint: 'resets on Sunday', maxMins: 7 * 24 * 60, stepMins: 12 * 60, unit: 'd' },
  { key: 'perMonth', label: 'Each month', hint: 'resets on the 1st', maxMins: 31 * 24 * 60, stepMins: 24 * 60, unit: 'd' },
]

const pad2 = n => String(n).padStart(2, '0')
const localDate = d => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`

/** "3h", "1h 45m", "45m", "0m" */
export function fmtMins(mins) {
  const h = Math.floor(mins / 60)
  const m = Math.round(mins % 60)
  return h && m ? `${h}h ${m}m` : h ? `${h}h` : `${m}m`
}

/**
 * A limit the way its own period says it: days for the week and the month
 * (halves included, so 12 hours is 0.5d rather than a number of hours), one
 * short token everywhere else. The SAME function draws the thumb and the line
 * above it — they disagreed once, "2d" over "48h" for one value, and a
 * settings screen that contradicts itself is worse than a terse one.
 */
export function limitLabel(mins, period) {
  if (mins == null || !Number.isFinite(mins)) return '\u221e'
  if (period?.unit === 'd') {
    const days = mins / (24 * 60)
    return `${Number.isInteger(days) ? days : days.toFixed(1)}d`
  }
  return shortDuration(mins)
}

/** The largest limit worth setting for a period: one step below its length,
 * since the length itself forbids nothing. */
export function lastFiniteLimit({ maxMins, stepMins }) {
  return maxMins - stepMins
}

/** A stored limit that reaches (or passes) its period's length means the same
 * as no limit, and is stored as one — otherwise the slider would show ∞ over
 * a number that is still being compared against. */
export function normalizeQuota(quota = {}) {
  return Object.fromEntries(
    QUOTA_PERIODS.map(p => {
      const v = quota[p.key]
      return [p.key, v == null || v >= p.maxMins ? null : v]
    }),
  )
}

/**
 * ONE TOKEN, never compounded: minutes below an hour, whole hours, whole days.
 * A thumb has room for three characters, and "2d" beats "2880" for a monthly
 * cap as surely as "45" beats "45m" for a short one.
 */
export function shortDuration(mins) {
  if (mins == null || !Number.isFinite(mins)) return '\u221e'
  if (mins < 60) return String(mins)
  if (mins % (24 * 60) === 0) return `${mins / (24 * 60)}d`
  if (mins % 60 === 0) return `${mins / 60}h`
  return String(mins)
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
    last6h: Object.entries(usage.hours).reduce((total, [k, secs]) => (+k > nowHour - 6 ? total + secs : total), 0),
    today: usage.days[localDate(d)] ?? 0,
    session: windowUsed(usage, now),
    last24h: Object.entries(usage.hours).reduce((total, [k, secs]) => (+k > nowHour - 24 ? total + secs : total), 0),
    wtd: daysSince(localDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() - d.getDay()))),
    mtd: daysSince(localDate(new Date(d.getFullYear(), d.getMonth(), 1))),
    ytd: daysSince(localDate(new Date(d.getFullYear(), 0, 1))),
  }
}

/** Seconds watched in each period, from whichever buckets are handed in —
 * pass the merged ones (statsUsage) and the limits hold across devices. */
export function usageByPeriod(usage, now = Date.now()) {
  const d = new Date(now)
  const nowHour = Math.floor(now / HOUR_MS)
  const since = key =>
    Object.entries(usage.days ?? {}).reduce((total, [k, secs]) => (k >= key ? total + secs : total), 0)
  return {
    per6h: Object.entries(usage.hours ?? {}).reduce(
      (total, [k, secs]) => (+k > nowHour - 6 ? total + secs : total),
      0,
    ),
    perDay: (usage.days ?? {})[localDate(d)] ?? 0,
    perWeek: since(localDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() - d.getDay()))),
    perMonth: since(localDate(new Date(d.getFullYear(), d.getMonth(), 1))),
  }
}

/* WHAT A DEVICE PUSHES, versus what it asks the DB for. A device is only an
   authority on its own recent watching: the trailing 6 hours of hour buckets
   (which is exactly what the per6h limit reads) and TODAY's day bucket, the
   only day whose total can still move. Everything longer — this week, this
   month, this year — is summed out of D1 across every device on the pull, so
   re-sending it was ~416 rows of already-settled history every few seconds.

   Today's day bucket is sent even though the hours could in principle add up
   to it, because the day key is LOCAL and an hour key is an epoch hour: the
   Worker cannot derive one from the other without knowing the device's
   timezone, and a day boundary that lands an hour out is a quota that resets
   at the wrong time.

   The FIRST push of a device (no high-water mark yet) carries everything it
   has. A phone used offline for a week and then signed in owes the account
   that history, and it is the one moment nothing else will carry it up. */
export const PUSH_RECENT_HOURS = 6

export function usagePushDelta(usage, since, now = Date.now()) {
  const days = usage?.days ?? {}
  const hours = usage?.hours ?? {}
  if (!since) return { days, hours }
  const oldest = Math.floor(now / HOUR_MS) - PUSH_RECENT_HOURS
  const today = localDate(new Date(now))
  return {
    days: today in days ? { [today]: days[today] } : {},
    hours: Object.fromEntries(Object.entries(hours).filter(([k]) => +k > oldest)),
  }
}

// ---------------------------------------------------------------------------
// today's override — a grown-up changing the limits, or adding minutes to
// them, for TODAY only. It dies at midnight: an exception that outlived the
// evening it was made for would be a parental control that erodes, and a week
// is far too long to owe a decision taken at one bedtime.

/** Midnight ending today, in local time. */
export function endOfDay(now = Date.now()) {
  const d = new Date(now)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime()
}

/** The override if it is still today's, else null. */
export function activeDayOverride(settings, now = Date.now()) {
  const day = settings?.day
  return day && day.until > now ? day : null
}

/** Minutes granted on top of every limit today. */
export function activeBonusMins(settings, now = Date.now()) {
  return activeDayOverride(settings, now)?.bonusMins ?? 0
}

/** The limits actually in force: today's if it replaced them, plus any granted
 * minutes. A period with no limit stays without one — adding time to "no
 * limit" is not a thing that means anything. */
export function effectiveQuota(settings, now = Date.now()) {
  const day = activeDayOverride(settings, now)
  const base = day?.limits ?? settings?.quota ?? {}
  const bonus = day?.bonusMins ?? 0
  return Object.fromEntries(
    QUOTA_PERIODS.map(({ key }) => [key, base[key] == null ? null : base[key] + bonus]),
  )
}

/**
 * Where a child stands against every limit at once: how long they have left
 * (the tightest one), which period that is, and what that limit is — the
 * player's meter needs all three to draw a bar that means something.
 */
export function quotaState(settings, watchStore, now = Date.now()) {
  const used = usageByPeriod(statsUsage(watchStore), now)
  const limits = effectiveQuota(settings, now)
  let secsLeft = Infinity
  let limitSecs = Infinity
  let period = null
  for (const { key } of QUOTA_PERIODS) {
    if (limits[key] == null) continue
    const left = limits[key] * 60 - (used[key] ?? 0)
    if (left < secsLeft) {
      secsLeft = left
      limitSecs = limits[key] * 60
      period = key
    }
  }
  return { secsLeft, limitSecs, period, used, limits, blocked: secsLeft <= 0 }
}

// ---------------------------------------------------------------------------
// useWatchStore

const WATCH_KEY = 'tinytube:v1'
const MAX_ENTRIES = 500
/* Watched, in one number, used everywhere: the badge on a card, the bucket
   that sinks to the bottom of the grid, what the hide-watched setting hides,
   and what saveProgress latches as `completed`. Past this it's just
   credits/outros — 90% is the parent-facing definition and there is no second
   one hiding in another file. */
export const WATCHED_THRESHOLD = 0.9
const LIKED_THRESHOLD = 0.2 // bailed before this -> probably didn't like it

const EMPTY_REMOTE = { days: {}, hours: {} }

/* One history per CHILD: progress and the watch quota both belong to the child
   watching, not to the device. The pre-children blob lives at the bare key and
   is adopted by the first child (FIRST_CHILD_ID), which is why that id is
   fixed. */
export function watchKey(childId) {
  return `${WATCH_KEY}:${childId ?? FIRST_CHILD_ID}`
}

function loadWatchStore(childId) {
  try {
    const raw =
      localStorage.getItem(watchKey(childId)) ??
      (childId === FIRST_CHILD_ID || childId == null ? localStorage.getItem(WATCH_KEY) : null)
    const parsed = JSON.parse(raw) ?? {}
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

function persist(store, childId) {
  const ids = Object.keys(store.watched)
  if (ids.length > MAX_ENTRIES) {
    ids.sort((a, b) => store.watched[a].updatedAt - store.watched[b].updatedAt)
    for (const id of ids.slice(0, ids.length - MAX_ENTRIES)) delete store.watched[id]
  }
  localStorage.setItem(watchKey(childId), JSON.stringify(store))
}

export function fraction(entry) {
  if (!entry) return 0
  if (entry.completed) return 1
  return entry.dur ? Math.min(entry.pos / entry.dur, 1) : 0
}

export function useWatchStore(childId = FIRST_CHILD_ID) {
  /* The store carries WHOSE it is, and every writer checks before writing.
     There is one `setStore` for every child, so a write whose childId came
     from an earlier render would otherwise land on whoever is showing now:
     a /sync/pull for A that resolves after the parent switched to B put A's
     rows AND A's account-wide usage into B's store, and B's quota then read
     A's watch time out of `remote`. Dropping the late write is right rather
     than merely safe — the pull's answer describes a child nobody is looking
     at, and the next launch pulls it again. */
  const [store, setStore] = useState(() => ({ ...loadWatchStore(childId), childId }))
  const forThisChild = useCallback(
    fn => setStore(prev => (prev.childId === childId ? fn(prev) : prev)),
    [childId],
  )

  // switching child swaps the whole history under us
  useEffect(() => {
    setStore({ ...loadWatchStore(childId), childId })
  }, [childId])

  const saveProgress = useCallback((id, pos, dur) => {
    forThisChild(prev => {
      const entry = prev.watched[id]
      const completed = (entry?.completed ?? false) || (dur > 0 && pos / dur > WATCHED_THRESHOLD)
      const next = {
        ...prev,
        lastVideoId: id,
        watched: { ...prev.watched, [id]: { pos, dur, completed, updatedAt: Date.now() } },
      }
      persist(next, childId)
      return next
    })
  }, [forThisChild, childId])

  const markCompleted = useCallback(id => {
    forThisChild(prev => {
      const entry = prev.watched[id] ?? { pos: 0, dur: 0 }
      const next = {
        ...prev,
        lastVideoId: id,
        watched: { ...prev.watched, [id]: { ...entry, completed: true, updatedAt: Date.now() } },
      }
      persist(next, childId)
      return next
    })
  }, [forThisChild, childId])

  const addWatchTime = useCallback(secs => {
    forThisChild(prev => {
      const next = { ...prev, usage: accrueUsage(prev.usage, secs) }
      persist(next, childId)
      return next
    })
  }, [forThisChild, childId])

  /* A /sync/pull's answer folded in: watched merges row-wise LWW; the
     account-wide usage sums land in `remote`, NEVER in `usage` — usage is what
     this device pushes back up, so folding remote into it would compound
     everyone's totals on every round trip. */
  const applyRemote = useCallback(({ watched, usage }) => {
    forThisChild(prev => {
      const next = {
        ...prev,
        watched: mergeWatched(prev.watched, watched),
        remote: usage ? { ...EMPTY_REMOTE, ...usage } : prev.remote,
      }
      persist(next, childId)
      return next
    })
  }, [forThisChild, childId])

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
 * 1. continue watching (20-90% done), closest to finished first
 * 2. fresh videos, round-robin across channels (newest first within a channel)
 *    so a high-volume channel can't flood out a quiet one
 * 3. abandoned (<20%, started but bailed), same round-robin
 * 4. watched (>90%) last — or not at all, when the parent has asked for them
 *    to be hidden. Sinking them is the default because a child who wants to
 *    watch something again should still be able to find it; hiding is the
 *    stronger version of the same idea and belongs to the parent.
 */
export function gallerySort(channels, watched, { hideWatched = false } = {}) {
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
    ...(hideWatched ? [] : done),
  ]
}

// ---------------------------------------------------------------------------
// useVideos

/**
 * Gallery data: parent-approved channels, with titles, avatars and videos
 * from the Worker's shared cache. There is no built-in catalog — an empty
 * approved list is an empty grid.
 */
export function useVideos(settings) {
  const [customVideosById, setCustomVideosById] = useState({})

  /* ONE request for every approved channel, and what comes back is the title
     and avatar as well as the videos: this app stores the parent's decision
     (which ids, what ages) and the Worker owns the facts about the channels
     themselves. Keyed on the ids alone, so an age edit does not refetch. */
  const { apiKey } = settings
  const customIds = settings.customChannels.map(ch => ch.channel_id).join(',')
  useEffect(() => {
    let cancelled = false
    const ids = customIds ? customIds.split(',') : []
    if (!ids.length) {
      setCustomVideosById({})
      return
    }
    getChannelsCached(apiKey, ids).then(byId => {
      if (!cancelled) setCustomVideosById(byId)
    })
    return () => {
      cancelled = true
    }
  }, [apiKey, customIds])

  const channels = useMemo(
    () => mergeChannels(customVideosById, settings),
    [customVideosById, settings],
  )

  // customById is what the parent's channel list hydrates its rows from
  return { channels, customById: customVideosById }
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
/* How often a re-pull is actually answered. Every playback asks; this is what
   keeps that from being a request per video. `force` bypasses it. */
export const PULL_MIN_MS = 15 * 60_000
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
  const [pulling, setPulling] = useState(false)
  /* When each child was last pulled, so a re-pull can be asked for freely and
     answered at most every PULL_MIN_MS. A ref, not storage: every launch is
     entitled to a fresh pull, and a child never pulled has no entry at all. */
  const pulledAt = useRef({})
  const timer = useRef(null)
  const childId = settingsStore.settings.childId

  const signOut = useCallback(() => {
    saveSyncSession(null)
    setSession(null)
    pulledAt.current = {}
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
    pulledAt.current = {}
    setSession(session)
    return email
  }, [])

  // pull on boot / sign-in / child switch
  const { save } = settingsStore
  const { applyRemote } = watchStore
  // the STORED blob (every child), not the flattened view — the view's child
  // fields are a copy, and pushing them would round-trip duplicates
  const stored = settingsStore.stored ?? settingsStore.settings
  /* Ask the DB what it has for this child. Throttled to PULL_MIN_MS so it can
     be called from anywhere without anyone counting — every playback asks,
     and most asks cost nothing. `force` is for the two cases where the answer
     is the point rather than a refresh: the parent's Refresh button, and a
     child standing at a spent quota, where the DB is the only place a grown-up
     could have granted more time from another device.

     Returns the remote payload when it actually pulled, else null. */
  const pull = useCallback(
    async ({ force = false } = {}) => {
      if (!session) return null
      const last = pulledAt.current[childId] ?? 0
      if (!force && Date.now() - last < PULL_MIN_MS) return null
      pulledAt.current[childId] = Date.now()
      setPulling(true)
      try {
        const remote = await syncFetch('/sync/pull', { child: childId }, session.token)
        applyRemote({ watched: remote.watched, usage: remote.usage })
        if (remote.settings && (remote.settings.updatedAt ?? 0) > (stored.updatedAt ?? 0)) {
          // keep the remote stamp: adopting a blob is not an edit
          save({ ...remote.settings.data, updatedAt: remote.settings.updatedAt })
        }
        return remote
      } catch (err) {
        // a failed pull is not a pull: let the next ask through immediately
        pulledAt.current[childId] = last
        dead(err)
        return null
      } finally {
        setPulling(false)
      }
    },
    [session, childId, stored, save, applyRemote, dead],
  )

  // pull on boot / sign-in / child switch — a child with no mark yet is due one
  useEffect(() => {
    pull()
  }, [session, childId]) // eslint-disable-line react-hooks/exhaustive-deps

  // push deltas, debounced, whenever local state moves
  const { watched, usage } = watchStore
  useEffect(() => {
    if (!session) return
    clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      // per child: each has its own history, so its own high-water mark
      const marks = typeof session.lastPushAt === 'object' ? session.lastPushAt : {}
      const since = marks[childId] ?? 0
      const payload = {
        child: childId,
        // this device's recent watching only — see usagePushDelta
        usage: { deviceId: session.deviceId, ...usagePushDelta(usage, since) },
      }
      const deltas = watchedDeltas(watched, since)
      if (deltas.length) payload.watched = deltas
      if ((stored.updatedAt ?? 0) > since) {
        payload.settings = { data: stored, updatedAt: stored.updatedAt }
      }
      syncFetch('/sync/push', payload, session.token)
        .then(() => {
          const next = { ...session, lastPushAt: { ...marks, [childId]: Date.now() } }
          saveSyncSession(next)
          setSession(next)
        })
        .catch(dead)
    }, PUSH_DEBOUNCE_MS)
    return () => clearTimeout(timer.current)
  }, [session, stored, watched, usage, childId, dead])

  return { session, signIn, signOut, pull, pulling }
}
