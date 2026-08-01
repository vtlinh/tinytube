import SwiftUI
import TinyTubeCore

/* The child's screens: the grid, and the read-only Channels tab.
   Counterpart of MainActivity plus BottomTabs.

   THE STATUS BAR HOLDS EXACTLY ONE CONTROL — the Parent button — and everything
   behind it is gated. Nothing else on this screen has an action that leaves it,
   and nothing here has a hidden one: no search, no text entry, no link out.

   THE CHANNELS TAB IS READ-ONLY. It shows the approved list and narrows the
   grid to one channel. It cannot remove a channel and it cannot open YouTube —
   `ChannelStore` is the parental control and editing it lives behind the gate,
   in ApprovedChannelsView. Don't give this one an action that changes what is
   approved. */
struct MainView: View {

    enum Tab { case videos, channels }

    @State private var tab: Tab = .videos
    @State private var byChannel: [String: [Video]] = [:]
    @State private var channels: [Channel] = []
    /* Approved order, kept separately from `channels` — that one is in the
       parent's chosen sort, which must not reshuffle the grid. */
    @State private var channelOrder: [String] = []
    @State private var sortMode: ChannelSort.Mode = .lastAdded
    @State private var sortWindow: Int?
    /* nil is the whole grid; a channel id narrows it. */
    @State private var filter: String?
    @State private var playing: PlayingList?
    @State private var gating = false
    @State private var showChallenge = false
    @State private var showParent = false

    private struct PlayingList: Identifiable {
        let id = UUID()
        let videos: [Video]
        let index: Int
    }

    /* The whole grid, or one channel's. `forChannel` collates on its own; the
       whole-grid path needs flatten AND collate, and the order handed to
       flatten is the approved list's — see ChannelFeeds.channelOrder for why
       that order matters even though collate re-sorts. */
    private var visible: [Video] {
        guard let filter else {
            return Library.collate(
                Library.flatten(byChannel: byChannel, channelOrder: channelOrder)
            )
        }
        return Library.forChannel(byChannel: byChannel, channelId: filter)
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(Color.white.opacity(0.1))

            Group {
                switch tab {
                case .videos: grid
                case .channels: channelList
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            bottomTabs
        }
        .background(Color.black)
        .task { await load() }
        .fullScreenCover(item: $playing) { list in
            PlayerView(videos: list.videos, index: list.index) {
                playing = nil
                /* Watch history changed, so the "most watched" order may have. */
                Task { await reload() }
            }
        }
        .fullScreenCover(isPresented: $showChallenge) {
            ChallengeView(
                onPass: { showChallenge = false; showParent = true },
                onCancel: { showChallenge = false }
            )
        }
        .fullScreenCover(isPresented: $showParent, onDismiss: { Task { await reload() } }) {
            ParentView { showParent = false }
        }
    }

    // MARK: - Chrome

    private var header: some View {
        HStack {
            Text(filterTitle)
                .font(.headline)
                .lineLimit(1)
            Spacer()
            /* The one control. Everything behind it is gated. */
            Button {
                openParentMode()
            } label: {
                Label("Parent", systemImage: "lock.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.subheadline.weight(.semibold))
            }
            .disabled(gating)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var filterTitle: String {
        guard let filter, let channel = channels.first(where: { $0.id == filter })
        else { return "TinyTube" }
        return channel.title
    }

    private var bottomTabs: some View {
        HStack {
            tabButton("Videos", systemImage: "square.grid.2x2.fill", tab: .videos)
            tabButton("Channels", systemImage: "person.2.fill", tab: .channels)
        }
        .padding(.top, 8)
        .background(Color.black)
        .overlay(alignment: .top) { Divider().overlay(Color.white.opacity(0.1)) }
    }

    private func tabButton(_ title: String, systemImage: String, tab target: Tab) -> some View {
        Button {
            tab = target
        } label: {
            VStack(spacing: 3) {
                Image(systemName: systemImage)
                Text(title).font(.caption2)
            }
            .frame(maxWidth: .infinity)
            .foregroundStyle(tab == target ? Color.white : Color.white.opacity(0.45))
        }
    }

    // MARK: - The grid

    private var grid: some View {
        ScrollView {
            if visible.isEmpty {
                emptyGrid
            } else {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 160), spacing: 12)],
                    spacing: 12
                ) {
                    ForEach(Array(visible.enumerated()), id: \.element.id) { position, video in
                        Button {
                            /* Handed the WHOLE visible list and this index, so
                               what plays next cannot leave the list that was on
                               screen. */
                            playing = PlayingList(videos: visible, index: position)
                        } label: {
                            tile(video)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(12)
            }
        }
    }

    private var emptyGrid: some View {
        VStack(spacing: 8) {
            Text(channels.isEmpty ? "No channels approved yet" : "Nothing here yet")
                .font(.headline)
            Text(channels.isEmpty
                 ? "Tap Parent to approve a channel."
                 : "New uploads appear here once a day.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 80)
        .padding(.horizontal, 32)
    }

    /* The poster, 16:9, holding its shape before it has loaded.
     *
     * The BOX is 16:9 and the IMAGE keeps its own ratio inside it — the
     * counterpart of RatioImageView plus scaleType="centerCrop" on Android, and
     * the two halves have to be separate or the picture is distorted rather
     * than cropped.
     *
     * That matters because YouTube's hqdefault.jpg is 480x360 — FOUR-THREE,
     * with black letterbox bars above and below the actual frame. Cropping it
     * to 16:9 removes exactly those bars. Forcing the image itself to 16:9
     * instead squashes the picture, which is what this did at first: an
     * explicit `.aspectRatio(16/9, contentMode: .fill)` on a `resizable()`
     * image sets the IMAGE's ratio, not the box's.
     *
     * `Color.clear` gives the tile its shape immediately, so the grid does not
     * reflow as posters arrive under a child's finger — a tap landing on a
     * different video than the one that was there when it started. Same reason
     * RatioImageView exists rather than adjustViewBounds. */
    private func tile(_ video: Video) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear
                .aspectRatio(16.0 / 9.0, contentMode: .fit)
                .overlay {
                    AsyncImage(url: URL(string: video.thumbnailURL)) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Rectangle().fill(Color.white.opacity(0.06))
                    }
                }
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: 10))

            Text(video.title)
                .font(.footnote)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .foregroundStyle(.white)
        }
    }

