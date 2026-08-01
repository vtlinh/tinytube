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

        let direct = YouTubeUrls.channelIdFromURL(url)
        let handle = YouTubeUrls.handleFromURL(url)

        let payload: [String: String]
        if let direct {
            payload = ["channel": direct]
        } else if let handle {
            payload = ["handle": handle]
        } else {
            return nil
        }

        guard let body = try? JSONSerialization.data(withJSONObject: payload),
              let data = await post(body)
        else {
            /* The Worker was unreachable, but if the id was in the URL all
               along we can still approve it — just without a nice name. */
            return direct.map { Resolved(id: $0, title: $0, avatarURL: nil) }
        }

        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        /* Re-validated on arrival, exactly as Uploads.parse re-validates video
           ids. This one becomes a primary key and a request parameter, and the
           avatar is stored and then fetched and drawn; "our own server said so"
           is not the same assurance as a check at the point of use. */
        guard let id = object["id"] as? String, YouTubeUrls.isValidChannelId(id)
        else { return nil }

        let title = (object["title"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let avatar = (object["avatarUrl"] as? String).flatMap {
            YouTubeUrls.isAllowedAvatar($0) ? $0 : nil
        }

        return Resolved(
            id: id,
            title: (title?.isEmpty == false) ? title! : id,
            avatarURL: avatar
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
