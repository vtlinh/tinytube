import SwiftUI
import TinyTubeCore

/* The app's entry point, and at this commit not much else.

   What exists here is the shell the build pipeline needs to have something to
   compile, archive and hand back as an unsigned IPA. The grid, the Channels
   tab, the player, parent mode and the stores are still to come; this is
   deliberately not a stub of any of them, because a placeholder shaped like the
   real screen is how a half-built app gets mistaken for a working one.

   What it does do is prove the two things the pipeline cannot otherwise show:
   that the app target links `TinyTubeCore` and can call into it, and that a
   build produced with no signing identity at all still archives. */
@main
struct TinyTubeApp: App {
    var body: some Scene {
        WindowGroup {
            ScaffoldView()
        }
    }
}

private struct ScaffoldView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("TinyTube")
                .font(.largeTitle.weight(.semibold))
            Text("iOS build scaffold — no screens yet.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            /* Reaching into the shared layer on purpose. If the package ever
               stops being linked into the app target this line is what fails,
               at compile time, rather than the first screen that needs it
               failing much later. */
            Text(verbatim: "core linked: \(VideoId.isValid("dQw4w9WgXcQ"))")
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
        .foregroundStyle(.white)
    }
}
