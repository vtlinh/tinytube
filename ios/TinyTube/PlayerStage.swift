import SwiftUI
import UIKit

/* Two small pieces of UIKit the player needs and SwiftUI does not offer.

   Both exist because PlayerActivity gets them from Android for free: one from
   dispatchTouchEvent, the other from a single line of manifest. */

/* Every touch anywhere on the player, WITHOUT consuming it.
 *
 * The counterpart of PlayerActivity.dispatchTouchEvent. While the overlay is
 * lifted the touches that matter go to the web view — scrubbing, tapping to
 * bring YouTube's controls up — and SwiftUI gestures that could see them would
 * also swallow them. Without this the idle countdown expires mid-scrub, which
 * is exactly the moment an adult is using the player.
 *
 * `cancelsTouchesInView = false` plus a delegate that recognises alongside
 * everything else is what makes it observation rather than interception. */
struct TouchReporter: UIViewRepresentable {

    let onTouch: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onTouch: onTouch) }

    func makeUIView(context: Context) -> UIView {
        let view = PassthroughView()
        let recogniser = UILongPressGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handle(_:))
        )
        /* Zero duration so it fires on touch-down rather than after a press. */
        recogniser.minimumPressDuration = 0
        recogniser.cancelsTouchesInView = false
        recogniser.delaysTouchesBegan = false
        recogniser.delaysTouchesEnded = false
        recogniser.delegate = context.coordinator
        /* ⚠️ ON THE WINDOW, NOT ON THIS VIEW.
         *
         * UIKit delivers a touch to the recognisers attached to the touch's
         * hit-test view and to that view's ANCESTORS. This view refuses to be
         * the hit-test view — that is the whole point of it, see below — and in
         * PlayerView's ZStack it is a SIBLING layered above the web view rather
         * than an ancestor of it. So a recogniser added here was never sent a
         * single touch and `onTouch` never ran: the idle countdown expired
         * mid-scrub every time, which is precisely the moment an adult is using
         * the player and the one thing this file exists to prevent.
         *
         * The window IS an ancestor of everything, and with
         * cancelsTouchesInView false this stays observation rather than
         * interception. */
        view.observer = recogniser
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.onTouch = onTouch
    }

    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) {
        (view as? PassthroughView)?.observer = nil
    }

    /* Hit-tests as if it were not there, so the web view underneath still gets
       everything, and carries the window-level recogniser for as long as it is
       on screen. */
    final class PassthroughView: UIView {
        override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }

        /* Attached when this view joins a window and taken off when it leaves,
           so nothing outlives the lifted overlay. */
        var observer: UIGestureRecognizer? {
            didSet {
                if let oldValue { oldValue.view?.removeGestureRecognizer(oldValue) }
                attach()
            }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            attach()
        }

        private func attach() {
            guard let observer else { return }
            guard let window else {
                observer.view?.removeGestureRecognizer(observer)
                return
            }
            if observer.view !== window {
                observer.view?.removeGestureRecognizer(observer)
                window.addGestureRecognizer(observer)
            }
        }

        deinit {
            if let g = observer { g.view?.removeGestureRecognizer(g) }
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onTouch: () -> Void
        init(onTouch: @escaping () -> Void) { self.onTouch = onTouch }

        @objc func handle(_ recogniser: UIGestureRecognizer) {
            if recogniser.state == .began { onTouch() }
        }

        func gestureRecognizer(
            _ g: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool { true }
    }
}

/* Which way up the app is allowed to be.
 *
 * PlayerActivity says `android:screenOrientation="sensorLandscape"` and Android
 * does the rest. iOS has no per-screen equivalent: the only thing consulted is
 * the app delegate, once, for the whole window — so the player writes what it
 * wants here and the delegate reads it.
 *
 * Landscape for the player because a 16:9 video in portrait is a letterboxed
 * strip with most of the screen wasted, and because the overlay, the reveal
 * corner and the measured bottom blocker are all sized against the picture.
 * `.landscape` rather than one side of it, matching sensorLandscape: a child
 * holding the phone the other way up should get a rotated picture, not an
 * upside-down one. */
enum OrientationLock {

    /* Read by the app delegate. Everything else in the app is happy either way,
       so the default is unrestricted. */
    static var allowed: UIInterfaceOrientationMask = .all

    static func lockToLandscape() {
        allowed = .landscape
        apply(.landscape)
    }

    static func unlock() {
        allowed = .all
        apply(.all)
    }

    /* Setting `allowed` only changes what iOS will PERMIT on the next rotation;
       it does not turn a device that is currently portrait. This asks for the
       change now. */
    private static func apply(_ mask: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive })
        else { return }

        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in
            /* Refused is survivable: on iPad with multitasking, and in a few
               other cases, the system simply declines. The player is still
               usable, just possibly portrait — so this is not worth an error
               path, and definitely not worth a crash. */
        }
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}
