import SwiftUI
import TinyTubeCore

/* The child's screens: the grid, and the read-only Channels tab.
   Counterpart of MainActivity plus BottomTabs.

   THE STATUS BAR HOLDS EXACTLY ONE CONTROL — the Parent button — and everything
   behind it is gated. Nothing else on this screen has an action that leaves it,
   and nothing here has a hidden one: no search, no text entry, no link out.

   THE CHANNELS TAB IS READ-ONLY. It shows the approved list and narrows the
   grid to one channel, or to a whole group. It cannot remove a channel, it
   cannot open YouTube, and it cannot make, rename or break up a group —
   `ChannelStore` is the parental control and editing it lives behind the gate,
   in ApprovedChannelsView. Don't give this one an action that changes what is
   approved.

   THE GROUPS ARE SHOWN AND THEIR MEMBERS ARE SHOWN TOO. A group is a header
   that filters to the whole group, with its channels listed individually
   beneath it — reaching one channel of a group must not cost a child two taps
   and an idea about how grouping works. Same rows, same order, as the parent's
   list: `ChannelGroups.arrange` decides both. */
struct MainView: View {

    enum Tab { case videos, channels }

    @State private var tab: Tab = .videos
    @State private var byChannel: [String: [Video]] = [:]
    @State private var channels: [Channel] = []
    @State private var groups: [ChannelGroups.Group] = []
    @State private var rows: [ChannelGroups.Row] = []
    /* Approved order — ChannelStore's, which is what the grid resolves ties
       by. Kept as its own array because `rows` is in the parent's chosen sort
       and grouped besides, and neither of those may reshuffle the grid. */
    @State private var channelOrder: [String] = []
    @State private var sortMode: ChannelSort.Mode = .lastAdded
    @State private var sortWindow: Int?
    /* nil is the whole grid; a channel or a group narrows it.
     *
     * An ID rather than the thing itself, with the title and the channels
     * derived from the current list on every read. That is what makes the
     * filter heal itself: a channel removed, or a group dissolved down to one
     * member, stops resolving and the grid widens back out rather than heading
     * itself after something that is gone. */
    @State private var filter: Filter?

    private enum Filter: Equatable {
        case channel(String)
        case group(String)
    }
    @State private var playing: PlayingList?
    @State private var gating = false
    @State private var showChallenge = false
    /* The arithmetic was answered correctly and parent mode is owed, but the
       challenge cover has not finished dismissing yet. See the covers below. */
    @State private var challengePassed = false
    @State private var showParent = false

    private struct PlayingList: Identifiable {
        let id = UUID()
        let videos: [Video]
        let index: Int
    }

    /* The whole grid, or one channel's, or one group's. `forChannels` collates
       on its own; the whole-grid path needs flatten AND collate, and the order
       handed to either is the approved list's — see ChannelFeeds.channelOrder
       for why that order matters even though collate re-sorts. */
    private var visible: [Video] {
        guard filter != nil else {
            return Library.collate(
                Library.flatten(byChannel: byChannel, channelOrder: channelOrder)
            )
        }
        return Library.forChannels(byChannel: byChannel, channelIds: filteredChannelIds)
    }

