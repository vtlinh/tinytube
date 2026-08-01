import Foundation

/* What counts as a video id.

   Curation is channel-level: a parent approves channels in parent mode, and the
   grid is built from those channels' uploads. Nothing hand-lists individual
   videos. But every id arriving from the Worker still has to be checked before
   it can become a tile, and this is where that happens.

   Deliberately free of UIKit so a plain toolchain can run it. A mistake here
   doesn't produce a crash or an empty screen — it puts a child in front of
   something nobody approved.

   Ported from VideoId.kt. */
public enum VideoId {

    /* YouTube video ids are exactly 11 characters of URL-safe base64.
       Hand-checked rather than done with a regex, and that is not
       squeamishness: NSRegularExpression anchors with ^ and $ match at LINE
       boundaries unless .anchorsMatchLines is off, so "aaaaaaaaaaa\nevil"
       satisfies a careless ^...$ — which is exactly the class of bug this
       function exists to prevent. Counting characters cannot do that. */
    public static func isValid(_ id: String) -> Bool {
        guard id.count == 11 else { return false }
        return id.unicodeScalars.allSatisfy(isIdCharacter)
    }

    static func isIdCharacter(_ c: Unicode.Scalar) -> Bool {
        switch c {
        case "A"..."Z", "a"..."z", "0"..."9", "_", "-": return true
        default: return false
        }
    }
}

/* One tile.

   publishedAt is epoch seconds, and is what the grid sorts on — newest first,
   across every approved channel rather than one channel after another. It is
   optional because the two sources know different things: the playlist page
   gives a hundred videos in upload order and no dates at all, the Atom feed
   gives fifteen with exact ones. Library.datePositions is what reconciles
   those, and after it has run every video has a key. A nil one sorts last,
   which is the right place for a video nothing can date. */
public struct Video: Equatable, Sendable {
    public let id: String
    public let title: String
    public let publishedAt: Int64?
    /* What the Worker said the poster is, when it said. Stored rather than
       always derived because it is what came back with the video — but it is
       checked against the hosts in Uploads.thumb before it is kept, because
       whatever is here is later fetched and drawn. */
    public let thumbURL: String?

    public init(id: String, title: String, publishedAt: Int64? = nil, thumbURL: String? = nil) {
        self.id = id
        self.title = title
        self.publishedAt = publishedAt
        self.thumbURL = thumbURL
    }

    /* i.ytimg.com serves thumbnails for any public video with no key and no
       cookie. hqdefault exists for every video; maxresdefault does not, and a
       missing one 404s into an empty tile. */
    public var thumbnailURL: String {
        thumbURL ?? "https://i.ytimg.com/vi/\(id)/hqdefault.jpg"
    }

    public func with(publishedAt: Int64?) -> Video {
        Video(id: id, title: title, publishedAt: publishedAt, thumbURL: thumbURL)
    }
}
