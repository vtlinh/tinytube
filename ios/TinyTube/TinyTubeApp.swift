import SwiftUI

/* The app's entry point.

   One scene, and the child's grid is it. Everything a parent can do is behind
   `MainView`'s single status-bar control and the gate behind that — there is no
   second entry point, and adding one would be skipping the gate once. */
/* The only thing this exists for is orientation.
 *
 * iOS asks the APP DELEGATE which orientations are allowed — there is no
 * per-screen setting, which is what Android's `android:screenOrientation` on
 * PlayerActivity gives it in one line. So the player writes its wish into
 * OrientationLock and this hands it back. */
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLock.allowed
    }
}

@main
struct TinyTubeApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            MainView()
                /* Dark regardless of the system setting, matching Android and
                   for the same reason: nearly all of this app's screen time is
                   a video on black, and a grid that follows the phone flashes
                   white on every return from the player. Info.plist sets
                   UIUserInterfaceStyle too; this covers anything SwiftUI would
                   otherwise resolve per-view. */
                .preferredColorScheme(.dark)
        }
    }
}
