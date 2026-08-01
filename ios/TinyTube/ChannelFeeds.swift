import Foundation
import TinyTubeCore

/* Recent uploads from every approved channel. Counterpart of ChannelFeeds.kt.

   The phone does not parse YouTube. It asks the Worker, and the question
   carries the ids it already has so the answer carries details only for what is
   new — 1.4 KB when nothing is new against 19 KB for a channel seen for the
   first time. See `Uploads` for the shape of it.

   At most once a day per channel. A channel-approval app is not a news feed:
   learning about an upload eleven hours late means it appears tomorrow, and
   asking on every app open costs somebody's data allowance.
   `ChannelStore.uploadsFetchedAt` is the clock and nil means never, which is
   why a newly approved channel fetches at once.

   None of it can carry a Short — the Worker asks for the channel's UULF
   playlist, which is YouTube's own uploads list with Shorts taken out. Nothing
   here classifies a video, and nothing here should start.

   Curation has not moved. Which channels a child may watch is still SQLite on
   this device; this only answers what an already-approved channel has posted. */
enum ChannelFeeds {

    /* Once a day. Long enough that the answer is measured in requests per week
       rather than per screen unlock, short enough that a channel a child
       watches every day is never more than a day behind. */
    static let refreshIntervalMillis: Int64 = 24 * 60 * 60 * 1000

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        /* Nothing here is worth a cached response: the reply is the answer to
           "what does this channel have NOW", and a stale 200 from a URL cache
           would be indistinguishable from a fresh one while quietly replacing
           the grid with yesterday's. */
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()

    /* Whatever was last stored, with no network. What the grid draws on launch,
       so a phone with no signal still shows something.
     *
     * `flatten` then `collate`, and BOTH are needed — unlike Kotlin, where the
     * one call does it. `flatten` concatenates per-channel lists in a given
     * order and `collate` dedupes and sorts by upload time; the split exists
     * because Swift dictionaries have no order and the caller has to supply it.
     *
     * The order it is given matters even though `collate` re-sorts. Swift's
     * sort is NOT stable, so `newestFirst` breaks ties on the input position —
     * which means the input position has to be something meaningful rather than
     * whatever order a dictionary happened to enumerate in. It is the approved
     * list's, newest-approved first. */
    static func cached() -> [Video] {
        Library.collate(
            Library.flatten(byChannel: cachedByChannel(), channelOrder: channelOrder())
        )
    }

    /* Newest-approved first, matching what ChannelStore.all() returns. Not the
       parent's chosen sort: that orders the LIST of channels, and reordering it
       must not silently reshuffle tiles the grid has already dated. */
    static func channelOrder() -> [String] { ChannelStore.all().map(\.id) }

    /* The same, keeping which channel each video came from — the Channels tab
       needs it, and the association exists only here because a video does not
       carry its channel. */
    static func cachedByChannel() -> [String: [Video]] { VideoStore.byChannel() }

    @discardableResult
    static func refresh(now: Int64) async -> [Video] {
        let byChannel = await refreshByChannel(now: now)
        return Library.collate(
            Library.flatten(byChannel: byChannel, channelOrder: channelOrder())
        )
    }

    static func refreshByChannel(now: Int64) async -> [String: [Video]] {
        for channel in ChannelStore.all() {
            guard due(channel: channel.id, now: now) else { continue }

            let videos = await fetchUploads(channelId: channel.id)

            /* A reply that parsed to nothing changes nothing, and is NOT marked
               as fetched. Marking a failure buys the outage a full day — the
               channel would go silent until tomorrow for a request that never
               produced an answer. */
            guard !videos.isEmpty else { continue }

            VideoStore.replace(channelId: channel.id, videos: videos)
            ChannelStore.markUploadsFetched(channelId: channel.id, now: now)
        }
        return cachedByChannel()
    }

    private static func due(channel channelId: String, now: Int64) -> Bool {
        guard let last = ChannelStore.uploadsFetchedAt(channelId: channelId) else {
            /* Never fetched — a channel approved a moment ago, which must fill
               its part of the grid immediately rather than tomorrow. */
            return true
        }
        /* A clock that moved backwards (a timezone change, a manual set) would
           otherwise park the channel until real time caught up. */
        return now - last >= refreshIntervalMillis || now < last
    }

    private static func fetchUploads(channelId: String) async -> [Video] {
        /* The ids this device already has, so the reply is mostly bare ids. */
        let known = VideoStore.knownIds(channelId: channelId)
        guard let body = await post(Uploads.request(channelId: channelId, known: known))
        else { return [] }

        /* Re-validated on arrival. `Uploads.parse` puts every id through
           `VideoId.isValid` and every thumbnail URL through a host check, even
           though the Worker checked both — an id reaches a URL and a JS string
           literal, and a stored thumbnail URL is later fetched and drawn. "Our
           own server said so" is not the same kind of assurance as a pattern
           match at the point of use. */
        let byId = Dictionary(
            VideoStore.forChannel(channelId).map { ($0.id, $0) },
            uniquingKeysWith: { a, _ in a }
        )
        return Uploads.parse(body, known: byId)
    }

    private static func post(_ json: String) async -> String? {
        var request = URLRequest(url: Endpoints.uploads)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(json.utf8)

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200
            else { return nil }
            return String(data: data, encoding: .utf8)
        } catch {
            /* No signal, a timeout, the Worker down. Every one of them means
               "no new information", which leaves the grid exactly as it was. */
            return nil
        }
    }

    /* A channel is no longer approved. ChannelStore.remove already drops its
       videos and its history; this exists so a caller that only wants the
       uploads gone does not have to reach into VideoStore. */
    static func forget(channelId: String) { VideoStore.forget(channelId: channelId) }
}
