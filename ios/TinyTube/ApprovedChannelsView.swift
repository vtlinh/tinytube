import SwiftUI
import TinyTubeCore

/* The approved channels, as a screen of its own again. Counterpart of
   ApprovedChannelsActivity.

   It spent a build inside SettingsView, as the list rather than a row that
   opened a screen, and that was right while it was a flat column. Groups
   changed the shape of it: there is a selection mode now, a title that becomes
   "3 selected", and a name sheet. A long-press that starts selecting rows in
   the middle of a Form full of pickers and sliders is a trap.

   No gate of its own — see ParentView. Getting here already required one.

   THE LIST IS ALWAYS EXPANDED. A group is a header with its channels beneath it
   as ordinary rows; there is no disclosure triangle and no collapsed state. A
   parent's list is a handful of rows, and hiding some behind a chevron would
   mean "what is approved?" could be answered wrongly by looking.
   `ChannelGroups.arrange` is what flattens it.

   SELECTION IS LONG-PRESS, and a plain tap keeps doing what it did — opening
   that channel in parent mode — because that is the thing done often and
   grouping is the thing done twice.

   THE NAME SHEET IS A SHEET, NOT AN ALERT, and that is a capability fact rather
   than a preference. SwiftUI's `.alert` with a TextField cannot disable its
   confirm button while the text is bad, and it cannot draw a message UNDER the
   field that changes as you type. Both are the point: the refusal has to be
   visible on the name being typed rather than arriving after the tap. */
struct ApprovedChannelsView: View {

    /* A row tapped means "take me to that channel", which this screen cannot do
       — ParentView owns the web view. So it is passed straight up. */
    let onOpenChannel: (Channel) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var channels: [Channel] = []
    @State private var groups: [ChannelGroups.Group] = []
    @State private var rows: [ChannelGroups.Row] = []
    @State private var sortMode: ChannelSort.Mode = .lastAdded
    @State private var sortWindow: Int?

    /* Channel IDs, not positions: the list re-sorts under a selection when a
       group is made, and positions would then point at the wrong rows. */
    @State private var selected: Set<String> = []
    @State private var pendingRemoval: Channel?
    @State private var naming = false
    @State private var typedName = ""
    @State private var showingHelp = false

    private var selecting: Bool { !selected.isEmpty }
    private var picked: [Channel] { channels.filter { selected.contains($0.id) } }

    var body: some View {
        NavigationStack {
            list
                .navigationTitle(selecting ? "\(selected.count) selected" : "Approved channels")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbarContent }
                .sheet(isPresented: $naming) { nameSheet }
                .alert("About approving", isPresented: $showingHelp) {
                    Button("OK", role: .cancel) {}
                } message: {
                    Text("""
                        The channels your child can watch. This is the parental \
                        control — nothing else decides what appears in the grid.

                        Approving a channel approves whatever it posts next, \
                        which no adult will have seen first. Choose channels you \
                        would trust unattended, and check back on them.
                        """)
                }
        }
        .onAppear { reload() }
        /* On the NavigationStack rather than on the list, because the sheet's
           own alert would otherwise be the second `.alert` on one view — the
           SwiftUI trap where the second silently never presents. */
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

    // MARK: - The list

