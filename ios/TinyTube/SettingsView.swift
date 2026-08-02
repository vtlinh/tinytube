import SwiftUI
import TinyTubeCore

/* The parent's choices and what the app is. Counterpart of SettingsActivity.

   In parent mode, because that is where every other parent control already is.
   It had a button on the child's status bar for one build; that bar holds
   exactly one control now, and this is not it. About used to be a screen of its
   own opened by long-pressing the grid's title — a parent-facing screen on the
   child's side, behind a gesture nobody would guess was there — and leads this
   one instead.

   THE APPROVED LIST IS NOT IN HERE. It was for one build, as the list itself
   rather than a row that opened a screen — which was right while it was a flat
   column. Groups gave it a selection mode, a title that becomes "3 selected"
   and a name sheet, and a Section cannot be any of those things: a long-press
   that starts selecting in among the pickers is a trap, and a header has one
   state. It is ApprovedChannelsView again, on the same bar this screen opens
   from.

   EVERY EXPLANATION IS BEHIND THE ? BESIDE ITS HEADING. They used to sit under
   the headings in grey, permanently, and four of them was most of the screen.
   The words are the same; they are just no longer in the way of the controls
   they describe.

   AN ALERT RATHER THAN A POPOVER, and that is a deployment-target fact rather
   than a preference: `.popover` on an iPhone only STAYS a popover from 16.4,
   via presentationCompactAdaptation, and this app targets 16.0 — where it takes
   over the whole screen, which is worse than the alert. Android gets a real
   anchored popup because it can; see Tooltip.kt.

   No update controls here, unlike Android: there is no self-update on iOS. See
   README's Platform differences and Distribution. */
struct SettingsView: View {

    @Environment(\.dismiss) private var dismiss

    @State private var nextMode: Playlist.Mode = .inOrder
    @State private var holdSeconds = HoldTime.defaultSeconds

    @State private var showingHelp = false
    @State private var helpTitle = ""
    @State private var helpBody = ""

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "dev"
        let build = info?["CFBundleVersion"] as? String ?? "0"
        return "\(short) (\(build))"
    }

    /* EVERY SECTION IS ITS OWN PROPERTY, and that is not a stylistic choice.
       Written inline, this Form was one expression large enough that Swift gave
       up on it — "unable to type-check this expression in reasonable time",
       which is a BUILD FAILURE, and one only the macOS job can produce.
       `swiftc -parse` cannot see it: parsing succeeds and it is type checking
       that runs out of budget. Adding a section back inline is how this breaks
       again. */
    var body: some View {
        NavigationStack {
            Form {
                about
                whenAVideoEnds
                holdToUnlock
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            /* THE TWO ALERTS ARE ON DIFFERENT VIEWS ON PURPOSE — this one on
               the Form, the other on the NavigationStack. Two `.alert`
               modifiers chained onto the SAME view is a long-standing SwiftUI
               trap: the second silently never presents, and the symptom is a
               button that appears to do nothing. */
            .alert(helpTitle, isPresented: $showingHelp) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(helpBody)
            }
        }
        .onAppear {
            nextMode = SettingsStore.nextMode()
            holdSeconds = SettingsStore.holdSeconds()
        }
    }

    // MARK: - Sections

    private var about: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text("TinyTube").font(.headline)
                Text("Version \(version)")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
            }
        }
    }

    private var whenAVideoEnds: some View {
        Section {
            Picker("", selection: $nextMode) {
                Text("Play the next one down the list").tag(Playlist.Mode.inOrder)
                Text("Play a random one").tag(Playlist.Mode.random)
            }
            .pickerStyle(.inline)
            .labelsHidden()
            .onChange(of: nextMode) { SettingsStore.setNextMode($0) }
        } header: {
            /* The two paragraphs this card used to print — what the choice
               does, and what "the list" means — are one tooltip rather than two
               question marks a millimetre apart. */
            heading("When a video ends", help: """
                Another video starts automatically. Choose which one.

                Either way it comes from whatever was on screen: everything, or \
                just that channel if you opened one. Playing in order stops at \
                the end of the list.
                """)
        }
    }

    private var holdToUnlock: some View {
        Section {
            /* One to five seconds. Five is the ceiling because a hold nobody
               will sit through is not a stronger lock, it is a control an adult
               gives up on — what keeps a child out is that the corner is
               invisible and somewhere nothing else is. */
            Stepper(
                value: $holdSeconds,
                in: HoldTime.minSeconds...HoldTime.maxSeconds
            ) {
                Text(holdLabel)
            }
            .onChange(of: holdSeconds) { SettingsStore.setHoldSeconds($0) }
        } header: {
            heading("Hold to unlock the player", help: """
                How long the corner of the player has to be held before \
                YouTube's own controls become reachable. Longer is harder for a \
                child to trigger by resting a thumb.
                """)
        }
    }

    // MARK: - Pieces

    /* A section heading and the ? that explains it. */
    private func heading(_ title: String, help: String) -> some View {
        HStack(spacing: 2) {
            Text(title)
            Button {
                helpTitle = title
                helpBody = help
                showingHelp = true
            } label: {
                Image(systemName: "questionmark.circle")
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.secondary)
            .accessibilityLabel("About \(title)")
            .accessibilityHint(help)
        }
    }

    // MARK: - Values

    private var holdLabel: String {
        "\(holdSeconds) second\(holdSeconds == 1 ? "" : "s")"
    }
}