    // MARK: - The Channels tab

    private var channelList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if sortMode == .mostWatched {
                    /* The list has to SAY which rung applied, or "most watched"
                       over an empty history is indistinguishable from a broken
                       sort. */
                    Text(windowLabel)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                }
                ForEach(channels) { channel in
                    Button {
                        filter = channel.id
                        tab = .videos
                    } label: {
                        channelRow(channel)
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(Color.white.opacity(0.06))
                }
            }
        }
        .safeAreaInset(edge: .top) {
            if filter != nil {
                Button("Show all channels") { filter = nil }
                    .font(.footnote)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
                    .background(Color.white.opacity(0.06))
            }
        }
    }

    private var windowLabel: String {
        guard let sortWindow, ChannelSort.windowsInDays.indices.contains(sortWindow) else {
            return "Most watched — no history yet, showing A–Z"
        }
        return "Most watched — last \(ChannelSort.windowsInDays[sortWindow]) days"
    }

    private func channelRow(_ channel: Channel) -> some View {
        HStack(spacing: 12) {
            /* scaledToFill, not a bare resizable: an avatar that is not
               square would otherwise be stretched into the circle rather than
               cropped to it. Same bug the posters had. */
            AsyncImage(url: channel.avatarURL.flatMap(URL.init(string:))) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Circle().fill(Color.white.opacity(0.08))
            }
            .frame(width: 40, height: 40)
            .clipShape(Circle())

            Text(channel.title)
                .font(.body)
                .foregroundStyle(.white)
                .lineLimit(1)
            Spacer()
            /* No remove button here. This tab is read-only — see the header. */
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    // MARK: - The gate

    private func openParentMode() {
        gating = true
        Gate.authenticate { outcome in
            gating = false
            switch outcome {
            case .passed: showParent = true
            case .needsChallenge: showChallenge = true
            case .failed: break
            }
        }
    }

    // MARK: - Loading

    private func load() async {
        await reload()
        /* At most once a day per channel; ChannelFeeds decides, not this. */
        await ChannelFeeds.refresh(now: nowMillis())
        await reload()
    }

    private func reload() async {
        let now = nowMillis()
        let mode = SettingsStore.channelSort()
        let counts = mode == .mostWatched ? WatchStore.countsByWindow(now: now) : []

        let approved = ChannelStore.all()
        byChannel = ChannelFeeds.cachedByChannel()
        channelOrder = approved.map(\.id)
        channels = ChannelSort.sort(approved, mode: mode, countsByWindow: counts)
        sortMode = mode
        sortWindow = mode == .mostWatched ? ChannelSort.windowIndex(counts) : nil

        /* A channel removed in parent mode must not leave the grid filtered to
           something that is no longer approved. */
        if let filter, !channels.contains(where: { $0.id == filter }) { self.filter = nil }
    }

    private func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
