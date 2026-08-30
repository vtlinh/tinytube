/** App shell: view routing (gallery | player | gate | settings | quota), the
 * parent gate dispatch, and the browser boot. */

import { StrictMode, useCallback, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import 'bootstrap/dist/css/bootstrap.min.css'
import './styles.css'
import {
  useSettings,
  useVideos,
  useWatchStore,
  useSync,
  verify,
  isBiometricAvailable,
  quotaState,
  nextVideoIndex,
} from './lib.js'
import Gallery from './gallery.jsx'
import PlayerView from './player.jsx'
import Settings from './settings.jsx'
import { EnrollGate, MathGate, QuotaGate, ChildPicker } from './landing.jsx'

export default function App() {
  const store = useSettings()
  const { channels, customById } = useVideos(store.settings)
  // one history per child: switching child swaps the grid's progress and the quota
  const watchStore = useWatchStore(store.settings.childId)
  const sync = useSync(store, watchStore) // inert until a parent signs in (Settings -> Sync)
  // what is playing: the list the child tapped on and where in it we are, or
  // null. The list travels with the tap so what plays next can never leave the
  // slice of the grid that was on screen.
  const [current, setCurrent] = useState(null) // {list, index} | null
  const [view, setView] = useState('gallery') // 'gallery' | 'gate' | 'settings' | 'quota'
  const [biometric, setBiometric] = useState(null) // null = still checking
  const [granting, setGranting] = useState(null) // minutes waiting on the gate

  /* The newest settings and history, readable from an async handler. A tap
     handler that awaits a pull holds closure variables from the render it was
     created in, and the whole point of that await is to read what the pull
     just changed. */
  const latest = useRef(null)
  latest.current = { settings: store.settings, watchStore }

  useEffect(() => {
    isBiometricAvailable().then(setBiometric)
  }, [])

  // player/gate/settings are history entries so the browser back button
  // (and iOS edge-swipe) lands back on the gallery instead of leaving the app
  useEffect(() => {
    const onPop = () => {
      setCurrent(null)
      setView('gallery')
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  const open = fn => arg => {
    history.pushState({ tinytube: true }, '')
    fn(arg)
  }
  const close = useCallback(() => history.back(), []) // popstate does the state reset
  const openSettings = useCallback(() => setView('settings'), [])

  if (biometric === null) {
    return (
      <div className="d-flex vh-100 align-items-center justify-content-center">
        <div className="spinner-border text-danger" role="status" />
      </div>
    )
  }

  // first run on a biometric-capable device: enroll before anything else
  if (biometric && !store.settings.passkeyId) {
    return <EnrollGate onEnrolled={store.setPasskey} />
  }

  if (current) {
    return (
      <PlayerView
        video={current.list[current.index]}
        watchStore={watchStore}
        settings={store.settings}
        onExit={close}
        /* One finished, so play the next one the child's mode asks for — in
           order, or a random one they have not seen. `nextVideoIndex` reads
           watched off the same store the grid's badges do; returning null
           means the list is spent, and then the player closes exactly as it
           always did. This does NOT re-check the quota: the counting loop
           stops playback the moment it runs out, whichever video is on. */
        onEnded={() => {
          const i = nextVideoIndex(current.list, current.index, store.settings.playback, watchStore.watched)
          if (i == null) close()
          else setCurrent({ ...current, index: i })
        }}
        onQuotaExhausted={() => {
          // same history depth: the player's entry becomes the quota screen's,
          // so back still lands on the gallery
          setCurrent(null)
          setView('quota')
        }}
      />
    )
  }

  if (view === 'gate') {
    return (
      <MathGate
        onPass={openSettings} // same history depth: back from settings -> gallery
        onFail={close}
      />
    )
  }

  if (view === 'settings') {
    return <Settings customById={customById} store={store} watchStore={watchStore} sync={sync} onDone={close} fromGate />
  }

  // enrolled device -> OS biometric prompt (called inside the tap handler to
  // keep iOS user activation); otherwise the math gate bootstraps enrollment.
  // Returns the view to show, or null (biometric cancelled/failed).
  const parentGate = async () => {
    if (store.settings.passkeyId) return (await verify(store.settings.passkeyId)) ? 'settings' : null
    return 'gate'
  }

  /* Extra time is a parent's decision, so it goes through the same gate parent
     mode does: the biometric where there is one, the arithmetic where there is
     not. The grant itself lasts the week — see addBonusMins. */
  const grant = mins => {
    store.addBonusMins(mins)
    setGranting(null)
    close() // back to the grid, with time on the clock
  }

  /* Choosing whose feed this is changes which channels are approved, so it
     goes through the gate exactly as parent mode does. */
  if (view === 'children') {
    return (
      <ChildPicker
        children={store.children}
        activeId={store.settings.childId}
        onPick={id => {
          store.switchChild(id)
          close()
        }}
        onCancel={close}
      />
    )
  }

  if (view === 'children-gate') {
    return <MathGate onPass={() => setView('children')} onFail={() => close()} />
  }

  if (view === 'grant-gate') {
    return (
      <MathGate
        onPass={() => grant(granting)}
        onFail={() => {
          setGranting(null)
          setView('quota')
        }}
      />
    )
  }

  if (view === 'quota') {
    return (
      <QuotaGate
        onParents={async () => {
          const v = await parentGate()
          if (v) setView(v) // same history depth, like MathGate onPass
        }}
        onAddTime={async mins => {
          // called from inside the tap handler, which is what keeps iOS's
          // user activation alive for the biometric prompt
          if (store.settings.passkeyId) {
            if (await verify(store.settings.passkeyId)) grant(mins)
            return
          }
          setGranting(mins)
          setView('grant-gate') // same history depth as the quota screen
        }}
        onBack={close}
      />
    )
  }

  const onParents = async () => {
    const v = await parentGate()
    if (v) open(() => setView(v))()
  }

  const onSwitchChild = async () => {
    // inside the tap handler, so iOS still counts it as user activation
    if (store.settings.passkeyId) {
      if (await verify(store.settings.passkeyId)) open(() => setView('children'))()
      return
    }
    open(() => setView('children-gate'))()
  }

  return (
    <Gallery
      channels={channels}
      childName={store.settings.childName}
      canSwitchChild={store.children.length > 1}
      onSwitchChild={onSwitchChild}
      groups={store.settings.groups}
      groupOf={store.settings.groupOf}
      hideWatched={store.settings.hideWatched}
      watchStore={watchStore}
      onPlay={async (list, index) => {
        // checked at tap time, not render time: a 12h window that expires while
        // the gallery sits idle must unblock immediately (expiry is lazy).
        // statsUsage folds in the synced account-wide usage, so switching
        // devices doesn't reset the meter
        if (!quotaState(store.settings, watchStore).blocked) {
          /* Every playback is an occasion to catch up with the DB — a quota
             or a channel list changed on another device reaches this one
             here. Not awaited and throttled to PULL_MIN_MS inside `pull`, so
             the tap is never held up by the network. */
          sync.pull()
          open(setCurrent)({ list, index })
          return
        }
        /* At the limit, the DB is the whole answer rather than a refresh: a
           grown-up may have granted time from another device, and that grant
           is in `day.bonusMins` inside the settings blob. So this one is
           forced past the throttle and awaited — then re-read from `latest`,
           because the pull is what just moved it. */
        await sync.pull({ force: true })
        const now = latest.current
        if (quotaState(now.settings, now.watchStore).blocked) open(() => setView('quota'))()
        else open(setCurrent)({ list, index })
      }}
      onParents={onParents}
    />
  )
}

// browser boot; absent in jsdom where tests import <App/> directly
const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}