    private var list: some View {
        List {
            if rows.isEmpty {
                Text("No channels approved yet. Find a channel and tap + to approve it.")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
            } else {
                /* Indexed rather than by identity: a Row is a header or a
                   channel, and the two have no id in common. The array is
                   rebuilt whole on every reload anyway. */
                ForEach(rows.indices, id: \.self) { index in
                    row(rows[index])
                }
            }
            if !selecting {
                Section {
                    Text(orderLabel)
                        .font(.footnote)
                        .foregroundStyle(Color.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private func row(_ row: ChannelGroups.Row) -> some View {
        switch row {
        case .header(let group, let size):
            groupRow(group: group, size: size)
        case .item(let channel, let grouped):
            channelRow(channel, grouped: grouped)
        }
    }

    /* A plain tap does nothing outside selection. There is nothing for it to do
       — the group is already expanded, and there is no "open a group" anywhere
       in this app. A long press selects the WHOLE group. */
    private func groupRow(group: ChannelGroups.Group, size: Int) -> some View {
        let members = ChannelGroups.membersOf(group.id, in: channels)
        /* Ticked only when the whole group is selected. A group with one member
           picked is not a selected group, and drawing it as one would say the
           Ungroup about to be tapped covers all of them. */
        let whole = !members.isEmpty && selected.isSuperset(of: members)
        return HStack(spacing: 10) {
            if selecting {
                Image(systemName: whole ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(whole ? Color.accentColor : Color.secondary)
            }
            Image(systemName: "folder.fill")
                .font(.footnote)
                .foregroundStyle(Color.secondary)
            Text(group.name).font(.subheadline.weight(.semibold))
            Spacer()
            Text("\(size) channels")
                .font(.caption)
                .foregroundStyle(Color.secondary)
        }
        .contentShape(Rectangle())
        .onTapGesture { if selecting { toggleGroup(group.id) } }
        .onLongPressGesture { toggleGroup(group.id) }
    }

    private func channelRow(_ channel: Channel, grouped: Bool) -> some View {
        let isPicked = selected.contains(channel.id)
        return HStack(spacing: 12) {
            if selecting {
                Image(systemName: isPicked ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isPicked ? Color.accentColor : Color.secondary)
            }
            CachedImage(url: channel.avatarURL) {
                Circle().fill(Color.secondary.opacity(0.2))
            }
            .frame(width: 36, height: 36)
            .clipShape(Circle())

            VStack(alignment: .leading, spacing: 2) {
                Text(channel.title).lineLimit(1)
                /* the handle if we have one, the id otherwise — something to
                   tell two similarly-named channels apart by */
                Text(subtitle(channel))
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
                    .lineLimit(1)
            }

            Spacer()

            /* Hidden rather than disabled while selecting: it sits exactly where
               a thumb goes to select the row, and a remove alert is not what
               that thumb asked for.

               A Button inside a row whose content also has a tap gesture is the
               pattern that reliably gives a List row two independent targets —
               a row that is itself a Button with another Button inside it hands
               every tap to whichever one SwiftUI decides owns the row. */
            if !selecting {
                Button {
                    pendingRemoval = channel
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Color.red)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Remove \(channel.title)")
            }
        }
        /* Members are indented under their header. Without it the header reads
           as a divider above an unrelated list rather than as something these
           rows are inside. Matches ApprovedChannelsActivity's INDENT_DP. */
        .padding(.leading, grouped ? 26 : 0)
        /* The whole row, not just the words: a gesture on an HStack reaches only
           where something is drawn without it. */
        .contentShape(Rectangle())
        .onTapGesture {
            if selecting {
                toggle(channel.id)
            } else {
                dismiss()
                onOpenChannel(channel)
            }
        }
        .onLongPressGesture { toggle(channel.id) }
    }

    // MARK: - The bar

    /* Two bars in one. Sort and the ? belong to the ordinary one; Group and
       Ungroup to the selecting one, and each appears only when the selection
       permits it — ChannelGroups decides that, not this screen. */
    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            if selecting {
                /* Cancels the selection before it leaves the screen. A parent
                   who long-pressed by accident should not have to deselect rows
                   one at a time. */
                Button("Cancel") { selected.removeAll() }
            } else {
                Button("Done") { dismiss() }
            }
        }
        ToolbarItemGroup(placement: .primaryAction) {
            if selecting {
                if ChannelGroups.canUngroup(picked) {
                    Button {
                        ChannelStore.ungroup(channelIds: Array(selected))
                        selected.removeAll()
                        reload()
                    } label: {
                        Label("Ungroup", systemImage: "folder.badge.minus")
                    }
                }
                if ChannelGroups.canGroup(picked) {
                    Button {
                        typedName = ChannelGroups.prefillName(
                            selected: picked, groups: groups, all: channels
                        ) ?? ""
                        naming = true
                    } label: {
                        Label("Group", systemImage: "folder.badge.plus")
                    }
                }
            } else {
                Button {
                    SettingsStore.setChannelSort(ChannelSort.next(sortMode))
                    reload()
                } label: {
                    Label("Change the order", systemImage: "arrow.up.arrow.down")
                }
                /* What approving a channel actually commits to. It was a ?
                   beside this list's heading while the list lived in settings,
                   and losing it with the move would have quietly dropped the
                   only place the app says that approving a channel approves
                   whatever it posts next. */
                Button {
                    showingHelp = true
                } label: {
                    Label("About approving", systemImage: "questionmark.circle")
                }
            }
        }
    }

    // MARK: - The name sheet

    /* Named on the way in rather than renamed afterwards: a group with no name
       has nothing to draw in its header, and "Untitled group" is a thing a
       parent then has to go and fix.

       Confirm is disabled while the name is one ChannelGroups.nameError
       refuses, with the reason under the field. Not an alert afterwards: the
       thing to fix is the text they are looking at. */
    private var nameSheet: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Group name", text: $typedName)
                        .autocorrectionDisabled()
                    if let message = nameErrorMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(Color.red)
                    }
                }
            }
            .navigationTitle("Name this group")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { naming = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Group") {
                        /* group() re-checks the name and the count. The sheet
                           has already refused both, so this is the second lock
                           on the same door — but it is the one that runs in a
                           transaction. */
                        if ChannelStore.group(channelIds: Array(selected), name: typedName) {
                            naming = false
                            selected.removeAll()
                            reload()
                        }
                    }
                    .disabled(nameError != nil)
                }
            }
        }
    }

    /* Judged against the names still IN USE once this selection has moved, not
       every name there is: a group whose every member is selected is emptied by
       the grouping and dissolves, so its name is free. That is what lets the
       prefilled "Cartoons" be accepted rather than refused as taken. */
    private var nameError: ChannelGroups.NameError? {
        ChannelGroups.nameError(
            typedName,
            existing: ChannelGroups.namesInUse(groups: groups, all: channels, selectedIds: selected)
        )
    }

    private var nameErrorMessage: String? {
        switch nameError {
        case .empty:
            /* Blank is the state the box STARTS in when nothing was prefilled,
               and shouting at a parent who has not typed anything yet is rude.
               Disabled, but silent. */
            return typedName.isEmpty ? nil : "Give the group a name."
        case .taken:
            return "There is already a group with that name."
        case nil:
            return nil
        }
    }

    // MARK: - Selection

    private func toggle(_ channelId: String) {
        if selected.contains(channelId) { selected.remove(channelId) }
        else { selected.insert(channelId) }
    }

    /* A header takes its whole group with it. Tapping it again puts them all
       back, rather than leaving a half-selected group whose header still looks
       selected — which is what toggling each member individually would do. */
    private func toggleGroup(_ groupId: String) {
        let members = ChannelGroups.membersOf(groupId, in: channels)
        if selected.isSuperset(of: members) { selected.subtract(members) }
        else { selected.formUnion(members) }
    }

    // MARK: - Values

    private func subtitle(_ channel: Channel) -> String {
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

    private func reload() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        sortMode = SettingsStore.channelSort()
        /* Read even for the two orders that don't use them, so the label is
           written from the same snapshot the list was arranged from. */
        let counts = sortMode == .mostWatched ? WatchStore.countsByWindow(now: now) : []
        channels = ChannelStore.all()
        groups = ChannelStore.groups()
        rows = ChannelGroups.arrange(
            channels: channels, groups: groups, mode: sortMode, countsByWindow: counts
        )
        sortWindow = sortMode == .mostWatched ? ChannelSort.windowIndex(counts) : nil

        /* A selection can outlive the rows it pointed at — removing a channel
           while selecting is the obvious way. */
        selected.formIntersection(Set(channels.map(\.id)))
    }

    private func remove(_ channel: Channel) {
        /* Everything goes together inside remove(): the row, its videos, its
           watch history and its cached pictures — and now the group it leaves
           behind, if that drops it below two. */
        ChannelStore.remove(channelId: channel.id)
        pendingRemoval = nil
        reload()
    }
}
