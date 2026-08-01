import SwiftUI

/* The app's entry point.

   One scene, and the child's grid is it. Everything a parent can do is behind
   `MainView`'s single status-bar control and the gate behind that — there is no
   second entry point, and adding one would be skipping the gate once. */
@main
struct TinyTubeApp: App {
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
