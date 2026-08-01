import SwiftUI
import TinyTubeCore

/* The parent's choices, what the app is, and the approved list. Counterpart of
   SettingsActivity.

   In parent mode, because that is where every other parent control already is.
   It had a button on the child's status bar for one build; that bar holds
   exactly one control now, and this is not it. About used to be a screen of its
   own opened by long-pressing the grid's title — a parent-facing screen on the
   child's side, behind a gesture nobody would guess was there — and leads this
   one instead.

   THE APPROVED LIST IS IN HERE, and as the list rather than as a row that opens
   one. ApprovedChannelsView is gone: a whole screen existed to show three or
   four rows, and the question a parent came to settings with is usually "what
   IS approved", which is now answered by looking.

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

    /* The list can hand back a channel to go and look at, which this screen
       cannot act on — ParentView owns the web view. So it is passed straight
       up, exactly as SettingsActivity does it with EXTRA_OPEN_URL. */
    let onOpenChannel: (Channel) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var nextMode: Playlist.Mode = .inOrder
    @State private var holdSeconds = HoldTime.defaultSeconds

    @State private var channels: [Channel] = []
    @State private var sortMode: ChannelSort.Mode = .lastAdded
    @State private var sortWindow: Int?
    @State private var pendingRemoval: Channel?

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
                channelsSection
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
            reloadChannels()
        }
        .alert(
            "Remove \(pendingRemoval?.title ?? "")?",
            isPresented: Binding(
                get: { pendingRemoval != nil },
                set: { if !$0 { pendingRemoval = nil } }
            )
        ) {
            /* Confirmed, because it is destructive and one row looks much like
               another on a small screen. */
            Button("Remove", role: .destructive) {
                if let channel = pendingRemoval { remove(channel) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Its videos will stop appearing, and its watch history goes with them.")
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

    /* LAST on the page, matching Android: it is the longest thing here, and the
       controls above it are the ones somebody scrolls to. */
    private var channelsSection: some View {
        Section {
            if channels.isEmpty {
                Text("Nothing is approved, so the grid is empty. Browse to a channel in parent mode and tap + to approve it.")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
            } else {
                ForEach(channels) { channel in
                    row(channel)
                }
            }
        } header: {
            channelsHeader
        }
    }

    private var channelsHeader: some View {
        HStack(spacing: 0) {
            heading("Channels", help: """
                The channels your child can watch. This is the parental control \
                — nothing else decides what appears in the grid.

                Approving a channel approves whatever it posts next, which no \
                adult will have seen first. Choose channels you would trust \
                unattended, and check back on them.
                """)

            Spacer()

            /* Cycles the three orders, and the label is what makes cycling
               legible: without it the list silently rearranges itself and
               nothing says why. It also has to name the case where "most
               watched" fell through to A–Z, or an empty history looks like a
               broken sort. */
            Button {
                SettingsStore.setChannelSort(ChannelSort.next(sortMode))
                reloadChannels()
            } label: {
                HStack(spacing: 4) {
                    Text(orderLabel)
                    Image(systemName: "arrow.up.arrow.down")
                }
            }
            .textCase(nil)
            .accessibilityLabel("Change the order")
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

    /* Tapping the row opens the channel; the ✕ removes it. Two targets in one
       row, which is exactly where SwiftUI is fussy: a row that is itself a
       Button with another Button inside it hands every tap to whichever one
       SwiftUI decides owns the row. So the open half is a tap gesture on the
       content and only the ✕ is a Button — the pattern that reliably gives a
       List row two independent targets. Android keeps them well apart for the
       same reason, in thumbs rather than in code. */
    private func row(_ channel: Channel) -> some View {
        HStack(spacing: 12) {
            /* scaledToFill for the same reason as the grid's posters: a bare
               resizable stretches a non-square image instead of cropping it. */
            AsyncImage(url: channel.avatarURL.flatMap(URL.init(string:))) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Circle().fill(Color.secondary.opacity(0.2))
            }
            .frame(width: 36, height: 36)
            .clipShape(Circle())

            VStack(alignment: .leading, spacing: 2) {
                Text(channel.title).lineLimit(1)
                /* the handle if we have one, the id otherwise — something to
                   tell two similarly-named channels apart by */
                Text(channelSubtitle(channel))
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
                    .lineLimit(1)
            }

            Spacer()

            Button {
                pendingRemoval = channel
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(Color.red)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Remove \(channel.title)")
        }
        /* The whole row, not just the words: a gesture on an HStack reaches
           only where something is drawn without it. */
        .contentShape(Rectangle())
        .onTapGesture {
            dismiss()
            onOpenChannel(channel)
        }
    }

    // MARK: - Values

    private var holdLabel: String {
        "\(holdSeconds) second\(holdSeconds == 1 ? "" : "s")"
    }

    private func channelSubtitle(_ channel: Channel) -> String {
        if let handle = channel.handle { return "@\(handle)" }
        return channel.id
    }

    /* What the order is, in words. "Most watched" that fell all the way through
       to A–Z has to say so: a list that looks unsorted and a list that is broken
       look the same otherwise. */
    private var orderLabel: String {
        switch sortMode {
        case .lastAdded: return "Recently added"
        case .aToZ: return "A–Z"
        case .mostWatched:
            guard let sortWindow, ChannelSort.windowsInDays.indices.contains(sortWindow) else {
                return "Most watched · nothing watched yet, so A–Z"
            }
            return "Most watched"
        }
    }

    // MARK: - Data

    private func reloadChannels() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        sortMode = SettingsStore.channelSort()
        /* Read even for the two orders that don't use them, so the label is
           written from the same snapshot the list was sorted from. */
        let counts = sortMode == .mostWatched ? WatchStore.countsByWindow(now: now) : []
        channels = ChannelSort.sort(ChannelStore.all(), mode: sortMode, countsByWindow: counts)
        sortWindow = sortMode == .mostWatched ? ChannelSort.windowIndex(counts) : nil
    }

    private func remove(_ channel: Channel) {
        /* Everything goes together inside remove(): the row, its videos, its
           watch history and its cached pictures. */
        ChannelStore.remove(channelId: channel.id)
        pendingRemoval = nil
        reloadChannels()
    }
}