    /* Which channels the filter covers, in ChannelStore's own order.
     *
     * That order decides how videos posted at the same second fall — see
     * Library.forChannels — and it is the one the unfiltered grid already
     * resolves ties by. The sort the parent picked deliberately does NOT come
     * into it: that arranges the channel LIST, and letting it reorder the video
     * grid would mean two videos swapped places because of a setting about
     * something else. */
    private var filteredChannelIds: [String] {
        guard let filter else { return [] }
        switch filter {
        case .channel(let id):
            return channelOrder.filter { $0 == id }
        case .group(let id):
            let members = ChannelGroups.membersOf(id, in: channels)
            return channelOrder.filter { members.contains($0) }
        }
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
        /* ⚠️ THE HANDOFF GOES THROUGH onDismiss, NOT THROUGH ONE UPDATE.
         *
         * Flipping both flags together — `showChallenge = false; showParent =
         * true` — asks this view to dismiss one fullScreenCover and present
         * another in the same state update, on a deployment target of iOS 16.0,
         * and the request to present arrives while the first is still
         * dismissing: the challenge closes and parent mode never opens. This is
         * the same trap ParentView documents for chained `.sheet` and
         * SettingsView for chained `.alert`.
         *
         * It bites on exactly the devices the arithmetic exists for — a phone
         * with no passcode is the only way Gate returns .needsChallenge — so a
         * correct answer would appear to do nothing and parent mode would be
         * unreachable. `passed` records the answer and the presentation waits
         * for the cover to be properly gone. */
        .fullScreenCover(isPresented: $showChallenge, onDismiss: {
            if challengePassed {
                challengePassed = false
                showParent = true
            }
        }) {
            ChallengeView(
                onPass: { challengePassed = true; showChallenge = false },
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

    private var filterTitle: String { narrowedTo ?? "TinyTube" }

    /* What the heading says while the grid is narrowed, or nil if the thing it
       named no longer exists. A group reads the same as a channel does, because
       from the grid's side they are the same thing: a narrower set of videos
       with a name and a way back. */
    private var narrowedTo: String? {
        guard let filter else { return nil }
        switch filter {
        case .channel(let id): return channels.first { $0.id == id }?.title
        case .group(let id): return groups.first { $0.id == id }?.name
        }
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
                    CachedImage(url: video.thumbnailURL) {
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
                /* Indexed rather than by identity: a Row is a header or a
                   channel, and the two have no id in common. The array is
                   rebuilt whole on every reload anyway, so there is no
                   incremental diff for an identity to serve. */
                ForEach(rows.indices, id: \.self) { index in
                    listRow(rows[index])
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
        return "Most watched"
    }

    /* Two kinds of row, the same two the parent's list has — and drawn from the
       same ChannelGroups.arrange, so the two screens cannot drift apart. What
       differs is what a tap does and what is missing: no remove, no long press,
       no selection. */
    @ViewBuilder
    private func listRow(_ row: ChannelGroups.Row) -> some View {
        switch row {
        case .header(let group, let size):
            /* Tapping a group shows every channel in it at once. Its members
               are listed underneath as well, so this is a shortcut rather than
               the only way in — a child who wants one channel of a group does
               not have to understand grouping to reach it. */
            Button {
                filter = .group(group.id)
                tab = .videos
            } label: {
                groupRow(name: group.name, size: size)
            }
            .buttonStyle(.plain)
        case .item(let channel, let grouped):
            Button {
                filter = .channel(channel.id)
                tab = .videos
            } label: {
                channelRow(channel, grouped: grouped)
            }
            .buttonStyle(.plain)
        }
    }

    private func groupRow(name: String, size: Int) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "folder.fill")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(name)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            Spacer()
            Text("\(size) channels")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 6)
    }

    private func channelRow(_ channel: Channel, grouped: Bool) -> some View {
        HStack(spacing: 12) {
            /* scaledToFill, not a bare resizable: an avatar that is not
               square would otherwise be stretched into the circle rather than
               cropped to it. Same bug the posters had. */
            CachedImage(url: channel.avatarURL) {
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
        /* Members are indented under their header. Without it the header reads
           as a divider above an unrelated list rather than as something these
           rows are inside. Matches MainActivity's GROUPED_INDENT_DP. */
        .padding(.leading, grouped ? 46 : 16)
        .padding(.trailing, 16)
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
        channels = approved
        groups = ChannelStore.groups()
        /* The same order and the same grouping the parent set on their own
           list. It is one list, and two arrangements of it is how a parent ends
           up unable to find on this screen what they just arranged on the
           other. Read-only here, like everything else on this tab. */
        rows = ChannelGroups.arrange(
            channels: approved, groups: groups, mode: mode, countsByWindow: counts
        )
        sortMode = mode
        sortWindow = mode == .mostWatched ? ChannelSort.windowIndex(counts) : nil

        /* A channel removed in parent mode — or a group dissolved because it
           dropped to one member — must not leave the grid filtered to something
           that is no longer there. */
        if filter != nil, narrowedTo == nil { filter = nil }
    }

    private func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
