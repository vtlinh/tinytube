import Foundation
import TinyTubeCore

/* Works out which channel the parent is currently looking at.
   Counterpart of ChannelResolver.kt.

   A /channel/UC… URL carries the id outright. Most YouTube URLs don't — they
   use an @handle, and a handle cannot be turned into an id locally.

   THE PHONE DOES NOT FETCH THE PAGE TO FIND OUT. Reading a channel page is the
   Worker's job, for the same reason the uploads parsing is: otherwise every
   approval downloads megabytes of somebody else's web app to read one
   24-character string out of it. The phone sends a HANDLE or a CHANNEL ID —
   never the URL — and the Worker builds every URL it fetches from the validated
   value. See the note above channel() in worker.js for why that distinction is
   what lets the route take input at all.

   Curation has not moved. The reply says which channel a page is for; whether a
   child may watch it is ChannelStore, on this device. */
enum ChannelResolver {

    struct Resolved: Equatable {
        let id: String
        let title: String
        let avatarURL: String?
        /* The channel's uploads, in the same reply. Approving needs the id, the
           name AND the first hundred videos, and asking for those separately
           meant two round trips with a parent watching a spinner through both.
           Empty means the Worker could not tell — which VideoStore.replace
           treats as "change nothing", not as "this channel has none". */
        let videos: [Video]
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        return URLSession(configuration: config)
    }()

    /* Nil when this page isn't a channel we can identify — a search results
       page, the home feed, a settings screen. The caller says so rather than
       approving something arbitrary. */
    static func resolve(url: String) async -> Resolved? {
        guard YouTubeUrls.isParentBrowsable(url) else { return nil }

        /* THE URL GOES AS-IS. The app does not pick the handle or id out of it
           first — reading YouTube is the Worker's job, and an address is
           something to read. The Worker validates the host and path and REBUILDS
           what it fetches, so nothing this app sends reaches fetch() verbatim.

           The id that comes back is still checked here. That has not moved and
           should not: it becomes a database primary key and a request
           parameter. */
        let direct = YouTubeUrls.channelIdFromURL(url)

        guard let body = try? JSONSerialization.data(withJSONObject: ["url": url]),
              let data = await post(body)
        else {
            /* The Worker was unreachable, but if the id was in the URL all
               along we can still approve it — just without a name or videos.
               The daily refresh will fill them in. */
            return direct.map { Resolved(id: $0, title: $0, avatarURL: nil, videos: []) }
        }

        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        guard let id = object["id"] as? String, YouTubeUrls.isValidChannelId(id)
        else { return nil }

        let title = (object["title"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        /* The Worker already refused an off-host avatar. Checked again because
           this is stored and then fetched and drawn, and "our own server said
           so" is not a check at the point of use — same reasoning as video
           ids. */
        let avatar = (object["avatarUrl"] as? String).flatMap {
            YouTubeUrls.isAllowedAvatar($0) ? $0 : nil
        }

        /* Straight through the same parser the uploads reply uses, so every id
           and thumbnail host is re-validated exactly as it would be there.
           `known` is empty: a channel being approved has nothing stored yet. */
        let text = String(data: data, encoding: .utf8) ?? ""
        let videos = Uploads.parse(text, known: [:])

        return Resolved(
            id: id,
            title: (title?.isEmpty == false) ? title! : id,
            avatarURL: avatar,
            videos: videos
        )
    }

    private static func post(_ body: Data) async -> Data? {
        var request = URLRequest(url: Endpoints.channel)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200
            else { return nil }
            return data
        } catch {
            return nil
        }
    }
}
