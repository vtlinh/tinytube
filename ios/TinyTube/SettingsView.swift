import SwiftUI
import TinyTubeCore

/* The parent's choices, plus what the app is. Counterpart of SettingsActivity.

   In parent mode, next to the approved list, because that is where every other
   parent control already is. It had a button on the child's status bar for one
   build; that bar holds exactly one control now, and this is not it. About used
   to be a screen of its own opened by long-pressing the grid's title — a
   parent-facing screen on the child's side, behind a gesture nobody would guess
   was there — and leads this one instead.

   No update controls here, unlike Android: there is no self-update on iOS. See
   README's Platform differences and Distribution. */
struct SettingsView: View {

    /* The approved list lives in here now, and it can hand back a channel to go
       and look at — which this screen cannot act on, because ParentView owns the
       web view. So it is passed straight up. Settings is a pass-through for that
       one value, exactly as SettingsActivity is on Android. */
    let onOpenChannel: (Channel) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var channelCount = 0

    @State private var nextMode: Playlist.Mode = .inOrder
    @State private var holdSeconds = HoldTime.defaultSeconds

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "dev"
        let build = info?["CFBundleVersion"] as? String ?? "0"
        return "\(short) (\(build))"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("TinyTube").font(.headline)
                        Text("Version \(version)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("When a video ends") {
                    Picker("", selection: $nextMode) {
                        Text("Play the next one down the list").tag(Playlist.Mode.inOrder)
                        Text("Play a random one").tag(Playlist.Mode.random)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    .onChange(of: nextMode) { SettingsStore.setNextMode($0) }

                    Text("Either way the list is whatever was on screen when the video was tapped. A video started inside a channel cannot lead out of it.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Hold to unlock the player") {
                    /* One to five seconds. Five is the ceiling because a hold
                       nobody will sit through is not a stronger lock, it is a
                       control an adult gives up on — what keeps a child out is
                       that the corner is invisible and somewhere nothing else
                       is. */
                    Stepper(
                        value: $holdSeconds,
                        in: HoldTime.minSeconds...HoldTime.maxSeconds
                    ) {
                        Text("\(holdSeconds) second\(holdSeconds == 1 ? "" : "s")")
                    }
                    .onChange(of: holdSeconds) { SettingsStore.setHoldSeconds($0) }
                }

                Section {
                    Text("Approving a channel approves whatever it posts next, which no adult will have seen first. Choose channels you would trust unattended, and check back on them.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                /* LAST on the page, matching Android. It opens a screen of its
                   own, and a control that leaves is a poor thing to meet before
                   the ones that just toggle something.
                 *
                 * The count is there because "Approved channels" with no number
                 * gives a parent no reason to look, and no way to notice the
                 * list has gone empty. */
                Section("Channels") {
                    NavigationLink {
                        ApprovedChannelsView { channel in
                            dismiss()
                            onOpenChannel(channel)
                        }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Approved channels")
                                Text(channelCount == 0
                                     ? "Nothing approved yet"
                                     : "\(channelCount) approved")
                                    .font(.footnote)
                                    .foregroundStyle(channelCount == 0 ? .secondary : .tint)
                            }
                        }
                    }

                    Text("The channels your child can watch. This is the parental control — nothing else decides what appears in the grid.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear {
            nextMode = SettingsStore.nextMode()
            holdSeconds = SettingsStore.holdSeconds()
            channelCount = ChannelStore.all().count
        }
    }
}
