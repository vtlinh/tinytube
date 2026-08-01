import SwiftUI
import TinyTubeCore

/* The approved list, with open and remove. Counterpart of
   ApprovedChannelsActivity.

   This is the editable one. The child's Channels tab shows the same list and
   cannot change it — editing what is approved lives here, behind the gate.

   No gate of its own: reaching parent mode already required one. */
struct ApprovedChannelsView: View {

    let onOpen: (Channel) -> Void

    @State private var channels: [Channel] = []
    @State private var mode: ChannelSort.Mode = .lastAdded
    @State private var window: Int?

    /* PUSHED from settings rather than presented, so it brings no
       NavigationStack and no Done button of its own — the stack it is pushed
       into supplies the bar and the way back. Wrapping itself would nest one
       stack inside another and give the screen two title bars. */
    var body: some View {
        List {
            if channels.isEmpty {
                Text("No channels approved yet.")
                    .foregroundStyle(.secondary)
            } else {
                Section {
                    ForEach(channels) { channel in
                        row(channel)
                    }
                } header: {
                    Text(header)
                }
            }
        }
        .navigationTitle("Approved channels")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                /* Cycles the three orders. Whichever is picked applies to the
                   child's Channels tab too — it is one list. */
                Button {
                    SettingsStore.setChannelSort(ChannelSort.next(mode))
                    reload()
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
            }
        }
        .onAppear(perform: reload)
    }

    /* The bar SAYS which order is in force, and for "most watched" which rung
       of the ladder applied — otherwise an empty history is indistinguishable
       from a broken sort. */
    private var header: String {
        switch mode {
        case .lastAdded: return "Recently added"
        case .aToZ: return "A–Z"
        case .mostWatched:
            guard let window, ChannelSort.windowsInDays.indices.contains(window) else {
                return "Most watched — no history yet, showing A–Z"
            }
            return "Most watched"
        }
    }

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

            Button {
                onOpen(channel)
            } label: {
                Text(channel.title).lineLimit(1)
            }
            .buttonStyle(.plain)

            Spacer()

            Button(role: .destructive) {
                /* Drops its videos from the grid immediately, and its watch
                   history with it. */
                ChannelStore.remove(channelId: channel.id)
                reload()
            } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove \(channel.title)")
        }
    }

    private func reload() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        mode = SettingsStore.channelSort()
        let counts = mode == .mostWatched ? WatchStore.countsByWindow(now: now) : []
        channels = ChannelSort.sort(ChannelStore.all(), mode: mode, countsByWindow: counts)
        window = mode == .mostWatched ? ChannelSort.windowIndex(counts) : nil
    }
}
