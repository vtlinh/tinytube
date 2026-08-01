import Foundation

/* Reading what the Worker says a channel has posted.

   The phone does not fetch YouTube itself. The Worker does that and answers
   with the list, and the phone sends along the ids it already has so the reply
   carries details only for what is new:

     {"channel":"UC…","videos":[
        "dQw4w9WgXcQ",                                     <- already had it
        {"id":"…","title":"…","published":123,"thumb":"…"}  <- new
     ]}

   Measured against a live channel: 1.4 KB when nothing is new, 19 KB for a
   channel seen for the first time.

   The order of `videos` is the answer to "what does this channel have now",
   including removals — so it replaces the stored list rather than merging into
   it. A bare id whose details we do NOT have is dropped rather than shown as a
   blank tile; it comes back in full on the next refresh, because the phone will
   no longer claim to know it.

   Ported from Uploads.kt, and none of it is trusted. The Worker validated these
   ids too, and this validates them again: an id goes into a URL and into a JS
   string literal on the way to the player, and "our own server said so" is not
   a reason to skip the check that stops the wrong video playing. */
public enum Uploads {

    /* What one reply may carry. The Worker sends a hundred; this is a bound on
       a hostile or broken one, not a target. */
    static let maxVideos = 500

    /* Parse a reply, given what the caller already had.
     *
     * `known` is looked up by id to fill in the bare entries. Never throws: a
     * truncated body, an error page, or a shape from a future Worker all come
     * back empty, and the caller keeps what it had. */
    public static func parse(_ body: String, known: [String: Video]) -> [Video] {
        guard let data = body.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data),
              let object = root as? [String: Any],
              let entries = object["videos"] as? [Any]
        else { return [] }

        var out: [Video] = []
        var seen = Set<String>()
        for entry in entries.prefix(maxVideos) {
            let video: Video?
            if let id = entry as? String {
                video = VideoId.isValid(id) ? known[id] : nil
            } else if let full = entry as? [String: Any] {
                video = self.full(full)
            } else {
                video = nil
            }
            guard let video, seen.insert(video.id).inserted else { continue }
            out.append(video)
        }
        return out
    }

    static func full(_ entry: [String: Any]) -> Video? {
        guard let id = entry["id"] as? String, VideoId.isValid(id) else { return nil }
        let rawTitle = (entry["title"] as? String) ?? ""
        let trimmed = rawTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        /* 0 is not a real upload time, and neither is a negative one, so both
           read as "no date" — which sorts last rather than to 1970. */
        let published = (entry["published"] as? NSNumber).map { $0.int64Value }.flatMap { $0 > 0 ? $0 : nil }
        return Video(
            id: id,
            title: trimmed.isEmpty ? id : trimmed,
            publishedAt: published,
            thumbURL: thumb(entry["thumb"] as? String, id: id)
        )
    }

    /* The thumbnail, but only from the hosts that serve them.
     *
     * Whatever is stored here is later fetched and drawn, so it does not get to
     * be an arbitrary URL somebody sent us. A reply that names anything else
     * falls back to the derived one, which always works. */
    public static func thumb(_ url: String?, id: String) -> String? {
        guard let url, !url.isEmpty, let host = Player.hostOf(url) else { return nil }
        return (host == "i.ytimg.com" || host == "img.youtube.com") ? url : nil
    }

    /* What the phone tells the Worker it already has. Ids only, and only ones
       that are well-formed — a malformed id in the request would just come back
       in full, but there is no reason to send one. */
    public static func request(channelId: String, known: [String]) -> String {
        let payload: [String: Any] = [
            "channel": channelId,
            "known": known.filter(VideoId.isValid),
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8)
        else { return "{}" }
        return text
    }
}
