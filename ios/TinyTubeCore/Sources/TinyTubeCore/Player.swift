import Foundation

/* Where the player's web view is allowed to navigate.

   Pure string logic, so the tests can hold it to its promises without a
   simulator. The navigation allowlist is the last thing standing between a tap
   on a related video and the open internet, and it is far too easy to get
   subtly wrong to leave untested.

   Ported from Player.kt, allowlist and all. If a host is added on one platform
   it has to be added on the other, with the same lookalike cases. */
public enum Player {

    /* youtube.com is the embed origin (see `origin`); youtube-nocookie stays
       allowed because it was the origin until recently. The others carry
       the player's own assets and API traffic. Nothing else loads, at all. */
    static let allowedHosts: Set<String> = [
        "www.youtube-nocookie.com",
        "youtube-nocookie.com",
        "www.youtube.com",       // the IFrame API script and player XHRs
        "youtube.com",
        "s.ytimg.com",           // player javascript and css
        "i.ytimg.com",           // thumbnails/posters
        "yt3.ggpht.com",         // channel avatars inside the player chrome
        "googlevideo.com",       // the media streams themselves
    ]

    /* Matching is on the parsed host only. Substring checks on the whole URL
       are the classic way to get this wrong: "youtube.com" appears in
       "https://youtube.com.attacker.example/", and a naive hasSuffix accepts
       "https://notyoutube.com/". */
    public static func isPlayerURL(_ url: String) -> Bool {
        guard let host = hostOf(url) else { return false }
        if allowedHosts.contains(host) { return true }
        /* Media comes from per-datacentre subdomains
           (rr3---sn-abc.googlevideo.com), so that one is matched by suffix —
           on a dot, so "evilgooglevideo.com" does not qualify. */
        return host.hasSuffix(".googlevideo.com")
    }

    /* The host of an http(s) URL, lowercased, or nil.
     *
     * Parsed by hand rather than with URLComponents, deliberately. This has to
     * agree with the Kotlin original character for character — it is the same
     * lock on two doors — and Foundation's parser differs from Android's in
     * exactly the corners that matter here, including what it does with
     * userinfo and with a URL it considers malformed. A hand parser is a few
     * lines and is the same few lines on both platforms.
     *
     * "javascript:", "intent:", "file:" and friends must never be treated as
     * navigable, and returning nil for them makes isPlayerURL refuse. */
    public static func hostOf(_ url: String) -> String? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        let scheme: String
        if lower.hasPrefix("https://") { scheme = "https://" }
        else if lower.hasPrefix("http://") { scheme = "http://" }
        else { return nil }

        var rest = String(trimmed.dropFirst(scheme.count))
        /* Stop at the first of / ? # — everything after is path, query or
           fragment and none of it is the host. */
        if let end = rest.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) {
            rest = String(rest[rest.startIndex..<end])
        }
        var host = rest.lowercased()

        /* "https://www.youtube.com@attacker.example/" is a request to
           attacker.example, and reading up to the '@' would get it backwards. */
        if let at = host.lastIndex(of: "@") {
            host = String(host[host.index(after: at)...])
        }
        if let colon = host.firstIndex(of: ":") {
            host = String(host[host.startIndex..<colon])
        }
        return host.isEmpty ? nil : host
    }

    /* The origin the WRAPPER document runs on — NOT where the player is served
       from. The embed iframe has always been www.youtube.com: `YT.Player` only
       points at the nocookie domain when passed `host:`, and the page below
       never has.

       BACK ON www.youtube-nocookie.com, AND IT HAS TO STAY THERE. Ported from
       Player.kt, where the whole reasoning is written out; the short version is
       that moving it to www.youtube.com to pick up a Premium session broke
       playback on BOTH platforms — every video came up "Video unavailable" —
       because this page is a synthetic document (`loadHTMLString` here,
       `loadDataWithBaseURL` on Android) claiming an origin it cannot prove, and
       YouTube's embed refuses to serve a player to it. The nocookie domain
       exists precisely to be embedded by pages that are not YouTube.

       Don't reinstate the other value on its own: it is not a configuration
       choice, it is the bug — and it was never the Premium lever either, which
       is the cookie policy on the third-party iframe rather than this string.
       Android probes that with setAcceptThirdPartyCookies; WKWebView has no
       equivalent, so iOS needs the wrapper served from a real origin. See
       README. */
    public static let origin = "https://www.youtube-nocookie.com"

    /* The page loaded into the player's web view.
     *
     * The id is re-validated here rather than trusted from the caller. It is
     * interpolated into a JS string literal, so this is the point where a bad
     * value would become script; VideoId already refuses those, and this is the
     * second lock on the same door. Returns nil instead of building a page. */
    public static func pageFor(videoId: String) -> String? {
        guard VideoId.isValid(videoId) else { return nil }
        return """
        <!doctype html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
        <style>
          html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}
          #p{position:absolute;inset:0;width:100%;height:100%}
        </style>
        </head>
        <body>
        <div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var player;
        function onYouTubeIframeAPIReady() {
          player = new YT.Player('p', {
            videoId: '\(videoId)',
            playerVars: {
              autoplay: 1, playsinline: 1, rel: 0, fs: 0,
              modestbranding: 1, iv_load_policy: 3, disablekb: 1
            },
            events: {
              onStateChange: function (e) {
                Bridge.onState(e.data);
                if (e.data === YT.PlayerState.ENDED) Bridge.onEnded();
              },
              onError: function (e) { Bridge.onError(String(e.data)); }
            }
          });
        }
        </script>
        </body>
        </html>
        """
    }
}
