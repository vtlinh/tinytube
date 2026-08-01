import Foundation

/* Reading YouTube URLs: what a channel id looks like, how to find one in a page
   the parent is standing on, and where parent mode is allowed to go.

   Ported from YouTubeUrls.kt. The channel id ends up in the database as a
   primary key and in a request to the Worker; a wrong one either fetches
   nothing or — worse — fetches somebody else's channel into a child's grid.

   Note what is NOT here, on either platform: the uploads and feed URLs. The
   phone stopped fetching YouTube directly when the parsing moved to the Worker,
   so those are built in worker.js and the UULF-not-UU rule that keeps Shorts
   off a child's screen lives there. */
public enum YouTubeUrls {

    /* Channel ids are "UC" followed by 22 URL-safe base64 characters. Counted
       rather than matched with an anchored regex, for the same reason
       VideoId.isValid counts: NSRegularExpression's ^ and $ match at line
       boundaries by default, so a newline is how a valid-looking prefix gets
       past a careless check. */
    public static func isValidChannelId(_ id: String) -> Bool {
        guard id.count == 24, id.hasPrefix("UC") else { return false }
        return id.dropFirst(2).unicodeScalars.allSatisfy(VideoId.isIdCharacter)
    }

    /* Hosts parent mode may browse. Wider than the player's allowlist on
       purpose — this is a grown-up looking for channels — but still bounded, so
       a stray tap on an ad or an external link doesn't wander off into the open
       web inside our web view. */
    static let parentHosts: Set<String> = [
        "www.youtube.com",
        "m.youtube.com",
        "youtube.com",
        "www.youtube-nocookie.com",
        "s.ytimg.com",
        "i.ytimg.com",
        "yt3.ggpht.com",
        "yt3.googleusercontent.com",
        "fonts.gstatic.com",
    ]

    /* Signing in, so a parent can reach their own subscriptions rather than
       hunting channels from a logged-out home page.

       Google's sign-in is a chain of redirects across several of its hosts, and
       it does not degrade when one is blocked — it simply stops on whichever
       step was refused, which from the inside looks like the app hanging rather
       than like a refusal. Enumerating the chain host by host turned out to be
       a losing game, so the whole of google.com is allowed here instead.

       This is PARENT mode only, behind the gate, and it is a browser for an
       adult. The player's allowlist is a separate, much narrower list and gains
       none of it — a signed-in Google page must never be reachable from the
       child's screen, and there is a test to that effect on both platforms. */
    static let signInHosts: Set<String> = [
        "google.com",
        "accounts.youtube.com",
        "consent.youtube.com",
    ]

    public static func isParentBrowsable(_ url: String) -> Bool {
        guard let host = Player.hostOf(url) else { return false }
        if parentHosts.contains(host) || signInHosts.contains(host) { return true }
        /* All matched on a leading dot, so "evilgooglevideo.com",
           "notgstatic.com" and "google.com.attacker.example" do not qualify. */
        return host.hasSuffix(".google.com")
            || host.hasSuffix(".googlevideo.com")
            || host.hasSuffix(".googleusercontent.com")
            || host.hasSuffix(".gstatic.com")
    }

    /* The path, with query and fragment removed. Returns nil for anything
       without an http(s) host, so a non-navigable scheme can't be probed. */
    public static func pathOf(_ url: String) -> String? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        let scheme: String
        if lower.hasPrefix("https://") { scheme = "https://" }
        else if lower.hasPrefix("http://") { scheme = "http://" }
        else { return nil }

        let afterScheme = trimmed.dropFirst(scheme.count)
        guard let slash = afterScheme.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" })
        else { return "/" }
        var path = String(afterScheme[slash...])
        if let cut = path.firstIndex(where: { $0 == "?" || $0 == "#" }) {
            path = String(path[path.startIndex..<cut])
        }
        return path.isEmpty ? "/" : path
    }

    /* Hosts that serve channel pages. Narrower than parentHosts, which also
       covers the images and media a page pulls in — none of those is ever
       somewhere a channel can be approved from. */
    static let pageHosts: Set<String> = ["www.youtube.com", "m.youtube.com", "youtube.com"]

    /* Is the parent standing on a channel, such that "approve" means something
       unambiguous?

       Anchored at the start of the path on purpose. A watch page mentions its
       uploader and a search result lists a dozen channels, but neither IS a
       channel — approving from one would be a guess about which channel was
       meant, and the guess would sometimes be wrong in a child's grid. */
    public static func isChannelPage(_ url: String) -> Bool {
        guard let host = Player.hostOf(url), pageHosts.contains(host),
              let path = pathOf(url)
        else { return false }
        if let id = firstSegmentChannelId(path) { return isValidChannelId(id) }
        return handleSegment(path) != nil
    }

    /* The channel id sitting in the URL itself, for /channel/UC… pages. */
    public static func channelIdFromURL(_ url: String) -> String? {
        guard Player.hostOf(url) != nil, let path = pathOf(url),
              let id = firstSegmentChannelId(path), isValidChannelId(id)
        else { return nil }
        return id
    }

    private static func firstSegmentChannelId(_ path: String) -> String? {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true)
        guard parts.count >= 2, parts[0] == "channel" else { return nil }
        return String(parts[1])
    }

    /* The @handle, for the many YouTube URLs that carry one instead. A handle
       is not a channel id and cannot be turned into one locally — it has to be
       resolved against the page. */
    public static func handleFromURL(_ url: String) -> String? {
        guard Player.hostOf(url) != nil, let path = pathOf(url) else { return nil }
        return handleSegment(path)
    }

    private static func handleSegment(_ path: String) -> String? {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true)
        guard let first = parts.first, first.hasPrefix("@") else { return nil }
        let handle = String(first.dropFirst())
        guard (3...30).contains(handle.count) else { return nil }
        let allowed = handle.unicodeScalars.allSatisfy { c in
            switch c {
            case "A"..."Z", "a"..."z", "0"..."9", ".", "_", "-": return true
            default: return false
            }
        }
        return allowed ? handle : nil
    }

    /* Hosts YouTube serves channel avatars from. Checked before an avatar URL
       is stored, because whatever is stored is later fetched and drawn: an
       og:image tag is page-controlled, and "some URL a page told us about" is
       not something to keep in the database and load on sight. */
    static let avatarHosts: Set<String> = ["yt3.ggpht.com", "yt3.googleusercontent.com"]

    public static func isAllowedAvatar(_ url: String) -> Bool {
        guard let host = Player.hostOf(url) else { return false }
        return avatarHosts.contains(host) || host.hasSuffix(".googleusercontent.com")
    }

    public static let parentStart = "https://m.youtube.com/"
}
